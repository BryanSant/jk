// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.http.Http;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.resolver.CacheSync;
import dev.jkbuild.util.JkDirs;
import dev.jkbuild.util.JkThreads;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * {@code jk sync} — bring the local toolchain + dependency cache in line with
 * the project's {@code jk.lock}:
 * <ol>
 *   <li>Reads {@code jk.lock}, or builds one by delegating to {@link LockFlow}
 *       when missing.</li>
 *   <li>Resolves the project's pinned JDK, installing one from the JetBrains
 *       feed if it isn't already on disk.</li>
 *   <li>Fetches any locked artifacts the content-addressed cache doesn't
 *       already hold.</li>
 * </ol>
 *
 * <p>Steps 2 and 3 run in parallel — they share no state and both touch the
 * network, so overlapping them on {@link JkThreads#io()} shaves wall-clock
 * for the cold-cache + missing-JDK case.
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

        Lockfile lock;
        JkBuild build = parseBuildIfPresent(dir);
        if (!Files.exists(lockFile)) {
            var result = LockFlow.run(dir, cache, List.of(), false, repoUrl, "jk sync");
            if (result.workspaceMemberCount() > 0) {
                System.out.println("Workspace: " + result.workspaceMemberCount() + " member"
                        + (result.workspaceMemberCount() == 1 ? "" : "s"));
            }
            if (result.status() != 0) return result.status();
            lock = result.lockfile();
            build = result.build();
            System.out.println("Created " + lockFile + " (" + lock.packages().size() + " package"
                    + (lock.packages().size() == 1 ? "" : "s") + ")");
        } else {
            lock = LockfileReader.read(lockFile);
        }

        // Step 2 + Step 3: JDK ensure || CAS sync, in parallel.
        final JkBuild buildFinal = build;
        final Lockfile lockFinal = lock;
        CompletableFuture<JdkEnsure.Outcome> jdkFut = CompletableFuture.supplyAsync(() -> {
            try {
                return JdkEnsure.ensure(dir, jdksDir, buildFinal, lockFinal);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, JkThreads.io());

        Cas cas = new Cas(cache);
        Http http = new Http();
        CompletableFuture<CacheSync.Report> syncFut = CompletableFuture.supplyAsync(() -> {
            try {
                return new CacheSync(cas, http).sync(lockFinal);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, JkThreads.io());

        int exit = 0;

        try {
            JdkEnsure.Outcome jdkOutcome = jdkFut.get();
            if (jdkOutcome.jdk().isEmpty()) {
                System.out.println("JDK: (no pin in jk.lock or jk.toml; falling back to JAVA_HOME)");
            } else {
                var jdk = jdkOutcome.jdk().get();
                switch (jdkOutcome.source()) {
                    case ALREADY_PINNED, LOCKFILE_INSTALL ->
                            System.out.println("JDK: " + jdk.identifier() + " (already installed)");
                    case INSTALLED ->
                            System.out.println("JDK: installed " + jdk.identifier()
                                    + (jdkOutcome.specUsed() != null
                                            ? " (from spec " + jdkOutcome.specUsed() + ")"
                                            : ""));
                }
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.err.println("jk sync: JDK: " + cause.getMessage());
            exit = 1;
        }

        try {
            CacheSync.Report report = syncFut.get();
            System.out.println(report.fetched() + " fetched, "
                    + report.upToDate() + " up-to-date, "
                    + report.skipped() + " skipped");
            if (report.hasErrors()) {
                for (String error : report.errors()) {
                    System.err.println("  " + error);
                }
                exit = 1;
            } else {
                // Stamp a per-project reachability manifest so the future
                // mark-and-sweep treats this project's locked deps as live
                // — important between sync and the first build, when no
                // action record covers them yet.
                try {
                    dev.jkbuild.task.SyncManifest.write(
                            cache.resolve("actions"), lockFile, lockFinal);
                } catch (java.io.IOException manifestErr) {
                    // Best-effort: a manifest write failure shouldn't fail
                    // the sync. Worst case: a sweep collects the deps and
                    // the next build re-fetches them.
                    System.err.println("jk sync: warning: could not stamp "
                            + "reachability manifest (" + manifestErr.getMessage() + ")");
                }
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.err.println("jk sync: cache: " + cause.getMessage());
            exit = 1;
        }

        if (exit == 0) {
            // Opportunistic cache prune — no-op when [cache].auto-prune is
            // off; detached subprocess otherwise. The build-flavour hook
            // is symmetric; both trigger paths share the scheduler.
            try {
                var cacheConfig = dev.jkbuild.config.JkCacheConfig.fromToml(dir.resolve("jk.toml"));
                dev.jkbuild.task.CachePruneScheduler.resolveJkExe().ifPresent(exe ->
                        dev.jkbuild.task.CachePruneScheduler.maybeRun(cacheConfig, cache, exe));
            } catch (java.io.IOException ignored) {
                // Cache hygiene is never load-bearing.
            }
        }
        return exit;
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
}
