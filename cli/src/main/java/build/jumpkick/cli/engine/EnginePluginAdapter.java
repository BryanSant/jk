// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.cli.engine;

import build.jumpkick.plugin.build.Phase;
import build.jumpkick.cli.Jk;
import build.jumpkick.engine.EnginePaths;
import build.jumpkick.engine.protocol.EngineProtocol;
import build.jumpkick.plugin.protocol.Ndjson;
import build.jumpkick.run.PipelineListener;
import build.jumpkick.run.PipelineResult;
import build.jumpkick.run.PipelineView;
import build.jumpkick.run.Step;
import build.jumpkick.run.StepStatus;
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
 * Drives the hosted worker commands ({@code jk audit} / {@code format} / {@code publish} / {@code
 * image} / {@code import} and {@code jk mvn}/{@code gradle} provisioning) against the engine — the
 * Wave-2 sibling of {@link EngineResolveAdapter}: sends the request, replays the single-pipeline event
 * stream into the command's listener, hands the command's repeated structured events ({@code
 * audit-finding} / {@code format-file} / {@code import-note}) to a per-command hook, and returns the
 * raw terminal {@code pipeline-finish} line so the command can decode its variant fields.
 *
 * <p>Per the rendering rule, everything arriving here is plain structured text — the engine never
 * themes output; the command's renderer colorizes client-side.
 */
final class EnginePluginAdapter {

    private EnginePluginAdapter() {}

    /** A hosted single-pipeline run's outcome: the replayed result plus the raw terminal line to decode. */
    record HostedFinish(PipelineResult result, String finishLine) {}

    /**
     * Send {@code requestLine} and replay the single-pipeline stream: plan-step burst → {@code
     * listenerFactory} (invoked once the step list is known, mirroring {@code
     * EngineBuildListenerAdapter.runTest}) → pipeline events → terminal pipeline-finish. {@code onEvent}
     * receives each command-specific structured event as {@code (type, rawLine)}.
     */
    static HostedFinish stream(
            EnginePaths.Paths paths,
            String requestLine,
            String pipelineName,
            Function<List<Step>, PipelineListener> listenerFactory,
            BiConsumer<String, String> onEvent)
            throws IOException {
        return stream(paths, requestLine, pipelineName, listenerFactory, onEvent, null);
    }

    /**
     * As {@link #stream(EnginePaths.Paths, String, String, Function, BiConsumer)}, additionally
     * invoking {@code preFinish} with the raw terminal line <em>before</em> the listener's own
     * {@code pipelineFinish} is dispatched — for commands whose console listener renders summary fields
     * (populated from the finish line) from within its {@code pipelineFinish} handler, mirroring how
     * {@code EngineBuildListenerAdapter.runTest} settles {@code testResultOut} first.
     */
    static HostedFinish stream(
            EnginePaths.Paths paths,
            String requestLine,
            String pipelineName,
            Function<List<Step>, PipelineListener> listenerFactory,
            BiConsumer<String, String> onEvent,
            java.util.function.Consumer<String> preFinish)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineClient.connect(build.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader =
                    EngineClient.protocolReader(ch);
            // The session envelope — variant selection, client env, and worker-JVM tuning —
            // rides EVERY hosted-pipeline request line (compile/image/native/publish/install/...).
            // An empty envelope attaches nothing, so unadorned pipelines are byte-identical.
            var session = build.jumpkick.config.SessionContext.current();
            send(writer, EngineProtocol.withSession(
                    requestLine, session.variant(), session.clientEnv(), session.jvm(),
                    session.config().rebuildOr(false)));

            List<Step> steps = new ArrayList<>();
            List<PipelineResult.Diagnostic> diagnostics = new ArrayList<>();
            PipelineListener listener = null;

            String line;
            while ((line = reader.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (type == null) continue;
                switch (type) {
                    case EngineProtocol.PLAN_STEP -> steps.add(Step.builder(Ndjson.str(line, "name"))
                            .label(Ndjson.str(line, "label")).phase(Phase.fromWireOrNull(Ndjson.str(line, "phase")))
                            .build());
                    case EngineProtocol.PLAN_DONE -> listener = listenerFactory.apply(steps);
                    case EngineProtocol.AUDIT_FINDING,
                            EngineProtocol.FORMAT_FILE,
                            EngineProtocol.IMPORT_NOTE,
                            EngineProtocol.PRUNE_WAIT ->
                        onEvent.accept(type, line);
                    case EngineProtocol.PIPELINE_FINISH -> {
                        PipelineResult result = new PipelineResult(
                                pipelineName,
                                Ndjson.bool(line, "success", false),
                                Duration.ZERO,
                                List.of(),
                                List.of(),
                                diagnostics,
                                false,
                                false);
                        if (preFinish != null) preFinish.accept(line);
                        if (listener != null) listener.pipelineFinish(result);
                        return new HostedFinish(result, line);
                    }
                    case EngineProtocol.ERROR -> throw new IOException(
                            "jk engine: run failed: " + Ndjson.str(line, "message"));
                    default -> dispatchPipelineEvent(type, line, listener, diagnostics);
                }
            }
            throw disconnected();
        }
    }

