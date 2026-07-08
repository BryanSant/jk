// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.engine.EngineClient;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.runtime.JdkEnsure;
import dev.jkbuild.runtime.SyncGoals;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code jk sync} — bring the local toolchain + dependency cache in line with the project's {@code
 * jk.lock}. The goal itself (parse-lock → ensure-jdk ∥ sync-cas ∥ … → sync-modules) lives in
 * {@link SyncGoals}; see its javadoc for the phase breakdown.
 *
 * <p><b>Engine-hosted</b> (Wave 1 of the slim-client migration): the CAS fetches and any auto-lock
 * run inside the resident engine ({@link EngineClient#runSync}); this command pre-flights the JDK
 * ensure (installs stay client-side — the engine only ever <em>resolves</em> an installed JDK, per
 * {@code docs/engine.md}), sends the request, and renders the streamed events with the same console
 * listener the in-process path attaches. The test-only in-process path (see {@link
 * #engineDisabledForTests}) builds the identical goal via {@link SyncGoals}.
 */
public final class SyncCommand implements CliCommand {

    private Path cacheDir;
    private Path jdksDir;
    private java.net.URI repoUrl;
    private boolean offlinePrepare;
    private boolean sources;
    private GlobalOptions global;

    @Override
    public String name() {
        return "sync";
    }

    @Override
    public String description() {
        return "Ensure our local cache has all project dependencies";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value(
                                "<dir>",
                                "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.",
                                "--cache-dir")
                        .hide(),
                Opt.value("<dir>", "Override the JDK install root. Default: the IntelliJ JDK directory.", "--jdks-dir")
                        .hide(),
                Opt.value("<url>", "Override declared repos with a single URL (for tests).", "--repo-url")
                        .hide(),
                Opt.flag("Prepare for an offline build.", "--offline-prepare"),
                Opt.flag("Also download sources JARs when available.", "--sources"));
    }

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()}'s javadoc for the full rationale. Same system property,
     * same "never a user-facing flag" contract; a real {@code jk sync} invocation always engine-hosts.
     */
    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        this.cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.repoUrl = in.value("repo-url").map(java.net.URI::create).orElse(null);
        this.offlinePrepare = in.isSet("offline-prepare");
        this.sources = in.isSet("sources");
        this.global = GlobalOptions.from(in);

        Path dir = global.workingDir();
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);

        String targetLabel = dir.getFileName() != null ? dir.getFileName().toString() : dir.toString();
        GoalConsole.Mode mode = GoalConsole.modeFor(global);

        if (engineDisabledForTests()) {
            return runInProcess(dir, cache, mode, targetLabel);
        }

        // Pre-flight the JDK ensure client-side: a missing pinned JDK is downloaded HERE, before
        // the request — never silently inside the engine (docs/engine.md keeps installs, and any
        // interactive consent, client-side). The engine's own ensure-jdk phase then only resolves
        // the already-installed JDK (JdkEnsure with allowInstall=false).
        Path lockFile = dir.resolve("jk.lock");
        JkBuild build = SyncGoals.parseBuildIfPresent(dir);
        Lockfile lock = null;
        if (Files.isRegularFile(lockFile)) {
            try {
                lock = LockfileReader.read(lockFile);
            } catch (Exception ignored) {
                // unreadable lock — the engine's parse-lock phase surfaces the real error
            }
        }
        try {
            JdkEnsure.ensure(dir, jdksDir, build, lock, m -> CliOutput.err("jk sync: " + m));
        } catch (Exception e) {
            CliOutput.err("jk sync: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            return 1;
        }

        // Summary counts arrive on the terminal goal-finish, before the console listener's own
        // goalFinish renders the line — so these holders are settled exactly like the in-process
        // path's counters.
        long[] fetched = new long[1];
        long[] upToDate = new long[1];
        ConsoleSpec spec = syncSpec(() -> fetched[0], () -> upToDate[0]);

        var session = dev.jkbuild.config.SessionContext.current();
        GoalResult result;
        try {
            result = EngineClient.runSync(
                    dev.jkbuild.engine.EnginePaths.current(),
                    new EngineClient.SyncRequest(
                            dir,
                            cache,
                            jdksDir,
                            repoUrl,
                            sources,
                            session.offline(),
                            session.force(),
                            session.config().refreshOr(false),
                            global.verbose),
                    phases -> GoalConsole.chooseConsoleListener(phases, mode, spec, targetLabel),
                    fetched,
                    upToDate);
        } catch (IOException e) {
            CliOutput.err("jk sync: " + e.getMessage());
            return Exit.SOFTWARE;
        }
        // The engine ran the opportunistic cache prune on success (it did the work); nothing more
        // to do here. The progress-bar listener has already surfaced any failure.
        return result.success() ? 0 : 1;
    }

    // ---- test-only in-process path (identical goal via SyncGoals) ------------

    private int runInProcess(Path dir, Path cache, GoalConsole.Mode mode, String targetLabel) {
        AtomicInteger totalFetched = new AtomicInteger(0);
        AtomicInteger totalUpToDate = new AtomicInteger(0);

        Goal goal = SyncGoals.syncGoal(
                dir, cache, jdksDir, repoUrl, sources, totalFetched, totalUpToDate, Coords::module, true);

        ConsoleSpec spec = syncSpec(totalFetched::get, totalUpToDate::get);
        GoalResult result = GoalConsole.runGoal(goal, mode, cache, spec, targetLabel);

        if (result.success()) {
            // Opportunistic cache prune — no-op when auto-prune is off.
            var cacheConfig = dev.jkbuild.config.JkCacheConfig.resolve();
            dev.jkbuild.task.CachePruneScheduler.resolveJkExe()
                    .ifPresent(exe -> dev.jkbuild.task.CachePruneScheduler.maybeRun(cacheConfig, cache, exe));
            return 0;
        }
        // The progress-bar listener (or SilentListener on a pipe) has
        // already surfaced the failure — no command-side summary.
        return 1;
    }

    /** The Sync chip spec; counts are read lazily, at result-line render time. */
    private static ConsoleSpec syncSpec(java.util.function.LongSupplier fetched, java.util.function.LongSupplier upToDate) {
        return new ConsoleSpec(
                "Sync",
                r -> {
                    long f = fetched.getAsLong();
                    long u = upToDate.getAsLong();
                    return f == 0 && u == 0 ? "already up to date" : f + " fetched, " + u + " up-to-date";
                },
                r -> "Failed to sync dependencies.",
                true);
    }
}
