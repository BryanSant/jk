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
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Drives the hosted worker verbs ({@code jk audit} / {@code format} / {@code publish} / {@code
 * image} / {@code import} and {@code jk mvn}/{@code gradle} provisioning) against the engine — the
 * Wave-2 sibling of {@link EngineResolveAdapter}: sends the request, replays the single-goal event
 * stream into the command's listener, hands the verb's repeated structured events ({@code
 * audit-finding} / {@code format-file} / {@code import-note}) to a per-verb hook, and returns the
 * raw terminal {@code goal-finish} line so the command can decode its variant fields.
 *
 * <p>Per the rendering rule, everything arriving here is plain structured text — the engine never
 * themes output; the command's renderer colorizes client-side.
 */
final class EngineWorkerAdapter {

    private EngineWorkerAdapter() {}

    /** A hosted single-goal run's outcome: the replayed result plus the raw terminal line to decode. */
    record HostedFinish(GoalResult result, String finishLine) {}

    /**
     * Send {@code requestLine} and replay the single-goal stream: plan-phase burst → {@code
     * listenerFactory} (invoked once the phase list is known, mirroring {@code
     * EngineBuildListenerAdapter.runTest}) → goal events → terminal goal-finish. {@code onEvent}
     * receives each verb-specific structured event as {@code (type, rawLine)}.
     */
    static HostedFinish stream(
            EnginePaths.Paths paths,
            String requestLine,
            String goalName,
            Function<List<Phase>, GoalListener> listenerFactory,
            BiConsumer<String, String> onEvent)
            throws IOException {
        return stream(paths, requestLine, goalName, listenerFactory, onEvent, null);
    }

    /**
     * As {@link #stream(EnginePaths.Paths, String, String, Function, BiConsumer)}, additionally
     * invoking {@code preFinish} with the raw terminal line <em>before</em> the listener's own
     * {@code goalFinish} is dispatched — for verbs whose console listener renders summary fields
     * (populated from the finish line) from within its {@code goalFinish} handler, mirroring how
     * {@code EngineBuildListenerAdapter.runTest} settles {@code testResultOut} first.
     */
    static HostedFinish stream(
            EnginePaths.Paths paths,
            String requestLine,
            String goalName,
            Function<List<Phase>, GoalListener> listenerFactory,
            BiConsumer<String, String> onEvent,
            java.util.function.Consumer<String> preFinish)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineClient.connect(dev.jkbuild.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader =
                    EngineClient.protocolReader(ch);
            // The session envelope — variant selection, client env, and worker-JVM tuning —
            // rides EVERY hosted-goal request line (compile/image/native/publish/install/...).
            // An empty envelope attaches nothing, so unadorned goals are byte-identical.
            var session = dev.jkbuild.config.SessionContext.current();
            send(writer, EngineProtocol.withSession(
                    requestLine, session.variant(), session.clientEnv(), session.jvm()));

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
                    case EngineProtocol.AUDIT_FINDING,
                            EngineProtocol.FORMAT_FILE,
                            EngineProtocol.IMPORT_NOTE,
                            EngineProtocol.PRUNE_WAIT ->
                        onEvent.accept(type, line);
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
                        if (preFinish != null) preFinish.accept(line);
                        if (listener != null) listener.goalFinish(result);
                        return new HostedFinish(result, line);
                    }
                    case EngineProtocol.ERROR -> throw new IOException(
                            "jk engine: run failed: " + Ndjson.str(line, "message"));
                    default -> dispatchGoalEvent(type, line, listener, diagnostics);
                }
            }
            throw disconnected();
        }
    }

    /**
     * Send a one-shot {@link EngineProtocol#PROVISION_REQUEST} and wait for its terminal — no goal
     * events stream (see the protocol docs); the worker may still take a while (a distribution
     * download), which is fine on this blocking read.
     */
    static dev.jkbuild.runtime.HostedEvents.Provision provision(EnginePaths.Paths paths, String requestLine)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineClient.connect(dev.jkbuild.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader =
                    EngineClient.protocolReader(ch);
            // The session envelope — variant selection, client env, and worker-JVM tuning —
            // rides EVERY hosted-goal request line (compile/image/native/publish/install/...).
            // An empty envelope attaches nothing, so unadorned goals are byte-identical.
            var session = dev.jkbuild.config.SessionContext.current();
            send(writer, EngineProtocol.withSession(
                    requestLine, session.variant(), session.clientEnv(), session.jvm()));

            String line;
            while ((line = reader.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (type == null) continue;
                switch (type) {
                    case EngineProtocol.PROVISION_RESULT -> {
                        return new dev.jkbuild.runtime.HostedEvents.Provision(
                                Ndjson.str(line, "bin"),
                                Ndjson.str(line, "version"),
                                Ndjson.str(line, "source"),
                                Ndjson.str(line, "error"),
                                Ndjson.intValue(line, "exit", 1),
                                Ndjson.str(line, "diag"));
                    }
                    case EngineProtocol.ERROR -> throw new IOException(
                            "jk engine: run failed: " + Ndjson.str(line, "message"));
                    default -> {
                        /* forward-compatible no-op */
                    }
                }
            }
            throw disconnected();
        }
    }

    /**
     * Replay one standard single-goal wire event into {@code listener} (accumulating {@code
     * goal-diagnostic}s aside) — the same shared tail {@link EngineResolveAdapter} keeps for the
     * resolver family. Unknown types are forward-compatible no-ops.
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
            case EngineProtocol.ERROR_LINE -> listener.error(
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
}
