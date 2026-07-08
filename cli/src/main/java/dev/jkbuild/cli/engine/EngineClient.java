// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.engine;

import dev.jkbuild.config.JkEngineConfig;
import dev.jkbuild.engine.EnginePaths;
import dev.jkbuild.jdk.GlobalDefaultJdk;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.jdk.JavaHomes;
import dev.jkbuild.jdk.JdkEnsure;
import dev.jkbuild.engine.protocol.EngineProtocol;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.runtime.ExplainPlan;
import dev.jkbuild.runtime.WorkspaceBuildListener;
import dev.jkbuild.runtime.WorkspaceRequest;
import dev.jkbuild.runtime.WorkspaceResult;
import dev.jkbuild.task.CachePruneScheduler;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CLI-side counterpart to {@link dev.jkbuild.engine.EngineServer}: connects, spawns the engine lazily
 * when none is reachable, and handles version-skew by killing a stale engine and starting a fresh
 * one — all transparent to the caller. See {@code docs/engine.md}.
 */
public final class EngineClient {

    /** Per-read/connect socket timeout — a live engine replies in well under this. */
    private static final int SOCKET_TIMEOUT_MILLIS = 2_000;

    /** How long to wait for a freshly spawned engine to come up before giving up. */
    private static final Duration DEFAULT_START_TIMEOUT = Duration.ofSeconds(5);

    private EngineClient() {}

    /** What a connection's {@code hello}/{@code hello-ack} handshake reveals about the engine. */
    public record Handshake(String version, long pid, long startedAtMillis) {}

    /**
     * The {@code jk engine status} snapshot. Memory fields are best-effort: {@code -1} means the
     * engine couldn't observe that number (no OS RSS source, or an older engine that predates the
     * fields).
     */
    public record Status(
            String version,
            long pid,
            long startedAtMillis,
            int idleMinutes,
            int activeRequests,
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            long rssBytes) {}

    /**
     * Connect, ping, and get {@code pong} back — the engine-existence check per {@code docs/engine.md}
     * (never trust a pidfile alone). {@code false} for anything from "nothing is listening" to "it
     * answered something unexpected."
     */
    public static boolean ping(Path socket) {
        try (SocketChannel ch = connect(socket)) {
            String reply = exchange(ch, EngineProtocol.ping());
            return EngineProtocol.PONG.equals(EngineProtocol.typeOf(reply));
        } catch (IOException e) {
            return false;
        }
    }

