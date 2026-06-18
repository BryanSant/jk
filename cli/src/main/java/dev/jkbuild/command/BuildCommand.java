// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.runtime.BuildGraph;
import dev.jkbuild.runtime.BuildPipeline;
import dev.jkbuild.util.JkThreads;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.AggregateContext;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.tui.CommandManager;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.test.JUnitLauncher;
import dev.jkbuild.util.JkDirs;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code jk build} — smart meta-goal that orchestrates the full pipeline:
 *
 * <ol>
 *   <li>{@code parse-build} — load {@code jk.toml}; if {@code jk.lock} is
 *       absent, run the lock resolver inline (same as {@code jk lock}).</li>
 *   <li>{@code sync-deps} (IO) — ensure all locked artifacts are in the CAS;
 *       virtually a no-op when everything is already cached.</li>
 *   <li>{@code ensure-jdk} (IO, parallel with sync-deps) — install the
 *       pinned JDK when it is not yet on disk.</li>
 *   <li>{@code compile-java} (CPU) — javac, with action-cache + freshness
 *       stamp skip layers.</li>
 *   <li>{@code compile-kotlin} (CPU) — no-op when no {@code .kt} sources.</li>
 *   <li>{@code copy-resources} (CPU) — mirror {@code src/main/resources}.</li>
 *   <li>{@code compile-test} (CPU) — compile {@code src/test/java}.</li>
 *   <li>{@code run-tests} (IO) — fork JUnit Platform runner(s).</li>
 *   <li>{@code package-jar} (CPU) — assemble the project jar.</li>
 *   <li>{@code native-image} (IO, only when {@code native = "always"}) —
 *       GraalVM native-image compilation.</li>
 *   <li>{@code write-stamp} (SYNC) — refresh the freshness stamp.</li>
 * </ol>
 */
public final class BuildCommand implements CliCommand {

