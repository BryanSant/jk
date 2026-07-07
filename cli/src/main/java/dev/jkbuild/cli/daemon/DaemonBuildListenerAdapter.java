// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.daemon;

import dev.jkbuild.cli.Jk;
import dev.jkbuild.config.Session;
import dev.jkbuild.config.SessionContext;
import dev.jkbuild.daemon.DaemonPaths;
import dev.jkbuild.daemon.protocol.DaemonProtocol;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.GoalView;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseStatus;
import dev.jkbuild.runtime.BuildGraph;
import dev.jkbuild.runtime.BuildService;
import dev.jkbuild.runtime.WorkspaceBuildListener;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives a {@code buildWorkspace} call against a daemon instead of in-process: sends a {@link
 * DaemonProtocol#BUILD_REQUEST}, decodes the resulting stream of wire events, and re-invokes the
 * <em>exact same</em> {@link WorkspaceBuildListener}/{@link GoalListener} instances the caller already
 * builds for the in-process path — {@code BuildCommand}'s rendering code doesn't need to know whether
 * it's watching a live {@code Goal} or a socket. See {@code docs/daemon.md}.
 *
 * <p>Reconstructing {@link BuildService.ModulePlan} client-side needs a real (but never-executed)
 * {@link Goal} and {@link BuildGraph.BuildUnit} to satisfy their constructors — both are public
 * exactly because {@code ModulePlan.unit()} is the only engine-internal accessor (package-private by
 * design, per {@code docs/architecture/re-foundation.md} M6), and nothing here or in any renderer
 * calls it. The synthesized {@code Goal} is built with inert {@link Phase}s (default no-op body) via
 * the same public builder the engine itself uses — never {@code run()} — purely so {@code .name()}/
 * {@code .phases()} read correctly for the renderers that already only read those two accessors.
 *
 * <p><b>Resolved Phase 2 gap:</b> {@code BuildCommand}'s {@code onModuleStart} bodies used to call
 * {@code m.goal().addListener(EventLogListener.open(...))} to attach jk's durable per-module run log
 * directly onto the live {@code Goal} — a no-op against this adapter's synthetic (never-{@code
 * run()}) {@code Goal}, since nothing ever drains it. Fixed by composing the log listener into the
 * listener {@code onModuleStart} <em>returns</em> instead ({@code CompositeGoalListener}) — that one
 * is driven by wire-replayed events in the daemon-hosted case and by {@code Goal.run()} directly in
 * the in-process case, so it works either way with no protocol changes.
 */
final class DaemonBuildListenerAdapter {

    private DaemonBuildListenerAdapter() {}

    /** One module's identity/sizing, accumulated from the {@code plan-module}/{@code plan-phase} burst. */
    private static final class ModuleMeta {
        final String coord;
        final String goalName;
        final int weight;
        final boolean fullyCached;
        final List<Phase> phases = new ArrayList<>();

        ModuleMeta(String coord, String goalName, int weight, boolean fullyCached) {
            this.coord = coord;
            this.goalName = goalName;
            this.weight = weight;
            this.fullyCached = fullyCached;
        }
    }