    /** Connect and perform the {@code hello}/{@code hello-ack} handshake; empty if unreachable. */
    public static Optional<Handshake> handshake(Path socket, String clientVersion) {
        try (SocketChannel ch = connect(socket)) {
            String ack = exchange(ch, EngineProtocol.hello(clientVersion));
            if (!EngineProtocol.HELLO_ACK.equals(EngineProtocol.typeOf(ack))) return Optional.empty();
            return Optional.of(new Handshake(
                    Ndjson.str(ack, "version"),
                    Ndjson.longValue(ack, "pid", -1),
                    Ndjson.longValue(ack, "startedAt", -1)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Connect and request a status snapshot; empty if no engine is reachable. */
    public static Optional<Status> status(Path socket) {
        try (SocketChannel ch = connect(socket)) {
            exchange(ch, EngineProtocol.hello("status-probe")); // handshake first, response discarded
            String ack = exchange(ch, EngineProtocol.statusRequest());
            if (!EngineProtocol.STATUS_ACK.equals(EngineProtocol.typeOf(ack))) return Optional.empty();
            return Optional.of(new Status(
                    Ndjson.str(ack, "version"),
                    Ndjson.longValue(ack, "pid", -1),
                    Ndjson.longValue(ack, "startedAt", -1),
                    Ndjson.intValue(ack, "idleMinutes", -1),
                    Ndjson.intValue(ack, "activeRequests", -1),
                    Ndjson.longValue(ack, "heapUsedBytes", -1),
                    Ndjson.longValue(ack, "heapCommittedBytes", -1),
                    Ndjson.longValue(ack, "heapMaxBytes", -1),
                    Ndjson.longValue(ack, "rssBytes", -1)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Ask a reachable engine to shut down gracefully; {@code true} if one was reached and acknowledged
     * (or was already not running — stopping a non-running engine is not an error), {@code false} if
     * one was reachable but didn't acknowledge cleanly.
     */
    public static boolean stop(Path socket) {
        SocketChannel ch;
        try {
            ch = connect(socket);
        } catch (IOException e) {
            return true; // nothing reachable — a no-op "stop" is success
        }
        try (ch) {
            String bye = exchange(ch, EngineProtocol.shutdown());
            return EngineProtocol.BYE.equals(EngineProtocol.typeOf(bye));
        } catch (IOException e) {
            return false; // reachable but didn't behave — a real problem, not "already stopped"
        }
    }

    /**
     * The one entry point real commands use: a live, version-matched engine is guaranteed to be
     * reachable at {@code paths.socket()} when this returns normally. Spawns lazily if none is
     * running; kills and replaces a stale (version-mismatched) engine transparently. Throws with a
     * message pointing at the engine's log file if it still can't be reached after a fresh spawn —
     * per {@code docs/engine.md}, the engine is load-bearing and this is not silently swallowed.
     */
    public static Handshake ensureRunning(EnginePaths.Paths paths, String clientVersion) throws IOException {
        return ensureRunning(paths, clientVersion, DEFAULT_START_TIMEOUT);
    }

    /**
     * Run a workspace build against the engine at {@code paths} instead of in-process — the engine
     * equivalent of the engine's {@code BuildService.buildWorkspace}, driving the exact same {@code listener}.
     * Ensures a live, version-matched engine first (spawning/replacing as needed), then streams the
     * build over a fresh connection. Throws with a clear message on any failure; per {@code
     * docs/engine.md} there is no in-process fallback.
     */
    public static WorkspaceResult buildWorkspace(
            EnginePaths.Paths paths, WorkspaceRequest req, WorkspaceBuildListener listener)
            throws IOException {
        return EngineBuildListenerAdapter.buildWorkspace(paths, req, listener);
    }

    /** Everything an engine-hosted {@code jk test} run needs — mirrors {@code TestCommand}'s own local fields. */
    public record TestRequest(
            Path entryDir,
            Path cache,
            Path jdksDir,
            int workers,
            String profile,
            boolean verbose,
            boolean offline,
            boolean force) {}

    /**
     * Run a single project's test goal against the engine (Phase 3) — see {@link
     * EngineBuildListenerAdapter#runTest} for the exact contract.
     */
    public static dev.jkbuild.run.GoalResult runTest(
            EnginePaths.Paths paths,
            TestRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            dev.jkbuild.run.TestSummary[] testResultOut)
            throws IOException {
        return EngineBuildListenerAdapter.runTest(paths, req, listenerFactory, testResultOut);
    }

    /** Everything an engine-hosted single-project {@code jk build} needs — mirrors {@code BuildCommand}'s local fields. */
    public record SingleBuildRequest(
            Path entryDir,
            Path cache,
            Path jdksDir,
            int workers,
            String profile,
            boolean skipTests,
            boolean verbose,
            boolean offline,
            boolean force) {}

    /**
     * Run a single (non-workspace) project's build against the engine — the engine equivalent of
     * {@code BuildCommand.runForDir}'s {@code agg == null} branch — see {@link
     * EngineBuildListenerAdapter#runSingleBuild} for the exact contract.
     */
    public static dev.jkbuild.run.GoalResult runSingleBuild(
            EnginePaths.Paths paths,
            SingleBuildRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            dev.jkbuild.run.TestSummary[] testResultOut,
            String[] buildOutcomeOut)
            throws IOException {
        return EngineBuildListenerAdapter.runSingleBuild(paths, req, listenerFactory, testResultOut, buildOutcomeOut);
    }

    /**
     * Everything an engine-hosted {@code jk explain} needs: the entry dir/cache for the plan, plus
     * the plan-affecting {@code jk build} options the engine-side ETA estimate feeds through the
     * shared goal assembly (Wave 3 — the estimate used to be computed client-side).
     */
    public record ExplainRequest(
            Path entryDir,
            Path cache,
            int workers,
            boolean skipTests,
            String profile,
            Path jdksDir,
            boolean serial,
            boolean parallelTests,
            boolean verbose) {}

    /**
     * Pre-flight a build's dirty forecast against the engine — {@code jk build}'s fully-cached
     * shortcut and dirty hint (see {@link EngineBuildListenerAdapter#forecast}).
     */
    public static dev.jkbuild.runtime.BuildForecast forecast(
            EnginePaths.Paths paths, Path entryDir, Path cache, boolean skipTests) throws IOException {
        return EngineBuildListenerAdapter.forecast(paths, entryDir, cache, skipTests);
    }

    /**
     * Forecast a build against the engine ({@code jk explain}) — see {@link
     * EngineBuildListenerAdapter#explain} for the exact contract. {@code etaOut} (a single-slot
     * holder, may be {@code null}) receives the engine-computed build-time estimate in millis
     * ({@code 0} = unknown).
     */
    public static ExplainPlan explain(EnginePaths.Paths paths, ExplainRequest req, long[] etaOut)
            throws IOException {
        return EngineBuildListenerAdapter.explain(paths, req, etaOut);
    }

    // ---- resolver family (jk lock / update / sync — Wave 1 of the slim client) ----------------

    /** Everything an engine-hosted {@code jk lock} needs — mirrors {@code LockCommand}'s local fields. */
    public record LockRequest(
            Path entryDir,
            Path cache,
            List<String> features,
            boolean noDefaultFeatures,
            boolean sources,
            java.net.URI repoUrl,
            boolean offline,
            boolean force,
            boolean verbose) {}

    /** Everything an engine-hosted {@code jk update} needs — mirrors {@code UpdateCommand}'s local fields. */
    public record UpdateRequest(
            Path entryDir,
            Path cache,
            List<String> features,
            boolean noDefaultFeatures,
            java.net.URI repoUrl,
            boolean offline,
            boolean force,
            boolean verbose) {}

    /** Everything an engine-hosted {@code jk sync} needs — mirrors {@code SyncCommand}'s local fields. */
    public record SyncRequest(
            Path entryDir,
            Path cache,
            Path jdksDir,
            java.net.URI repoUrl,
            boolean sources,
            boolean offline,
            boolean force,
            boolean refresh,
            boolean verbose) {}

    /**
     * A lock/update cascade's client-side renderer contract — see {@link EngineResolveAdapter} for
     * the wire mechanics. {@code onModuleStart} is invoked once per module (entry project first,
     * then workspace modules in declaration order), after its phase list has arrived, and returns
     * the {@link dev.jkbuild.run.GoalListener} the module's wire events should drive — the same
     * listener the in-process path would attach to the live goal. {@code onPackage} fires per
     * resolved package (plain, unthemed — the renderer colorizes); {@code onModuleFinish} fires
     * after that listener's own {@code goalFinish} has been dispatched.
     */
    public interface LockHandler {
        dev.jkbuild.run.GoalListener onModuleStart(String dir, String coord, List<dev.jkbuild.run.Phase> phases);

        default void onPackage(String dir, String name, String version) {}

        default void onModuleFinish(String dir, dev.jkbuild.run.GoalResult result, LockCounts counts) {}
    }

    /** A finished lock/update module's written-lockfile counts ({@code -1} when the goal failed before writing). */
    public record LockCounts(long packages, long sources, long plugins) {}

    /**
     * A lock/update request's terminal outcome. {@code errors} carries pre-goal failures (manifest
     * parse, workspace module load) as plain text; {@code refreshed} is {@code jk update --git}'s
     * refreshed count ({@code -1} otherwise). {@code exitCode} is authoritative — computed
     * engine-side from the phase statuses (resolve failure exits 6, config problems 2).
     */
    public record LockOutcome(boolean success, int exitCode, List<String> errors, int refreshed) {}

    /**
     * Run {@code jk lock}'s workspace cascade against the engine — see {@link
     * EngineResolveAdapter#runLock} for the exact contract. {@code handler} is the command's
     * renderer; the returned outcome's {@code exitCode} is authoritative (computed engine-side).
     */
    public static LockOutcome runLock(EnginePaths.Paths paths, LockRequest req, LockHandler handler)
            throws IOException {
        return EngineResolveAdapter.runLock(paths, req, handler);
    }

    /**
     * Run {@code jk update}'s full re-resolve cascade against the engine (rides {@code jk lock}'s
     * event vocabulary) — see {@link EngineResolveAdapter#runUpdate}.
     */
    public static LockOutcome runUpdate(EnginePaths.Paths paths, UpdateRequest req, LockHandler handler)
            throws IOException {
        return EngineResolveAdapter.runUpdate(paths, req, handler);
    }

    /**
     * Run {@code jk update --git [<name>]} against the engine ({@code gitTarget == null} refreshes
     * every git dependency) — see {@link EngineResolveAdapter#runUpdateGitOnly}.
     */
    public static LockOutcome runUpdateGitOnly(EnginePaths.Paths paths, UpdateRequest req, String gitTarget)
            throws IOException {
        return EngineResolveAdapter.runUpdateGitOnly(paths, req, gitTarget);
    }

    /**
     * Run {@code jk sync}'s single goal against the engine — see {@link
     * EngineResolveAdapter#runSync} for the exact contract (the {@code jk test} listener-factory
     * shape, plus fetched/up-to-date count holders for the summary line).
     */
    public static dev.jkbuild.run.GoalResult runSync(
            EnginePaths.Paths paths,
            SyncRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            long[] fetchedOut,
            long[] upToDateOut)
            throws IOException {
        return EngineResolveAdapter.runSync(paths, req, listenerFactory, fetchedOut, upToDateOut);
    }

    // ---- hosted worker verbs (Wave 2 of the slim client) ---------------------------------------

    /** Everything an engine-hosted {@code jk audit} needs — mirrors {@code AuditCommand}'s local fields. */
    public record AuditRequest(
            Path entryDir, Path cache, String severity, java.net.URI osvBatchUrl, java.net.URI osvVulnsUrl) {}

    /**
     * Run {@code jk audit}'s goal against the engine (the worker forks engine-side). Findings
     * stream to {@code findings} as plain structured strings — the command assembles/renders the
     * report and applies the severity threshold itself.
     */
    public static dev.jkbuild.run.GoalResult runAudit(
            EnginePaths.Paths paths,
            AuditRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            dev.jkbuild.runtime.HostedEvents.FindingObserver findings)
            throws IOException {
        return EngineWorkerAdapter.stream(
                        paths,
                        EngineProtocol.auditRequest(
                                req.entryDir().toString(),
                                req.cache().toString(),
                                req.severity(),
                                req.osvBatchUrl() != null ? req.osvBatchUrl().toString() : null,
                                req.osvVulnsUrl() != null ? req.osvVulnsUrl().toString() : null),
                        "audit",
                        listenerFactory,
                        (type, line) -> findings.onFinding(
                                Ndjson.str(line, "module"),
                                Ndjson.str(line, "version"),
                                Ndjson.str(line, "vulnId"),
                                Ndjson.str(line, "severity"),
                                Ndjson.str(line, "summary")))
                .result();
    }

    /** Everything an engine-hosted {@code jk format} needs — resolved styles, not raw flags. */
    public record FormatRequest(
            Path entryDir,
            Path cache,
            boolean check,
            String javaStyle,
            String kotlinStyle,
            boolean optimizeImports,
            Path rewriteConfig,
            boolean offline,
            boolean verbose) {}

    /** A hosted {@code jk format} run's summary, decoded from the terminal goal-finish. */
    public record FormatOutcome(
            dev.jkbuild.run.GoalResult result, int changed, int clean, int errors, int total, int workerExit) {}

    /**
     * Run {@code jk format}'s goal against the engine (source collection, formatter-jar resolution,
     * and the worker fork all engine-side). Per-file results stream to {@code files}; the counts
     * (and the worker's check-mode exit code) ride the returned outcome.
     */
    public static FormatOutcome runFormat(
            EnginePaths.Paths paths,
            FormatRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            dev.jkbuild.runtime.HostedEvents.FileObserver files)
            throws IOException {
        EngineWorkerAdapter.HostedFinish finish = EngineWorkerAdapter.stream(
                paths,
                EngineProtocol.formatRequest(
                        req.entryDir().toString(),
                        req.cache().toString(),
                        req.check(),
                        req.javaStyle(),
                        req.kotlinStyle(),
                        req.optimizeImports(),
                        req.rewriteConfig() != null ? req.rewriteConfig().toString() : null,
                        req.offline(),
                        req.verbose()),
                "format",
                listenerFactory,
                (type, line) -> files.onFile(
                        Ndjson.str(line, "path"),
                        Ndjson.str(line, "status"),
                        Ndjson.str(line, "message"),
                        Ndjson.intValue(line, "index", 0),
                        Ndjson.intValue(line, "total", 0)));
        return new FormatOutcome(
                finish.result(),
                Ndjson.intValue(finish.finishLine(), "formatChanged", -1),
                Ndjson.intValue(finish.finishLine(), "formatClean", -1),
                Ndjson.intValue(finish.finishLine(), "formatErrors", -1),
                Ndjson.intValue(finish.finishLine(), "formatTotal", -1),
                Ndjson.intValue(finish.finishLine(), "formatWorkerExit", -1));
    }

    /**
     * Everything an engine-hosted {@code jk publish} needs. The credential and GPG passphrase were
     * resolved client-side (env/keychain live here, not in the engine's inherited environment); they
     * cross the user-owned socket and are never logged.
     */
    public record PublishRequest(
            Path entryDir,
            Path cache,
            java.net.URI repoUrl,
            String region,
            String endpoint,
            Path jarPath,
            boolean allowSnapshot,
            boolean dryRun,
            Path keyFile,
            String gpgPassphrase,
            boolean sigstore,
            boolean slsa,
            boolean sbom,
            dev.jkbuild.credential.RepoCredential credential,
            boolean verbose) {}

    /** A hosted {@code jk publish} run's summary, decoded from the terminal goal-finish. */
    public record PublishOutcome(dev.jkbuild.run.GoalResult result, int files) {}

    /** Run {@code jk publish}'s goal against the engine (the publisher worker forks engine-side). */
    public static PublishOutcome runPublish(
            EnginePaths.Paths paths,
            PublishRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory)
            throws IOException {
        String authType;
        String user = null;
        String pass = null;
        String token = null;
        if (req.credential() instanceof dev.jkbuild.credential.RepoCredential.Basic b) {
            authType = "basic";
            user = b.username();
            pass = b.password();
        } else if (req.credential() instanceof dev.jkbuild.credential.RepoCredential.Bearer b) {
            authType = "bearer";
            token = b.token();
        } else {
            authType = "anonymous";
        }
        EngineWorkerAdapter.HostedFinish finish = EngineWorkerAdapter.stream(
                paths,
                EngineProtocol.publishRequest(
                        req.entryDir().toString(),
                        req.cache().toString(),
                        req.repoUrl().toString(),
                        req.region(),
                        req.endpoint(),
                        req.jarPath() != null ? req.jarPath().toString() : null,
                        req.allowSnapshot(),
                        req.dryRun(),
                        req.keyFile() != null ? req.keyFile().toString() : null,
                        req.gpgPassphrase(),
                        req.sigstore(),
                        req.slsa(),
                        req.sbom(),
                        authType,
                        user,
                        pass,
                        token,
                        req.verbose()),
                "publish",
                listenerFactory,
                (type, line) -> {});
        return new PublishOutcome(finish.result(), Ndjson.intValue(finish.finishLine(), "publishFiles", -1));
    }

    /** Everything an engine-hosted {@code jk image} needs — mirrors {@code ImageCommand}'s local fields. */
    public record ImageRequest(
            Path entryDir,
            Path cache,
            Path jdksDir,
            String mainClass,
            String registry,
            String tag,
            String tarballArg,
            String dockerExecutable,
            boolean skipTests,
            boolean offline,
            boolean force,
            boolean rerun,
            boolean verbose) {}

    /**
     * A hosted {@code jk image} run's structured summary. Exactly one of {@code tarball} (tarball
     * mode) or {@code daemonExe} (daemon-load mode) is non-null, or neither (registry push — render
     * {@code ref}); {@code testResult} is non-null when the pipeline's run-tests phase reported
     * counts.
     */
    public record ImageSummary(
            dev.jkbuild.run.TestSummary testResult,
            String ref,
            String tarball,
            String name,
            String version,
            String daemonExe) {}

    /**
     * Run {@code jk image}'s goal against the engine (full pipeline + image tail engine-side).
     * {@code summaryOut} (a single-slot holder) is populated from the terminal goal-finish
     * <em>before</em> it reaches {@code listenerFactory}'s listener — whose own {@code goalFinish}
     * handler renders the success tail from those fields, exactly the {@code runTest} holder
     * pattern.
     */
    public static dev.jkbuild.run.GoalResult runImage(
            EnginePaths.Paths paths,
            ImageRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            ImageSummary[] summaryOut)
            throws IOException {
        return EngineWorkerAdapter.stream(
                        paths,
                        EngineProtocol.imageRequest(
                                req.entryDir().toString(),
                                req.cache().toString(),
                                req.jdksDir() != null ? req.jdksDir().toString() : null,
                                req.mainClass(),
                                req.registry(),
                                req.tag(),
                                req.tarballArg(),
                                req.dockerExecutable(),
                                req.skipTests(),
                                req.offline(),
                                req.force(),
                                req.rerun(),
                                req.verbose()),
                        "image",
                        listenerFactory,
                        (type, line) -> {},
                        line -> {
                            long total = Ndjson.longValue(line, "testTotal", -1);
                            dev.jkbuild.run.TestSummary testResult = total < 0
                                    ? null
                                    : new dev.jkbuild.run.TestSummary(
                                            total,
                                            Ndjson.longValue(line, "testSucceeded", 0),
                                            Ndjson.longValue(line, "testFailed", 0),
                                            Ndjson.longValue(line, "testSkipped", 0),
                                            List.of());
                            summaryOut[0] = new ImageSummary(
                                    testResult,
                                    Ndjson.str(line, "imageRef"),
                                    Ndjson.str(line, "imageTarball"),
                                    Ndjson.str(line, "imageName"),
                                    Ndjson.str(line, "imageVersion"),
                                    Ndjson.str(line, "imageDaemonExe"));
                        })
                .result();
    }

    /** Everything an engine-hosted {@code jk import} needs — pre-flighted absolute paths. */
    public record ImportRequest(
            Path source, Path out, Path baseDir, Path tmpDir, boolean force, Path report, Path cache) {}

    /** A hosted {@code jk import} run's summary, decoded from the terminal goal-finish. */
    public record ImportOutcome(
            dev.jkbuild.run.GoalResult result, int exitCode, int warnings, String error, String diag) {}

    /** Run {@code jk import}'s goal against the engine, streaming progress notes to {@code notes}. */
    public static ImportOutcome runImport(
            EnginePaths.Paths paths,
            ImportRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            dev.jkbuild.runtime.HostedEvents.NoteObserver notes)
            throws IOException {
        EngineWorkerAdapter.HostedFinish finish = EngineWorkerAdapter.stream(
                paths,
                EngineProtocol.importRequest(
                        req.source().toString(),
                        req.out().toString(),
                        req.baseDir().toString(),
                        req.tmpDir().toString(),
                        req.force(),
                        req.report() != null ? req.report().toString() : null,
                        req.cache().toString()),
                "import",
                listenerFactory,
                (type, line) -> notes.onNote(Ndjson.str(line, "kind"), Ndjson.str(line, "text")));
        String line = finish.finishLine();
        return new ImportOutcome(
                finish.result(),
                Ndjson.intValue(line, "importExit", 1),
                Ndjson.intValue(line, "importWarnings", 0),
                Ndjson.str(line, "importError"),
                Ndjson.str(line, "importDiag"));
    }

    /**
     * Provision a Maven/Gradle distribution via the engine ({@code jk mvn}/{@code jk gradle}) — a
     * one-shot request; the exec of the provisioned tool stays in this client process (it inherits
     * this terminal's stdio, which the engine deliberately never touches).
     */
    public static dev.jkbuild.runtime.HostedEvents.Provision provision(
            EnginePaths.Paths paths, Path cache, Path projectDir, Path toolsRoot, boolean noDiscover, boolean gradle)
            throws IOException {
        return EngineWorkerAdapter.provision(
                paths,
                EngineProtocol.provisionRequest(
                        cache.toString(), projectDir.toString(), toolsRoot.toString(), noDiscover, gradle));
    }

    // ---- hosted pipeline verbs (Wave 3 of the slim client) --------------------------------------

    /** Everything an engine-hosted {@code jk compile} needs — mirrors {@code CompileCommand}'s local fields. */
    public record CompileRequest(
            Path entryDir, Path cache, String profile, boolean offline, boolean force, boolean verbose) {}

    /**
     * Run {@code jk compile}'s compile-only goal against the engine — {@code jk test}'s
     * listener-factory shape, plain terminal goal-finish.
     */
    public static dev.jkbuild.run.GoalResult runCompile(
            EnginePaths.Paths paths,
            CompileRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory)
            throws IOException {
        return EngineWorkerAdapter.stream(
                        paths,
                        EngineProtocol.compileRequest(
                                req.entryDir().toString(),
                                req.cache().toString(),
                                req.profile(),
                                req.offline(),
                                req.force(),
                                req.verbose()),
                        "compile",
                        listenerFactory,
                        (type, line) -> {})
                .result();
    }

    /**
     * Everything an engine-hosted {@code jk native} needs. {@code graalByDir} maps each
     * native-eligible module dir to the GraalVM home the client resolved for it — resolution (and
     * any consent prompt / install) happens client-side <em>before</em> this request, because it
     * owns the terminal.
     */
    public record NativeRequest(
            Path entryDir,
            Path cache,
            Path jdksDir,
            String mainClass,
            boolean skipTests,
            boolean offline,
            boolean force,
            boolean verbose,
            List<String> extraArgs,
            java.util.Map<Path, Path> graalByDir) {}

    /**
     * Run {@code jk native}'s hosted module cascade against the engine, driving {@code listener}
     * exactly as {@link #buildWorkspace} does (the cascade speaks the workspace event vocabulary; a
     * single project is a cascade of one). The returned result's {@code exitCode} is authoritative
     * — computed engine-side with {@code jk native}'s 64/4/1 mapping.
     */
    public static WorkspaceResult runNative(
            EnginePaths.Paths paths, NativeRequest req, WorkspaceBuildListener listener) throws IOException {
        return EngineBuildListenerAdapter.runNative(paths, req, listener);
    }

    /**
     * Everything an engine-hosted {@code jk install} (project mode) needs. {@code m2Dir} is the
     * resolved local Maven repo root; {@code graalHome} is non-null only for a native application
     * (resolved client-side, same pre-flight as {@link NativeRequest}).
     */
    public record InstallRequest(
            Path entryDir,
            Path cache,
            Path m2Dir,
            Path graalHome,
            boolean skipTests,
            boolean offline,
            boolean force,
            boolean verbose) {}

    /**
     * Run {@code jk install}'s build + cache-install goal against the engine — {@link #runTest}'s
     * exact contract ({@code testResultOut} settles before the terminal goal-finish reaches the
     * listener). The launcher-writing "make install" half stays in the calling command.
     */
    public static dev.jkbuild.run.GoalResult runInstall(
            EnginePaths.Paths paths,
            InstallRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            dev.jkbuild.run.TestSummary[] testResultOut)
            throws IOException {
        return EngineBuildListenerAdapter.runInstall(paths, req, listenerFactory, testResultOut);
    }

    /** Everything an engine-hosted {@code jk install <git-url>} fetch needs — pre-split/expanded client-side. */
    public record GitFetchRequest(String url, String canonicalUrl, String ref, Path cache, boolean refresh) {}

    /** A hosted git fetch's outcome: the goal result plus the materialized checkout + sha (null on failure). */
    public record GitFetchOutcome(dev.jkbuild.run.GoalResult result, Path checkout, String sha) {}

    /**
     * Materialize a git checkout via the engine ({@code jk install <git-url>}'s clone half; the
     * git-client worker forks engine-side). The checkout path + resolved sha ride the terminal
     * goal-finish and feed the follow-up {@link #runInstall}.
     */
    public static GitFetchOutcome runGitFetch(
            EnginePaths.Paths paths,
            GitFetchRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory)
            throws IOException {
        EngineWorkerAdapter.HostedFinish finish = EngineWorkerAdapter.stream(
                paths,
                EngineProtocol.gitFetchRequest(
                        req.url(), req.canonicalUrl(), req.ref(), req.cache().toString(), req.refresh()),
                "install-git-fetch",
                listenerFactory,
                (type, line) -> {});
        String checkout = Ndjson.str(finish.finishLine(), "gitCheckout");
        return new GitFetchOutcome(
                finish.result(), checkout != null ? Path.of(checkout) : null, Ndjson.str(finish.finishLine(), "gitSha"));
    }

    // ---- hosted long-tail verbs (Wave 4 of the slim client) -------------------------------------

    /**
     * Everything an engine-hosted tool resolution needs ({@code jk tool install}/{@code jk tool
     * run}/{@code jk install <g:a:v>}). {@code mainClass} is the {@code --main} override (may be
     * {@code null}); {@code repoUrl} overrides Maven Central (may be {@code null}).
     */
    public record ToolResolveRequest(String coord, String bin, String mainClass, java.net.URI repoUrl, Path cache) {}

    /**
     * A hosted tool resolution's outcome: the goal result plus the resolved main class and
     * classpath (empty on failure) — the ingredients of a client-side {@code ToolEnv}.
     */
    public record ToolResolveOutcome(dev.jkbuild.run.GoalResult result, String mainClass, List<Path> classpath) {}

    /**
     * Resolve a Maven-published CLI tool against the engine (the POM walk + jar fetches run
     * engine-side; see {@code ToolGoals}). The launcher write / inheritIO exec stays in the calling
     * command — it owns the user's {@code ~/.jk/bin} and terminal.
     */
    public static ToolResolveOutcome runToolResolve(
            EnginePaths.Paths paths,
            ToolResolveRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory)
            throws IOException {
        EngineWorkerAdapter.HostedFinish finish = EngineWorkerAdapter.stream(
                paths,
                EngineProtocol.toolResolveRequest(
                        req.coord(),
                        req.bin(),
                        req.mainClass(),
                        req.repoUrl() != null ? req.repoUrl().toString() : null,
                        req.cache().toString()),
                "tool-resolve",
                listenerFactory,
                (type, line) -> {});
        return new ToolResolveOutcome(
                finish.result(),
                Ndjson.str(finish.finishLine(), "toolMainClass"),
                Ndjson.strArray(finish.finishLine(), "toolClasspath").stream()
                        .map(Path::of)
                        .toList());
    }

    /**
     * Everything an engine-hosted script/jar preparation needs ({@code jk tool run <file>}).
     * {@code mode} = {@code java}/{@code kt}/{@code kts}/{@code jar}; {@code stateDir}/{@code
     * repoUrl} may be {@code null} (defaults).
     */
    public record ScriptPrepareRequest(
            String mode, Path script, Path cache, Path stateDir, java.net.URI repoUrl, boolean forceRecompile) {}

    /**
     * A hosted script preparation's outcome: the goal result plus the exec ingredients — fields not
     * applicable to the mode (and everything on failure) are {@code null}/empty.
     */
    public record ScriptPrepareOutcome(
            dev.jkbuild.run.GoalResult result,
            String mainClass,
            List<Path> classpath,
            Path classesDir,
            Path kotlincBin,
            Path stdlib) {}

    /**
     * Prepare a loose script/jar against the engine ({@code jk tool run <file>}: header parse, dep
     * resolution, compile / kotlinc provision / manifest inspection all engine-side — see {@code
     * ScriptGoals}). The exec of the prepared program stays in the calling command — it owns this
     * terminal.
     */
    public static ScriptPrepareOutcome runScriptPrepare(
            EnginePaths.Paths paths,
            ScriptPrepareRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory)
            throws IOException {
        EngineWorkerAdapter.HostedFinish finish = EngineWorkerAdapter.stream(
                paths,
                EngineProtocol.scriptPrepareRequest(
                        req.mode(),
                        req.script().toString(),
                        req.cache().toString(),
                        req.stateDir() != null ? req.stateDir().toString() : null,
                        req.repoUrl() != null ? req.repoUrl().toString() : null,
                        req.forceRecompile()),
                "script-prepare",
                listenerFactory,
                (type, line) -> {});
        String line = finish.finishLine();
        String classesDir = Ndjson.str(line, "scriptClassesDir");
        String kotlincBin = Ndjson.str(line, "scriptKotlincBin");
        String stdlib = Ndjson.str(line, "scriptStdlib");
        return new ScriptPrepareOutcome(
                finish.result(),
                Ndjson.str(line, "scriptMainClass"),
                Ndjson.strArray(line, "scriptClasspath").stream().map(Path::of).toList(),
                classesDir != null ? Path.of(classesDir) : null,
                kotlincBin != null ? Path.of(kotlincBin) : null,
                stdlib != null ? Path.of(stdlib) : null);
    }

    /**
     * Everything an engine-hosted cache maintenance op needs ({@code op} = {@code prune}/{@code
     * purge}/{@code gc} — {@code jk cache prune}/{@code purge}, {@code jk clean --cache}). {@code
     * maxSize} may be {@code null}; the non-prune ops ignore the prune-only fields.
     */
    public record CacheMaintRequest(
            String op, Path cache, int olderThanDays, boolean dryRun, boolean sweep, String maxSize, boolean includeJkTmp) {}

    /** A hosted cache maintenance op's summary, decoded from the terminal goal-finish ({@code -1} = n/a). */
    public record CacheMaintSummary(long files, long bytes, long reachableEvicted, long repoLinks) {}

    /**
     * Run a cache maintenance op against the engine, which executes it as an idle-boundary job: the
     * mutation waits until no pipeline is in flight (and blocks new ones while it runs), holding the
     * cross-process {@code .prune.lock} throughout. {@code onWait} fires when the engine reports the
     * job is queued — {@code pipelines} in-flight builds ({@code external=true}: another process's
     * prune) — so the command can explain the pause before the progress UI starts. {@code
     * summaryOut} (a single-slot holder) is populated from the terminal goal-finish <em>before</em>
     * it reaches {@code listenerFactory}'s listener, whose own {@code goalFinish} handler renders
     * the summary line from those fields — the {@code runImage} holder pattern.
     */
    public static dev.jkbuild.run.GoalResult runCacheMaintenance(
            EnginePaths.Paths paths,
            CacheMaintRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            java.util.function.ObjIntConsumer<Boolean> onWait,
            CacheMaintSummary[] summaryOut)
            throws IOException {
        return EngineWorkerAdapter.stream(
                        paths,
                        EngineProtocol.cachePruneRequest(
                                req.op(),
                                req.cache().toString(),
                                req.olderThanDays(),
                                req.dryRun(),
                                req.sweep(),
                                req.maxSize(),
                                req.includeJkTmp()),
                        "cache-" + req.op(),
                        listenerFactory,
                        (type, line) -> onWait.accept(
                                Ndjson.bool(line, "external", false), Ndjson.intValue(line, "pipelines", 0)),
                        line -> summaryOut[0] = new CacheMaintSummary(
                                Ndjson.longValue(line, "cacheFiles", -1),
                                Ndjson.longValue(line, "cacheBytes", -1),
                                Ndjson.longValue(line, "cacheReachableEvicted", -1),
                                Ndjson.longValue(line, "cacheRepoLinks", -1)))
                .result();
    }

    static Handshake ensureRunning(EnginePaths.Paths paths, String clientVersion, Duration startTimeout)
            throws IOException {
        Optional<Handshake> existing = handshake(paths.socket(), clientVersion);
        if (existing.isPresent()) {
            if (clientVersion.equals(existing.get().version())) return existing.get();
            killStale(existing.get().pid(), startTimeout);
        }
        spawn(paths, clientVersion);
        return awaitStartup(paths, clientVersion, startTimeout)
                .orElseThrow(() -> new IOException(
                        "could not start the build engine — see " + paths.log() + " for details"));
    }

    private static void killStale(long pid, Duration timeout) {
        ProcessHandle.of(pid).ifPresent(h -> {
            h.destroy();
            long deadline = System.nanoTime() + timeout.toNanos();
            while (h.isAlive() && System.nanoTime() < deadline) {
                sleepQuietly(20);
            }
            if (h.isAlive()) h.destroyForcibly();
        });
    }

    /**
     * Which engine artifact a spawn chose. {@code EXE}: {@code path} is an executable whose {@code
     * main()} IS the engine loop (no {@code --engine-server} flag). {@code JAR}: {@code path} is
     * the engine's fat jar ({@code ~/.jk/lib/jk-engine-<version>.jar}), launched as {@code
     * <managed-jdk>/bin/java … -cp <path> dev.jkbuild.cli.EngineMain} — the engine is a plain JVM
     * app, never a native image. {@code FALLBACK}: {@code path} is the client binary itself,
     * re-invoked with the flag. {@code how} is the one-word provenance for the log header.
     */
    record EngineArtifact(Kind kind, String path, String how) {
        enum Kind {
            EXE,
            JAR,
            FALLBACK
        }
    }

    /**
     * Resolution order for the engine artifact (docs/engine.md lifecycle §2):
     * (a) the {@code JK_ENGINE_EXE} env override — always treated as a dedicated engine
     * executable; (b) {@code jk-engine-<version>.jar} in {@code libDir} ({@code ~/.jk/lib/}) whose
     * filename version equals this client's version — the installed layout, hosted on the
     * jk-managed JDK; a missing or version-skewed jar never launches; (c) the client binary itself
     * with {@code --engine-server} — the JVM dist (installDist ships no second start script) and
     * dev workflows.
     */
    static EngineArtifact resolveEngineArtifact(String envOverride, String jkExe, String version, Path libDir) {
        if (envOverride != null && !envOverride.isBlank()) {
            return new EngineArtifact(EngineArtifact.Kind.EXE, envOverride, "JK_ENGINE_EXE");
        }
        Path engineJar = libDir.resolve("jk-engine-" + version + ".jar");
        if (Files.isRegularFile(engineJar)) {
            return new EngineArtifact(EngineArtifact.Kind.JAR, engineJar.toString(), "lib");
        }
        return new EngineArtifact(EngineArtifact.Kind.FALLBACK, jkExe, "fallback");
    }

    /**
     * The JDK that hosts the engine JVM — the jk-managed default ({@code current}, then {@code
     * default}: the same global tiers {@code JdkResolution} walks), then the JVM running jk /
     * {@code $JAVA_HOME} — but only a candidate that meets the engine's runtime floor: the engine
     * jars are compiled by the same JDK release that built this client, and a user whose projects
     * pin an older JDK can easily have that older release as their global default (it governs
     * worker JVMs, not the engine host). When nothing installed qualifies, install exactly the
     * floor release via {@link JdkEnsure#install(String, java.util.function.Consumer)} — no
     * resolution walk, no global-default side effects. The engine is deliberately NOT keyed to any
     * project's JDK pin: one engine serves many workspaces.
     */
    private static Path engineJavaHome() throws IOException {
        int floor = Runtime.version().feature(); // the release that compiled this client AND the engine jar
        GlobalDefaultJdk defaults = GlobalDefaultJdk.current();
        for (Optional<Path> candidate : List.of(defaults.currentHome(), defaults.defaultHome())) {
            if (candidate.isPresent() && meetsFloor(candidate.get(), floor)) return candidate.get();
        }
        try {
            Path running = JavaHomes.runningJavaHome();
            if (meetsFloor(running, floor)) return running;
        } catch (IllegalStateException noRunningJvm) {
            // Native client with no JAVA_HOME — fall through to the bootstrap install.
        }
        System.err.println("jk: installing a JDK " + floor + " to host the build engine ...");
        try {
            Path home = JdkEnsure.install(String.valueOf(floor), System.err::println).home();
            if (meetsFloor(home, floor)) return home;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            throw new IOException("no JDK " + floor + "+ can host the jk engine (" + e.getMessage()
                    + ") — run `jk jdk install " + floor + "` or point JAVA_HOME at one");
        }
        throw new IOException(
                "no JDK " + floor + "+ can host the jk engine — run `jk jdk install " + floor
                        + "` or point JAVA_HOME at one");
    }

    /**
     * The engine's AOT cache path, keyed to the engine jar (name:size:mtime, hashed) so a changed
     * {@code jk-engine-<version>.jar} yields a fresh key — the previous key's cache would be
     * silently ignored by {@code AOTMode=auto} forever, never retrained. Caches for previous jars
     * are deleted best-effort while resolving the current one.
     */
    private static Path aotCachePath(EnginePaths.Paths paths, Path engineJar) {
        StringBuilder signature = new StringBuilder();
        try {
            signature
                    .append(engineJar.getFileName())
                    .append(':')
                    .append(Files.size(engineJar))
                    .append(':')
                    .append(Files.getLastModifiedTime(engineJar).toMillis());
        } catch (IOException e) {
            signature.append("unreadable");
        }
        Path cache = paths.dir()
                .resolve("engine-" + dev.jkbuild.util.Hashing.sha256Hex(signature.toString()).substring(0, 16)
                        + ".aot");
        try (var stale = Files.newDirectoryStream(paths.dir(), "engine-*.aot")) {
            for (Path p : stale) {
                if (!p.equals(cache)) Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
            // Cleanup is opportunistic; a leftover cache costs disk, not correctness.
        }
        return cache;
    }

    /**
     * True when the JDK at {@code home} is release {@code floor} or newer — one {@code release}-file
     * read via the discovery probes' shared helper. Unreadable/none → false (never spawn an engine
     * that dies on {@code UnsupportedClassVersionError} when a better tier is available).
     */
    private static boolean meetsFloor(Path home, int floor) {
        return dev.jkbuild.discovery.ProbeSupport.discoverJdk(home, "engine-host")
                .map(hit -> {
                    int dot = hit.version().indexOf('.');
                    String feature = dot < 0 ? hit.version() : hit.version().substring(0, dot);
                    try {
                        return Integer.parseInt(feature) >= floor;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                })
                .orElse(false);
    }

    /** Spawn a fresh engine, detached — mirrors {@link CachePruneScheduler}'s spawn-and-forget pattern. */
    private static void spawn(EnginePaths.Paths paths, String clientVersion) throws IOException {
        String jkExe = CachePruneScheduler.resolveJkExe()
                .orElseThrow(() -> new IOException("could not resolve the running jk binary's path"));
        EngineArtifact engine = resolveEngineArtifact(
                System.getenv("JK_ENGINE_EXE"), jkExe, clientVersion, dev.jkbuild.util.JkDirs.lib());
        JkEngineConfig config = JkEngineConfig.resolve();
        Files.createDirectories(paths.dir());
        rotateLog(paths.log());
        List<String> command = new ArrayList<>();
        // The child detaches ITSELF into its own session (setsid(2) via PosixDetach, first thing
        // in the engine role) — without that it stays in THIS client's process group, and a
        // Ctrl-C/SIGTERM aimed at the client (or its whole group) would take down the engine and
        // every other build it is hosting.
        //
        // Sizing the engine's heap (docs/engine.md "Memory target") happens on the spawn line —
        // the spawner is the only place that can, since a process can't shrink its own -Xmx, and
        // the -Xms pre-sizing matters for a long-lived process (no growth churn). How the numbers
        // ride along differs per artifact form below; user config max-heap-mb stays authoritative
        // everywhere.
        switch (engine.kind()) {
            case JAR -> {
                // The installed engine: a plain JVM app on the jk-managed JDK, one fat jar on the
                // classpath. Tuning is ordinary JVM flags — SerialGC (lowest footprint/latency; a
                // ≤256 MiB heap is well inside its comfort zone) plus the JkEngineConfig heap
                // numbers. The long-lived engine is exactly what HotSpot's JIT and SHA-256
                // intrinsics want; there is no native engine image. --enable-native-access:
                // PosixDetach's setsid(2) FFM downcall without the JDK's restricted-method
                // warning.
                command.add(engineJavaHome()
                        .resolve("bin")
                        .resolve(HostPlatform.isWindows() ? "java.exe" : "java")
                        .toString());
                command.add("-XX:+UseSerialGC");
                // AOT cache (JEP 514, JDK 25+): pre-parsed class metadata AND AOT-compiled code,
                // taming the cold engine's JIT-warmup tail. Engine cold start is user-visible
                // latency — the first command after an idle timeout waits for this spawn. The
                // cache file is keyed to the exact jar because AOTMode=auto silently ignores
                // a mismatched cache: an unkeyed name would stop helping at the first upgrade and
                // never retrain. First spawn after a jar change trains (-XX:AOTCacheOutput,
                // assembled on clean exit — idle recycling makes that routine); every later cold
                // start maps the cache.
                Path aotCache = aotCachePath(paths, Path.of(engine.path()));
                command.add((Files.exists(aotCache) ? "-XX:AOTCache=" : "-XX:AOTCacheOutput=") + aotCache);
                if (config.heapCapped()) {
                    command.add("-Xms" + config.minHeapMb() + "m");
                    command.add("-Xmx" + config.maxHeapMb() + "m");
                }
                command.add("--enable-native-access=ALL-UNNAMED");
                command.add("-cp");
                command.add(engine.path());
                command.add("dev.jkbuild.cli.EngineMain");
            }
            case EXE -> {
                // A dedicated engine executable (JK_ENGINE_EXE): its main() IS the engine loop, no
                // flag. The -Xm* args land as argv; EngineMain ignores argv, so a wrapper that
                // doesn't consume them degrades to an unsized engine, never a dead one.
                command.add(engine.path());
                if (config.heapCapped()) {
                    command.add("-Xms" + config.minHeapMb() + "m");
                    command.add("-Xmx" + config.maxHeapMb() + "m");
                }
            }
            case FALLBACK -> {
                // This same binary re-invoked with --engine-server. On the JVM dist the flag finds
                // EngineMain through the InProcessEngine seam; on the slim native client (which
                // links no engine code) the child reports plainly that the engine jar is missing
                // and exits — the ensure-running timeout then surfaces that log line.
                command.add(engine.path());
                command.add("--engine-server");
                if (config.heapCapped() && isNativeImage()) {
                    command.add("-Xms" + config.minHeapMb() + "m");
                    command.add("-Xmx" + config.maxHeapMb() + "m");
                }
            }
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        // On the JVM dist the sizing knob is the start script's JVM-options env var — appended
        // last so it wins over any blanket JK_OPTS the user exported. SerialGC for the same
        // reasons as the JAR spawn line.
        if (config.heapCapped() && engine.kind() == EngineArtifact.Kind.FALLBACK && !isNativeImage()) {
            String opts = System.getenv("JK_OPTS");
            String sizing = "-XX:+UseSerialGC -Xms" + config.minHeapMb() + "m -Xmx" + config.maxHeapMb() + "m";
            pb.environment().put("JK_OPTS", (opts == null || opts.isBlank() ? "" : opts + " ") + sizing);
        }
        // Merge stderr into stdout inside the child (one fd, no interleaving risk from two
        // independently-opened streams onto the same file), then route that to the log — a fresh
        // file every start, per docs/engine.md. The spawner writes the log's first line itself
        // (which artifact it chose — the one fact the engine can't know), then the child appends;
        // if that header can't be written, fall back to plain truncate-and-redirect.
        pb.redirectErrorStream(true);
        pb.redirectOutput(
                writeSpawnHeader(paths.log(), engine)
                        ? ProcessBuilder.Redirect.appendTo(paths.log().toFile())
                        : ProcessBuilder.Redirect.to(paths.log().toFile()));
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();
        p.getOutputStream().close(); // EOF immediately; the engine doesn't read stdin
    }

    /**
     * Start the fresh log with the spawn decision, truncating whatever {@link #rotateLog} left
     * behind (it's best-effort). {@code false} — and no header — if the file isn't writable; the
     * caller then falls back to the truncating redirect so log semantics stay identical.
     */
    private static boolean writeSpawnHeader(Path log, EngineArtifact engine) {
        try {
            Files.writeString(
                    log,
                    "jk engine: spawning " + engine.path() + " (" + engine.how() + ")" + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** True when this client runs as a GraalVM native image (so the spawned engine will too). */
    private static boolean isNativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }

    /**
     * Keep exactly one historical log ({@code <key>.log} → {@code <key>.log.1}) before each fresh
     * engine start truncates {@code <key>.log}. Without this, a crash followed by the next lazy
     * respawn (which happens automatically, often before anyone looks) would silently destroy the
     * crashed engine's own log — the one file {@link #ensureRunning}'s error message and {@code jk
     * engine status} both point at for post-mortem. Best-effort: a failure here (e.g. permissions)
     * never blocks starting the engine.
     */
    private static void rotateLog(Path log) {
        if (!Files.exists(log)) return;
        try {
            Files.move(
                    log,
                    log.resolveSibling(log.getFileName() + ".1"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Best-effort — the next start still truncates/overwrites `log` either way.
        }
    }

    private static Optional<Handshake> awaitStartup(EnginePaths.Paths paths, String clientVersion, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<Handshake> h = handshake(paths.socket(), clientVersion);
            if (h.isPresent()) return h;
            sleepQuietly(50);
        }
        return Optional.empty();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Package-visible: {@link EngineBuildListenerAdapter} opens its own long-lived connection. On
     * the loopback-TCP transport (Windows — see {@link dev.jkbuild.engine.EngineTransport}), {@code
     * socket} holds the port number (not a real socket path) and this also sends the required
     * {@link EngineProtocol#AUTH} line before returning, so every caller authenticates transparently
     * without needing its own knowledge of the transport.
     */
    static SocketChannel connect(Path socket) throws IOException {
        if (dev.jkbuild.engine.EngineTransport.useLoopbackTcp()) {
            int port = Integer.parseInt(Files.readString(socket).trim());
            String token =
                    Files.readString(dev.jkbuild.engine.EnginePaths.tokenFor(socket)).trim();
            SocketChannel ch = SocketChannel.open(
                    new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port));
            BufferedWriter authWriter =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            authWriter.write(EngineProtocol.auth(token));
            authWriter.write('\n');
            authWriter.flush();
            return ch;
        }
        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        ch.connect(UnixDomainSocketAddress.of(socket));
        return ch;
    }

    /**
     * Send one line, read one reply line, over an already-connected channel. {@link SocketChannel}
     * (a Unix-domain channel doesn't support the legacy {@code .socket()}/{@code setSoTimeout}
     * adapter) has no built-in read timeout, so a watchdog thread closes the channel if the engine
     * doesn't reply in time — an interruptible-channel read blocked on a closed channel throws
     * promptly, which this turns into a clear timeout error rather than hanging the CLI forever.
     */
    private static String exchange(SocketChannel ch, String line) throws IOException {
        BufferedWriter writer =
                new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
        writer.write(line);
        writer.write('\n');
        writer.flush();
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
        Thread watchdog = new Thread(
                () -> {
                    try {
                        Thread.sleep(SOCKET_TIMEOUT_MILLIS);
                        ch.close();
                    } catch (InterruptedException ignored) {
                        // exchange() finished in time — nothing to do
                    } catch (IOException ignored) {
                        // already closing
                    }
                },
                "jk-engine-client-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            String reply = reader.readLine();
            if (reply == null) throw new IOException("engine closed the connection without replying");
            return reply;
        } catch (java.nio.channels.AsynchronousCloseException e) {
            throw new IOException("engine did not reply within " + SOCKET_TIMEOUT_MILLIS + "ms", e);
        } finally {
            watchdog.interrupt();
        }
    }
}