    /**
     * Send a one-shot {@link EngineProtocol#PROVISION_REQUEST} and wait for its terminal — no pipeline
     * events stream (see the protocol docs); the worker may still take a while (a distribution
     * download), which is fine on this blocking read.
     */
    static build.jumpkick.runtime.HostedEvents.Provision provision(EnginePaths.Paths paths, String requestLine)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineClient.connect(build.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader =
                    EngineClient.protocolReader(ch);
            // The session envelope — variant selection, client env, and worker-JVM tuning —
            // rides EVERY hosted-pipeline request line (compile/image/native/publish/install/...).
            // An empty envelope attaches nothing, so unadorned pipelines are byte-identical.
            var session = build.jumpkick.config.SessionContext.current();
            send(writer, EngineProtocol.withSession(
                    requestLine, session.variant(), session.clientEnv(), session.jvm(),
                    session.config().rebuildOr(false)));

            String line;
            while ((line = reader.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (type == null) continue;
                switch (type) {
                    case EngineProtocol.PROVISION_RESULT -> {
                        return new build.jumpkick.runtime.HostedEvents.Provision(
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
     * Replay one standard single-pipeline wire event into {@code listener} (accumulating {@code
     * pipeline-diagnostic}s aside) — the same shared tail {@link EngineResolveAdapter} keeps for the
     * resolver family. Unknown types are forward-compatible no-ops.
     */
    private static void dispatchPipelineEvent(
            String type, String line, PipelineListener listener, List<PipelineResult.Diagnostic> diagnostics) {
        if (listener == null) {
            // pipeline-diagnostics can still matter pre-listener; everything else needs one.
            if (EngineProtocol.PIPELINE_DIAGNOSTIC.equals(type)) {
                diagnostics.add(readDiagnostic(line));
            }
            return;
        }
        switch (type) {
            case EngineProtocol.PIPELINE_START -> listener.pipelineStart(readPipelineView(line));
            case EngineProtocol.STEP_START -> listener.stepStart(
                    Ndjson.str(line, "step"), Phase.fromWireOrNull(Ndjson.str(line, "phase")), Ndjson.intValue(line, "ticks", 0));
            case EngineProtocol.PROGRESS -> listener.progress(
                    Ndjson.str(line, "step"), Ndjson.intValue(line, "delta", 0), readPipelineView(line));
            case EngineProtocol.TICK_UPDATE -> listener.tickUpdate(
                    Ndjson.str(line, "step"), Ndjson.intValue(line, "delta", 0), readPipelineView(line));
            case EngineProtocol.LABEL -> listener.label(Ndjson.str(line, "step"), Ndjson.str(line, "label"));
            case EngineProtocol.OUTPUT -> listener.output(Ndjson.str(line, "step"), Ndjson.str(line, "line"));
            case EngineProtocol.WARN -> listener.warn(
                    Ndjson.str(line, "step"), Ndjson.str(line, "code"), Ndjson.str(line, "message"));
            case EngineProtocol.ERROR_LINE -> listener.error(
                    Ndjson.str(line, "step"),
                    Ndjson.str(line, "code"),
                    Ndjson.str(line, "message"),
                    Ndjson.str(line, "test"),
                    Ndjson.str(line, "exceptionClass"));
            case EngineProtocol.PIPELINE_DIAGNOSTIC -> diagnostics.add(readDiagnostic(line));
            case EngineProtocol.STEP_FINISH -> listener.stepFinish(
                    Ndjson.str(line, "step"), Phase.fromWireOrNull(Ndjson.str(line, "phase")), StepStatus.valueOf(Ndjson.str(line, "status")), Duration.ZERO);
            default -> {
                /* forward-compatible no-op */
            }
        }
    }

    private static PipelineResult.Diagnostic readDiagnostic(String line) {
        return new PipelineResult.Diagnostic(
                Ndjson.str(line, "step"),
                Ndjson.str(line, "code"),
                Ndjson.str(line, "message"),
                Ndjson.str(line, "test"),
                Ndjson.str(line, "exceptionClass"));
    }

    private static PipelineView readPipelineView(String line) {
        return new PipelineView(
                Ndjson.str(line, "pipelineName"),
                Ndjson.longValue(line, "numerator", 0),
                Ndjson.longValue(line, "denominator", 0),
                Ndjson.intValue(line, "stepsTotal", 0),
                Ndjson.intValue(line, "stepsComplete", 0),
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