    @Override public String name() { return "build"; }
    @Override public String description() { return "Compile, test, and package the project"; }
    @Override public List<Opt> options() {
        return List.of(
                Opt.value("<name>", "Build profile to apply. Default: auto (ci if CI=true, else none).", "--profile"),
                Opt.value("<N>", "Number of test-runner JVMs to fork in parallel. Default 1.", "-w", "--workers"),
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir").hide(),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir").hide(),
                Opt.flag("Skip compiling and running tests.", "--skip-tests"),
                Opt.flag("Build workspace modules one at a time (the rich serial view).", "--no-parallel"),
                Opt.flag("Run modules' tests concurrently too (default: tests serialized).", "--parallel-tests"));
    }

    String profileName;
    Integer workers;
    Path cacheDir;
    Path jdksDir;
    dev.jkbuild.cli.BuildOptions buildOpts;
    GlobalOptions global;
    boolean noParallel;

    // ---- GoalKeys -------------------------------------------------------
    //
    // BuildPipeline owns the phase DAG and all of its keys; BuildCommand only
    // reads a few results back out of the finished goal to render its result
    // line. GoalKeys are name-keyed, so these match BuildPipeline's by name.

    private static final GoalKey<String> BUILD_OUTCOME = GoalKey.of("build-outcome", String.class);
    private static final GoalKey<Path>   JAR_PATH      = GoalKey.of("jar-path",      Path.class);
    private static final GoalKey<BuildLayout> LAYOUT   = GoalKey.of("layout",        BuildLayout.class);
    private static final GoalKey<JUnitLauncher.Result> TEST_RESULT =
            GoalKey.of("test-result", JUnitLauncher.Result.class);

    // ---- Entry point ----------------------------------------------------

    @Override
    public int run(Invocation in) throws Exception {
        this.profileName = in.value("profile").orElse(null);
        this.workers = in.value("workers").map(Integer::parseInt).orElse(null);
        this.cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.buildOpts = new dev.jkbuild.cli.BuildOptions();
        this.buildOpts.skipTests = in.isSet("skip-tests");
        this.noParallel = in.isSet("no-parallel");
        this.global = GlobalOptions.from(in);
        // Opt-in: run modules' tests concurrently. Default serializes them
        // (shared ports/locks/fixtures) — see BuildPipeline's test gate.
        BuildPipeline.setParallelTests(in.isSet("parallel-tests"));
        Path startDir = global.workingDir();
        Path buildFile = startDir.resolve("jk.toml");
        if (!Files.exists(buildFile)) {
            System.err.println("jk build: no jk.toml in " + startDir);
            return 2;
        }
        // Peek at the manifest before committing to a per-dir build. A
        // workspace root dispatches to runWorkspaceBuild. A workspace member
        // also redirects — jk build from any member builds the whole workspace
        // in topological order, same as running from the root.
        JkBuild peek;
        try {
            peek = JkBuildParser.parse(buildFile);
        } catch (RuntimeException e) {
            System.err.println("jk build: " + e.getMessage());
            return 2;
        }
        if (peek.isWorkspaceRoot()) {
            return buildWorkspace(startDir, peek);
        }
        // Member redirect: discover the enclosing workspace and build from there.
        try {
            var rootOpt = WorkspaceLocator.findRoot(startDir);
            if (rootOpt.isPresent()) {
                Path root = rootOpt.get();
                if (!global.outputIsJson()) {
                    System.err.println("jk build: building workspace from "
                            + root.getFileName()
                            + " (member: " + startDir.getFileName() + ")");
                }
                return buildWorkspace(root, JkBuildParser.parse(root.resolve("jk.toml")));
            }
        } catch (java.io.IOException e) {
            // Workspace discovery failed — fall through to single-project build.
        }
        int dep = buildCompositeDeps(startDir, peek);
        if (dep != 0) return dep;
        return runForDir(startDir);
    }

    /** Default: parallel graph build; {@code --no-parallel}: the serial rich aggregate view. */
    private int buildWorkspace(Path root, JkBuild rootBuild) throws Exception {
        return noParallel ? runWorkspaceBuild(root, rootBuild) : runGraphParallel(root, rootBuild);
    }

    private static final Object OUT_LOCK = new Object();

    /** One unit's build outcome, with its buffered output (flushed together on completion). */
    private record UnitOutcome(String coord, boolean success, int exitCode, long millis, List<String> output) {}

    /**
     * Build the whole composite + workspace graph in parallel (Option B): one
     * {@link BuildGraph}, scheduled by topological level, independent units built
     * concurrently on {@link JkThreads#io()} (their CPU work shares the bounded cpu
     * pool, so no oversubscription). Each unit runs buffered — its output is
     * captured and flushed as one contiguous block on completion, so parallel logs
     * never interleave. Tests are serialized across units by default
     * (BuildPipeline's gate); {@code --parallel-tests} lifts that.
     */
    private int runGraphParallel(Path entryDir, JkBuild entryBuild) throws Exception {
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        BuildGraph.Result graph;
        try {
            graph = BuildGraph.resolve(entryDir, entryBuild, cache.resolve("git"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("jk build: interrupted resolving the build graph");
            return 2;
        }
        if (graph.hasErrors()) {
            for (String err : graph.errors()) System.err.println(ConsoleSpec.errorLine("composite", err));
            return 2;
        }
        List<BuildGraph.BuildUnit> units = graph.topoOrder();
        if (units.isEmpty()) {
            System.out.println("(workspace declares no members)");
            return 0;
        }
        // --output json / --verbose keep the buffered, non-live path (NDJSON streams
        // / full per-phase logs); AUTO/QUIET get the live aggregate view (a single
        // spinner header + bar + a tree of the members building right now).
        GoalConsole.Mode mode = GoalConsole.modeFor(global);
        return (mode == GoalConsole.Mode.AUTO || mode == GoalConsole.Mode.QUIET)
                ? runGraphLive(units, graph.edges(), mode)
                : runGraphPlain(units, graph.edges());
    }

    /** Buffered, append-only scheduler: each unit flushes a block + a [k/N] line on completion. */
    private int runGraphPlain(List<BuildGraph.BuildUnit> units, Map<Path, Set<Path>> edges) {
        Set<Path> unitDirs = new java.util.HashSet<>();
        for (BuildGraph.BuildUnit u : units) unitDirs.add(u.dir());
        Set<Path> done = java.util.concurrent.ConcurrentHashMap.newKeySet();
        List<BuildGraph.BuildUnit> remaining = new ArrayList<>(units);
        int total = units.size();
        int completed = 0;
        long start = System.nanoTime();
        while (!remaining.isEmpty()) {
            List<BuildGraph.BuildUnit> ready = remaining.stream()
                    .filter(u -> edges.getOrDefault(u.dir(), Set.of()).stream()
                            .filter(unitDirs::contains).allMatch(done::contains))
                    .toList();
            List<java.util.concurrent.CompletableFuture<UnitOutcome>> futures = new ArrayList<>();
            for (BuildGraph.BuildUnit u : ready) {
                futures.add(java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> buildUnit(u), JkThreads.io()));
            }
            for (java.util.concurrent.CompletableFuture<UnitOutcome> f : futures) {
                UnitOutcome o = f.join();
                report(o, ++completed, total);
                if (!o.success()) {
                    System.err.println("jk build: " + o.coord() + " failed (exit " + o.exitCode() + ")");
                    return o.exitCode();
                }
            }
            for (BuildGraph.BuildUnit u : ready) done.add(u.dir());
            remaining.removeAll(ready);
        }
        System.out.println(Theme.colorize("✓", Theme.active().success())
                + " Build Successful: built " + total + " module" + (total == 1 ? "" : "s")
                + " " + elapsedSince(start));
        return 0;
    }

    /**
     * Live aggregate scheduler: one {@link CommandManager} (goal mode) shows a
     * spinner header + a single bar calibrated to the whole graph + a tree of the
     * members building <em>right now</em>; the tree grows to the parallelism limit
     * and shrinks back to 0 as units drain. Each unit's process output is buffered
     * and flushed (with a ✓/✗ {@code [k/N]} line) above the region when it
     * completes — so concurrent logs never interleave. On a non-interactive
     * terminal nothing animates; the same blocks + lines print append-only.
     */
    private int runGraphLive(List<BuildGraph.BuildUnit> units, Map<Path, Set<Path>> edges,
                             GoalConsole.Mode mode) throws Exception {
        Set<Path> unitDirs = new java.util.HashSet<>();
        for (BuildGraph.BuildUnit u : units) unitDirs.add(u.dir());
        boolean animate = mode == GoalConsole.Mode.AUTO && GoalConsole.isInteractiveTerminal();
        CommandManager view = CommandManager.goal(System.out, "Build", animate);
        AggregateContext agg = new AggregateContext(view);
        long start = System.nanoTime();
        int total = units.size();

        // Pre-scan: prepare every unit's goal and sum its estimated weight so the
        // bar calibrates to the whole-graph total and advances 0→100% across it.
        Map<Path, PreparedMember> prepared = new LinkedHashMap<>();
        long totalWeight = 0;
        for (BuildGraph.BuildUnit u : units) {
            PreparedMember pm = prepareMember(u.dir(), u.isDependency() || buildOpts.skipTests);
            if (pm == null) {
                view.finishFailure("No jk.toml in " + u.dir() + " " + elapsedSince(start));
                return 2;
            }
            prepared.put(u.dir(), pm);
            totalWeight += pm.barWeight();
        }
        agg.calibrate(totalWeight);

        Set<Path> done = java.util.concurrent.ConcurrentHashMap.newKeySet();
        List<BuildGraph.BuildUnit> remaining = new ArrayList<>(units);
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
        while (!remaining.isEmpty()) {
            List<BuildGraph.BuildUnit> ready = remaining.stream()
                    .filter(u -> edges.getOrDefault(u.dir(), Set.of()).stream()
                            .filter(unitDirs::contains).allMatch(done::contains))
                    .toList();
            List<java.util.concurrent.CompletableFuture<UnitOutcome>> futures = new ArrayList<>();
            for (BuildGraph.BuildUnit u : ready) {
                PreparedMember pm = prepared.get(u.dir());
                futures.add(java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> buildUnitLive(u, pm, agg, view, completed, total), JkThreads.io()));
            }
            UnitOutcome failure = null;
            for (java.util.concurrent.CompletableFuture<UnitOutcome> f : futures) {
                UnitOutcome o = f.join();
                if (!o.success() && failure == null) failure = o;
            }
            if (failure != null) {
                view.finishFailure(buildFailedAt(failure.coord(), start));
                for (GoalResult.Diagnostic d : agg.lastErrors()) {
                    if ("test-failure".equals(d.code())) continue;  // already printed by run-tests
                    System.err.println(ConsoleSpec.renderError(d));
                }
                return failure.exitCode();
            }
            for (BuildGraph.BuildUnit u : ready) done.add(u.dir());
            remaining.removeAll(ready);
        }
        view.finishSuccess("built " + total + " module" + (total == 1 ? "" : "s")
                + " " + elapsedSince(start));
        return 0;
    }

    /**
     * Build one unit feeding the shared live {@code view}; buffer its output and,
     * on completion, flush the block + a colored ✓/✗ {@code [k/N]} line above the
     * region (one atomic {@code writeAbove} so a unit's lines never split).
     */
    private UnitOutcome buildUnitLive(BuildGraph.BuildUnit unit, PreparedMember pm, AggregateContext agg,
                                      CommandManager view, java.util.concurrent.atomic.AtomicInteger completed,
                                      int total) {
        List<String> buf = java.util.Collections.synchronizedList(new ArrayList<>());
        long t0 = System.nanoTime();
        boolean ok;
        int exit;
        try {
            GoalResult result = GoalConsole.runGoalIntoBuffered(
                    pm.goal(), pm.cache(), unit.coord(), agg, pm.barWeight(), buf);
            ok = result.success();
            exit = ok ? 0 : exitCodeFor(pm.goal());
        } catch (RuntimeException e) {
            synchronized (buf) { buf.add("  ✗ " + (e.getMessage() == null ? e.toString() : e.getMessage())); }
            ok = false;
            exit = 1;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        int k = completed.incrementAndGet();
        StringBuilder block = new StringBuilder();
        synchronized (buf) { for (String l : buf) block.append(l).append('\n'); }
        block.append(completionLine(ok, k, total, unit.coord(), ms));
        view.writeAbove(block.toString());
        return new UnitOutcome(unit.coord(), ok, exit, ms, List.of());
    }

    /** Build one graph unit (dependency units compile-only) with output buffered. */
    private UnitOutcome buildUnit(BuildGraph.BuildUnit unit) {
        PreparedMember pm = prepareMember(unit.dir(), unit.isDependency() || buildOpts.skipTests);
        if (pm == null) {
            return new UnitOutcome(unit.coord(), false, 2, 0, List.of("no jk.toml in " + unit.dir()));
        }
        long t0 = System.nanoTime();
        try {
            GoalConsole.Buffered b = GoalConsole.runGoalBuffered(pm.goal(), pm.cache());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            int exit = b.result().success() ? 0 : exitCodeFor(pm.goal());
            return new UnitOutcome(unit.coord(), b.result().success(), exit, ms, b.output());
        } catch (RuntimeException e) {
            return new UnitOutcome(unit.coord(), false, 1, (System.nanoTime() - t0) / 1_000_000,
                    List.of("  ✗ " + (e.getMessage() == null ? e.toString() : e.getMessage())));
        }
    }

    /** Test failures exit 4; everything else exits 1 (mirrors {@link #runPrepared}). */
    private static int exitCodeFor(Goal goal) {
        var testResult = goal.get(TEST_RESULT).orElse(null);
        return testResult != null && !testResult.allPassed() ? 4 : 1;
    }

    /** Flush a finished unit's buffered output, then a one-line [k/N] result. Serialized. */
    private void report(UnitOutcome o, int k, int total) {
        if (global.outputIsJson()) return;
        synchronized (OUT_LOCK) {
            for (String line : o.output()) System.out.println(line);
            System.out.println(completionLine(o.success(), k, total, o.coord(), o.millis()));
        }
    }

    /**
     * A finished unit's scroll-back line: {@code ✓ [01/16] group:artifact took 16ms}.
     * No leading indent (it's complete, not active); the numerator is zero-padded
     * to the denominator's width; the duration is normalized like every other jk
     * duration ({@link ConsoleSpec#took}). The only color is the green check at
     * the start and the bright-black {@code took …} at the end — the {@code [k/N]}
     * count is plain and the {@code group:artifact} is plain with a strikethrough
     * to mark it done. A failed unit keeps the red cross and {@code — failed}.
     */
    private static String completionLine(boolean ok, int index, int total, String coord, long millis) {
        var th = Theme.active();
        String mark = Theme.colorize(ok ? "✓" : "✗", ok ? th.success() : th.error());
        String num = String.format("%0" + Integer.toString(total).length() + "d", index);
        StringBuilder sb = new StringBuilder();
        sb.append(mark).append(' ')
                .append('[').append(num).append('/').append(total).append(']')
                .append(' ');
        if (ok) {
            sb.append(Theme.colorize(coord, th.plainWhite().crossedOut()))
                    .append(' ').append(ConsoleSpec.took(java.time.Duration.ofMillis(millis)));
        } else {
            sb.append(CommandManager.coloredMember(coord))
                    .append(' ').append(Theme.colorize("— failed", th.error()));
        }
        return sb.toString();
    }

    /**
     * Build every member of the workspace whose root is {@code workspaceRoot}.
     * Members compile in topological order computed from each member's
     * inter-sibling deps (a sibling listed as a regular Maven coord whose
     * group+artifact match another member's {@code [project]}). Each
     * member's jar lands at {@code <workspaceRoot>/target/} per the
     * {@link BuildLayout} contract.
     *
     * <p>If the root manifest also declares its own {@code [project]} with
     * source files, that build is skipped — the workspace root is
     * coordinator-only here. (We may revisit this once virtual workspaces
     * land; for now the assumption matches every multi-module JVM project
     * we've seen.)
     */
    private int runWorkspaceBuild(Path workspaceRoot, JkBuild root) throws Exception {
        Map<Path, JkBuild> membersByDir;
        try {
            membersByDir = WorkspaceLoader.loadMembers(workspaceRoot, root);
        } catch (RuntimeException e) {
            System.err.println("jk build: " + e.getMessage());
            return 2;
        }
        if (membersByDir.isEmpty()) {
            System.out.println("(workspace declares no members)");
            return 0;
        }
        // Build any composite (path / branch-git) dependency units the workspace
        // (or its members) declare, before the members that consume them.
        int dep = buildCompositeDeps(workspaceRoot, root);
        if (dep != 0) return dep;
        List<Path> sorted = topoSortMembers(membersByDir);
        GoalConsole.Mode mode = GoalConsole.modeFor(global);

        // --output json / --verbose keep per-member rendering (NDJSON streams,
        // verbose wants the full per-phase log). Banners separate the members.
        if (mode != GoalConsole.Mode.AUTO && mode != GoalConsole.Mode.QUIET) {
            for (int i = 0; i < sorted.size(); i++) {
                Path memberDir = sorted.get(i);
                System.out.println();
                System.out.println("══ " + workspaceRoot.relativize(memberDir)
                        + " (" + (i + 1) + "/" + sorted.size() + ") ══");
                int exit = runForDir(memberDir);
                if (exit != 0) {
                    System.err.println("jk build: " + workspaceRoot.relativize(memberDir)
                            + " failed (exit " + exit + ")");
                    return exit;
                }
            }
            return 0;
        }

        // AUTO / QUIET: every member feeds ONE aggregate view (spinner header +
        // single bar + merged phase list). Settle it once after the last member.
        boolean animate = mode == GoalConsole.Mode.AUTO && GoalConsole.isInteractiveTerminal();
        CommandManager view = CommandManager.goal(System.out, "Build", animate);
        AggregateContext agg = new AggregateContext(view);
        int built = 0;
        long buildStart = System.nanoTime();
        // Route every member's phase/process output above the one shared region.
        try (var cap = view.captureOutput()) {
            // Breadth-first pre-scan — build every member's goal and sum its
            // estimated ticks so the bar calibrates to the whole-workspace total
            // and advances 0→100% without resetting per member. These are the very
            // goals we then run in-process, one member at a time.
            Map<Path, PreparedMember> prepared = new LinkedHashMap<>();
            long total = 0;
            for (Path memberDir : sorted) {
                PreparedMember pm;
                try {
                    pm = prepareMember(memberDir);
                } catch (Exception e) {
                    view.finishFailure(Theme.colorize("Build failed", Theme.active().error())
                            + " " + elapsedSince(buildStart));
                    throw e;
                }
                if (pm == null) {
                    view.finishFailure("No jk.toml in "
                            + workspaceRoot.relativize(memberDir) + " " + elapsedSince(buildStart));
                    return 2;
                }
                total += pm.barWeight();
                prepared.put(memberDir, pm);
            }
            agg.calibrate(total);

            for (Path memberDir : sorted) {
                String member = workspaceRoot.relativize(memberDir).toString();
                PreparedMember pm = prepared.get(memberDir);
                int exit;
                try {
                    exit = runPrepared(pm, agg);
                } catch (Exception e) {
                    view.finishFailure(buildFailedAt(member, buildStart));
                    throw e;
                }
                if (exit != 0) {
                    view.finishFailure(buildFailedAt(member, buildStart));
                    for (GoalResult.Diagnostic d : agg.lastErrors()) {
                        // Per-test failures (code "test-failure") were already printed
                        // in full by the run-tests phase; keep them for --output json
                        // but don't echo them again here.
                        if ("test-failure".equals(d.code())) continue;
                        System.err.println(ConsoleSpec.renderError(d));
                    }
                    return exit;
                }
                built++;
            }
        }
        String duration = " " + elapsedSince(buildStart);
        // "✓ Build Successful: " prefix costs ~20 visible columns.
        String msg = workspaceSummary(root, workspaceRoot, sorted, view.width() - 20, duration);
        view.finishSuccess(msg + duration);
        return 0;
    }

    /**
     * "Built jktest and its :cli member"
     * "Built jktest and its :cli, :core members"
     * "Built jktest and its :cli, :runtime and 1 other member"
     * "Built jktest and its :cli, :runtime and 4 other members"
     *
     * Falls back to "Built jktest and its N members" when {@code maxCols} is too
     * small to fit the named form (including the {@code durationSuffix}).
     */
    private static String workspaceSummary(JkBuild root, Path workspaceRoot,
                                           List<Path> members, int maxCols, String durationSuffix) {
        String rootName = root.project().name();
        if (rootName.isBlank()) rootName = workspaceRoot.getFileName().toString();
        String cyanRoot = Theme.colorize(rootName, Theme.active().cyan());

        int n = members.size();
        if (n == 0) return "Built " + cyanRoot;

        String m0 = Theme.colorize(":" + workspaceRoot.relativize(members.get(0)),
                Theme.active().cyan());
        String candidate;
        if (n == 1) {
            candidate = "Built " + cyanRoot + " and its " + m0 + " member";
        } else {
            String m1 = Theme.colorize(":" + workspaceRoot.relativize(members.get(1)),
                    Theme.active().cyan());
            if (n == 2) {
                candidate = "Built " + cyanRoot + " and its " + m0 + ", " + m1 + " members";
            } else {
                int others = n - 2;
                candidate = "Built " + cyanRoot + " and its " + m0 + ", " + m1
                        + " and " + others + " other " + (others == 1 ? "member" : "members");
            }
        }
        if (visibleLength(candidate + durationSuffix) <= maxCols) return candidate;

        // Terminal too narrow: compact form with just the count.
        return "Built " + cyanRoot + " and its " + n + " " + (n == 1 ? "member" : "members");
    }

    /** Count visible (non-ANSI-escape) characters in {@code s}. */
    private static int visibleLength(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '\033' && i + 1 < s.length() && s.charAt(i + 1) == '[') {
                i += 2;
                while (i < s.length() && !Character.isLetter(s.charAt(i))) i++;
                if (i < s.length()) i++; // consume the final letter
            } else {
                count++;
                i++;
            }
        }
        return count;
    }

    /**
     * Order workspace members so each builds after its sibling deps.
     * Kahn's algorithm against the in-workspace dep graph. Sibling
     * matches are by full Maven coord ({@code group:artifact}) — members
     * declare sibling deps explicitly with inline coords, no
     * {@code .workspace = true} shorthand needed.
     *
     * <p>The graph also includes {@code [build].order-after} edges: build-order-only
     * prerequisites (by project name or {@code group:artifact}) that carry no
     * classpath or lockfile weight — used when a module must build after a sibling
     * it doesn't actually depend on (e.g. to embed that sibling's artifact hash).
     *
     * <p>Cycles (which the workspace's
     * {@link dev.jkbuild.config.WorkspaceLoader} doesn't currently
     * detect) result in any unsorted members being appended in
     * declaration order so the build still attempts to make progress.
     */
    static List<Path> topoSortMembers(Map<Path, JkBuild> membersByDir) {
        Map<String, Path> dirByCoord = new HashMap<>();
        Map<String, Path> dirByName  = new HashMap<>(); // for workspace: references
        for (var e : membersByDir.entrySet()) {
            String coord = e.getValue().project().group()
                    + ":" + e.getValue().project().name();
            dirByCoord.put(coord, e.getKey());
            dirByName.put(e.getValue().project().name(), e.getKey());
        }
        Map<Path, Set<Path>> requires = new LinkedHashMap<>();
        for (var e : membersByDir.entrySet()) {
            Set<Path> prereqs = new LinkedHashSet<>();
            for (Scope scope : Scope.values()) {
                for (Dependency d : e.getValue().dependencies().of(scope)) {
                    String module = d.module();
                    Path depDir = dirByCoord.get(module);
                    // workspace: deps use "workspace:<name>" — resolve by bare name
                    if (depDir == null && module.startsWith("workspace:")) {
                        depDir = dirByName.get(module.substring("workspace:".length()));
                    }
                    if (depDir != null && !depDir.equals(e.getKey())) {
                        prereqs.add(depDir);
                    }
                }
            }
            // [build].order-after (+ [build.embed-sha] sources): build-order-only
            // edges (no classpath/lock). Each entry names a sibling by project name
            // or group:artifact coord.
            for (String ref : e.getValue().build().allOrderAfter()) {
                Path depDir = dirByCoord.get(ref);
                if (depDir == null) depDir = dirByName.get(ref);
                if (depDir != null && !depDir.equals(e.getKey())) {
                    prereqs.add(depDir);
                }
            }
            requires.put(e.getKey(), prereqs);
        }
        Map<Path, Integer> remainingPrereqs = new HashMap<>();
        for (var e : requires.entrySet()) {
            remainingPrereqs.put(e.getKey(), e.getValue().size());
        }
        java.util.Deque<Path> queue = new java.util.ArrayDeque<>();
        for (var e : remainingPrereqs.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }
        List<Path> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            Path next = queue.removeFirst();
            sorted.add(next);
            for (var e : requires.entrySet()) {
                if (e.getValue().contains(next)) {
                    int rem = remainingPrereqs.merge(e.getKey(), -1, Integer::sum);
                    if (rem == 0) queue.add(e.getKey());
                }
            }
        }
        if (sorted.size() != membersByDir.size()) {
            // Cycle. Fall back to declaration order for the stragglers
            // so the build still tries to make progress.
            for (Path p : membersByDir.keySet()) {
                if (!sorted.contains(p)) sorted.add(p);
            }
        }
        return sorted;
    }

    /**
     * Build the entry's transitive composite ({@code path} / branch-git) dependency
     * units from source — compile-only, in dependency order — via the SAME real
     * pipeline as any project ({@code prepareMember} → {@code coreBuilder}). jk's
     * {@code includeBuild} analog. The consumer/members then locate these jars on
     * their classpath ({@code CompositeLocator}). No-op when none are declared.
     * Returns 0 on success, else an exit code (errors already printed).
     */
    private int buildCompositeDeps(Path entryDir, JkBuild entry) throws Exception {
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        return CompositeBuild.buildDependencies(entryDir, entry, cache, jdksDir, profileName, global);
    }

    private int runForDir(Path dir) throws Exception {
        return runForDir(dir, null);
    }

    /**
     * Build one project directory. When {@code agg} is non-null this is a
     * workspace member whose events feed the shared aggregate view rather than
     * a per-member progress display.
     */
    private int runForDir(Path dir, AggregateContext agg) throws Exception {
        Path buildFile = dir.resolve("jk.toml");
        if (!Files.exists(buildFile)) {
            System.err.println("jk build: no jk.toml in " + dir);
            return 2;
        }
        return runPrepared(prepareMember(dir), agg);
    }

    /**
     * Construct (but do not run) a member's build goal: inputs → core phases →
     * declared tails. Returns {@code null} when {@code dir} has no {@code jk.toml}.
     * Split out of {@link #runForDir} so the workspace path can build every
     * member's goal up front and sum {@link Goal#estimatedTotalWeight()} to
     * calibrate the shared progress bar before any member runs.
     */
    private PreparedMember prepareMember(Path dir) {
        return prepareMember(dir, buildOpts.skipTests);
    }

    /**
     * As {@link #prepareMember(Path)} but with an explicit {@code skipTests} — used
     * to build composite dependency units compile-only (a dependency's tests aren't
     * run when it's consumed as a source dependency).
     */
    private PreparedMember prepareMember(Path dir, boolean skipTests) {
        try { dir = dir.toRealPath(); } catch (java.io.IOException ignored) {}
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Path buildFile = dir.resolve("jk.toml");
        if (!Files.exists(buildFile)) return null;
        Path lockFile = dir.resolve("jk.lock");
        int workerCount = workers != null && workers > 0 ? workers : 1;
        // Lexical pre-discovery so the run-tests phase's scope is known before
        // any phase runs — see TestCommand.estimateTestCount.
        int estimatedTestCount = skipTests ? 0
                : TestCommand.estimateTestCount(dir.resolve("src/test/java"));
        BuildPipeline.Inputs inputs = new BuildPipeline.Inputs(
                dir, cache, buildFile, lockFile, dir,
                workerCount, estimatedTestCount, profileName, jdksDir, skipTests, global.verbose);
        Goal.Builder builder = BuildPipeline.coreBuilder(inputs);
        BuildPipeline.appendDeclaredTails(builder, inputs);
        Goal goal = builder.build();
        // Estimate the member's bar weight once, here — the workspace pre-scan sums
        // these into the calibrated total, and the same value is the member's slice
        // of the aggregate bar (see AggregateMemberListener). Computing it once
        // keeps the slice byte-for-byte equal to what was summed into `total`.
        return new PreparedMember(dir, buildTarget(buildFile, dir), cache, goal,
                goal.estimatedTotalWeight());
    }

    /** Run an already-built member goal and map its result to an exit code. */
    private int runPrepared(PreparedMember pm, AggregateContext agg) {
        Goal goal = pm.goal();
        GoalResult result;
        if (agg != null) {
            // Workspace member: feed the one shared aggregate view, scaling this
            // member's progress into its reserved slice of the calibrated total.
            result = GoalConsole.runGoalInto(goal, pm.cache(), pm.target(), agg, pm.barWeight());
        } else {
            ConsoleSpec spec = new ConsoleSpec("Build",
                    r -> successMessage(goal, r),
                    r -> failureMessage(goal, r));
            result = GoalConsole.runGoal(goal, GoalConsole.modeFor(global), pm.cache(), spec, pm.target());
        }

        if (result.success()) {
            try {
                var cacheConfig = dev.jkbuild.config.JkCacheConfig.fromToml(pm.dir().resolve("jk.toml"));
                dev.jkbuild.task.CachePruneScheduler.resolveJkExe().ifPresent(exe ->
                        dev.jkbuild.task.CachePruneScheduler.maybeRun(cacheConfig, pm.cache(), exe));
            } catch (IOException ignored) {}
            return 0;
        }
        // Test failures get exit 4; other failures exit 1.
        var testResult = goal.get(TEST_RESULT).orElse(null);
        if (testResult != null && !testResult.allPassed()) return 4;
        return 1;
    }

    /**
     * A workspace member's goal, built and ready to run, paired with its pre-scan
     * bar weight — the member's slice of the calibrated aggregate total.
     */
    private record PreparedMember(Path dir, String target, Path cache, Goal goal,
                                  long barWeight) {}

    // ---- success summary -----------------------------------------------

    /** Header member label for the goal view: the project's {@code group:artifact}. */
    static String buildTarget(Path buildFile, Path dir) {
        try {
            var p = JkBuildParser.parse(buildFile).project();
            return p.group() + ":" + p.name();
        } catch (Exception e) {
            return dir.getFileName() == null ? "" : dir.getFileName().toString();
        }
    }

    /** Success result line (sans the leading ✓ and trailing duration). */
    private static String successMessage(Goal goal, GoalResult result) {
        String outcome = goal.get(BUILD_OUTCOME).orElse("");
        if ("up-to-date".equals(outcome)) {
            return "Up to date";
        }
        String built = Theme.colorize("Built", Theme.active().focused());
        String label = jarLabel(goal);
        return label.isEmpty() ? built : built + " " + label;
    }

    /**
     * The built jar as a yellow, project-relative path — {@code target/foo-1.0.jar}
     * rather than the bare filename — so the result line points at the artifact on
     * disk. Falls back to the filename if the layout can't relativize it, and to
     * {@code ""} when no jar was produced.
     */
    private static String jarLabel(Goal goal) {
        Path jar = goal.get(JAR_PATH).orElse(null);
        if (jar == null) return "";
        String rel = goal.get(LAYOUT)
                .map(layout -> relativeOrName(layout.memberRoot(), jar))
                .orElseGet(() -> jar.getFileName().toString());
        return Theme.colorize(rel, Theme.active().highlight());
    }

    /** {@code base}-relative path with forward slashes, or the bare filename on failure. */
    private static String relativeOrName(Path base, Path jar) {
        try {
            return base.relativize(jar).toString().replace(java.io.File.separatorChar, '/');
        } catch (RuntimeException e) {
            return jar.getFileName().toString();
        }
    }

    /** Failure result line (sans the leading ✗ and trailing duration), in red. */
    private static String failureMessage(Goal goal, GoalResult result) {
        var err = Theme.active().error();
        var testResult = goal.get(TEST_RESULT).orElse(null);
        if (testResult != null && !testResult.allPassed()) {
            String jar = goal.get(JAR_PATH).map(p -> p.getFileName().toString()).orElse("");
            return Theme.colorize(jar.isEmpty() ? "Tests failed" : "Tests failed while building " + jar, err);
        }
        return Theme.colorize("Build failed", err);
    }

    /** Dim {@code "took Xms"} from a wall-clock start captured with {@link System#nanoTime()}. */
    static String elapsedSince(long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        return dev.jkbuild.cli.run.ConsoleSpec.took(java.time.Duration.ofMillis(ms));
    }

    /**
     * Workspace build-failure result line: red "Build failed", the failing member
     * in cyan, dim duration — e.g. {@code ‼ Build failed: Failure at kernel/core in 8.7s}
     * (the {@code ‼} + red is added by {@code finishFailure}).
     */
    private static String buildFailedAt(String member, long buildStart) {
        return Theme.colorize("Build failed", Theme.active().error())
                + ": Failure at " + Theme.colorize(member, Theme.active().cyan())
                + " " + elapsedSince(buildStart);
    }

}
