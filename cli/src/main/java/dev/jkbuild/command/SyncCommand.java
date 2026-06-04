// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.runtime.LockFlow;

import dev.jkbuild.runtime.JdkEnsure;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.theme.Coords;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.http.Http;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.resolver.CacheSync;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.run.PhaseStatus;
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
 *       CAS doesn't already hold. Per-package progress events come from
 *       {@link CacheSync.ProgressObserver}.</li>
 *   <li>{@code write-sync-manifest} (SYNC) — stamp
 *       {@code actions/synced/<projectFingerprint>}.</li>
 * </ol>
 */
@Command(name = "sync", description = "Ensure our local cache has all project dependencies")
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
            description = "Prepare for an offline build.")
    boolean offlinePrepare;

    @picocli.CommandLine.Mixin GlobalOptions global;

    /** Cross-phase keys. Lifted out so each phase reads/writes through the same handle. */
    private static final GoalKey<Lockfile> LOCKFILE = GoalKey.of("lockfile", Lockfile.class);
    private static final GoalKey<JkBuild> BUILD = GoalKey.of("build", JkBuild.class);
    private static final GoalKey<JdkEnsure.Outcome> JDK_OUTCOME =
            GoalKey.of("jdk-outcome", JdkEnsure.Outcome.class);
    private static final GoalKey<CacheSync.Report> CAS_REPORT =
            GoalKey.of("cas-report", CacheSync.Report.class);
    private static final GoalKey<dev.jkbuild.runtime.JkWorkerSync.Result> WORKER_REPORT =
            GoalKey.of("worker-report", dev.jkbuild.runtime.JkWorkerSync.Result.class);
    private static final GoalKey<Integer> WORKSPACE_MEMBERS =
            GoalKey.of("workspace-members", Integer.class);
    private static final GoalKey<Boolean> LOCKFILE_CREATED =
            GoalKey.of("lockfile-created", Boolean.class);

    @Override
    public Integer call() throws Exception {
        Path dir = global.workingDir();
        Path lockFile = dir.resolve("jk.lock");
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);

        Phase parseLock = Phase.builder("parse-lock")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("parse jk.lock");
                    if (!Files.exists(lockFile)) {
                        ctx.label("resolve deps");
                        var result = LockFlow.run(
                                dir, cache, List.of(), false, repoUrl);
                        if (result.workspaceMemberCount() > 0) {
                            ctx.put(WORKSPACE_MEMBERS, result.workspaceMemberCount());
                        }
                        if (result.status() != 0) {
                            ctx.error("lock", result.error() != null
                                    ? result.error()
                                    : "lockfile resolution failed (exit " + result.status() + ")");
                            throw new RuntimeException("lock-flow failed");
                        }
                        ctx.put(LOCKFILE, result.lockfile());
                        if (result.build() != null) ctx.put(BUILD, result.build());
                        ctx.put(LOCKFILE_CREATED, true);
                    } else {
                        ctx.put(LOCKFILE, LockfileReader.read(lockFile));
                        var build = parseBuildIfPresent(dir);
                        if (build != null) ctx.put(BUILD, build);
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
                    Lockfile lock = ctx.require(LOCKFILE);
                    JkBuild build = ctx.get(BUILD).orElse(null);
                    try {
                        var outcome = JdkEnsure.ensure(dir, jdksDir, build, lock,
                                m -> ctx.warn("jdk", m));
                        ctx.put(JDK_OUTCOME, outcome);
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
                .scope(0)                 // grown once parse-lock fills lockfile
                .execute(ctx -> {
                    Lockfile lock = ctx.require(LOCKFILE);
                    int packages = lock.packages().size();
                    if (packages > 0) ctx.updateScope(packages);
                    ctx.label("fetch deps");

                    Cas cas = new Cas(cache);
                    Http http = new Http();
                    // Per-package progress: each callback adds 1 to the
                    // numerator on whatever thread completed the fetch.
                    // The TUI bar advances as individual deps land; the
                    // event log records them ordered by completion.
                    var observer = new CacheSync.ProgressObserver() {
                        @Override public void fetched(Lockfile.Package pkg) {
                            ctx.label("fetched " + Coords.module(pkg.name(), pkg.version()));
                            ctx.progress(1);
                        }
                        @Override public void upToDate(Lockfile.Package pkg) {
                            ctx.progress(1);
                        }
                        @Override public void skipped(Lockfile.Package pkg) {
                            ctx.progress(1);
                        }
                        @Override public void failed(Lockfile.Package pkg, String error) {
                            ctx.error("dep", Coords.module(pkg.name(), pkg.version()) + " — " + error);
                            ctx.progress(1);
                        }
                    };
                    boolean noCache = dev.jkbuild.config.ActiveConfig.get().noCacheOr(false);
                    var report = new CacheSync(cas, http).sync(lock, observer, noCache);
                    ctx.put(CAS_REPORT, report);
                    if (report.hasErrors()) {
                        throw new RuntimeException("dep fetch had errors");
                    }
                })
                .build();

        // jk's own worker jars (test-runner, kotlin-compiler) — pulled from the
        // local Maven repo into the CAS so `jk test` / Kotlin builds find them by
        // SHA. Best-effort: absent workers warn but don't fail the sync.
        Phase syncWorkers = Phase.builder("sync-workers")
                .kind(PhaseKind.IO)
                .requires("parse-lock")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("sync jk workers");
                    Cas cas = new Cas(cache);
                    try {
                        var report = dev.jkbuild.runtime.JkWorkerSync.ensureInCas(cas,
                                new dev.jkbuild.runtime.JkWorkerSync.Observer() {
                                    @Override public void fetched(String artifact) {
                                        ctx.label("fetched " + artifact);
                                    }
                                    @Override public void missing(String artifact, String detail) {
                                        ctx.warn("worker", artifact + ": " + detail);
                                    }
                                });
                        ctx.put(WORKER_REPORT, report);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("interrupted syncing jk workers", e);
                    }
                    ctx.progress(1);
                })
                .build();

        Phase writeManifest = Phase.builder("write-sync-manifest")
                .requires("sync-cas")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("stamp reachability manifest");
                    try {
                        Lockfile lock = ctx.require(LOCKFILE);
                        dev.jkbuild.task.SyncManifest.write(
                                cache.resolve("actions"), lockFile, lock);
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
                .addPhase(syncWorkers)
                .addPhase(writeManifest)
                .build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);

        if (result.success()) {
            printSuccessSummary(goal, lockFile);
            // Opportunistic cache prune — no-op when auto-prune is off.
            try {
                var cacheConfig = dev.jkbuild.config.JkCacheConfig.fromToml(
                        dir.resolve("jk.toml"));
                dev.jkbuild.task.CachePruneScheduler.resolveJkExe().ifPresent(exe ->
                        dev.jkbuild.task.CachePruneScheduler.maybeRun(
                                cacheConfig, cache, exe));
            } catch (IOException ignored) {
                // Cache hygiene is never load-bearing.
            }
            return 0;
        }
        // The progress-bar listener (or SilentListener on a pipe) has
        // already surfaced the failure — no command-side summary.
        return 1;
    }

    /**
     * On success, surface the backward-compat one-liners every prior
     * version of {@code jk sync} printed. Tests + dotfiles + scripts
     * grep for these.
     */
    private void printSuccessSummary(Goal goal, Path lockFile) {
        if (global.outputIsJson()) return;
        goal.get(WORKSPACE_MEMBERS).ifPresent(n ->
                System.out.println("Workspace: " + n + " member" + (n == 1 ? "" : "s")));
        boolean created = goal.get(LOCKFILE_CREATED).orElse(false);
        goal.get(LOCKFILE).ifPresent(lock -> {
            if (created) {
                int n = lock.packages().size();
                System.out.println("Created " + lockFile + " (" + n
                        + " package" + (n == 1 ? "" : "s") + ")");
            }
        });
        goal.get(JDK_OUTCOME).ifPresent(SyncCommand::printJdkSummary);
        goal.get(CAS_REPORT).ifPresent(r ->
                System.out.println(r.fetched() + " fetched, "
                        + r.upToDate() + " up-to-date, "
                        + r.skipped() + " skipped"));
        goal.get(WORKER_REPORT).ifPresent(r -> {
            if (r.fetched() > 0 || r.missing() > 0) {
                System.out.println("Workers: " + r.present() + " present, "
                        + r.fetched() + " fetched, " + r.missing() + " missing");
            }
        });
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
            return null;
        }
    }
}
