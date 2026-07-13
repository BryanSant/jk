// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.engine;

import dev.jkbuild.cli.Jk;
import dev.jkbuild.engine.EnginePaths;
import dev.jkbuild.engine.protocol.EngineProtocol;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.GoalView;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseStatus;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Drives the resolver-family verbs ({@code jk lock} / {@code jk update} / {@code jk sync}) against
 * the engine instead of in-process — the Wave-1 sibling of {@link EngineBuildListenerAdapter}: sends
 * the request, decodes the resulting wire-event stream, and re-invokes the exact listener/handler
 * the command's renderer already builds for the in-process path.
 *
 * <p>Lock/update stream a <em>cascade</em>: a {@code lock-module} opens each module's scope (entry
 * project first, then workspace modules in declaration order), followed by that module's plan-phase
 * burst and dir-tagged goal events, ending in a {@code lock-finish} terminal. Sync is a single goal
 * — {@code jk test}'s exact wire shape, plus summary counts on the terminal goal-finish.
 *
 * <p>Per the re-foundation rendering rule, everything arriving here is plain structured text — the
 * engine never themes output. The {@link LockHandler} callbacks hand the structured pieces
 * (coordinates, versions, counts, diagnostics) to the command, whose renderer colorizes client-side.
 */
final class EngineResolveAdapter {

    private EngineResolveAdapter() {}

