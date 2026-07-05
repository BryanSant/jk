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
        Set<Path> all = new HashSet<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder()) all.add(u.dir());
        if (SessionContext.current().config().rerunOr(false)) return all;
        try {
            Cas cas = new Cas(cache);
            ActionCache ac = new ActionCache(cas, cache.resolve("actions"));
            Set<Path> dirty = new HashSet<>();
            for (BuildPlanForecast.Module m : BuildPlanForecast.of(graph, cas, ac, cache)) {
                if (m.dirty()) dirty.add(m.unit().dir());
            }
            return dirty;
        } catch (RuntimeException e) {
            return all;
        }
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
            boolean verbose) {}

    /** A module's assembled goal + the estimates a caller needs to render/calibrate. */
    public record ModulePlan(BuildGraph.BuildUnit unit, Goal goal, int weight, boolean fullyCached, Path cache) {
        public String coord() {
            return unit.coord();
        }

        public Path dir() {
            return unit.dir();
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
     * Build a whole workspace: resolve the module graph, size the worker-JVM memory plan, assemble
     * each module's goal, then schedule them in dependency order (each level concurrent) — running
     * every module's goal and surfacing artifacts under the workspace {@code target/}. Progress flows
     * to {@code listener}; the returned {@link WorkspaceResult} is the aggregate outcome. Pure of
     * presentation — the caller renders from the events.
     */
    public static WorkspaceResult buildWorkspace(WorkspaceRequest req, WorkspaceBuildListener listener) {
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
        JvmOptions.planAndApply(HeapPlan.requestedJvms(width, req.workers() > 0 ? req.workers() : 1, parallelTests, cap));

        Set<Path> moduleDirs = new LinkedHashSet<>();
        for (BuildGraph.BuildUnit u : units) moduleDirs.add(u.dir());
        Set<Path> dirty = forecastDirtyDirs(graph, req.cache());

        Map<Path, ModulePlan> plans = new LinkedHashMap<>();
        for (BuildGraph.BuildUnit u : units) {
            ModulePlan p = prepareModule(u, req, moduleDirs, dirty.contains(u.dir()));
            if (p == null) {
                ModuleOutcome o = new ModuleOutcome(u.coord(), u.dir(), false, 2, 0);
                listener.onModuleFinish(o);
                WorkspaceResult r = new WorkspaceResult(false, 2, List.of(o), List.of());
                listener.onWorkspaceFinish(r);
                return r;
            }
            plans.put(u.dir(), p);
        }
        listener.onPlan(List.copyOf(plans.values()));

        Map<Path, Path> wsLinks = computeWorkspaceLinks(plans.keySet(), req.entryDir());
        List<ModuleOutcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        ModuleOutcome failure = WorkspaceScheduler.run(
                units,
                BuildGraph.BuildUnit::dir,
                graph.edges(),
                u -> runModule(plans.get(u.dir()), listener),
                (ready, results, remaining) -> {
                    for (int i = 0; i < results.size(); i++) {
                        ModuleOutcome o = results.get(i);
                        outcomes.add(o);
                        if (o.success()) linkModuleArtifacts(ready.get(i).dir(), wsLinks);
                        else return o; // fail-fast
                    }
                    return null;
                });
        boolean ok = failure == null;
        WorkspaceResult result = new WorkspaceResult(ok, ok ? 0 : failure.exitCode(), List.copyOf(outcomes), List.of());
        listener.onWorkspaceFinish(result);
        return result;
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
