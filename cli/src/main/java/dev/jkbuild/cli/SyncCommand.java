// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.http.Http;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.resolver.CacheSync;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jk sync} — bring the local toolchain + dependency cache in line with
 * the project's {@code jk.lock}.
 *
 * <p>Organised as a {@link Goal} with four phases:
 * <ol>
 *   <li>{@code parse-lock} (SYNC) — read {@code jk.lock} or delegate to
 *       {@link LockFlow} when it's missing.</li>
 *   <li>{@code ensure-jdk} (IO, parallel) — resolve or install the
 *       project's pinned JDK via {@link JdkEnsure}.</li>
 *   <li>{@code sync-cas} (IO, parallel) — fetch any locked artifacts the
 *       CAS doesn't already hold.</li>
 *   <li>{@code write-sync-manifest} (SYNC) — stamp
 *       {@code actions/synced/<projectFingerprint>} so the future sweep
 *       treats this project's deps as reachable until the manifest
 *       ages out.</li>
 * </ol>
 *
 * <p>The progress bar / verbose / JSON / silent rendering is fanned out
 * by {@link GoalConsole}; this command's body just builds the goal and
 * prints the existing one-line summaries after it finishes.
 */
@Command(name = "sync", description = "Reconcile JDK + cache to jk.lock")
public final class SyncCommand implements Callable<Integer> {    @Option(names = "--cache-dir", hidden = true,
            description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
    Path cacheDir;

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @Option(names = "--repo-url", hidden = true,
            description = "Override declared repos with a single URL (for tests).")
    java.net.URI repoUrl;

    @Option(names = "--offline-prepare",
            description = "Prepare for an offline build (accepted, no-op in v0.1).")
    boolean offlinePrepare;

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws Exception {
        Path dir = global.workingDir();
        Path lockFile = dir.resolve("jk.lock");
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);

        // Mutable holder shared across phases. The parse-lock phase
        // populates the lockfile + build; later phases consume it.
        // Using a small inline record-holder keeps the surface tiny.
        State state = new State();

        Phase parseLock = Phase.builder("parse-lock")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("parse jk.lock");
                    if (!Files.exists(lockFile)) {
                        ctx.label("resolve deps");
                        var result = LockFlow.run(
                                dir, cache, List.of(), false, repoUrl, "jk sync");
                        state.workspaceMembers = result.workspaceMemberCount();
                        if (result.status() != 0) {
                            ctx.error("lock", "lockfile resolution failed (exit "
                                    + result.status() + ")");
                            throw new RuntimeException("lock-flow failed");
                        }
                        state.lockfile = result.lockfile();
                        state.build = result.build();
                        state.lockfileCreated = true;
                    } else {
                        state.lockfile = LockfileReader.read(lockFile);
                        state.build = parseBuildIfPresent(dir);
                    }
                    ctx.progress(1);
                })
                .build();

        Phase ensureJdk = Phase.builder("ensure-jdk")
                .kind(PhaseKind.IO)
                .requires("parse-lock")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("resolve JDK");
                    try {
                        state.jdkOutcome = JdkEnsure.ensure(
                                dir, jdksDir, state.build, state.lockfile);
                    } catch (Exception e) {
                        ctx.error("jdk", e.getMessage() == null
                                ? e.getClass().getSimpleName() : e.getMessage());
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();

        Phase syncCas = Phase.builder("sync-cas")
                .kind(PhaseKind.IO)
                .requires("parse-lock")
                .scope(0)                 // unknown until parse-lock fills lockfile
                .execute(ctx -> {
                    int packages = state.lockfile.packages().size();
                    if (packages > 0) ctx.updateScope(packages);
                    ctx.label("fetch deps");
                    Cas cas = new Cas(cache);
                    Http http = new Http();
                    var report = new CacheSync(cas, http).sync(state.lockfile);
                    state.casReport = report;
                    // Report progress as one chunk after CacheSync completes —
                    // per-package events are a follow-up that needs CacheSync
                    // itself to expose a callback.
                    ctx.progress(packages);
                    for (String err : report.errors()) {
                        ctx.error("dep", err);
                    }
                    if (report.hasErrors()) {
                        throw new RuntimeException("dep fetch had errors");
                    }
                })
                .build();

        Phase writeManifest = Phase.builder("write-sync-manifest")
                .requires("sync-cas")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("stamp reachability manifest");
                    try {
                        dev.jkbuild.task.SyncManifest.write(
                                cache.resolve("actions"), lockFile, state.lockfile);
                    } catch (IOException e) {
                        ctx.warn("manifest",
                                "could not stamp reachability manifest: " + e.getMessage());
                    }
                    ctx.progress(1);
                })
                .build();

        Goal goal = Goal.builder("sync")
                .addPhase(parseLock)
                .addPhase(ensureJdk)
                .addPhase(syncCas)
                .addPhase(writeManifest)
                .build();

        var result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);

        // Backward-compat summary lines. The Goal listeners already
        // surfaced live progress + diagnostics; these one-liners
        // preserve the previous CLI shape for scripts/tests.
        if (state.workspaceMembers > 0) {
            System.out.println("Workspace: " + state.workspaceMembers + " member"
                    + (state.workspaceMembers == 1 ? "" : "s"));
        }
        if (state.lockfileCreated && state.lockfile != null) {
            System.out.println("Created " + lockFile + " ("
                    + state.lockfile.packages().size() + " package"
                    + (state.lockfile.packages().size() == 1 ? "" : "s") + ")");
        }
        if (state.jdkOutcome != null) {
            printJdkSummary(state.jdkOutcome);
        }
        if (state.casReport != null) {
            System.out.println(state.casReport.fetched() + " fetched, "
                    + state.casReport.upToDate() + " up-to-date, "
                    + state.casReport.skipped() + " skipped");
        }

        if (result.success()) {
            // Opportunistic cache prune — no-op when [cache].auto-prune
            // is off; detached subprocess otherwise.
            try {
                var cacheConfig = dev.jkbuild.config.JkCacheConfig.fromToml(
                        dir.resolve("jk.toml"));
                dev.jkbuild.task.CachePruneScheduler.resolveJkExe().ifPresent(exe ->
                        dev.jkbuild.task.CachePruneScheduler.maybeRun(
                                cacheConfig, cache, exe));
            } catch (IOException ignored) {
                // Cache hygiene is never load-bearing.
            }
        }
        return result.success() ? 0 : 1;
    }

    private static void printJdkSummary(JdkEnsure.Outcome outcome) {
        if (outcome.jdk().isEmpty()) {
            System.out.println("JDK: (no pin in jk.lock or jk.toml; falling back to JAVA_HOME)");
            return;
        }
        var jdk = outcome.jdk().get();
        switch (outcome.source()) {
            case ALREADY_PINNED, LOCKFILE_INSTALL ->
                    System.out.println("JDK: " + jdk.identifier() + " (already installed)");
            case INSTALLED ->
                    System.out.println("JDK: installed " + jdk.identifier()
                            + (outcome.specUsed() != null
                                    ? " (from spec " + outcome.specUsed() + ")"
                                    : ""));
        }
    }

    private static JkBuild parseBuildIfPresent(Path dir) {
        Path buildFile = dir.resolve("jk.toml");
        if (!Files.exists(buildFile)) return null;
        try {
            return JkBuildParser.parse(buildFile);
        } catch (Exception e) {
            // Sync should still succeed even if jk.toml is malformed (the
            // lockfile is authoritative). Caller falls back to lockfile-only
            // resolution.
            return null;
        }
    }

    /**
     * Mutable scratchpad shared across phases. The Goal framework deliberately
     * doesn't ship a "phase result" channel — phases that need to hand data
     * downstream use closures over a small holder like this.
     */
    private static final class State {
        Lockfile lockfile;
        JkBuild build;
        boolean lockfileCreated;
        int workspaceMembers;
        JdkEnsure.Outcome jdkOutcome;
        CacheSync.Report casReport;
    }
}