    /**
     * Run {@code jk outdated} against the engine: one synchronous request, one {@code outdated-ack}
     * carrying the {@link dev.jkbuild.engine.protocol.OutdatedReport} back. Read-only — no cascade,
     * no goal stream.
     */
    static dev.jkbuild.engine.protocol.OutdatedReport runOutdated(
            EnginePaths.Paths paths, EngineClient.OutdatedRequest req) throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);
        try (SocketChannel ch = EngineClient.connect(dev.jkbuild.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
            writer.write(EngineProtocol.outdatedRequest(
                    req.entryDir().toString(),
                    req.cache().toString(),
                    req.repoUrl() != null ? req.repoUrl().toString() : null,
                    req.offline(),
                    req.force()));
            writer.write('\n');
            writer.flush();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!EngineProtocol.OUTDATED_ACK.equals(EngineProtocol.typeOf(line))) continue;
                return dev.jkbuild.engine.protocol.OutdatedReport.decode(line);
            }
            throw new IOException("jk engine: disconnected before answering the outdated request");
        }
    }

    /** Run {@code jk lock}'s cascade against the engine, driving {@code handler}. */
    static EngineClient.LockOutcome runLock(
            EnginePaths.Paths paths, EngineClient.LockRequest req, EngineClient.LockHandler handler)
            throws IOException {
        return streamCascade(
                paths,
                EngineProtocol.lockRequest(
                        req.entryDir().toString(),
                        req.cache().toString(),
                        req.features(),
                        req.noDefaultFeatures(),
                        req.sources(),
                        req.repoUrl() != null ? req.repoUrl().toString() : null,
                        req.offline(),
                        req.force(),
                        req.verbose()),
                handler,
                "lock");
    }

    /** Run {@code jk update}'s full re-resolve cascade against the engine, driving {@code handler}. */
    static EngineClient.LockOutcome runUpdate(
            EnginePaths.Paths paths, EngineClient.UpdateRequest req, EngineClient.LockHandler handler)
            throws IOException {
        return streamCascade(paths, updateRequestLine(req, false, null), handler, "update");
    }

    /**
     * Run {@code jk update --git [<name>]} against the engine. No goal events stream — the engine
     * splices the lock and replies with just the terminal, whose {@code refreshed} count and plain
     * {@code errors} the command renders.
     */
    static EngineClient.LockOutcome runUpdateGitOnly(
            EnginePaths.Paths paths, EngineClient.UpdateRequest req, String gitTarget) throws IOException {
        return streamCascade(paths, updateRequestLine(req, true, gitTarget), NOOP_HANDLER, "update");
    }

    private static String updateRequestLine(EngineClient.UpdateRequest req, boolean gitOnly, String gitTarget) {
        return EngineProtocol.updateRequest(
                req.entryDir().toString(),
                req.cache().toString(),
                req.features(),
                req.noDefaultFeatures(),
                req.repoUrl() != null ? req.repoUrl().toString() : null,
                gitOnly,
                gitTarget,
                req.offline(),
                req.force(),
                req.verbose());
    }

    /**
     * Run {@code jk sync}'s single goal against the engine — the same listener-factory contract as
     * {@link EngineBuildListenerAdapter#runTest}. {@code fetchedOut}/{@code upToDateOut} (single-slot
     * holders) are populated from the terminal goal-finish <em>before</em> it reaches the factory's
     * listener, exactly mirroring how the in-process path's counters are already settled by the time
     * the console listener's own {@code goalFinish} renders the summary line.
     */
    static GoalResult runSync(
            EnginePaths.Paths paths,
            EngineClient.SyncRequest req,
            Function<List<Phase>, GoalListener> listenerFactory,
            long[] fetchedOut,
            long[] upToDateOut)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineClient.connect(dev.jkbuild.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));

            send(
                    writer,
                    EngineProtocol.syncRequest(
                            req.entryDir().toString(),
                            req.cache().toString(),
                            req.jdksDir() != null ? req.jdksDir().toString() : null,
                            req.repoUrl() != null ? req.repoUrl().toString() : null,
                            req.sources(),
                            req.offline(),
                            req.force(),
                            req.refresh(),
                            req.verbose()));

            List<Phase> phases = new ArrayList<>();
            List<GoalResult.Diagnostic> diagnostics = new ArrayList<>();
            GoalListener listener = null;

            String line;
            while ((line = reader.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (type == null) continue;
                switch (type) {
                    case EngineProtocol.PLAN_PHASE -> phases.add(Phase.builder(Ndjson.str(line, "name"))
                            .label(Ndjson.str(line, "label"))
                            .build());
                    case EngineProtocol.PLAN_DONE -> listener = listenerFactory.apply(phases);
                    case EngineProtocol.GOAL_FINISH -> {
                        if (fetchedOut != null) fetchedOut[0] = Ndjson.longValue(line, "syncFetched", 0);
                        if (upToDateOut != null) upToDateOut[0] = Ndjson.longValue(line, "syncUpToDate", 0);
                        GoalResult result = new GoalResult(
                                "sync",
                                Ndjson.bool(line, "success", false),
                                Duration.ZERO,
                                List.of(),
                                List.of(),
                                diagnostics,
                                false,
                                false);
                        if (listener != null) listener.goalFinish(result);
                        return result;
                    }
                    case EngineProtocol.BUILD_ERROR -> throw new IOException(
                            "jk engine: run failed: " + Ndjson.str(line, "message"));
                    default -> dispatchGoalEvent(type, line, listener, diagnostics);
                }
            }
            throw disconnected();
        }
    }

    /** Send a cascade request and replay its stream into {@code handler} until the terminal arrives. */
    private static EngineClient.LockOutcome streamCascade(
            EnginePaths.Paths paths, String requestLine, EngineClient.LockHandler handler, String goalName)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineClient.connect(dev.jkbuild.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
            send(writer, requestLine);

            // Cascade state: the module currently streaming. Modules are strictly sequential on the
            // wire (the engine locks them one at a time), so one slot suffices.
            String currentDir = null;
            String currentCoord = null;
            List<Phase> phases = new ArrayList<>();
            List<GoalResult.Diagnostic> diagnostics = new ArrayList<>();
            GoalListener listener = null;

            String line;
            while ((line = reader.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (type == null) continue;
                switch (type) {
                    case EngineProtocol.LOCK_MODULE -> {
                        currentDir = Ndjson.str(line, "dir");
                        currentCoord = Ndjson.str(line, "coord");
                        phases = new ArrayList<>();
                        diagnostics = new ArrayList<>();
                        listener = null;
                    }
                    case EngineProtocol.PLAN_PHASE -> phases.add(Phase.builder(Ndjson.str(line, "name"))
                            .label(Ndjson.str(line, "label"))
                            .build());
                    case EngineProtocol.PLAN_DONE -> listener = handler.onModuleStart(currentDir, currentCoord, phases);
                    case EngineProtocol.LOCK_PACKAGE -> handler.onPackage(
                            Ndjson.str(line, "dir"), Ndjson.str(line, "name"), Ndjson.str(line, "version"));
                    case EngineProtocol.GOAL_FINISH -> {
                        GoalResult result = new GoalResult(
                                goalName,
                                Ndjson.bool(line, "success", false),
                                Duration.ZERO,
                                List.of(),
                                List.of(),
                                diagnostics,
                                false,
                                false);
                        if (listener != null) listener.goalFinish(result);
                        handler.onModuleFinish(
                                currentDir,
                                result,
                                new EngineClient.LockCounts(
                                        Ndjson.longValue(line, "lockPackages", -1),
                                        Ndjson.longValue(line, "lockSources", -1),
                                        Ndjson.longValue(line, "lockPlugins", -1)));
                    }
                    case EngineProtocol.LOCK_FINISH -> {
                        return new EngineClient.LockOutcome(
                                Ndjson.bool(line, "success", false),
                                Ndjson.intValue(line, "exitCode", 1),
                                Ndjson.strArray(line, "errors"),
                                Ndjson.intValue(line, "refreshed", -1));
                    }
                    case EngineProtocol.BUILD_ERROR -> throw new IOException(
                            "jk engine: run failed: " + Ndjson.str(line, "message"));
                    default -> dispatchGoalEvent(type, line, listener, diagnostics);
                }
            }
            throw disconnected();
        }
    }

    /**
     * Replay one standard single-goal wire event into {@code listener} (accumulating {@code
     * goal-diagnostic}s aside, like {@link EngineBuildListenerAdapter} does) — the shared tail of
     * both stream loops. Unknown types are forward-compatible no-ops.
     */
    private static void dispatchGoalEvent(
            String type, String line, GoalListener listener, List<GoalResult.Diagnostic> diagnostics) {
        if (listener == null) {
            // goal-diagnostics can still matter pre-listener; everything else needs one.
            if (EngineProtocol.GOAL_DIAGNOSTIC.equals(type)) {
                diagnostics.add(readDiagnostic(line));
            }
            return;
        }
        switch (type) {
            case EngineProtocol.GOAL_START -> listener.goalStart(readGoalView(line));
            case EngineProtocol.PHASE_START -> listener.phaseStart(
                    Ndjson.str(line, "phase"), Ndjson.intValue(line, "scope", 0));
            case EngineProtocol.PROGRESS -> listener.progress(
                    Ndjson.str(line, "phase"), Ndjson.intValue(line, "delta", 0), readGoalView(line));
            case EngineProtocol.SCOPE_UPDATE -> listener.scopeUpdate(
                    Ndjson.str(line, "phase"), Ndjson.intValue(line, "delta", 0), readGoalView(line));
            case EngineProtocol.LABEL -> listener.label(Ndjson.str(line, "phase"), Ndjson.str(line, "label"));
            case EngineProtocol.OUTPUT -> listener.output(Ndjson.str(line, "phase"), Ndjson.str(line, "line"));
            case EngineProtocol.WARN -> listener.warn(
                    Ndjson.str(line, "phase"), Ndjson.str(line, "code"), Ndjson.str(line, "message"));
            case EngineProtocol.ERROR -> listener.error(
                    Ndjson.str(line, "phase"),
                    Ndjson.str(line, "code"),
                    Ndjson.str(line, "message"),
                    Ndjson.str(line, "test"),
                    Ndjson.str(line, "exceptionClass"));
            case EngineProtocol.GOAL_DIAGNOSTIC -> diagnostics.add(readDiagnostic(line));
            case EngineProtocol.PHASE_FINISH -> listener.phaseFinish(
                    Ndjson.str(line, "phase"), PhaseStatus.valueOf(Ndjson.str(line, "status")), Duration.ZERO);
            default -> {
                /* forward-compatible no-op */
            }
        }
    }

    private static GoalResult.Diagnostic readDiagnostic(String line) {
        return new GoalResult.Diagnostic(
                Ndjson.str(line, "phase"),
                Ndjson.str(line, "code"),
                Ndjson.str(line, "message"),
                Ndjson.str(line, "test"),
                Ndjson.str(line, "exceptionClass"));
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

    private static void send(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.write('\n');
        writer.flush();
    }

    private static IOException disconnected() {
        return new IOException("jk engine: the build engine disconnected unexpectedly before finishing "
                + "(it may have crashed); run `jk engine status` for details");
    }

    private static final EngineClient.LockHandler NOOP_HANDLER = (dir, coord, phases) -> new GoalListener() {};
}
