// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cache.Linking;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.SessionContext;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.resolver.pubgrub.UnsatisfiableException;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.task.ActionCache;
import dev.jkbuild.test.JUnitLauncher;
import dev.jkbuild.worker.HeapPlan;
import dev.jkbuild.worker.JvmOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The engine-side entry point a front-end (the "client") calls to drive a build — the facade the
 * re-foundation hoists {@code BuildCommand}'s orchestration into, so the CLI, an IntelliJ plugin, a
 * web app, or a GitHub Action all share one build API.
 *
 * <p>Pure orchestration/policy: like {@link LockFlow}, nothing here writes to {@code stdout}/{@code
 * stderr}; results are returned for the caller's view layer to render. This is the growing surface —
 * M2 moves the workspace scheduler and the shared explain/build planner behind it next; today it owns
 * the pre-build workspace lock-freshness guard.
 */
public final class BuildService {

    private BuildService() {}

    /**
     * Outcome of the pre-build workspace lock-freshness guard.
     *
     * @param status process exit code — {@code 0} means the lock is fresh (or was re-locked OK)
     * @param error a bare message to surface (no verb prefix), or {@code null}
     */
    public record LockGuard(int status, String error) {
        public static final LockGuard OK = new LockGuard(0, null);
    }

    /**
     * Ensure the workspace lock reflects its manifests before a build: if the root {@code jk.lock} is
     * absent, older than the root {@code jk.toml}, or older than any declared member manifest, re-run
     * the {@link LockFlow lock pipeline}. Soft failures (I/O, network) don't block the build — the
     * per-module path surfaces genuine problems when it resolves classpaths.
     */
    public static LockGuard ensureWorkspaceLockFresh(Path root, JkBuild rootBuild, Path cache) {
        Path rootLock = root.resolve("jk.lock");
        if (!workspaceLockStale(root, rootBuild, rootLock)) return LockGuard.OK;
        try {
            LockFlow.Result r = LockFlow.run(root, cache, List.of(), true, null);
            return r.status() != 0 ? new LockGuard(r.status(), r.error()) : LockGuard.OK;
        } catch (UnsatisfiableException e) {
            return new LockGuard(6, e.getMessage());
        } catch (Exception e) {
            return LockGuard.OK; // soft failure — let the per-module path surface real errors
        }
    }

    /**
     * True when {@code rootLock} is absent or older than the root manifest or any declared member
     * manifest — i.e. the merged workspace lock no longer reflects the manifests it was derived from.
     */
    public static boolean workspaceLockStale(Path root, JkBuild rootBuild, Path rootLock) {
        if (!Files.exists(rootLock)) return true;
        if (AutoLock.isStale(root, rootLock)) return true; // root jk.toml newer than the lock
        if (rootBuild.workspace() != null) {
            for (String module : rootBuild.workspace().modules()) {
                Path moduleDir = root.resolve(module).normalize();
                if (AutoLock.isStale(moduleDir, rootLock)) return true; // a member manifest is newer
            }
        }
        return false;
    }

    // =========================================================================
    // Workspace artifact placement
    // =========================================================================

    /**
     * Compute the {@code src → <wsRoot>/target/<name>} hard-link map for a workspace build: every
     * application module's final artifacts (jar / shadow / native binary+library / OCI tar) are
     * surfaced under the workspace root's {@code target/}. On a filename collision across modules the
     * link name is prefixed with the module's group. Pure — {@link #linkModuleArtifacts} applies it.
     */
    public static Map<Path, Path> computeWorkspaceLinks(Iterable<Path> moduleDirs, Path workspaceRoot) {
        Path wsRoot = workspaceRoot.toAbsolutePath().normalize();
        Map<Path, List<Path>> moduleArtifacts = new LinkedHashMap<>();
        Map<Path, String> moduleGroup = new LinkedHashMap<>();
        for (Path moduleDir : moduleDirs) {
            Path normalDir = moduleDir.toAbsolutePath().normalize();
            if (normalDir.equals(wsRoot)) continue;
            Path buildFile = moduleDir.resolve("jk.toml");
            if (!Files.exists(buildFile)) continue;
            JkBuild build;
            try {
                build = JkBuildParser.parse(buildFile);
            } catch (Exception ignored) {
                continue;
            }
            BuildLayout layout = BuildLayout.of(wsRoot, moduleDir, build);
            if (!layout.hasMain()) continue;
            List<Path> candidates = new ArrayList<>();
            candidates.add(layout.mainJar());
            candidates.add(layout.shadowJar());
            candidates.add(layout.nativeBinary());
            candidates.add(layout.nativeLibrary());
            candidates.add(layout.ociImageTar());
            moduleArtifacts.put(normalDir, candidates);
            moduleGroup.put(normalDir, build.project().group());
        }
        // Count per filename across all modules to detect collisions.
        Map<String, Long> filenameCounts = new HashMap<>();
        for (List<Path> arts : moduleArtifacts.values()) {
            for (Path art : arts) filenameCounts.merge(art.getFileName().toString(), 1L, Long::sum);
        }
        // Build the final src→linkDest map.
        Path wsTarget = wsRoot.resolve("target");
        Map<Path, Path> links = new LinkedHashMap<>();
        for (var entry : moduleArtifacts.entrySet()) {
            Path normalDir = entry.getKey();
            String group = moduleGroup.get(normalDir);
            for (Path art : entry.getValue()) {
                String filename = art.getFileName().toString();
                String linkName =
                        filenameCounts.getOrDefault(filename, 0L) > 1 ? group + "-" + filename : filename;
                links.put(art, wsTarget.resolve(linkName));
            }
        }
        return links;
    }