    /**
     * Run {@code req} against the daemon at {@code paths}, spawning/reconnecting as needed, and drive
     * {@code listener} exactly as {@link BuildService#buildWorkspace} would in-process. Throws with a
     * clear message on any daemon-unreachable/protocol failure — per {@code docs/daemon.md} there is
     * no in-process fallback.
     */
    static BuildService.WorkspaceResult buildWorkspace(
            DaemonPaths.Paths paths, BuildService.WorkspaceRequest req, WorkspaceBuildListener listener)
            throws IOException {
        DaemonClient.ensureRunning(paths, Jk.VERSION);
        Session session = SessionContext.current();

        try (SocketChannel ch = DaemonClient.connect(paths.socket())) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));

            writer.write(DaemonProtocol.buildRequest(
                    req.entryDir().toString(),
                    req.cache().toString(),
                    req.jdksDir() != null ? req.jdksDir().toString() : null,
                    req.workers(),
                    req.profile(),
                    req.skipTests(),
                    req.verbose(),
                    req.maxModuleConcurrency(),
                    session.parallelTests(),
                    session.offline(),
                    session.force()));
            writer.write('\n');
            writer.flush();

            return streamEvents(reader, listener, req.cache());
        }
    }

    /**
     * Run a single project's test goal against the daemon (Phase 3). {@code listenerFactory} builds
     * the actual console {@link GoalListener} once the goal's phase list is known (mirroring {@code
     * GoalConsole.runGoal}'s own mode-based listener choice, which also needs {@code goal.phases()}
     * before it can construct a {@code CommandManagerListener}) — the wire doesn't have a real {@code
     * Goal} to ask, so the phases arrive as their own small event burst first. {@code testResultOut},
     * if non-null, is populated with the test-run counts (for exit-code/summary logic) before the
     * terminal {@code goal-finish} event reaches {@code listenerFactory}'s listener, exactly mirroring
     * how the in-process path's {@code goal.get(TEST_RESULT)} is already populated by the time the
     * console listener's own {@code goalFinish} fires.
     */
    static GoalResult runTest(
            DaemonPaths.Paths paths,
            DaemonClient.TestRequest req,
            java.util.function.Function<List<Phase>, GoalListener> listenerFactory,
            dev.jkbuild.test.JUnitLauncher.Result[] testResultOut)
            throws IOException {
        DaemonClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = DaemonClient.connect(paths.socket())) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));

            writer.write(DaemonProtocol.testRequest(
                    req.entryDir().toString(),
                    req.cache().toString(),
                    req.jdksDir() != null ? req.jdksDir().toString() : null,
                    req.workers(),
                    req.profile(),
                    req.verbose()));
            writer.write('\n');
            writer.flush();

            return streamSingleGoalEvents(reader, listenerFactory, testResultOut, null);
        }
    }

    /**
     * Run a single (non-workspace) project's real build goal against the daemon — the counterpart of
     * {@code BuildCommand.runForDir}. Same shape as {@link #runTest}, plus {@code buildOutcomeOut}
     * (populated with {@code BuildPipeline.BUILD_OUTCOME}, if the goal reported one, before the
     * terminal {@code goal-finish} reaches {@code listenerFactory}'s listener) so the caller's
     * summary line (e.g. "project up to date" vs "project built") can match the in-process path.
     */
    static GoalResult runSingleBuild(
            DaemonPaths.Paths paths,
            DaemonClient.SingleBuildRequest req,
            java.util.function.Function<List<Phase>, GoalListener> listenerFactory,
            dev.jkbuild.test.JUnitLauncher.Result[] testResultOut,
            String[] buildOutcomeOut)
            throws IOException {
        DaemonClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = DaemonClient.connect(paths.socket())) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));

            writer.write(DaemonProtocol.singleBuildRequest(
                    req.entryDir().toString(),
                    req.cache().toString(),
                    req.jdksDir() != null ? req.jdksDir().toString() : null,
                    req.workers(),
                    req.profile(),
                    req.skipTests(),
                    req.verbose(),
                    req.offline(),
                    req.force()));
            writer.write('\n');
            writer.flush();

            return streamSingleGoalEvents(reader, listenerFactory, testResultOut, buildOutcomeOut);
        }
    }

    /**
     * Forecast a build against the daemon — the counterpart of {@code ExplainCommand}'s direct {@code
     * BuildService.explain} call. Synchronous: sends {@link DaemonProtocol#EXPLAIN_REQUEST} and reads
     * the module/phase/edge burst to completion, reconstructing a real {@link BuildService.ExplainPlan}.
     * Unlike {@link #buildModulePlan}, no inert-object trickery is needed here — {@link
     * dev.jkbuild.runtime.BuildPlanForecast.Module}/{@code Phase} are pure public data, just built from
     * a synthetic (never-executed) {@link BuildGraph.BuildUnit}, exactly as {@link #buildModulePlan}
     * already does for the same reason ({@code Module.unit()} is package-private and never read here).
     */
    static BuildService.ExplainPlan explain(DaemonPaths.Paths paths, Path entryDir, Path cache) throws IOException {
        DaemonClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = DaemonClient.connect(paths.socket())) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));

            writer.write(DaemonProtocol.explainRequest(entryDir.toString(), cache.toString()));
            writer.write('\n');
            writer.flush();

            List<dev.jkbuild.runtime.BuildPlanForecast.Module> modules = new ArrayList<>();
            Map<String, List<dev.jkbuild.runtime.BuildPlanForecast.Phase>> phasesByDir = new LinkedHashMap<>();
            Map<String, String> coordByDir = new LinkedHashMap<>();
            Map<String, int[]> countsByDir = new LinkedHashMap<>(); // [sourceCount, testCount]
            Map<String, boolean[]> flagsByDir = new LinkedHashMap<>(); // [producesJar, producesImage]
            List<String> order = new ArrayList<>();
            Map<Path, java.util.Set<Path>> edges = new LinkedHashMap<>();
            List<String> errors = new ArrayList<>();

            String line;
            while ((line = reader.readLine()) != null) {
                String type = DaemonProtocol.typeOf(line);
                if (type == null) continue;
                switch (type) {
                    case DaemonProtocol.EXPLAIN_MODULE -> {
                        String dir = Ndjson.str(line, "dir");
                        order.add(dir);
                        coordByDir.put(dir, Ndjson.str(line, "coord"));
                        countsByDir.put(dir, new int[] {
                            Ndjson.intValue(line, "sourceCount", 0), Ndjson.intValue(line, "testCount", 0)
                        });
                        flagsByDir.put(dir, new boolean[] {
                            Ndjson.bool(line, "producesJar", false), Ndjson.bool(line, "producesImage", false)
                        });
                        phasesByDir.put(dir, new ArrayList<>());
                    }
                    case DaemonProtocol.EXPLAIN_PHASE -> {
                        String dir = Ndjson.str(line, "dir");
                        phasesByDir
                                .get(dir)
                                .add(new dev.jkbuild.runtime.BuildPlanForecast.Phase(
                                        Ndjson.str(line, "name"),
                                        dev.jkbuild.runtime.BuildPlanForecast.Status.valueOf(
                                                Ndjson.str(line, "status")),
                                        Ndjson.str(line, "text"),
                                        Ndjson.str(line, "key")));
                    }
                    case DaemonProtocol.EXPLAIN_EDGE -> {
                        Path dir = Path.of(Ndjson.str(line, "dir"));
                        Path dependsOn = Path.of(Ndjson.str(line, "dependsOnDir"));
                        edges.computeIfAbsent(dir, d -> new java.util.LinkedHashSet<>()).add(dependsOn);
                    }
                    case DaemonProtocol.EXPLAIN_ERROR -> errors.add(Ndjson.str(line, "message"));
                    case DaemonProtocol.EXPLAIN_DONE -> {
                        for (String dir : order) {
                            int[] counts = countsByDir.get(dir);
                            boolean[] flags = flagsByDir.get(dir);
                            BuildGraph.BuildUnit unit =
                                    new BuildGraph.BuildUnit(Path.of(dir), null, coordByDir.get(dir), BuildGraph.Origin.MODULE);
                            modules.add(new dev.jkbuild.runtime.BuildPlanForecast.Module(
                                    unit, phasesByDir.get(dir), counts[0], counts[1], flags[0], flags[1]));
                        }
                        return new BuildService.ExplainPlan(
                                modules, edges, Ndjson.intValue(line, "maxReadyWidth", 1), errors);
                    }
                    default -> {
                        /* forward-compatible no-op */
                    }
                }
            }
            throw new IOException("jk daemon: the build daemon disconnected unexpectedly before finishing "
                    + "(it may have crashed); run `jk daemon status` for details");
        }
    }

    private static GoalResult streamSingleGoalEvents(
            BufferedReader reader,
            java.util.function.Function<List<Phase>, GoalListener> listenerFactory,
            dev.jkbuild.test.JUnitLauncher.Result[] testResultOut,
            String[] buildOutcomeOut)
            throws IOException {
        List<Phase> phases = new ArrayList<>();
        List<GoalResult.Diagnostic> diagnostics = new ArrayList<>();
        GoalListener listener = null;

        String line;
        while ((line = reader.readLine()) != null) {
            String type = DaemonProtocol.typeOf(line);
            if (type == null) continue;
            switch (type) {
                case DaemonProtocol.PLAN_PHASE -> phases.add(Phase.builder(Ndjson.str(line, "name"))
                        .label(Ndjson.str(line, "label"))
                        .build());
                case DaemonProtocol.PLAN_DONE -> listener = listenerFactory.apply(phases);
                case DaemonProtocol.GOAL_START -> listener.goalStart(readGoalView(line));
                case DaemonProtocol.PHASE_START -> listener.phaseStart(
                        Ndjson.str(line, "phase"), Ndjson.intValue(line, "scope", 0));
                case DaemonProtocol.PROGRESS -> listener.progress(
                        Ndjson.str(line, "phase"), Ndjson.intValue(line, "delta", 0), readGoalView(line));
                case DaemonProtocol.SCOPE_UPDATE -> listener.scopeUpdate(
                        Ndjson.str(line, "phase"), Ndjson.intValue(line, "delta", 0), readGoalView(line));
                case DaemonProtocol.LABEL -> listener.label(Ndjson.str(line, "phase"), Ndjson.str(line, "label"));
                case DaemonProtocol.OUTPUT -> listener.output(Ndjson.str(line, "phase"), Ndjson.str(line, "line"));
                case DaemonProtocol.WARN -> listener.warn(
                        Ndjson.str(line, "phase"), Ndjson.str(line, "code"), Ndjson.str(line, "message"));
                case DaemonProtocol.ERROR -> listener.error(
                        Ndjson.str(line, "phase"),
                        Ndjson.str(line, "code"),
                        Ndjson.str(line, "message"),
                        Ndjson.str(line, "test"),
                        Ndjson.str(line, "exceptionClass"));
                case DaemonProtocol.GOAL_DIAGNOSTIC -> diagnostics.add(new GoalResult.Diagnostic(
                        Ndjson.str(line, "phase"),
                        Ndjson.str(line, "code"),
                        Ndjson.str(line, "message"),
                        Ndjson.str(line, "test"),
                        Ndjson.str(line, "exceptionClass")));
                case DaemonProtocol.PHASE_FINISH -> listener.phaseFinish(
                        Ndjson.str(line, "phase"), PhaseStatus.valueOf(Ndjson.str(line, "status")), Duration.ZERO);
                case DaemonProtocol.GOAL_FINISH -> {
                    boolean success = Ndjson.bool(line, "success", false);
                    long total = Ndjson.longValue(line, "testTotal", -1);
                    if (total >= 0 && testResultOut != null) {
                        testResultOut[0] = new dev.jkbuild.test.JUnitLauncher.Result(
                                total,
                                Ndjson.longValue(line, "testSucceeded", 0),
                                Ndjson.longValue(line, "testFailed", 0),
                                Ndjson.longValue(line, "testSkipped", 0),
                                List.of());
                    }
                    if (buildOutcomeOut != null) {
                        buildOutcomeOut[0] = Ndjson.str(line, "buildOutcome");
                    }
                    GoalResult result = new GoalResult(
                            "test", success, Duration.ZERO, List.of(), List.of(), diagnostics, false, false);
                    listener.goalFinish(result);
                    return result;
                }
                case DaemonProtocol.BUILD_ERROR -> throw new IOException(
                        "jk daemon: run failed: " + Ndjson.str(line, "message"));
                default -> {
                    /* forward-compatible no-op */
                }
            }
        }
        throw new IOException("jk daemon: the build daemon disconnected unexpectedly before finishing "
                + "(it may have crashed); run `jk daemon status` for details");
    }

    private static BuildService.WorkspaceResult streamEvents(
            BufferedReader reader, WorkspaceBuildListener listener, Path cache) throws IOException {
        Map<String, ModuleMeta> planByDir = new LinkedHashMap<>();
        Map<String, GoalListener> goalListenersByDir = new LinkedHashMap<>();
        Map<String, List<GoalResult.Diagnostic>> diagnosticsByDir = new LinkedHashMap<>();
        List<BuildService.ModuleOutcome> outcomes = new ArrayList<>();
        String pendingPlanDir = null; // the dir most recently opened by plan-module, for plan-phase lines

        String line;
        while ((line = reader.readLine()) != null) {
            String type = DaemonProtocol.typeOf(line);
            if (type == null) continue;
            String dir = Ndjson.str(line, "dir");
            switch (type) {
                case DaemonProtocol.PLAN_MODULE -> {
                    planByDir.put(
                            dir,
                            new ModuleMeta(
                                    Ndjson.str(line, "coord"),
                                    Ndjson.str(line, "goalName"),
                                    Ndjson.intValue(line, "weight", 0),
                                    Ndjson.bool(line, "fullyCached", false)));
                    pendingPlanDir = dir;
                }
                case DaemonProtocol.PLAN_PHASE -> {
                    ModuleMeta m = planByDir.get(dir != null ? dir : pendingPlanDir);
                    if (m != null) {
                        m.phases.add(Phase.builder(Ndjson.str(line, "name"))
                                .label(Ndjson.str(line, "label"))
                                .build());
                    }
                }
                case DaemonProtocol.PLAN_DONE -> listener.onPlan(buildModulePlans(planByDir, cache));
                case DaemonProtocol.ETA -> listener.onEtaEstimate(Ndjson.longValue(line, "millis", 0));
                case DaemonProtocol.MODULE_START -> {
                    BuildService.ModulePlan plan = buildModulePlan(dir, planByDir.get(dir), cache);
                    GoalListener gl = listener.onModuleStart(plan);
                    goalListenersByDir.put(dir, gl != null ? gl : new GoalListener() {});
                }
                case DaemonProtocol.GOAL_START -> goalListenersByDir
                        .getOrDefault(dir, NOOP)
                        .goalStart(readGoalView(line));
                case DaemonProtocol.PHASE_START -> goalListenersByDir
                        .getOrDefault(dir, NOOP)
                        .phaseStart(Ndjson.str(line, "phase"), Ndjson.intValue(line, "scope", 0));
                case DaemonProtocol.PROGRESS -> goalListenersByDir
                        .getOrDefault(dir, NOOP)
                        .progress(Ndjson.str(line, "phase"), Ndjson.intValue(line, "delta", 0), readGoalView(line));
                case DaemonProtocol.SCOPE_UPDATE -> goalListenersByDir
                        .getOrDefault(dir, NOOP)
                        .scopeUpdate(Ndjson.str(line, "phase"), Ndjson.intValue(line, "delta", 0), readGoalView(line));
                case DaemonProtocol.LABEL -> goalListenersByDir
                        .getOrDefault(dir, NOOP)
                        .label(Ndjson.str(line, "phase"), Ndjson.str(line, "label"));
                case DaemonProtocol.OUTPUT -> goalListenersByDir
                        .getOrDefault(dir, NOOP)
                        .output(Ndjson.str(line, "phase"), Ndjson.str(line, "line"));
                case DaemonProtocol.WARN -> goalListenersByDir
                        .getOrDefault(dir, NOOP)
                        .warn(Ndjson.str(line, "phase"), Ndjson.str(line, "code"), Ndjson.str(line, "message"));
                case DaemonProtocol.ERROR -> goalListenersByDir
                        .getOrDefault(dir, NOOP)
                        .error(
                                Ndjson.str(line, "phase"),
                                Ndjson.str(line, "code"),
                                Ndjson.str(line, "message"),
                                Ndjson.str(line, "test"),
                                Ndjson.str(line, "exceptionClass"));
                case DaemonProtocol.GOAL_DIAGNOSTIC -> diagnosticsByDir
                        .computeIfAbsent(dir, d -> new ArrayList<>())
                        .add(new GoalResult.Diagnostic(
                                Ndjson.str(line, "phase"),
                                Ndjson.str(line, "code"),
                                Ndjson.str(line, "message"),
                                Ndjson.str(line, "test"),
                                Ndjson.str(line, "exceptionClass")));
                case DaemonProtocol.PHASE_FINISH -> goalListenersByDir
                        .getOrDefault(dir, NOOP)
                        .phaseFinish(
                                Ndjson.str(line, "phase"),
                                PhaseStatus.valueOf(Ndjson.str(line, "status")),
                                Duration.ZERO);
                case DaemonProtocol.GOAL_FINISH -> {
                    ModuleMeta meta = planByDir.get(dir);
                    String goalName = meta != null ? meta.goalName : dir;
                    List<GoalResult.Diagnostic> diags = diagnosticsByDir.remove(dir);
                    GoalResult result = new GoalResult(
                            goalName,
                            Ndjson.bool(line, "success", false),
                            Duration.ZERO,
                            List.of(),
                            List.of(),
                            diags != null ? diags : List.of(),
                            false,
                            false);
                    goalListenersByDir.getOrDefault(dir, NOOP).goalFinish(result);
                }
                case DaemonProtocol.MODULE_FINISH -> {
                    BuildService.ModuleOutcome outcome = new BuildService.ModuleOutcome(
                            Ndjson.str(line, "coord"),
                            Path.of(dir),
                            Ndjson.bool(line, "success", false),
                            Ndjson.intValue(line, "exitCode", 1),
                            Ndjson.longValue(line, "millis", 0));
                    outcomes.add(outcome);
                    listener.onModuleFinish(outcome);
                }
                case DaemonProtocol.WORKSPACE_FINISH -> {
                    BuildService.WorkspaceResult result = new BuildService.WorkspaceResult(
                            Ndjson.bool(line, "success", false),
                            Ndjson.intValue(line, "exitCode", 1),
                            List.copyOf(outcomes),
                            Ndjson.strArray(line, "errors"));
                    listener.onWorkspaceFinish(result);
                    return result;
                }
                case DaemonProtocol.BUILD_ERROR -> throw new IOException(
                        "jk daemon: build failed: " + Ndjson.str(line, "message"));
                default -> {
                    /* forward-compatible no-op */
                }
            }
        }
        throw new IOException("jk daemon: the build daemon disconnected unexpectedly before finishing "
                + "(it may have crashed); run `jk daemon status` for details");
    }

    private static List<BuildService.ModulePlan> buildModulePlans(Map<String, ModuleMeta> planByDir, Path cache) {
        List<BuildService.ModulePlan> plans = new ArrayList<>(planByDir.size());
        for (Map.Entry<String, ModuleMeta> e : planByDir.entrySet()) {
            plans.add(buildModulePlan(e.getKey(), e.getValue(), cache));
        }
        return plans;
    }

    private static BuildService.ModulePlan buildModulePlan(String dir, ModuleMeta m, Path cache) {
        Goal inertGoal = Goal.builder(m.goalName).addAllPhases(m.phases).build();
        BuildGraph.BuildUnit unit = new BuildGraph.BuildUnit(Path.of(dir), null, m.coord, BuildGraph.Origin.ROOT);
        return new BuildService.ModulePlan(unit, inertGoal, m.weight, m.fullyCached, cache);
    }

    private static GoalView readGoalView(String line) {
        return new GoalView(
                Ndjson.str(line, "goalName"),
                Ndjson.longValue(line, "numerator", 0),
                Ndjson.longValue(line, "denominator", 0),
                Ndjson.intValue(line, "phasesTotal", 0),
                Ndjson.intValue(line, "phasesComplete", 0),
                Ndjson.bool(line, "cancelled", false));
    }

    private static final GoalListener NOOP = new GoalListener() {};
}
