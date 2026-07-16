// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.plugin.build.Phase;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.plugin.protocol.Ndjson;
import cc.jumpkick.run.PipelineListener;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.PipelineView;
import cc.jumpkick.run.Step;
import cc.jumpkick.run.StepStatus;
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
 * Drives the resolver-family commands ({@code jk lock} / {@code jk update} / {@code jk sync}) against
 * the engine instead of in-process — the Wave-1 sibling of {@link EngineBuildListenerAdapter}: sends
 * the request, decodes the resulting wire-event stream, and re-invokes the exact listener/handler
 * the command's renderer already builds for the in-process path.
 *
 * <p>Lock/update stream a <em>cascade</em>: a {@code lock-module} opens each module's ticks (entry
 * project first, then workspace modules in declaration order), followed by that module's plan-step
 * burst and dir-tagged pipeline events, ending in a {@code lock-finish} terminal. Sync is a single pipeline
 * — {@code jk test}'s exact wire shape, plus summary counts on the terminal pipeline-finish.
 *
 * <p>Per the re-foundation rendering rule, everything arriving here is plain structured text — the
 * engine never themes output. The {@link LockHandler} callbacks hand the structured pieces
 * (coordinates, versions, counts, diagnostics) to the command, whose renderer colorizes client-side.
 */
final class EngineResolveAdapter {

    private EngineResolveAdapter() {}

    /**
     * Run {@code jk outdated} against the engine: one synchronous request, one {@code outdated-ack}
     * carrying the {@link cc.jumpkick.engine.protocol.OutdatedReport} back. Read-only — no cascade,
     * no pipeline stream.
     */
    static cc.jumpkick.engine.protocol.OutdatedReport runOutdated(
            EnginePaths.Paths paths, EngineClient.OutdatedRequest req) throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);
        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader =
                    EngineClient.protocolReader(ch);
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
                return cc.jumpkick.engine.protocol.OutdatedReport.decode(line);
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
     * Run {@code jk update --git [<name>]} against the engine. No pipeline events stream — the engine
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
     * Run {@code jk sync}'s single pipeline against the engine — the same listener-factory contract as
     * {@link EngineBuildListenerAdapter#runTest}. {@code fetchedOut}/{@code upToDateOut} (single-slot
     * holders) are populated from the terminal pipeline-finish <em>before</em> it reaches the factory's
     * listener, exactly mirroring how the in-process path's counters are already settled by the time
     * the console listener's own {@code pipelineFinish} renders the summary line.
     */
    static PipelineResult runSync(
            EnginePaths.Paths paths,
            EngineClient.SyncRequest req,
            Function<List<Step>, PipelineListener> listenerFactory,
            long[] fetchedOut,
            long[] upToDateOut)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader =
                    EngineClient.protocolReader(ch);

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
                    case EngineProtocol.PIPELINE_FINISH -> {
                        if (fetchedOut != null) fetchedOut[0] = Ndjson.longValue(line, "syncFetched", 0);
                        if (upToDateOut != null) upToDateOut[0] = Ndjson.longValue(line, "syncUpToDate", 0);
                        PipelineResult result = new PipelineResult(
                                "sync",
                                Ndjson.bool(line, "success", false),
                                Duration.ZERO,
                                List.of(),
                                List.of(),
                                diagnostics,
                                false,
                                false);
                        if (listener != null) listener.pipelineFinish(result);
                        return result;
                    }
                    case EngineProtocol.ERROR -> throw new IOException(
                            "jk engine: run failed: " + Ndjson.str(line, "message"));
                    default -> dispatchPipelineEvent(type, line, listener, diagnostics);
                }
            }
            throw disconnected();
        }
    }

    /** Send a cascade request and replay its stream into {@code handler} until the terminal arrives. */
    private static EngineClient.LockOutcome streamCascade(
            EnginePaths.Paths paths, String requestLine, EngineClient.LockHandler handler, String pipelineName)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader =
                    EngineClient.protocolReader(ch);
            send(writer, requestLine);

            // Cascade state: the module currently streaming. Modules are strictly sequential on the
            // wire (the engine locks them one at a time), so one slot suffices.
            String currentDir = null;
            String currentCoord = null;
            List<Step> steps = new ArrayList<>();
            List<PipelineResult.Diagnostic> diagnostics = new ArrayList<>();
            PipelineListener listener = null;

            String line;
            while ((line = reader.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (type == null) continue;
                switch (type) {
                    case EngineProtocol.LOCK_MODULE -> {
                        currentDir = Ndjson.str(line, "dir");
                        currentCoord = Ndjson.str(line, "coord");
                        steps = new ArrayList<>();
                        diagnostics = new ArrayList<>();
                        listener = null;
                    }
                    case EngineProtocol.PLAN_STEP -> steps.add(Step.builder(Ndjson.str(line, "name"))
                            .label(Ndjson.str(line, "label")).phase(Phase.fromWireOrNull(Ndjson.str(line, "phase")))
                            .build());
                    case EngineProtocol.PLAN_DONE -> listener = handler.onModuleStart(currentDir, currentCoord, steps);
                    case EngineProtocol.LOCK_PACKAGE -> handler.onPackage(
                            Ndjson.str(line, "dir"), Ndjson.str(line, "name"), Ndjson.str(line, "version"));
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
                        if (listener != null) listener.pipelineFinish(result);
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
                    case EngineProtocol.ERROR -> throw new IOException(
                            "jk engine: run failed: " + Ndjson.str(line, "message"));
                    default -> dispatchPipelineEvent(type, line, listener, diagnostics);
                }
            }
            throw disconnected();
        }
    }

    /**
     * Replay one standard single-pipeline wire event into {@code listener} (accumulating {@code
     * pipeline-diagnostic}s aside, like {@link EngineBuildListenerAdapter} does) — the shared tail of
     * both stream loops. Unknown types are forward-compatible no-ops.
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

    private static final EngineClient.LockHandler NOOP_HANDLER = (dir, coord, steps) -> new PipelineListener() {};
}
