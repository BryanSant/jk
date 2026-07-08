// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.ProjectContext;
import dev.jkbuild.cli.engine.EngineClient;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.CommandManager;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.http.Http;
import dev.jkbuild.library.LibraryCatalog;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.WorkspaceMerge;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.repo.LibraryRegistryClient;
import dev.jkbuild.resolver.ResolveObserver;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.runtime.LockGoals;
import dev.jkbuild.util.JkDirs;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code jk lock} — resolve declared dependencies and write {@code jk.lock}.
 *
 * <p>Reads repositories + features from the project's jk.toml. Feature selection: {@code
 * --features=A,B} adds those features on top of the declared {@code features.default}; {@code
 * --no-default-features} disables the default list entirely. Cargo semantics.
 *
 * <p>For workspace roots, locking cascades: after the root's own {@code jk.lock} is written, each
 * declared module is locked in declaration order using its own {@code jk.lock} alongside its {@code
 * jk.toml}. {@code workspace:} placeholder deps are resolved to real Maven coords before each
 * module's solve; sibling-internal deps are filtered out (they're injected at build time via {@link
 * dev.jkbuild.config.WorkspaceClasspath}).
 *
 * <p><b>Engine-hosted</b> (Wave 1 of the slim-client migration): the PubGrub solve, CAS fetches and
 * git-source materialization run inside the resident engine ({@link EngineClient#runLock}); this
 * command is the renderer — it pre-flights the library-registry refresh, sends the request, and
 * replays the wire events into the exact same live/plain views the in-process path drives. The
 * goal machinery itself lives in {@link LockGoals} so the test-only in-process path (see {@link
 * #engineDisabledForTests}) runs the identical pipeline.
 */
public final class LockCommand implements CliCommand {

    private List<String> features = List.of();
    private boolean noDefaultFeatures;
    private boolean sources;
    private URI repoUrl;
    private Path cacheDir;
    private GlobalOptions global;

    @Override
    public String name() {
        return "lock";
    }

    @Override
    public String description() {
        return "Resolve versions for dependencies and write jk.lock";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<a,b,...>", "Activate listed features beyond the defaults.", "--features")
                        .splitOn(","),
                Opt.flag("Don't activate the project's default features.", "--no-default-features"),
                Opt.flag("Pin sources JARs for all Maven deps too.", "--sources"),
                Opt.value("<url>", "Override declared repos with a single URL.", "--repo-url")
                        .hide(),
                Opt.value(
                                "<dir>",
                                "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.",
                                "--cache-dir")
                        .hide(),
                Opt.value("<url>", "Override the library registry URL (used by tests).", "--library-registry-url")
                        .hide(),
                Opt.value(
                                "<file>",
                                "Override the downloaded library catalog path (used by tests).",
                                "--library-cache-file")
                        .hide());
    }

    private URI libraryRegistryUrl;
    private Path libraryCacheFile;

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()}'s javadoc for the full rationale. Same system property,
     * same "never a user-facing flag" contract; a real {@code jk lock} invocation always engine-hosts.
     */
    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        this.features = in.values("features");
        this.noDefaultFeatures = in.isSet("no-default-features");
        this.sources = in.isSet("sources");
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        this.libraryRegistryUrl = in.value("library-registry-url").map(URI::create).orElse(null);
        this.libraryCacheFile = in.value("library-cache-file").map(Path::of).orElse(null);
        this.global = GlobalOptions.from(in);

        Path dir = global.workingDir();
        if (ProjectContext.require(dir, "lock").isEmpty()) return Exit.CONFIG;
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);

        // Client-side pre-flight: revalidate the downloaded library catalog before anything parses
        // jk.toml — the engine reads the same on-disk cache file, so refreshing it here lands for
        // both the hosted and the in-process path.
        refreshLibraryRegistry(
                global.offline,
                libraryRegistryUrl != null ? libraryRegistryUrl : LibraryRegistryClient.DEFAULT_SOURCE,
                libraryCacheFile != null ? libraryCacheFile : LibraryCatalog.downloadedFile());

        GoalConsole.Mode mode = GoalConsole.modeFor(global);
        boolean live = mode == GoalConsole.Mode.AUTO || mode == GoalConsole.Mode.QUIET;

        if (engineDisabledForTests()) {
            return runInProcess(dir, cache, mode, live);
        }
        return live ? runHostedLive(dir, cache, mode) : runHostedPlain(dir, cache, mode);
    }

    // ---- engine-hosted paths -------------------------------------------------

    private EngineClient.LockRequest lockRequest(Path dir, Path cache) {
        var session = dev.jkbuild.config.SessionContext.current();
        return new EngineClient.LockRequest(
                dir, cache, features, noDefaultFeatures, sources, repoUrl,
                session.offline(), session.force(), global.verbose);
    }

    /**
     * Hosted live path (AUTO / QUIET): one shared {@link CommandManager} spanning root + all
     * workspace modules, driven from wire events — one row per module, per-package completion lines
     * (colorized here, never engine-side), and the final Lock chip.
     */
    private int runHostedLive(Path dir, Path cache, GoalConsole.Mode mode) {
        boolean animate = mode == GoalConsole.Mode.AUTO && GoalConsole.isInteractiveTerminal();
        CommandManager view = CommandManager.goal(CliOutput.stdout(), "Lock", animate);
        long start = System.nanoTime();

        AtomicInteger globalLocked = new AtomicInteger(0);
        List<String> errorLines = new ArrayList<>();
        Map<String, String> coordByDir = new java.util.HashMap<>();

        EngineClient.LockHandler handler = new EngineClient.LockHandler() {
            @Override
            public GoalListener onModuleStart(String moduleDir, String coord, List<Phase> phases) {
                coordByDir.put(moduleDir, coord);
                // The display label is empty so renderActiveRow produces "module › dep".
                view.addPhaseLabeled(coord, "lock", "");
                view.phaseRunning(coord, "lock");
                // Lock is purely resolution — total is unknown upfront, so we show a
                // static top-line label and record each resolved dep as a completion line.
                view.solveLabel("Locking versions…");
                return new GoalListener() {};
            }

            @Override
            public void onPackage(String moduleDir, String name, String version) {
                String coord = coordByDir.get(moduleDir);
                // Show active dep in the phase row (module › dep via renderActiveRow).
                view.phaseMessage(coord, "lock", Coords.module(name, version));
                // Record as a completion line with an absolute count bracket.
                int n = globalLocked.incrementAndGet();
                Theme t = Theme.active();
                String line = Theme.colorize(Glyphs.CHECK, t.success())
                        + " "
                        + ConsoleSpec.countBracket(n, t)
                        + " "
                        + Coords.module(name, version);
                if (view.animating()) {
                    view.addCompletion(line);
                } else {
                    CliOutput.out(line);
                }
            }

            @Override
            public void onModuleFinish(String moduleDir, GoalResult result, EngineClient.LockCounts counts) {
                view.phaseDone(coordByDir.get(moduleDir), "lock", result.success());
                if (!result.success()) {
                    for (GoalResult.Diagnostic d : result.errors()) {
                        errorLines.add(ConsoleSpec.renderError(d));
                    }
                }
            }
        };

        EngineClient.LockOutcome outcome;
        try {
            outcome = EngineClient.runLock(dev.jkbuild.engine.EnginePaths.current(), lockRequest(dir, cache), handler);
        } catch (java.io.IOException e) {
            view.finishGoalFailure(String.valueOf(e.getMessage()), List.of());
            return Exit.SOFTWARE;
        }
        if (!outcome.success()) {
            errorLines.addAll(outcome.errors());
            view.finishGoalFailure(lockFailTail(), errorLines);
            return outcome.exitCode();
        }
        view.finishGoalSuccess(lockSuccessTail(globalLocked.get(), start));
        return 0;
    }

    /** Hosted plain path (--verbose / --output json): one console listener per cascade module. */
    private int runHostedPlain(Path dir, Path cache, GoalConsole.Mode mode) {
        EngineClient.LockHandler handler = new EngineClient.LockHandler() {
            private GoalListener current;

            @Override
            public GoalListener onModuleStart(String moduleDir, String coord, List<Phase> phases) {
                current = GoalConsole.chooseConsoleListener("lock", phases, mode);
                return current;
            }

            @Override
            public void onPackage(String moduleDir, String name, String version) {
                // The engine sends structured lock-package events instead of pre-themed labels;
                // colorize here, client-side, exactly as the in-process goal labels itself.
                current.label("resolve", "Resolved " + Coords.module(name, version));
            }
        };

        EngineClient.LockOutcome outcome;
        try {
            outcome = EngineClient.runLock(dev.jkbuild.engine.EnginePaths.current(), lockRequest(dir, cache), handler);
        } catch (java.io.IOException e) {
            CliOutput.err("jk lock: " + e.getMessage());
            return Exit.SOFTWARE;
        }
        for (String err : outcome.errors()) {
            CliOutput.err("jk lock: " + err);
        }
        return outcome.exitCode();
    }

    // ---- test-only in-process path (identical pipeline via LockGoals) --------

    private int runInProcess(Path dir, Path cache, GoalConsole.Mode mode, boolean live) throws Exception {
        JkBuild root;
        try {
            root = JkBuildParser.parse(dir.resolve("jk.toml"));
        } catch (RuntimeException e) {
            CliOutput.err("jk lock: " + e.getMessage());
            return Exit.CONFIG;
        }

        JkBuild effectiveRoot = LockGoals.applyWorkspaceContextIfModule(dir, root);

        if (!live) {
            // --verbose / --output json: existing simple-task rendering.
            int result = lockSingleProject(dir, effectiveRoot, cache, "Lock", mode);
            if (result != 0) return result;
            if (effectiveRoot.isWorkspaceRoot()) {
                Map<Path, JkBuild> modules;
                try {
                    modules = WorkspaceLoader.loadModules(dir, effectiveRoot);
                } catch (RuntimeException e) {
                    CliOutput.err("jk lock: " + e.getMessage());
                    return Exit.CONFIG;
                }
                for (Map.Entry<Path, JkBuild> entry : modules.entrySet()) {
                    Path moduleDir = entry.getKey();
                    JkBuild rawModule = entry.getValue();
                    JkBuild effectiveModule = WorkspaceMerge.applyToModule(effectiveRoot, rawModule, modules.values());
                    String moduleLabel = dir.getFileName() + "/" + dir.relativize(moduleDir);
                    int moduleResult = lockSingleProject(moduleDir, effectiveModule, cache, moduleLabel, mode);
                    if (moduleResult != 0) return moduleResult;
                }
            }
            return 0;
        }

        // Live goal-mode TUI: one shared CommandManager spanning root + all workspace modules.
        boolean animate = mode == GoalConsole.Mode.AUTO && GoalConsole.isInteractiveTerminal();
        return runLive(dir, effectiveRoot, cache, animate);
    }

    /**
     * Live path: one {@link CommandManager} in goal mode, one row per module, a shared progress bar
     * calibrated to total packages across all modules, and a completed-package tail.
     */
    private int runLive(Path dir, JkBuild effectiveRoot, Path cache, boolean animate) throws Exception {
        CommandManager view = CommandManager.goal(CliOutput.stdout(), "Lock", animate);
        long start = System.nanoTime();

        AtomicInteger globalLocked = new AtomicInteger(0);
        List<String> errorLines = new ArrayList<>();

        String rootCoord = LockGoals.coordLabel(effectiveRoot, dir);
        int exit = lockModuleLive(dir, effectiveRoot, cache, rootCoord, view, globalLocked, errorLines);
        if (exit != 0) {
            view.finishGoalFailure(lockFailTail(), errorLines);
            return exit;
        }

        if (effectiveRoot.isWorkspaceRoot()) {
            Map<Path, JkBuild> modules;
            try {
                modules = WorkspaceLoader.loadModules(dir, effectiveRoot);
            } catch (RuntimeException e) {
                errorLines.add(e.getMessage());
                view.finishGoalFailure(lockFailTail(), errorLines);
                return Exit.CONFIG;
            }
            for (Map.Entry<Path, JkBuild> entry : modules.entrySet()) {
                Path moduleDir = entry.getKey();
                JkBuild rawModule = entry.getValue();
                JkBuild effectiveModule = WorkspaceMerge.applyToModule(effectiveRoot, rawModule, modules.values());
                String moduleCoord = LockGoals.coordLabel(rawModule, moduleDir);
                exit = lockModuleLive(moduleDir, effectiveModule, cache, moduleCoord, view, globalLocked, errorLines);
                if (exit != 0) {
                    view.finishGoalFailure(lockFailTail(), errorLines);
                    return exit;
                }
            }
        }

        view.finishGoalSuccess(lockSuccessTail(globalLocked.get(), start));
        return 0;
    }

    /**
     * Lock one module with the goal-mode TUI. Registers an active row in {@code view}, runs the full
     * lock goal (parse → resolve → lock-plugins → write), feeds per-package completions into the
     * tail, then marks the row done. Returns 0 on success, or a non-zero exit code on failure
     * (errors collected into {@code errorLines} for the caller to print above the failure chip).
     */
    private int lockModuleLive(
            Path dir,
            JkBuild effective,
            Path cache,
            String coord,
            CommandManager view,
            AtomicInteger globalLocked,
            List<String> errorLines)
            throws Exception {
        // Register one phase row for this module. The display label is empty so
        // renderActiveRow produces "module › dep" (two segments, not three).
        view.addPhaseLabeled(coord, "lock", "");
        view.phaseRunning(coord, "lock");

        // Lock is purely resolution — total is unknown upfront, so we show a
        // static top-line label and record each resolved dep as a completion line.
        view.solveLabel("Locking versions…");

        ResolveObserver observer = new ResolveObserver() {
            @Override
            public void onTotal(int total) {
                // total growth already rides the goal's scope updates
            }

            @Override
            public void onPackage(String pkg, String version) {
                // Show active dep in the phase row (module › dep via renderActiveRow).
                view.phaseMessage(coord, "lock", Coords.module(pkg, version));
                // Record as a completion line with an absolute count bracket.
                int n = globalLocked.incrementAndGet();
                Theme t = Theme.active();
                String line = Theme.colorize(Glyphs.CHECK, t.success())
                        + " "
                        + ConsoleSpec.countBracket(n, t)
                        + " "
                        + Coords.module(pkg, version);
                if (view.animating()) {
                    view.addCompletion(line);
                } else {
                    CliOutput.out(line);
                }
            }
        };

        // Build and run the lock goal directly (no GoalConsole wrapper).
        Goal goal = LockGoals.lockGoal(
                dir, effective, cache, repoUrl, features, !noDefaultFeatures, sources, observer, Coords::module);
        GoalResult result = goal.run();

        if (result.success()) {
            view.phaseDone(coord, "lock", true);
            return 0;
        }

        // Failure: collect diagnostics, mark row failed.
        view.phaseDone(coord, "lock", false);
        for (GoalResult.Diagnostic d : result.errors()) {
            errorLines.add(ConsoleSpec.renderError(d));
        }
        return LockGoals.failureExitCode(result);
    }

    /**
     * Run the lock pipeline (parse → resolve → lock-plugins → write) for one project directory.
     * Used by the non-live in-process path (--verbose / --output json). {@code effective} is the
     * pre-parsed {@link JkBuild} with any {@code workspace:} placeholders already resolved.
     */
    private int lockSingleProject(Path dir, JkBuild effective, Path cache, String label, GoalConsole.Mode mode)
            throws Exception {
        Goal goal = LockGoals.lockGoal(
                dir, effective, cache, repoUrl, features, !noDefaultFeatures, sources,
                ResolveObserver.NOOP, Coords::module);

        ConsoleSpec spec = new ConsoleSpec(
                label,
                r -> {
                    Lockfile lock = goal.get(LockGoals.LOCKFILE).orElseThrow();
                    int pkgs = lock.artifacts().size();
                    int plgs = lock.plugins().size();
                    long srcs = lock.artifacts().stream()
                            .filter(p -> p.sourcesChecksum() != null)
                            .count();
                    String depStr = "Resolved " + pkgs + " dependenc" + (pkgs == 1 ? "y" : "ies");
                    if (srcs > 0) depStr += ", " + srcs + " with sources";
                    return plgs > 0 ? depStr + ", " + plgs + " plugin" + (plgs == 1 ? "" : "s") : depStr;
                },
                r -> "Failed to resolve dependencies");

        GoalResult result = GoalConsole.run(goal, mode, cache, spec);
        return result.success() ? 0 : LockGoals.failureExitCode(result);
    }

    // ---- shared rendering helpers --------------------------------------------

    /** Failure result tail for the Lock chip (GoalWedge prepends "Failed to lock"). */
    private static String lockFailTail() {
        return "dependencies";
    }

    /** Success chip tail: {@code Lock successful. Resolved N dependencies took T}. */
    private static String lockSuccessTail(int pkgs, long startNanos) {
        return Theme.colorize("Lock successful", Theme.active().success())
                + ". Resolved "
                + Theme.colorize(String.valueOf(pkgs), Theme.active().focused())
                + " dependenc" + (pkgs == 1 ? "y" : "ies") + " "
                + ConsoleSpec.took(Duration.ofMillis((System.nanoTime() - startNanos) / 1_000_000));
    }

    /**
     * Best-effort revalidation of the downloaded library catalog layer ({@link
     * LibraryCatalog#downloadedFile()}) before {@code jk.toml} is parsed — parsing is what expands
     * short library names against the catalog, so this needs to land before resolution sees the
     * effective dependency list.
     *
     * <p>Only revalidates a catalog that's already been downloaded; a project that has never run
     * {@code jk library update} keeps resolving against the bundled floor rather than jk silently
     * reaching out to GitHub on its behalf. A conditional GET means the common case (nothing changed
     * upstream) costs one round trip of headers — a 304 — and any failure (offline, unreachable,
     * malformed payload) is swallowed: the existing cache, or the bundled floor if there's none, is
     * good enough to proceed with.
     */
    private static void refreshLibraryRegistry(boolean offline, URI source, Path cacheFile) {
        if (offline) return;
        if (!Files.isRegularFile(cacheFile)) return;
        Path etagFile = LibraryCatalog.etagFileFor(cacheFile);
        try {
            var result = new LibraryRegistryClient(new Http()).fetch(source, etagFile);
            if (result instanceof LibraryRegistryClient.Result.Updated updated) {
                LibraryCatalog.parse(new String(updated.body(), StandardCharsets.UTF_8)); // validate before writing
                writeAtomic(cacheFile, updated.body());
                if (updated.etag() != null) {
                    writeAtomic(etagFile, updated.etag().getBytes(StandardCharsets.UTF_8));
                } else {
                    Files.deleteIfExists(etagFile);
                }
            }
        } catch (Exception ignored) {
            // Fail soft: a stale or bundled catalog is still usable, and `jk lock` shouldn't fail
            // because the library registry is unreachable or handed back something malformed.
        }
    }

    private static void writeAtomic(Path target, byte[] data) throws java.io.IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
