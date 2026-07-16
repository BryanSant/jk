// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.plugin.build.Phase;
import cc.jumpkick.plugin.protocol.Ndjson;
import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.PipelineListener;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.PipelineView;
import cc.jumpkick.run.Step;
import cc.jumpkick.run.StepStatus;
import cc.jumpkick.runtime.ExplainPlan;
import cc.jumpkick.runtime.ModuleOutcome;
import cc.jumpkick.runtime.ModulePlan;
import cc.jumpkick.runtime.WorkspaceBuildListener;
import cc.jumpkick.runtime.WorkspaceRequest;
import cc.jumpkick.runtime.WorkspaceResult;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
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
 * Drives a {@code buildWorkspace} call against an engine instead of in-process: sends a {@link
 * EngineProtocol#BUILD_REQUEST}, decodes the resulting stream of wire events, and re-invokes the
 * <em>exact same</em> {@link WorkspaceBuildListener}/{@link PipelineListener} instances the caller already
 * builds for the in-process path — {@code BuildCommand}'s rendering code doesn't need to know whether
 * it's watching a live {@code Pipeline} or a socket. See {@code docs/engine.md}.
 *
 * <p>Reconstructing {@link ModulePlan} client-side goes through the engine's {@code
 * ModulePlan.fromWire} factory (and {@code BuildPlan.Module.fromWire} for explain), so no
 * engine-internal type is ever named here — per {@code docs/architecture/re-foundation.md} M6 the
 * {@code BuildUnit} those factories synthesize is package-private to the engine and never executed.
 * The synthesized {@link Pipeline} is built with inert {@link Step}s (default no-op body) via the same
 * public builder the engine itself uses — never {@code run()} — purely so {@code .name()}/
 * {@code .steps()} read correctly for the renderers that already only read those two accessors.
 *
 * <p><b>Resolved Step 2 gap:</b> {@code BuildCommand}'s {@code onModuleStart} bodies used to call
 * {@code m.pipeline().addListener(EventLogListener.open(...))} to attach jk's durable per-module run log
 * directly onto the live {@code Pipeline} — a no-op against this adapter's synthetic (never-{@code
 * run()}) {@code Pipeline}, since nothing ever drains it. Fixed by composing the log listener into the
 * listener {@code onModuleStart} <em>returns</em> instead ({@code CompositePipelineListener}) — that one
 * is driven by wire-replayed events in the engine-hosted case and by {@code Pipeline.run()} directly in
 * the in-process case, so it works either way with no protocol changes.
 */
final class EngineBuildListenerAdapter {

    private EngineBuildListenerAdapter() {}

    /** One module's identity/sizing, accumulated from the {@code plan-module}/{@code plan-step} burst. */
    private static final class ModuleMeta {
        final String coord;
        final String pipelineName;
        final int weight;
        final boolean fullyCached;
        final List<Step> steps = new ArrayList<>();

        ModuleMeta(String coord, String pipelineName, int weight, boolean fullyCached) {
            this.coord = coord;
            this.pipelineName = pipelineName;
            this.weight = weight;
            this.fullyCached = fullyCached;
        }
    }

