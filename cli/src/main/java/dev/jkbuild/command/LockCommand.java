// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.Jk;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.CommandManager;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.config.GlobalConfig;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.lock.LockfileWriter;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;
import dev.jkbuild.model.WorkspaceMerge;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.resolver.LockOrchestrator;
import dev.jkbuild.resolver.ResolveObserver;
import dev.jkbuild.resolver.VersionSelectors;
import dev.jkbuild.resolver.Versions;
import dev.jkbuild.resolver.pubgrub.UnsatisfiableException;
import dev.jkbuild.resolver.pubgrub.VersionSet;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.run.PhaseStatus;
import dev.jkbuild.runtime.CompileToolchain;
import dev.jkbuild.runtime.GitSourceResolution;
import dev.jkbuild.runtime.RepoGroupBuilder;
import dev.jkbuild.util.JkDirs;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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
                        .hide());
    }

    private static final GoalKey<JkBuild> EFFECTIVE = GoalKey.of("effective-build", JkBuild.class);
    private static final GoalKey<Lockfile> LOCKFILE = GoalKey.of("lockfile", Lockfile.class);

    @Override
    public int run(Invocation in) throws Exception {
        this.features = in.values("features");
        this.noDefaultFeatures = in.isSet("no-default-features");
        this.sources = in.isSet("sources");
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        this.global = GlobalOptions.from(in);

        Path dir = global.workingDir();
        if (!Files.exists(dir.resolve("jk.toml"))) {
            System.err.println("jk lock: no jk.toml in " + dev.jkbuild.cli.PathDisplay.styledRaw(dir));
            return 2;
        }
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);

        JkBuild root;
        try {
            root = JkBuildParser.parse(dir.resolve("jk.toml"));
        } catch (RuntimeException e) {
            System.err.println("jk lock: " + e.getMessage());
            return 2;
        }

        JkBuild effectiveRoot = applyWorkspaceContextIfModule(dir, root);

        GoalConsole.Mode mode = GoalConsole.modeFor(global);
        boolean live = mode == GoalConsole.Mode.AUTO || mode == GoalConsole.Mode.QUIET;

        if (!live) {
            // --verbose / --output json: existing simple-task rendering.
            int result = lockSingleProject(dir, effectiveRoot, cache, "Lock");
            if (result != 0) return result;
            if (effectiveRoot.isWorkspaceRoot()) {
                Map<Path, JkBuild> modules;
                try {
                    modules = WorkspaceLoader.loadModules(dir, effectiveRoot);
                } catch (RuntimeException e) {
                    System.err.println("jk lock: " + e.getMessage());
                    return 2;
                }
                for (Map.Entry<Path, JkBuild> entry : modules.entrySet()) {
                    Path moduleDir = entry.getKey();
                    JkBuild rawModule = entry.getValue();
                    JkBuild effectiveModule =
                            WorkspaceMerge.applyToModule(effectiveRoot, rawModule, modules.values());
                    String moduleLabel = dir.getFileName() + "/" + dir.relativize(moduleDir);
                    int moduleResult = lockSingleProject(moduleDir, effectiveModule, cache, moduleLabel);
                    if (moduleResult != 0) return moduleResult;
                }
            }
            return 0;
        }

        // Live goal-mode TUI: one shared CommandManager spanning root + all workspace modules.
        boolean animate = mode == GoalConsole.Mode.AUTO && GoalConsole.isInteractiveTerminal();
        return runLive(dir, effectiveRoot, cache, animate);
    }

    // ---- live goal-mode TUI ------------------------------------------------

    /**
     * Live path: one {@link CommandManager} in goal mode, one row per module, a shared progress bar
     * calibrated to total packages across all modules, and a completed-package tail.
     */
    private int runLive(Path dir, JkBuild effectiveRoot, Path cache, boolean animate) throws Exception {
        CommandManager view = CommandManager.goal(System.out, "Lock", animate);
        long start = System.nanoTime();

        // Shared counters across all modules.
        AtomicInteger globalLocked = new AtomicInteger(0);
        AtomicInteger globalTotal = new AtomicInteger(0);
        List<String> errorLines = new ArrayList<>();

        String rootCoord = coordLabel(effectiveRoot, dir);
        int exit = lockModuleLive(dir, effectiveRoot, cache, rootCoord, view, globalLocked, globalTotal, errorLines);
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
                return 2;
            }
            for (Map.Entry<Path, JkBuild> entry : modules.entrySet()) {
                Path moduleDir = entry.getKey();
                JkBuild rawModule = entry.getValue();
                JkBuild effectiveModule =
                        WorkspaceMerge.applyToModule(effectiveRoot, rawModule, modules.values());
                String moduleCoord = coordLabel(rawModule, moduleDir);
                exit = lockModuleLive(
                        moduleDir, effectiveModule, cache, moduleCoord, view, globalLocked, globalTotal, errorLines);
                if (exit != 0) {
                    view.finishGoalFailure(lockFailTail(), errorLines);
                    return exit;
                }
            }
        }

        int pkgs = globalLocked.get();
        String depStr = "Resolved " + pkgs + " dependenc" + (pkgs == 1 ? "y" : "ies");
        view.finishGoalSuccess(
                Theme.colorize("Lock successful", Theme.active().success())
                        + " · "
                        + depStr
                        + " "
                        + ConsoleSpec.took(Duration.ofMillis((System.nanoTime() - start) / 1_000_000)));
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
            AtomicInteger globalTotal,
            List<String> errorLines)
            throws Exception {
        // Register and activate this module's row.
        view.addPhaseLabeled(coord, "lock", coord);
        view.phaseRunning(coord, "lock");

        Path lockFile = dir.resolve("jk.lock");

        // Estimate this module's scope and add it to the global denominator.
        AtomicInteger moduleTotal = new AtomicInteger(scopeEstimate(effective, lockFile));
        globalTotal.addAndGet(moduleTotal.get());
        view.progress(globalLocked.get(), globalTotal.get());

        // Observer: feeds per-package completions + updates the bar.
        ResolveObserver observer = new ResolveObserver() {
            @Override
            public void onTotal(int total) {
                // Adjust global denominator by the delta from our estimate.
                int delta = total - moduleTotal.getAndSet(total);
                if (delta != 0) globalTotal.addAndGet(delta);
                view.progress(globalLocked.get(), Math.max(globalTotal.get(), globalLocked.get()));
            }

            @Override
            public void onPackage(String module, String version) {
                // Update active-row label with the coord just resolved.
                view.phaseMessage(coord, "lock", Coords.module(module, version));
                int gn = globalLocked.incrementAndGet();
                int gt = Math.max(globalTotal.get(), gn);
                view.progress(gn, gt);
                // Add to the completion tail (newest-first, capped by MAX_COMPLETIONS).
                String line = lockCompletionLine(gn, gt, module, version);
                if (view.animating()) {
                    view.addCompletion(line);
                } else {
                    view.writeAbove("    " + line);
                }
            }
        };

        // Build and run the lock goal directly (no GoalConsole wrapper).
        Goal goal = buildLockGoal(dir, effective, cache, lockFile, moduleTotal, observer);
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
        // Mirror the exit-code logic from the plain path.
        boolean resolveFailed = result.phases().stream()
                .filter(p -> p.status() == PhaseStatus.FAIL)
                .map(GoalResult.PhaseReport::name)
                .anyMatch("resolve"::equals);
        return resolveFailed ? 6 : 2;
    }

    /** Failure result tail for the Lock chip (GoalWedge prepends "Failed to lock"). */
    private static String lockFailTail() {
        return "dependencies";
    }

    /**
     * A single package's completion line:
     * {@code ✓ [N of Total] group:artifact:version}
     */
    private static String lockCompletionLine(int n, int total, String module, String version) {
        var th = Theme.active();
        String check = Theme.colorize(Glyphs.CHECK + "", th.success());
        String num = String.format("%0" + Integer.toString(Math.max(total, n)).length() + "d", n);
        String count = Theme.colorize("[", th.darkGray())
                + num
                + " of "
                + total
                + Theme.colorize("]", th.darkGray());
        return check + " " + count + " Locked " + Coords.module(module, version);
    }

    /**
     * Best-effort scope estimate for a module: the existing lockfile size (re-run) or declared deps ×
     * transitive expansion factor.
     */
    private static int scopeEstimate(JkBuild effective, Path lockFile) {
        try {
            int n = LockfileReader.read(lockFile).artifacts().size();
            if (n > 0) return n;
        } catch (Exception ignored) {
        }
        try {
            int declared = effective.dependencies().byScope().values().stream()
                    .mapToInt(List::size)
                    .sum();
            return Math.max(5, declared * 8);
        } catch (Exception ignored) {
        }
        return 20;
    }

    /**
     * Display coordinate for a module: {@code group:artifact} from its {@code [project]}, falling
     * back to the directory name.
     */
    private static String coordLabel(JkBuild build, Path dir) {
        try {
            var p = build.project();
            return p.group() + ":" + p.name();
        } catch (Exception e) {
            return dir.getFileName() == null ? dir.toString() : dir.getFileName().toString();
        }
    }

    /**
     * Build the lock goal phases for one module. The {@code observer} feeds per-package events into
     * the caller (either the TUI or a plain observer). The {@code moduleTotal} is updated by the
     * resolve phase's {@code onTotal} callback so the caller can adjust the global bar denominator.
     */
    private Goal buildLockGoal(
            Path dir,
            JkBuild effective,
            Path cache,
            Path lockFile,
            AtomicInteger resolveEstimate,
            ResolveObserver observer) {

        Phase parseBuild = Phase.builder("parse-build")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml");
                    ctx.put(EFFECTIVE, effective);
                    ctx.progress(1);
                })
                .build();

        Phase resolve = Phase.builder("resolve")
                .label("Resolving")
                .kind(PhaseKind.IO)
                .requires("parse-build")
                .scope(resolveEstimate::get)
                .execute(ctx -> {
                    ctx.label("Resolving");
                    JkBuild eff = ctx.require(EFFECTIVE);
                    Cas cas = new Cas(cache);
                    if (global.offline && Files.exists(lockFile)) {
                        try {
                            Lockfile existing = LockfileReader.read(lockFile);
                            requireOfflineSatisfiable(eff, existing, cas);
                            ctx.progress(existing.artifacts().size());
                            ctx.put(LOCKFILE, existing);
                            return;
                        } catch (Exception e) {
                            ctx.error("resolve", e.getMessage());
                            throw new RuntimeException(e);
                        }
                    }
                    RepoGroup baseRepos = RepoGroupBuilder.buildFor(eff, repoUrl, cas);
                    Map<String, String> lockedShas = Map.of();
                    if (Files.exists(lockFile)) {
                        try {
                            lockedShas = GitSourceResolution.lockedImmutableShas(LockfileReader.read(lockFile));
                        } catch (Exception ignored) {
                        }
                    }
                    GitSourceResolution.Prepared prep;
                    try {
                        prep = GitSourceResolution.prepare(
                                eff, baseRepos, cas, CompileToolchain.resolveJavaHome(dir), Jk.VERSION, lockedShas);
                    } catch (Exception e) {
                        ctx.error("resolve", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    RepoGroup repos = prep.repos();
                    LockOrchestrator orchestrator = new LockOrchestrator(repos);
                    // Wrap the caller's observer so it also drives ctx.label/progress
                    // (needed for the bar when running under GoalConsole).
                    ResolveObserver wrappedObserver = new ResolveObserver() {
                        @Override
                        public void onTotal(int total) {
                            int delta = total - resolveEstimate.getAndSet(total);
                            if (delta > 0) ctx.updateScope(delta);
                            observer.onTotal(total);
                        }

                        @Override
                        public void onPackage(String module, String version) {
                            ctx.label("Resolved " + Coords.module(module, version));
                            ctx.progress(1);
                            observer.onPackage(module, version);
                        }
                    };
                    try {
                        Lockfile lock = sources
                                ? orchestrator.lockWithSources(
                                        prep.project(), Jk.VERSION, features, !noDefaultFeatures, wrappedObserver)
                                : orchestrator.lock(
                                        prep.project(), Jk.VERSION, features, !noDefaultFeatures, wrappedObserver);
                        lock = GitSourceResolution.stamp(lock, prep.gitInfoByKey());
                        String kotlinVersion = resolveKotlinVersion(eff, repos);
                        if (kotlinVersion != null) {
                            ctx.label("resolved kotlin " + kotlinVersion);
                            lock = lock.withKotlin(kotlinVersion);
                        }
                        ctx.put(LOCKFILE, lock);
                    } catch (UnsatisfiableException e) {
                        ctx.error("verbatim", e.getMessage());
                        throw new RuntimeException(e);
                    } catch (Exception e) {
                        ctx.error("resolve", e.getMessage());
                        throw new RuntimeException(e);
                    }
                })
                .build();

        Phase lockPlugins = Phase.builder("lock-plugins")
                .kind(PhaseKind.IO)
                .requires("resolve")
                .scope(() -> effective.plugins().isEmpty() ? 0 : effective.plugins().size())
                .execute(ctx -> {
                    var decls = effective.plugins();
                    if (decls.isEmpty()) return;
                    ctx.label("lock plugins");
                    Cas cas = new Cas(cache);
                    RepoGroup repos = RepoGroupBuilder.buildFor(effective, repoUrl, cas);
                    var entries = new ArrayList<Lockfile.PluginEntry>();
                    for (var pd : decls) {
                        ctx.label("lock " + pd.coordinate());
                        var coord = Coordinate.of(pd.group(), pd.name(), pd.version());
                        try {
                            var fetched = repos.tryFetchArtifact(coord)
                                    .orElseThrow(() -> new RuntimeException(
                                            pd.coordinateWithVersion() + " not found in any repo"));
                            entries.add(new Lockfile.PluginEntry(
                                    pd.coordinate(), pd.version(), "sha256:" + fetched.fetched().sha256()));
                        } catch (Exception e) {
                            ctx.error("plugin", pd.coordinate() + " — " + e.getMessage());
                            throw new RuntimeException(e);
                        }
                        ctx.progress(1);
                    }
                    ctx.put(LOCKFILE, ctx.require(LOCKFILE).withPlugins(entries));
                })
                .build();

        Phase write = Phase.builder("write-lockfile")
                .requires("lock-plugins")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("write " + lockFile.getFileName());
                    LockfileWriter.write(ctx.require(LOCKFILE), lockFile);
                    ctx.progress(1);
                })
                .build();

        return Goal.builder("lock")
                .addPhase(parseBuild)
                .addPhase(resolve)
                .addPhase(lockPlugins)
                .addPhase(write)
                .build();
    }

    // ---- plain (non-live) path: unchanged ----------------------------------

    /**
     * Run the three-phase lock pipeline (parse → resolve → write) for one project directory. Used by
     * the non-live path (--verbose / --output json). {@code effective} is the pre-parsed {@link
     * JkBuild} with any {@code workspace:} placeholders already resolved.
     */
    private int lockSingleProject(Path dir, JkBuild effective, Path cache, String label) throws Exception {
        Path lockFile = dir.resolve("jk.lock");

        AtomicInteger resolveEstimate = new AtomicInteger(0);

        Phase parseBuild = Phase.builder("parse-build")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml");
                    ctx.put(EFFECTIVE, effective);
                    ctx.progress(1);
                })
                .build();

        Phase resolve = Phase.builder("resolve")
                .label("Resolving")
                .kind(PhaseKind.IO)
                .requires("parse-build")
                .scope(() -> {
                    try {
                        int n = LockfileReader.read(lockFile).artifacts().size();
                        if (n > 0) {
                            resolveEstimate.set(n);
                            return n;
                        }
                    } catch (Exception ignored) {
                    }
                    try {
                        int declared = effective.dependencies().byScope().values().stream()
                                .mapToInt(List::size)
                                .sum();
                        int estimate = Math.max(5, declared * 8);
                        resolveEstimate.set(estimate);
                        return estimate;
                    } catch (Exception ignored) {
                    }
                    resolveEstimate.set(20);
                    return 20;
                })
                .execute(ctx -> {
                    ctx.label("Resolving");
                    JkBuild eff = ctx.require(EFFECTIVE);
                    Cas cas = new Cas(cache);
                    if (global.offline && Files.exists(lockFile)) {
                        try {
                            Lockfile existing = LockfileReader.read(lockFile);
                            requireOfflineSatisfiable(eff, existing, cas);
                            ctx.progress(existing.artifacts().size());
                            ctx.put(LOCKFILE, existing);
                            return;
                        } catch (Exception e) {
                            ctx.error("resolve", e.getMessage());
                            throw new RuntimeException(e);
                        }
                    }
                    RepoGroup baseRepos = RepoGroupBuilder.buildFor(eff, repoUrl, cas);
                    Map<String, String> lockedShas = Map.of();
                    if (Files.exists(lockFile)) {
                        try {
                            lockedShas = GitSourceResolution.lockedImmutableShas(LockfileReader.read(lockFile));
                        } catch (Exception ignored) {
                        }
                    }
                    GitSourceResolution.Prepared prep;
                    try {
                        prep = GitSourceResolution.prepare(
                                eff, baseRepos, cas, CompileToolchain.resolveJavaHome(dir), Jk.VERSION, lockedShas);
                    } catch (Exception e) {
                        ctx.error("resolve", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    RepoGroup repos = prep.repos();
                    LockOrchestrator orchestrator = new LockOrchestrator(repos);
                    ResolveObserver observer = new ResolveObserver() {
                        @Override
                        public void onTotal(int total) {
                            int delta = total - resolveEstimate.get();
                            if (delta > 0) ctx.updateScope(delta);
                        }

                        @Override
                        public void onPackage(String module, String version) {
                            ctx.label("Resolved " + Coords.module(module, version));
                            ctx.progress(1);
                        }
                    };
                    try {
                        Lockfile lock = sources
                                ? orchestrator.lockWithSources(
                                        prep.project(), Jk.VERSION, features, !noDefaultFeatures, observer)
                                : orchestrator.lock(prep.project(), Jk.VERSION, features, !noDefaultFeatures, observer);
                        lock = GitSourceResolution.stamp(lock, prep.gitInfoByKey());
                        String kotlinVersion = resolveKotlinVersion(eff, repos);
                        if (kotlinVersion != null) {
                            ctx.label("resolved kotlin " + kotlinVersion);
                            lock = lock.withKotlin(kotlinVersion);
                        }
                        ctx.put(LOCKFILE, lock);
                    } catch (UnsatisfiableException e) {
                        ctx.error("verbatim", e.getMessage());
                        throw new RuntimeException(e);
                    } catch (Exception e) {
                        ctx.error("resolve", e.getMessage());
                        throw new RuntimeException(e);
                    }
                })
                .build();

        Phase lockPlugins = Phase.builder("lock-plugins")
                .kind(PhaseKind.IO)
                .requires("resolve")
                .scope(() -> effective.plugins().isEmpty() ? 0 : effective.plugins().size())
                .execute(ctx -> {
                    var decls = effective.plugins();
                    if (decls.isEmpty()) return;
                    ctx.label("lock plugins");
                    Cas cas = new Cas(cache);
                    dev.jkbuild.repo.RepoGroup repos =
                            dev.jkbuild.runtime.RepoGroupBuilder.buildFor(effective, repoUrl, cas);
                    var entries = new java.util.ArrayList<Lockfile.PluginEntry>();
                    for (var pd : decls) {
                        ctx.label("lock " + pd.coordinate());
                        var coord = dev.jkbuild.model.Coordinate.of(pd.group(), pd.name(), pd.version());
                        try {
                            var fetched = repos.tryFetchArtifact(coord)
                                    .orElseThrow(() -> new RuntimeException(
                                            pd.coordinateWithVersion() + " not found in any repo"));
                            entries.add(new Lockfile.PluginEntry(
                                    pd.coordinate(),
                                    pd.version(),
                                    "sha256:" + fetched.fetched().sha256()));
                        } catch (Exception e) {
                            ctx.error("plugin", pd.coordinate() + " — " + e.getMessage());
                            throw new RuntimeException(e);
                        }
                        ctx.progress(1);
                    }
                    ctx.put(LOCKFILE, ctx.require(LOCKFILE).withPlugins(entries));
                })
                .build();

        Phase write = Phase.builder("write-lockfile")
                .requires("lock-plugins")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("write " + lockFile.getFileName());
                    LockfileWriter.write(ctx.require(LOCKFILE), lockFile);
                    ctx.progress(1);
                })
                .build();

        Goal goal = Goal.builder("lock")
                .addPhase(parseBuild)
                .addPhase(resolve)
                .addPhase(lockPlugins)
                .addPhase(write)
                .build();

        ConsoleSpec spec = new ConsoleSpec(
                label,
                r -> {
                    Lockfile lock = goal.get(LOCKFILE).orElseThrow();
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

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache, spec);
        if (!result.success()) {
            String failed = result.phases().stream()
                    .filter(p -> p.status() == PhaseStatus.FAIL)
                    .map(GoalResult.PhaseReport::name)
                    .findFirst()
                    .orElse("?");
            return failed.equals("resolve") ? 6 : 2;
        }
        return 0;
    }

    // ---- shared helpers ----------------------------------------------------

    /**
     * When invoked from a workspace module (not the root), discover the enclosing workspace and apply
     * module context: resolve {@code workspace:} placeholders and filter out sibling-internal dep
     * coords so the solver only sees external Maven coordinates. Returns {@code project} unchanged if
     * it is a workspace root or no enclosing workspace is found.
     */
    private static JkBuild applyWorkspaceContextIfModule(Path dir, JkBuild project) {
        if (project.isWorkspaceRoot()) return project;
        try {
            var rootOpt = WorkspaceLocator.findRoot(dir);
            if (rootOpt.isEmpty()) return project;
            Path wsRoot = rootOpt.get();
            JkBuild wsRootBuild = JkBuildParser.parse(wsRoot.resolve("jk.toml"));
            if (!wsRootBuild.isWorkspaceRoot()) return project;
            var siblings = WorkspaceLoader.loadModules(wsRoot, wsRootBuild);
            return WorkspaceMerge.applyToModule(wsRootBuild, project, siblings.values());
        } catch (Exception ignored) {
            return project;
        }
    }

    /**
     * Resolve the project's {@code kotlin} version selector to a concrete Kotlin compiler release.
     * Returns {@code null} for a Java project or when resolution can't complete.
     */
    private static String resolveKotlinVersion(JkBuild effective, RepoGroup repos) {
        if (!effective.project().isKotlin()) return null;
        VersionSelector selector = effective.project().kotlin();
        if (selector instanceof VersionSelector.Exact exact) {
            return exact.version();
        }
        VersionSet set = VersionSelectors.toVersionSet(selector);
        Coordinate coord = Coordinate.of("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "any");
        List<String> available;
        try {
            available = repos.availableVersions(coord);
        } catch (java.io.IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return available.stream()
                .filter(set::contains)
                .filter(Versions::isStable)
                .max(Versions::compare)
                .or(() -> available.stream().filter(set::contains).max(Versions::compare))
                .orElse(null);
    }

    /**
     * Throw if an existing lockfile can't be honored entirely from the local CAS while offline.
     */
    private static void requireOfflineSatisfiable(JkBuild effective, Lockfile lock, Cas cas) {
        java.util.Set<String> locked = new java.util.HashSet<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            locked.add(pkg.name());
        }
        for (var entry : effective.dependencies().byScope().entrySet()) {
            if (entry.getKey() == Scope.PLATFORM) continue;
            for (var dep : entry.getValue()) {
                if (!locked.contains(dep.module())) {
                    throw new IllegalStateException("offline: "
                            + dep.module()
                            + " is declared in jk.toml but not in jk.lock; run `jk lock` online first");
                }
            }
        }
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            String checksum = pkg.checksum();
            if (checksum == null) continue;
            String hex = checksum.startsWith("sha256:") ? checksum.substring("sha256:".length()) : checksum;
            if (!cas.contains(hex)) {
                throw new IllegalStateException("offline: "
                        + pkg.name()
                        + ":"
                        + pkg.version()
                        + " is locked but its artifact isn't cached; run `jk sync` online first");
            }
        }
    }
}