    /**
     * The set of module dirs the forecast predicts will do real work this build — used to reserve
     * their progress-bar slice up front. {@code --force} marks every module dirty; on any forecast
     * error, pessimistically returns all modules (so nothing is under-reserved).
     */
    public static Set<Path> forecastDirtyDirs(BuildGraph.Result graph, Path cache) {
        return forecastDirtyDirs(graph, cache, false);
    }

    /**
     * As {@link #forecastDirtyDirs(BuildGraph.Result, Path)} but honoring {@code skipTests}: a
     * {@code --skip-tests} build never runs the test phases, so their staleness must neither mark a
     * module dirty (it would force an engine build of a fully-cached workspace) nor cost the
     * test-stamp content hashing.
     */
    public static Set<Path> forecastDirtyDirs(BuildGraph.Result graph, Path cache, boolean skipTests) {
        Set<Path> all = new HashSet<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder()) all.add(u.dir());
        if (SessionContext.current().config().rerunOr(false)) return all;
        try {
            Cas cas = new Cas(cache);
            ActionCache ac = new ActionCache(cas, cache.resolve("actions"));
            Set<Path> dirty = new HashSet<>();
            for (BuildPlanForecast.Module m : BuildPlanForecast.of(graph, cas, ac, cache, skipTests)) {
                if (m.dirty()) dirty.add(m.dir());
                if (Perf.ENABLED && m.dirty()) {
                    for (BuildPlanForecast.Phase p : m.phases()) {
                        if (!p.cached())
                            System.err.println(
                                    "[jk-perf] dirty " + m.coord() + " " + p.name() + " (" + p.text() + ")");
                    }
                }
            }
            return dirty;
        } catch (RuntimeException e) {
            return all;
        }
    }

    // =========================================================================
    // Explain / plan (the front-end-callable dry-run planner)
    // =========================================================================

    /**
     * A front-end-facing forecast of {@code jk build}: the workspace's modules in dependency order
     * (each already front-end-safe — see {@link BuildPlanForecast.Module}), the prereq edges and
     * peak-concurrency width the ETA model needs, and any graph-resolution errors. Deliberately free
     * of {@link BuildGraph}/{@link BuildGraph.BuildUnit}: edges are exposed as a plain {@code dir →
     * prereq dirs} map (keyed by the same {@link BuildPlanForecast.Module#dir()} the caller iterates),
     * so a non-CLI client can render the plan and compute the estimate without engine internals.
     *
     * @param modules dependency-first modules; empty when {@link #hasErrors()}
     * @param edges module dir → the dirs that must build before it
     * @param maxReadyWidth widest wave of independent modules (drives ETA concurrency)
     * @param errors graph-resolution errors (cycle / depth-cap / missing jk.toml); non-empty ⇒ no plan
     */
    public record ExplainPlan(
            List<BuildPlanForecast.Module> modules,
            Map<Path, Set<Path>> edges,
            int maxReadyWidth,
            List<String> errors) {
        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    /**
     * Forecast the build without running it: resolve the module graph and run the truthful
     * per-phase {@link BuildPlanForecast} over it, returning an {@link ExplainPlan} the caller
     * renders. Pure policy — nothing here writes to {@code stdout}/{@code stderr}. Graph-resolution
     * errors come back in {@link ExplainPlan#errors()} (the caller renders the same failure); an
     * {@link IOException} probing the workspace still propagates, exactly as the direct resolve did.
     */
    public static ExplainPlan explain(Path entryDir, JkBuild entryBuild, Path cache) throws IOException {
        BuildGraph.Result graph = BuildGraph.resolve(entryDir, entryBuild);
        if (graph.hasErrors()) {
            return new ExplainPlan(List.of(), Map.of(), 1, List.copyOf(graph.errors()));
        }
        Cas cas = new Cas(cache);
        ActionCache actionCache = new ActionCache(cas, cache.resolve("actions"));
        List<BuildPlanForecast.Module> modules = BuildPlanForecast.of(graph, cas, actionCache, cache);
        return new ExplainPlan(modules, graph.edges(), graph.maxReadyWidth(), List.of());
    }

    // =========================================================================
    // Build preflight (resolve + dirty forecast, for the fully-cached shortcut)
    // =========================================================================

    /**
     * An opaque, front-end-safe handle to a resolved build graph: enough for a caller to branch on
     * resolution errors / an empty workspace and then forecast dirty modules, without ever naming
     * {@link BuildGraph}/{@link BuildGraph.BuildUnit}. The engine-internal {@link BuildGraph.Result}
     * is reachable only through the package-private {@link #graph()} accessor (feeding {@link
     * #forecastDirtyDirs(ResolvedGraph, Path)}), so the boundary is compiler-enforced.
     *
     * <p>A {@code final class} rather than a {@code record} precisely so {@code graph()} can drop
     * below {@code public}.
     */
    public static final class ResolvedGraph {
        private final BuildGraph.Result graph;

        private ResolvedGraph(BuildGraph.Result graph) {
            this.graph = graph;
        }

        /** Engine-internal graph — package-private so front-ends can't reach {@link BuildGraph.Result}. */
        BuildGraph.Result graph() {
            return graph;
        }

        public boolean hasErrors() {
            return graph.hasErrors();
        }

        public List<String> errors() {
            return List.copyOf(graph.errors());
        }

        /** True when the graph resolved to zero build units (a workspace that declares no modules). */
        public boolean isEmpty() {
            return graph.topoOrder().isEmpty();
        }

        /** The build units in dependency-first order, as plain directory paths. */
        public List<Path> moduleDirs() {
            List<Path> dirs = new ArrayList<>();
            for (BuildGraph.BuildUnit u : graph.topoOrder()) dirs.add(u.dir());
            return dirs;
        }
    }

    /** Resolve the module graph rooted at {@code entryDir}, wrapped so front-ends never name {@link BuildGraph}. */
    public static ResolvedGraph resolveGraph(Path entryDir, JkBuild entryBuild) throws IOException {
        return new ResolvedGraph(BuildGraph.resolve(entryDir, entryBuild));
    }

    /** {@link #forecastDirtyDirs(BuildGraph.Result, Path)} over a front-end-held {@link ResolvedGraph}. */
    public static Set<Path> forecastDirtyDirs(ResolvedGraph graph, Path cache) {
        return forecastDirtyDirs(graph.graph(), cache);
    }

    /** {@link #forecastDirtyDirs(BuildGraph.Result, Path, boolean)} over a front-end-held {@link ResolvedGraph}. */
    public static Set<Path> forecastDirtyDirs(ResolvedGraph graph, Path cache, boolean skipTests) {
        return forecastDirtyDirs(graph.graph(), cache, skipTests);
    }

    // =========================================================================
    // Workspace build (the front-end-callable event-emitting entry point)
    // =========================================================================

    /** What a front-end asks the engine to build. */
    public record WorkspaceRequest(
            Path entryDir,
            JkBuild entryBuild,
            Path cache,
            Path jdksDir,
            int workers,
            String profile,
            boolean skipTests,
            boolean verbose,
            // Max modules to build concurrently. 0 = auto (schedule every ready level's units at once,
            // memory permitting — the parallel default); 1 = strictly serial; N = a rolling window of N.
            int maxModuleConcurrency,
            // Pre-computed dirty module dirs, or null to forecast internally. Lets a caller that
            // already forecasted (the CLI's fully-cached "all up to date" shortcut) skip a redundant pass.
            Set<Path> dirtyHint,
            // True (the single-CLI-process default): this call sizes and applies its own worker-JVM
            // memory plan (see the buildWorkspace javadoc). False: the caller already owns memory
            // planning for the process — e.g. a host serving several concurrent buildWorkspace calls
            // in one JVM plans once for its own assumed concurrency, and a per-call plan here would
            // stomp the shared HeapPlan/WorkerSlots state out from under a sibling call in flight.
            boolean applyMemoryPlan,
            // True (jk build): auto-freshen the merged workspace lock before any build decision via
            // ensureWorkspaceLockFresh — the guard BuildCommand used to run client-side, now applied
            // here so an engine-hosted build request freshens the lock engine-side. False for callers
            // that must use the pinned lock verbatim (jk verify's scratch rebuild).
            boolean freshenLock) {}

    /**
     * A module's assembled goal + the estimates a caller needs to render/calibrate. The front-end
     * contract is {@link #coord()}, {@link #dir()}, {@link #goal()}, {@link #weight()}, {@link
     * #fullyCached()}, and {@link #cache()}. The engine-internal {@link BuildGraph.BuildUnit} used to
     * run the module is exposed only through the package-private {@link #unit()} accessor, so the
     * boundary is compiler-enforced: engine consumers in {@code dev.jkbuild.runtime} can reach it,
     * front-ends in other packages (the CLI) cannot.
     *
     * <p>A {@code final class} rather than a {@code record} precisely so {@code unit()} can drop below
     * {@code public} — a record's canonical accessors are always {@code public}.
     */
    public static final class ModulePlan {
        private final BuildGraph.BuildUnit unit;
        private final Goal goal;
        private final int weight;
        private final boolean fullyCached;
        private final Path cache;

        ModulePlan(BuildGraph.BuildUnit unit, Goal goal, int weight, boolean fullyCached, Path cache) {
            this.unit = unit;
            this.goal = goal;
            this.weight = weight;
            this.fullyCached = fullyCached;
            this.cache = cache;
        }

        /**
         * Reconstruct a plan client-side from wire-level data (engine front-ends). The synthetic
         * unit this creates is never executed — renderers read only {@link #coord()}, {@link
         * #dir()}, and {@link #goal()} — and it never crosses back into the engine. This is the
         * only construction path outside the engine package, so {@link BuildGraph.BuildUnit} stays
         * invisible to front-ends.
         */
        public static ModulePlan fromWire(
                Path dir, String coord, Goal goal, int weight, boolean fullyCached, Path cache) {
            BuildGraph.BuildUnit unit = new BuildGraph.BuildUnit(dir, null, coord, BuildGraph.Origin.ROOT);
            return new ModulePlan(unit, goal, weight, fullyCached, cache);
        }

        /** Engine-internal build unit — package-private so front-ends can't reach {@link BuildGraph.BuildUnit}. */
        BuildGraph.BuildUnit unit() {
            return unit;
        }

        public String coord() {
            return unit.coord();
        }

        public Path dir() {
            return unit.dir();
        }

        public Goal goal() {
            return goal;
        }

        public int weight() {
            return weight;
        }

        public boolean fullyCached() {
            return fullyCached;
        }

        public Path cache() {
            return cache;
        }
    }

    /** The result of one module's build. */
    public record ModuleOutcome(String coord, Path dir, boolean success, int exitCode, long millis) {}

    /**
     * The whole workspace build result.
     *
     * @param errors graph-resolution errors (composite deps); non-empty ⇒ nothing built
     */
    public record WorkspaceResult(boolean success, int exitCode, List<ModuleOutcome> modules, List<String> errors) {}

    private static final GoalKey<JUnitLauncher.Result> TEST_RESULT =
            GoalKey.of("test-result", JUnitLauncher.Result.class);

    /**
     * Build a whole workspace: resolve the module graph, size the worker-JVM memory plan (unless
     * {@link WorkspaceRequest#applyMemoryPlan()} is {@code false} — see its javadoc), assemble each
     * module's goal, then schedule them in dependency order (each level concurrent) — running every
     * module's goal and surfacing artifacts under the workspace {@code target/}. Progress flows to
     * {@code listener}; the returned {@link WorkspaceResult} is the aggregate outcome. Pure of
     * presentation — the caller renders from the events.
     *
     * <p>This method does not assume it is the only in-flight caller in the process: memory planning
     * is opt-out precisely so a host running several concurrent builds in one JVM (a resident engine)
     * can plan once for its own concurrency instead of letting each call overwrite the shared
     * {@code HeapPlan}/{@code WorkerSlots} state sized for just itself.
     */
    public static WorkspaceResult buildWorkspace(WorkspaceRequest req, WorkspaceBuildListener listener) {
        // Whole-workspace staleness guard (moved engine-side from BuildCommand in the slim-client
        // migration): the per-module cache forecast can report "all up to date" against per-module
        // locks even when the merged workspace lock is stale or a root-declared dependency is
        // unresolvable. Re-lock when stale so an unsatisfiable dep fails the build here instead of
        // lying "up to date". Soft failures (I/O, network) don't block — see ensureWorkspaceLockFresh.
        if (req.freshenLock()) {
            LockGuard guard = ensureWorkspaceLockFresh(req.entryDir(), req.entryBuild(), req.cache());
            if (guard.status() != 0) {
                WorkspaceResult r = new WorkspaceResult(
                        false,
                        guard.status(),
                        List.of(),
                        List.of(guard.error() != null ? guard.error() : "dependency resolution failed"));
                listener.onWorkspaceFinish(r);
                return r;
            }
        }
        BuildGraph.Result graph;
        try {
            graph = BuildGraph.resolve(req.entryDir(), req.entryBuild());
        } catch (IOException e) {
            WorkspaceResult r = new WorkspaceResult(false, 2, List.of(), List.of(String.valueOf(e.getMessage())));
            listener.onWorkspaceFinish(r);
            return r;
        }
        if (graph.hasErrors()) {
            WorkspaceResult r = new WorkspaceResult(false, 2, List.of(), List.copyOf(graph.errors()));
            listener.onWorkspaceFinish(r);
            return r;
        }
        List<BuildGraph.BuildUnit> units = graph.topoOrder();
        if (units.isEmpty()) {
            WorkspaceResult r = new WorkspaceResult(true, 0, List.of(), List.of());
            listener.onWorkspaceFinish(r);
            return r;
        }
        // Size worker-JVM heaps/concurrency from free memory before any fork (engine resource plan).
        int cap = Runtime.getRuntime().availableProcessors();
        boolean parallelTests = SessionContext.current().parallelTests();
        int width = BuildGraph.maxReadyWidth(units, graph.edges());
        // A module-concurrency cap (e.g. --no-parallel → 1) bounds the peak module count for both the
        // memory plan and the ETA below, so serial builds size heaps and estimate time as serial.
        if (req.maxModuleConcurrency() > 0) width = Math.min(width, req.maxModuleConcurrency());
        if (req.applyMemoryPlan()) {
            JvmOptions.planAndApply(HeapPlan.requestedJvms(width, req.workers() > 0 ? req.workers() : 1, parallelTests, cap));
        }

        Set<Path> moduleDirs = new LinkedHashSet<>();
        for (BuildGraph.BuildUnit u : units) moduleDirs.add(u.dir());
        long tf = Perf.start();
        Set<Path> dirty =
                req.dirtyHint() != null ? req.dirtyHint() : forecastDirtyDirs(graph, req.cache(), req.skipTests());
        Perf.end("ws-forecast(hint=" + (req.dirtyHint() != null) + ")", tf);

        // Each module's phase durations feed one shared sink, folded into the learned ledger on success.
        List<PhaseTimings.Sample> timingSamples = Collections.synchronizedList(new ArrayList<>());
        Map<Path, ModulePlan> plans = new LinkedHashMap<>();
        long tp = Perf.start();
        for (BuildGraph.BuildUnit u : units) {
            ModulePlan p = prepareModule(u, req, moduleDirs, dirty.contains(u.dir()));
            if (p == null) {
                ModuleOutcome o = new ModuleOutcome(u.coord(), u.dir(), false, 2, 0);
                listener.onModuleFinish(o);
                WorkspaceResult r = new WorkspaceResult(false, 2, List.of(o), List.of());
                listener.onWorkspaceFinish(r);
                return r;
            }
            p.goal().addListener(new PhaseTimingsRecorder(u.dir().toString(), timingSamples));
            plans.put(u.dir(), p);
        }
        Perf.end("ws-prepare-modules", tp);
        listener.onPlan(List.copyOf(plans.values()));

        // ETA model (schedule-aware, per-module warm/cold rate) — engine knowledge, emitted as events.
        long teta = Perf.start();
        Map<Path, EffortWeights.ModuleCost> costByDir = new LinkedHashMap<>();
        for (var e : plans.entrySet()) {
            costByDir.put(
                    e.getKey(),
                    EffortWeights.costOf(e.getKey(), graph.edges().getOrDefault(e.getKey(), Set.of()), e.getValue().goal()));
        }
        Perf.end("ws-eta-costs", teta);
        int requestedJvms = HeapPlan.requestedJvms(width, req.workers() > 0 ? req.workers() : 1, parallelTests, cap);
        // Clamp the ETA's module concurrency to the cap so a serial build (cap 1) estimates serially.
        final int concurrency =
                req.maxModuleConcurrency() > 0 ? Math.min(requestedJvms, req.maxModuleConcurrency()) : requestedJvms;
        listener.onEtaEstimate(seedEta(
                new ArrayList<>(costByDir.values()), plans.keySet(), concurrency, parallelTests, req.cache(), req.jdksDir()));

        Map<Path, Path> wsLinks = computeWorkspaceLinks(plans.keySet(), req.entryDir());
        List<ModuleOutcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        List<Double> observedRates = Collections.synchronizedList(new ArrayList<>());
        long start = System.nanoTime();
        long tsched = Perf.start();
        ModuleOutcome failure = WorkspaceScheduler.run(
                units,
                BuildGraph.BuildUnit::dir,
                graph.edges(),
                u -> runModule(plans.get(u.dir()), listener),
                (ready, results, remaining) -> {
                    for (int i = 0; i < results.size(); i++) {
                        ModuleOutcome o = results.get(i);
                        outcomes.add(o);
                        if (!o.success()) return o; // fail-fast
                        linkModuleArtifacts(ready.get(i).dir(), wsLinks);
                        ModulePlan p = plans.get(ready.get(i).dir());
                        // Skip fully-cached modules — their near-zero time isn't representative of work.
                        if (p != null && !p.fullyCached() && p.weight() > 0 && o.millis() > 0)
                            observedRates.add(o.millis() / (double) p.weight());
                    }
                    // Re-project remaining ETA from measured throughput: elapsed + reprojected remainder.
                    Double liveMpw = medianRate(observedRates);
                    if (liveMpw != null && !remaining.isEmpty()) {
                        Set<Path> remDirs = new HashSet<>();
                        for (BuildGraph.BuildUnit u : remaining) remDirs.add(u.dir());
                        List<EffortWeights.ModuleCost> rem = new ArrayList<>();
                        for (var ce : costByDir.entrySet()) if (remDirs.contains(ce.getKey())) rem.add(ce.getValue());
                        long elapsed = (System.nanoTime() - start) / 1_000_000;
                        listener.onEtaEstimate(elapsed
                                + EffortWeights.scheduleMillis(rem, concurrency, false, parallelTests, Math.round(liveMpw)));
                    }
                    return null;
                },
                req.maxModuleConcurrency());
        Perf.end("ws-schedule-run", tsched);
        boolean ok = failure == null;
        if (ok) {
            // Fold this run's phase durations + measured throughput into the learned ledger + host
            // calibration (EWMA) so the next build's estimate is time-accurate. Failed builds don't
            // record — their phase times are abnormal.
            PhaseTimings.record(
                    req.cache(), timingSamples, PhaseTimings.DEFAULT_ALPHA, System.currentTimeMillis());
            Double runMpw = medianRate(observedRates);
            if (runMpw != null) Calibration.refine(runMpw, System.currentTimeMillis());
        }
        WorkspaceResult result = new WorkspaceResult(ok, ok ? 0 : failure.exitCode(), List.copyOf(outcomes), List.of());
        listener.onWorkspaceFinish(result);
        return result;
    }

    /**
     * Initial schedule-aware ETA (ms): each module converts weight→ms at its own rate — a warm module
     * (its dir has learned timings) at the reference {@link EffortWeights#MS_PER_WEIGHT}; a cold module
     * at this host's measured {@link Calibration}. Returns {@code 0} ("count up") only when a cold
     * module exists and the host is uncalibratable (no JDK yet) — then no rate is trustworthy.
     */
    private static long seedEta(
            List<EffortWeights.ModuleCost> costs,
            Set<Path> dirs,
            int concurrency,
            boolean parallelTests,
            Path cache,
            Path jdksDir) {
        PhaseTimings timings = PhaseTimings.load(cache);
        java.util.function.Predicate<Path> warm = dir -> timings.hasTimingsFor(List.of(dir.toString()));
        boolean anyCold = dirs.stream().anyMatch(dir -> !warm.test(dir));
        Calibration cal = anyCold ? Calibration.ensure(jdksDir) : null;
        if (anyCold && (cal == null || !cal.present())) return 0;
        double coldRate = cal != null ? cal.msPerWeight() : EffortWeights.MS_PER_WEIGHT;
        return EffortWeights.scheduleMillis(
                costs, concurrency, false, parallelTests, dir -> warm.test(dir) ? EffortWeights.MS_PER_WEIGHT : coldRate);
    }

    /** Median of observed per-module ms/weight rates, or null when none recorded yet. */
    private static Double medianRate(List<Double> rates) {
        List<Double> sorted;
        synchronized (rates) {
            if (rates.isEmpty()) return null;
            sorted = new ArrayList<>(rates);
        }
        sorted.sort(Double::compareTo);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    /** Assemble one module's goal + estimates (the headless counterpart of the CLI's prepareModule). */
    private static ModulePlan prepareModule(
            BuildGraph.BuildUnit u, WorkspaceRequest req, Set<Path> moduleDirs, boolean forceRebuild) {
        Path dir = u.dir();
        Path buildFile = dir.resolve("jk.toml");
        if (!Files.exists(buildFile)) return null;
        BuildPipeline.Inputs inputs = BuildPlanForecast.inputsFor(
                dir,
                req.cache(),
                req.workers() > 0 ? req.workers() : 1,
                req.jdksDir(),
                req.profile(),
                req.skipTests(),
                req.verbose(),
                moduleDirs);
        boolean fullyCached = false;
        if (!forceRebuild) {
            try {
                Cas cas = new Cas(inputs.cache());
                JkBuild build = JkBuildParser.parse(buildFile);
                CompileSupport.Languages langs = CompileSupport.resolveLanguages(build.project(), dir);
                boolean compact = CompileSupport.isSimpleLayout(build.project(), dir);
                EffortWeights.Plan plan = EffortWeights.predict(inputs, cas, compact, langs.java(), langs.kotlin());
                fullyCached = plan.fullyCached();
            } catch (Exception ignored) {
                // best-effort prediction; proceed normally
            }
        }
        Goal.Builder b = BuildPipeline.coreBuilder(inputs, forceRebuild);
        BuildPipeline.appendDeclaredTails(b, inputs);
        Goal goal = b.build();
        return new ModulePlan(u, goal, goal.estimatedTotalWeight(), fullyCached, req.cache());
    }

    /** Run one module's goal, attaching the caller's per-module listener; map the result to an outcome. */
    private static ModuleOutcome runModule(ModulePlan plan, WorkspaceBuildListener listener) {
        GoalListener ml = listener.onModuleStart(plan);
        if (ml != null) plan.goal().addListener(ml);
        long t0 = System.nanoTime();
        try {
            GoalResult r = plan.goal().run();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            int exit = r.success() ? 0 : exitCodeFor(plan.goal());
            ModuleOutcome o = new ModuleOutcome(plan.coord(), plan.dir(), r.success(), exit, ms);
            listener.onModuleFinish(o);
            return o;
        } catch (RuntimeException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            ModuleOutcome o = new ModuleOutcome(plan.coord(), plan.dir(), false, 1, ms);
            listener.onModuleFinish(o);
            return o;
        }
    }

    /** Test failures exit 4; every other goal failure exits 1. */
    private static int exitCodeFor(Goal goal) {
        JUnitLauncher.Result tr = goal.get(TEST_RESULT).orElse(null);
        return tr != null && !tr.allPassed() ? 4 : 1;
    }

    /** Apply the subset of {@code workspaceLinks} whose sources live under {@code moduleDir} (best-effort). */
    public static void linkModuleArtifacts(Path moduleDir, Map<Path, Path> workspaceLinks) {
        if (workspaceLinks.isEmpty()) return;
        Path normalDir = moduleDir.toAbsolutePath().normalize();
        for (var entry : workspaceLinks.entrySet()) {
            Path src = entry.getKey();
            if (!src.startsWith(normalDir)) continue;
            if (!Files.isRegularFile(src)) continue;
            try {
                Linking.linkOrCopy(src, entry.getValue());
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }
}