    /**
     * Run {@code req} against the engine at {@code paths}, spawning/reconnecting as needed, and drive
     * {@code listener} exactly as the engine's {@code BuildService.buildWorkspace} would in-process. Throws with a
     * clear message on any engine-unreachable/protocol failure — per {@code docs/engine.md} there is
     * no in-process fallback.
     */
    static WorkspaceResult buildWorkspace(
            EnginePaths.Paths paths, WorkspaceRequest req, WorkspaceBuildListener listener) throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);
        Session session = SessionContext.current();

        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);

            writer.write(EngineProtocol.withSession(
                    EngineProtocol.buildRequest(
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
                            session.force(),
                            // jk build asks the engine to auto-freshen a stale workspace lock; verify's
                            // scratch rebuild must use the pinned lock verbatim (see WorkspaceRequest).
                            req.freshenLock()),
                    req.variant(),
                    req.clientEnv(),
                    SessionContext.current().jvm(),
                    // rebuild rides the session envelope: bypass jk's caches without implying
                    // refresh — verify's scratch rebuild stays CAS-local (no re-download).
                    session.config().rebuildOr(false)));
            writer.write('\n');
            writer.flush();

            return streamEvents(reader, listener, req.cache());
        }
    }

    /**
     * Run a single project's test pipeline against the engine (Step 3). {@code listenerFactory} builds
     * the actual console {@link PipelineListener} once the pipeline's step list is known (mirroring {@code
     * PipelineConsole.runPipeline}'s own mode-based listener choice, which also needs {@code pipeline.steps()}
     * before it can construct a {@code CommandManagerListener}) — the wire doesn't have a real {@code
     * Pipeline} to ask, so the steps arrive as their own small event burst first. {@code testResultOut},
     * if non-null, is populated with the test-run counts (for exit-code/summary logic) before the
     * terminal {@code pipeline-finish} event reaches {@code listenerFactory}'s listener, exactly mirroring
     * how the in-process path's {@code pipeline.get(TEST_RESULT)} is already populated by the time the
     * console listener's own {@code pipelineFinish} fires.
     */
    static PipelineResult runTest(
            EnginePaths.Paths paths,
            EngineClient.TestRequest req,
            java.util.function.Function<List<Step>, PipelineListener> listenerFactory,
            cc.jumpkick.run.TestSummary[] testResultOut)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);

            writer.write(EngineProtocol.withSession(
                    EngineProtocol.testRequest(
                            req.entryDir().toString(),
                            req.cache().toString(),
                            req.jdksDir() != null ? req.jdksDir().toString() : null,
                            req.workers(),
                            req.profile(),
                            req.verbose(),
                            req.offline(),
                            req.force()),
                    SessionContext.current().variant(),
                    SessionContext.current().clientEnv(),
                    SessionContext.current().jvm(),
                    SessionContext.current().config().rebuildOr(false)));
            writer.write('\n');
            writer.flush();

            return streamSinglePipelineEvents(reader, listenerFactory, testResultOut, null);
        }
    }

    /**
     * Run a single (non-workspace) project's real build pipeline against the engine — the counterpart of
     * {@code BuildCommand.runForDir}. Same shape as {@link #runTest}, plus {@code buildOutcomeOut}
     * (populated with {@code BuildPipelines.BUILD_OUTCOME}, if the pipeline reported one, before the
     * terminal {@code pipeline-finish} reaches {@code listenerFactory}'s listener) so the caller's
     * summary line (e.g. "project up to date" vs "project built") can match the in-process path.
     */
    static PipelineResult runSingleBuild(
            EnginePaths.Paths paths,
            EngineClient.SingleBuildRequest req,
            java.util.function.Function<List<Step>, PipelineListener> listenerFactory,
            cc.jumpkick.run.TestSummary[] testResultOut,
            String[] buildOutcomeOut)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);

            writer.write(EngineProtocol.withSession(
                    EngineProtocol.singleBuildRequest(
                            req.entryDir().toString(),
                            req.cache().toString(),
                            req.jdksDir() != null ? req.jdksDir().toString() : null,
                            req.workers(),
                            req.profile(),
                            req.skipTests(),
                            req.verbose(),
                            req.offline(),
                            req.force()),
                    req.variant(),
                    req.clientEnv(),
                    SessionContext.current().jvm(),
                    SessionContext.current().config().rebuildOr(false)));
            writer.write('\n');
            writer.flush();

            return streamSinglePipelineEvents(reader, listenerFactory, testResultOut, buildOutcomeOut);
        }
    }

    /**
     * Run {@code jk native}'s hosted module cascade against the engine — it speaks {@link
     * EngineProtocol#BUILD_REQUEST}'s workspace event vocabulary (see {@link
     * EngineProtocol#NATIVE_REQUEST}), so the stream replays through the exact same {@link
     * WorkspaceBuildListener} plumbing {@link #buildWorkspace} uses. Module and workspace exit
     * codes are engine-computed ({@code jk native}'s 64/4/1 mapping).
     */
    static WorkspaceResult runNative(
            EnginePaths.Paths paths, EngineClient.NativeRequest req, WorkspaceBuildListener listener)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);

            Map<String, String> graalHomes = new java.util.LinkedHashMap<>();
            for (Map.Entry<Path, Path> e : req.graalByDir().entrySet()) {
                graalHomes.put(e.getKey().toString(), e.getValue().toString());
            }
            writer.write(EngineProtocol.withSession(
                    EngineProtocol.nativeRequest(
                            req.entryDir().toString(),
                            req.cache().toString(),
                            req.jdksDir() != null ? req.jdksDir().toString() : null,
                            req.mainClass(),
                            req.skipTests(),
                            req.offline(),
                            req.force(),
                            req.verbose(),
                            req.extraArgs(),
                            graalHomes),
                    SessionContext.current().variant(),
                    SessionContext.current().clientEnv(),
                    SessionContext.current().jvm(),
                    SessionContext.current().config().rebuildOr(false)));
            writer.write('\n');
            writer.flush();

            return streamEvents(reader, listener, req.cache());
        }
    }

    /**
     * Run {@code jk install}'s hosted build + cache-install pipeline against the engine — {@link
     * #runTest}'s exact shape ({@code testResultOut} settles before the terminal {@code
     * pipeline-finish} reaches the listener); the launcher-writing "make install" half runs in the
     * caller afterwards.
     */
    static PipelineResult runInstall(
            EnginePaths.Paths paths,
            EngineClient.InstallRequest req,
            java.util.function.Function<List<Step>, PipelineListener> listenerFactory,
            cc.jumpkick.run.TestSummary[] testResultOut)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);

            writer.write(EngineProtocol.withSession(
                    EngineProtocol.installRequest(
                            req.entryDir().toString(),
                            req.cache().toString(),
                            req.m2Dir().toString(),
                            req.graalHome() != null ? req.graalHome().toString() : null,
                            req.skipTests(),
                            req.offline(),
                            req.force(),
                            req.verbose()),
                    SessionContext.current().variant(),
                    SessionContext.current().clientEnv(),
                    SessionContext.current().jvm(),
                    SessionContext.current().config().rebuildOr(false)));
            writer.write('\n');
            writer.flush();

            return streamSinglePipelineEvents(reader, listenerFactory, testResultOut, null);
        }
    }

    /**
     * Forecast a build against the engine — the counterpart of {@code ExplainCommand}'s direct {@code
     * BuildService.explain} call. Synchronous: sends {@link EngineProtocol#EXPLAIN_REQUEST} and reads
     * the module/step/edge burst to completion, reconstructing a real {@link ExplainPlan}.
     * Unlike {@link #buildModulePlan}, no inert-object trickery is needed here — {@link
     * cc.jumpkick.runtime.BuildPlan.Module}/{@code Step} are pure public data, reconstructed
     * via {@code Module.fromWire}, exactly as {@link #buildModulePlan} does with {@code
     * ModulePlan.fromWire} ({@code Module.unit()} is package-private and never read here).
     *
     * <p>The request carries the plan-affecting build options the engine-side ETA estimate needs
     * (Wave 3 — see {@code BuildService.estimateEtaMillis}); the estimate rides back as an {@code
     * eta} event inside the burst and settles {@code etaOut[0]} ({@code 0} = unknown) before the
     * terminal {@code explain-done}.
     */
    static ExplainPlan explain(EnginePaths.Paths paths, EngineClient.ExplainRequest req, long[] etaOut)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);

        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);

            writer.write(EngineProtocol.explainRequest(
                    req.entryDir().toString(),
                    req.cache().toString(),
                    req.workers(),
                    req.skipTests(),
                    req.profile(),
                    req.jdksDir() != null ? req.jdksDir().toString() : null,
                    req.serial(),
                    req.parallelTests(),
                    req.verbose()));
            writer.write('\n');
            writer.flush();

            List<cc.jumpkick.runtime.BuildPlan.Module> modules = new ArrayList<>();
            Map<String, List<cc.jumpkick.runtime.BuildPlan.Step>> stepsByDir = new LinkedHashMap<>();
            Map<String, String> coordByDir = new LinkedHashMap<>();
            Map<String, int[]> countsByDir = new LinkedHashMap<>(); // [sourceCount, testCount]
            Map<String, boolean[]> flagsByDir = new LinkedHashMap<>(); // [producesJar, producesImage]
            List<String> order = new ArrayList<>();
            Map<Path, java.util.Set<Path>> edges = new LinkedHashMap<>();
            List<String> errors = new ArrayList<>();

            String line;
            while ((line = reader.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (type == null) continue;
                switch (type) {
                    case EngineProtocol.EXPLAIN_MODULE -> {
                        String dir = Ndjson.str(line, "dir");
                        order.add(dir);
                        coordByDir.put(dir, Ndjson.str(line, "coord"));
                        countsByDir.put(dir, new int[] {
                            Ndjson.intValue(line, "sourceCount", 0), Ndjson.intValue(line, "testCount", 0)
                        });
                        flagsByDir.put(dir, new boolean[] {
                            Ndjson.bool(line, "producesJar", false), Ndjson.bool(line, "producesImage", false)
                        });
                        stepsByDir.put(dir, new ArrayList<>());
                    }
                    case EngineProtocol.EXPLAIN_STEP -> {
                        String dir = Ndjson.str(line, "dir");
                        stepsByDir
                                .get(dir)
                                .add(new cc.jumpkick.runtime.BuildPlan.Step(
                                        Ndjson.str(line, "name"),
                                        cc.jumpkick.runtime.BuildPlan.Status.valueOf(Ndjson.str(line, "status")),
                                        Ndjson.str(line, "text"),
                                        Ndjson.str(line, "key")));
                    }
                    case EngineProtocol.EXPLAIN_EDGE -> {
                        Path dir = Path.of(Ndjson.str(line, "dir"));
                        Path dependsOn = Path.of(Ndjson.str(line, "dependsOnDir"));
                        edges.computeIfAbsent(dir, d -> new java.util.LinkedHashSet<>())
                                .add(dependsOn);
                    }
                    case EngineProtocol.ERROR -> errors.add(Ndjson.str(line, "message"));
                    case EngineProtocol.ETA -> {
                        if (etaOut != null) etaOut[0] = Ndjson.longValue(line, "millis", 0);
                    }
                    case EngineProtocol.EXPLAIN_DONE -> {
                        for (String dir : order) {
                            int[] counts = countsByDir.get(dir);
                            boolean[] flags = flagsByDir.get(dir);
                            modules.add(cc.jumpkick.runtime.BuildPlan.Module.fromWire(
                                    Path.of(dir),
                                    coordByDir.get(dir),
                                    stepsByDir.get(dir),
                                    counts[0],
                                    counts[1],
                                    flags[0],
                                    flags[1]));
                        }
                        return new ExplainPlan(modules, edges, Ndjson.intValue(line, "maxReadyWidth", 1), errors);
                    }
                    default -> {
                        /* forward-compatible no-op */
                    }
                }
            }
            throw new IOException("jk engine: the build engine disconnected unexpectedly before finishing "
                    + "(it may have crashed); run `jk engine status` for details");
        }
    }

    /**
     * Pre-flight a build's dirty forecast against the engine ({@code jk build}'s fully-cached
     * shortcut + dirty hint — see {@link EngineProtocol#FORECAST_REQUEST}). Synchronous: one
     * request line, one {@code forecast-ack} back. The session's offline/force/rerun flags ride
     * the request so the engine's forecast honors them exactly as the in-process one did.
     */
    /** One engine-hosted jk.toml edit: returns changed; throws with the engine's message. */
    static boolean edit(EnginePaths.Paths paths, Path file, String op, java.util.List<String> args) throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);
        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);
            writer.write(EngineProtocol.editRequest(file.toString(), op, args));
            writer.write('\n');
            writer.flush();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!EngineProtocol.EDIT_ACK.equals(EngineProtocol.typeOf(line))) continue;
                String error = Ndjson.str(line, "error");
                if (error != null) throw new IOException(error);
                return Ndjson.bool(line, "changed", false);
            }
            throw new IOException("jk engine: disconnected before answering the edit request");
        }
    }

    /** One engine-hosted tree render: the marker-tagged tree; throws with the engine's message. */
    static String treeRender(
            EnginePaths.Paths paths,
            Path dir,
            int maxDepth,
            boolean flatten,
            boolean stack,
            java.util.List<String> scopes)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);
        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);
            writer.write(EngineProtocol.treeRequest(dir.toString(), maxDepth, flatten, stack, scopes));
            writer.write('\n');
            writer.flush();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!EngineProtocol.TREE_ACK.equals(EngineProtocol.typeOf(line))) continue;
                String error = Ndjson.str(line, "error");
                if (error != null) throw new IOException(error);
                return Ndjson.str(line, "rendered");
            }
            throw new IOException("jk engine: disconnected before answering the tree request");
        }
    }

    /** One engine-hosted why lookup. */
    static cc.jumpkick.engine.protocol.WhyReport why(EnginePaths.Paths paths, Path dir, String query)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);
        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);
            writer.write(EngineProtocol.whyRequest(dir.toString(), query));
            writer.write('\n');
            writer.flush();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!EngineProtocol.WHY_ACK.equals(EngineProtocol.typeOf(line))) continue;
                return cc.jumpkick.engine.protocol.WhyReport.decode(line);
            }
            throw new IOException("jk engine: disconnected before answering the why request");
        }
    }

    /** One engine-hosted IDE model computation: the wire model back, generation stays client-side. */
    static cc.jumpkick.engine.protocol.IdeWireModel ideModel(
            EnginePaths.Paths paths, Path dir, Path cache, Path jdksDir) throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);
        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);
            writer.write(EngineProtocol.ideModelRequest(
                    dir.toString(), cache.toString(), jdksDir == null ? null : jdksDir.toString()));
            writer.write('\n');
            writer.flush();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!EngineProtocol.IDE_MODEL_ACK.equals(EngineProtocol.typeOf(line))) continue;
                return cc.jumpkick.engine.protocol.IdeWireModel.decode(line);
            }
            throw new IOException("jk engine: disconnected before answering the ide-model request");
        }
    }

    /** One engine-hosted generator run: file payloads back, guards/writes stay client-side. */
    static cc.jumpkick.engine.protocol.GeneratedFiles generate(
            EnginePaths.Paths paths, Path dir, String kind, java.util.Map<String, String> params) throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);
        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);
            writer.write(EngineProtocol.generateRequest(dir.toString(), kind, params));
            writer.write('\n');
            writer.flush();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!EngineProtocol.GENERATE_ACK.equals(EngineProtocol.typeOf(line))) continue;
                return cc.jumpkick.engine.protocol.GeneratedFiles.decode(line);
            }
            throw new IOException("jk engine: disconnected before answering the generate request");
        }
    }

    /** One engine-hosted plugin command run. */
    static cc.jumpkick.engine.protocol.PluginCommandReport pluginCommand(
            EnginePaths.Paths paths, Path dir, Path cache, String command, java.util.List<String> args)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);
        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);
            writer.write(EngineProtocol.withSession(
                    EngineProtocol.pluginCommandRequest(dir.toString(), cache.toString(), command, args),
                    SessionContext.current().variant(),
                    SessionContext.current().clientEnv(),
                    SessionContext.current().jvm(),
                    SessionContext.current().config().rebuildOr(false)));
            writer.write('\n');
            writer.flush();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!EngineProtocol.PLUGIN_VERB_ACK.equals(EngineProtocol.typeOf(line))) continue;
                return cc.jumpkick.engine.protocol.PluginCommandReport.decode(line);
            }
            throw new IOException("jk engine: disconnected before answering the plugin command");
        }
    }

    /** One engine-hosted deny check: policy parse + lock read + violations, engine-side. */
    static cc.jumpkick.engine.protocol.DenyReport denyCheck(EnginePaths.Paths paths, Path dir) throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);
        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);
            writer.write(EngineProtocol.denyCheckRequest(dir.toString()));
            writer.write('\n');
            writer.flush();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!EngineProtocol.DENY_CHECK_ACK.equals(EngineProtocol.typeOf(line))) continue;
                return cc.jumpkick.engine.protocol.DenyReport.decode(line);
            }
            throw new IOException("jk engine: disconnected before answering the deny check");
        }
    }

    static cc.jumpkick.engine.protocol.ProjectInfo projectInfo(EnginePaths.Paths paths, Path dir) throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);
        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);
            writer.write(EngineProtocol.projectInfoRequest(dir.toString(), ""));
            writer.write('\n');
            writer.flush();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!EngineProtocol.PROJECT_INFO_ACK.equals(EngineProtocol.typeOf(line))) continue;
                return cc.jumpkick.engine.protocol.ProjectInfo.decode(line);
            }
            throw new IOException("jk engine: disconnected before answering the project-info request");
        }
    }

    static cc.jumpkick.engine.protocol.ExecPlan execPlan(
            EnginePaths.Paths paths,
            Path dir,
            Path cache,
            String kind,
            String mainOverride,
            String binName,
            Path binDir,
            Path libDir)
            throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);
        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);
            writer.write(EngineProtocol.withSession(
                    EngineProtocol.execPlanRequest(
                            dir.toString(),
                            cache.toString(),
                            kind,
                            mainOverride,
                            binName,
                            binDir == null ? null : binDir.toString(),
                            libDir == null ? null : libDir.toString()),
                    SessionContext.current().variant(),
                    SessionContext.current().clientEnv(),
                    SessionContext.current().jvm(),
                    SessionContext.current().config().rebuildOr(false)));
            writer.write('\n');
            writer.flush();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!EngineProtocol.EXEC_PLAN_ACK.equals(EngineProtocol.typeOf(line))) continue;
                return cc.jumpkick.engine.protocol.ExecPlan.decode(line);
            }
            throw new IOException("jk engine: disconnected before answering the exec-plan request");
        }
    }

    static cc.jumpkick.runtime.BuildForecast forecast(
            EnginePaths.Paths paths, Path entryDir, Path cache, boolean skipTests) throws IOException {
        EngineClient.ensureRunning(paths, Jk.VERSION);
        Session session = SessionContext.current();
        try (SocketChannel ch = EngineClient.connect(cc.jumpkick.engine.EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            BufferedReader reader = EngineClient.protocolReader(ch);
            writer.write(EngineProtocol.forecastRequest(
                    entryDir.toString(),
                    cache.toString(),
                    skipTests,
                    session.offline(),
                    session.force(),
                    session.config().rebuildOr(false)));
            writer.write('\n');
            writer.flush();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!EngineProtocol.FORECAST_ACK.equals(EngineProtocol.typeOf(line))) continue;
                java.util.Set<Path> dirty = new java.util.LinkedHashSet<>();
                for (String d : Ndjson.strArray(line, "dirtyDirs")) dirty.add(Path.of(d));
                return new cc.jumpkick.runtime.BuildForecast(
                        dirty,
                        Ndjson.bool(line, "lockStale", false),
                        Ndjson.bool(line, "empty", false),
                        Ndjson.strArray(line, "errors"));
            }
            throw new IOException("jk engine: the build engine disconnected unexpectedly before finishing "
                    + "(it may have crashed); run `jk engine status` for details");
        }
    }

    private static PipelineResult streamSinglePipelineEvents(
            BufferedReader reader,
            java.util.function.Function<List<Step>, PipelineListener> listenerFactory,
            cc.jumpkick.run.TestSummary[] testResultOut,
            String[] buildOutcomeOut)
            throws IOException {
        List<Step> steps = new ArrayList<>();
        List<PipelineResult.Diagnostic> diagnostics = new ArrayList<>();
        PipelineListener listener = null;
        // The wire carries no duration; the summary's "took …" is this client-side
        // wall clock over the whole stream (spawn latency excluded — ensureRunning
        // already returned before the request was written).
        long startNanos = System.nanoTime();

        String line;
        while ((line = reader.readLine()) != null) {
            String type = EngineProtocol.typeOf(line);
            if (type == null) continue;
            switch (type) {
                case EngineProtocol.PLAN_STEP ->
                    steps.add(Step.builder(Ndjson.str(line, "name"))
                            .label(Ndjson.str(line, "label"))
                            .phase(Phase.fromWireOrNull(Ndjson.str(line, "phase")))
                            .build());
                case EngineProtocol.PLAN_DONE -> listener = listenerFactory.apply(steps);
                case EngineProtocol.PIPELINE_START -> listener.pipelineStart(readPipelineView(line));
                case EngineProtocol.STEP_START ->
                    listener.stepStart(
                            Ndjson.str(line, "step"),
                            Phase.fromWireOrNull(Ndjson.str(line, "phase")),
                            Ndjson.intValue(line, "ticks", 0));
                case EngineProtocol.PROGRESS ->
                    listener.progress(
                            Ndjson.str(line, "step"), Ndjson.intValue(line, "delta", 0), readPipelineView(line));
                case EngineProtocol.TICK_UPDATE ->
                    listener.tickUpdate(
                            Ndjson.str(line, "step"), Ndjson.intValue(line, "delta", 0), readPipelineView(line));
                case EngineProtocol.LABEL -> listener.label(Ndjson.str(line, "step"), Ndjson.str(line, "label"));
                case EngineProtocol.OUTPUT -> listener.output(Ndjson.str(line, "step"), Ndjson.str(line, "line"));
                case EngineProtocol.WARN ->
                    listener.warn(Ndjson.str(line, "step"), Ndjson.str(line, "code"), Ndjson.str(line, "message"));
                case EngineProtocol.ERROR_LINE ->
                    listener.error(
                            Ndjson.str(line, "step"),
                            Ndjson.str(line, "code"),
                            Ndjson.str(line, "message"),
                            Ndjson.str(line, "test"),
                            Ndjson.str(line, "exceptionClass"));
                case EngineProtocol.PIPELINE_DIAGNOSTIC ->
                    diagnostics.add(new PipelineResult.Diagnostic(
                            Ndjson.str(line, "step"),
                            Ndjson.str(line, "code"),
                            Ndjson.str(line, "message"),
                            Ndjson.str(line, "test"),
                            Ndjson.str(line, "exceptionClass")));
                case EngineProtocol.STEP_FINISH ->
                    listener.stepFinish(
                            Ndjson.str(line, "step"),
                            Phase.fromWireOrNull(Ndjson.str(line, "phase")),
                            StepStatus.valueOf(Ndjson.str(line, "status")),
                            Duration.ZERO);
                case EngineProtocol.PIPELINE_FINISH -> {
                    boolean success = Ndjson.bool(line, "success", false);
                    long total = Ndjson.longValue(line, "testTotal", -1);
                    if (total >= 0 && testResultOut != null) {
                        testResultOut[0] = new cc.jumpkick.run.TestSummary(
                                total,
                                Ndjson.longValue(line, "testSucceeded", 0),
                                Ndjson.longValue(line, "testFailed", 0),
                                Ndjson.longValue(line, "testSkipped", 0),
                                List.of());
                    }
                    if (buildOutcomeOut != null) {
                        buildOutcomeOut[0] = Ndjson.str(line, "buildOutcome");
                    }
                    PipelineResult result = new PipelineResult(
                            "test",
                            success,
                            Duration.ofNanos(System.nanoTime() - startNanos),
                            List.of(),
                            List.of(),
                            diagnostics,
                            false,
                            false);
                    listener.pipelineFinish(result);
                    return result;
                }
                case EngineProtocol.ERROR ->
                    throw new IOException("jk engine: run failed: " + Ndjson.str(line, "message"));
                default -> {
                    /* forward-compatible no-op */
                }
            }
        }
        throw new IOException("jk engine: the build engine disconnected unexpectedly before finishing "
                + "(it may have crashed); run `jk engine status` for details");
    }

    private static WorkspaceResult streamEvents(BufferedReader reader, WorkspaceBuildListener listener, Path cache)
            throws IOException {
        Map<String, ModuleMeta> planByDir = new LinkedHashMap<>();
        Map<String, PipelineListener> pipelineListenersByDir = new LinkedHashMap<>();
        Map<String, List<PipelineResult.Diagnostic>> diagnosticsByDir = new LinkedHashMap<>();
        List<ModuleOutcome> outcomes = new ArrayList<>();
        String pendingPlanDir = null; // the dir most recently opened by plan-module, for plan-step lines

        String line;
        while ((line = reader.readLine()) != null) {
            String type = EngineProtocol.typeOf(line);
            if (type == null) continue;
            String dir = Ndjson.str(line, "dir");
            switch (type) {
                case EngineProtocol.PLAN_MODULE -> {
                    planByDir.put(
                            dir,
                            new ModuleMeta(
                                    Ndjson.str(line, "coord"),
                                    Ndjson.str(line, "pipelineName"),
                                    Ndjson.intValue(line, "weight", 0),
                                    Ndjson.bool(line, "fullyCached", false)));
                    pendingPlanDir = dir;
                }
                case EngineProtocol.PLAN_STEP -> {
                    ModuleMeta m = planByDir.get(dir != null ? dir : pendingPlanDir);
                    if (m != null) {
                        m.steps.add(Step.builder(Ndjson.str(line, "name"))
                                .label(Ndjson.str(line, "label"))
                                .phase(Phase.fromWireOrNull(Ndjson.str(line, "phase")))
                                .build());
                    }
                }
                case EngineProtocol.PLAN_DONE -> listener.onPlan(buildModulePlans(planByDir, cache));
                case EngineProtocol.ETA -> listener.onEtaEstimate(Ndjson.longValue(line, "millis", 0));
                case EngineProtocol.MODULE_START -> {
                    ModulePlan plan = buildModulePlan(dir, planByDir.get(dir), cache);
                    PipelineListener gl = listener.onModuleStart(plan);
                    pipelineListenersByDir.put(dir, gl != null ? gl : new PipelineListener() {});
                }
                case EngineProtocol.PIPELINE_START ->
                    pipelineListenersByDir.getOrDefault(dir, NOOP).pipelineStart(readPipelineView(line));
                case EngineProtocol.STEP_START ->
                    pipelineListenersByDir
                            .getOrDefault(dir, NOOP)
                            .stepStart(
                                    Ndjson.str(line, "step"),
                                    Phase.fromWireOrNull(Ndjson.str(line, "phase")),
                                    Ndjson.intValue(line, "ticks", 0));
                case EngineProtocol.PROGRESS ->
                    pipelineListenersByDir
                            .getOrDefault(dir, NOOP)
                            .progress(
                                    Ndjson.str(line, "step"),
                                    Ndjson.intValue(line, "delta", 0),
                                    readPipelineView(line));
                case EngineProtocol.TICK_UPDATE ->
                    pipelineListenersByDir
                            .getOrDefault(dir, NOOP)
                            .tickUpdate(
                                    Ndjson.str(line, "step"),
                                    Ndjson.intValue(line, "delta", 0),
                                    readPipelineView(line));
                case EngineProtocol.LABEL ->
                    pipelineListenersByDir
                            .getOrDefault(dir, NOOP)
                            .label(Ndjson.str(line, "step"), Ndjson.str(line, "label"));
                case EngineProtocol.OUTPUT ->
                    pipelineListenersByDir
                            .getOrDefault(dir, NOOP)
                            .output(Ndjson.str(line, "step"), Ndjson.str(line, "line"));
                case EngineProtocol.WARN ->
                    pipelineListenersByDir
                            .getOrDefault(dir, NOOP)
                            .warn(Ndjson.str(line, "step"), Ndjson.str(line, "code"), Ndjson.str(line, "message"));
                case EngineProtocol.ERROR_LINE ->
                    pipelineListenersByDir
                            .getOrDefault(dir, NOOP)
                            .error(
                                    Ndjson.str(line, "step"),
                                    Ndjson.str(line, "code"),
                                    Ndjson.str(line, "message"),
                                    Ndjson.str(line, "test"),
                                    Ndjson.str(line, "exceptionClass"));
                case EngineProtocol.PIPELINE_DIAGNOSTIC ->
                    diagnosticsByDir
                            .computeIfAbsent(dir, d -> new ArrayList<>())
                            .add(new PipelineResult.Diagnostic(
                                    Ndjson.str(line, "step"),
                                    Ndjson.str(line, "code"),
                                    Ndjson.str(line, "message"),
                                    Ndjson.str(line, "test"),
                                    Ndjson.str(line, "exceptionClass")));
                case EngineProtocol.STEP_FINISH ->
                    pipelineListenersByDir
                            .getOrDefault(dir, NOOP)
                            .stepFinish(
                                    Ndjson.str(line, "step"),
                                    Phase.fromWireOrNull(Ndjson.str(line, "phase")),
                                    StepStatus.valueOf(Ndjson.str(line, "status")),
                                    Duration.ZERO);
                case EngineProtocol.PIPELINE_FINISH -> {
                    ModuleMeta meta = planByDir.get(dir);
                    String pipelineName = meta != null ? meta.pipelineName : dir;
                    List<PipelineResult.Diagnostic> diags = diagnosticsByDir.remove(dir);
                    PipelineResult result = new PipelineResult(
                            pipelineName,
                            Ndjson.bool(line, "success", false),
                            Duration.ZERO,
                            List.of(),
                            List.of(),
                            diags != null ? diags : List.of(),
                            false,
                            false);
                    pipelineListenersByDir.getOrDefault(dir, NOOP).pipelineFinish(result);
                }
                case EngineProtocol.MODULE_FINISH -> {
                    ModuleOutcome outcome = new ModuleOutcome(
                            Ndjson.str(line, "coord"),
                            Path.of(dir),
                            Ndjson.bool(line, "success", false),
                            Ndjson.intValue(line, "exitCode", 1),
                            Ndjson.longValue(line, "millis", 0));
                    outcomes.add(outcome);
                    listener.onModuleFinish(outcome);
                }
                case EngineProtocol.WORKSPACE_FINISH -> {
                    WorkspaceResult result = new WorkspaceResult(
                            Ndjson.bool(line, "success", false),
                            Ndjson.intValue(line, "exitCode", 1),
                            List.copyOf(outcomes),
                            Ndjson.strArray(line, "errors"));
                    listener.onWorkspaceFinish(result);
                    return result;
                }
                case EngineProtocol.ERROR ->
                    throw new IOException("jk engine: build failed: " + Ndjson.str(line, "message"));
                default -> {
                    /* forward-compatible no-op */
                }
            }
        }
        throw new IOException("jk engine: the build engine disconnected unexpectedly before finishing "
                + "(it may have crashed); run `jk engine status` for details");
    }

    private static List<ModulePlan> buildModulePlans(Map<String, ModuleMeta> planByDir, Path cache) {
        List<ModulePlan> plans = new ArrayList<>(planByDir.size());
        for (Map.Entry<String, ModuleMeta> e : planByDir.entrySet()) {
            plans.add(buildModulePlan(e.getKey(), e.getValue(), cache));
        }
        return plans;
    }

    private static ModulePlan buildModulePlan(String dir, ModuleMeta m, Path cache) {
        Pipeline inertPipeline =
                Pipeline.builder(m.pipelineName).addAllSteps(m.steps).build();
        return ModulePlan.fromWire(Path.of(dir), m.coord, inertPipeline, m.weight, m.fullyCached, cache);
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

    private static final PipelineListener NOOP = new PipelineListener() {};
}
