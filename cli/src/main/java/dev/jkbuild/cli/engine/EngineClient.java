// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.engine;

import dev.jkbuild.config.JkEngineConfig;
import dev.jkbuild.engine.EnginePaths;
import dev.jkbuild.engine.protocol.EngineProtocol;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.runtime.BuildService;
import dev.jkbuild.runtime.WorkspaceBuildListener;
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
     * equivalent of {@link BuildService#buildWorkspace}, driving the exact same {@code listener}.
     * Ensures a live, version-matched engine first (spawning/replacing as needed), then streams the
     * build over a fresh connection. Throws with a clear message on any failure; per {@code
     * docs/engine.md} there is no in-process fallback.
     */
    public static BuildService.WorkspaceResult buildWorkspace(
            EnginePaths.Paths paths, BuildService.WorkspaceRequest req, WorkspaceBuildListener listener)
            throws IOException {
        return EngineBuildListenerAdapter.buildWorkspace(paths, req, listener);
    }

    /** Everything an engine-hosted {@code jk test} run needs — mirrors {@code TestCommand}'s own local fields. */
    public record TestRequest(Path entryDir, Path cache, Path jdksDir, int workers, String profile, boolean verbose) {}

    /**
     * Run a single project's test goal against the engine (Phase 3) — see {@link
     * EngineBuildListenerAdapter#runTest} for the exact contract.
     */
    public static dev.jkbuild.run.GoalResult runTest(
            EnginePaths.Paths paths,
            TestRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            dev.jkbuild.test.JUnitLauncher.Result[] testResultOut)
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
            dev.jkbuild.test.JUnitLauncher.Result[] testResultOut,
            String[] buildOutcomeOut)
            throws IOException {
        return EngineBuildListenerAdapter.runSingleBuild(paths, req, listenerFactory, testResultOut, buildOutcomeOut);
    }

    /**
     * Forecast a build against the engine ({@code jk explain}) — see {@link
     * EngineBuildListenerAdapter#explain} for the exact contract.
     */
    public static BuildService.ExplainPlan explain(EnginePaths.Paths paths, Path entryDir, Path cache)
            throws IOException {
        return EngineBuildListenerAdapter.explain(paths, entryDir, cache);
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
            dev.jkbuild.runtime.AuditGoals.FindingObserver findings)
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
            dev.jkbuild.runtime.FormatGoals.FileObserver files)
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
            dev.jkbuild.test.JUnitLauncher.Result testResult,
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
                            dev.jkbuild.test.JUnitLauncher.Result testResult = total < 0
                                    ? null
                                    : new dev.jkbuild.test.JUnitLauncher.Result(
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
            dev.jkbuild.runtime.CompatGoals.NoteObserver notes)
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
    public static dev.jkbuild.runtime.CompatGoals.Provision provision(
            EnginePaths.Paths paths, Path cache, Path projectDir, Path toolsRoot, boolean noDiscover, boolean gradle)
            throws IOException {
        return EngineWorkerAdapter.provision(
                paths,
                EngineProtocol.provisionRequest(
                        cache.toString(), projectDir.toString(), toolsRoot.toString(), noDiscover, gradle));
    }

    static Handshake ensureRunning(EnginePaths.Paths paths, String clientVersion, Duration startTimeout)
            throws IOException {
        Optional<Handshake> existing = handshake(paths.socket(), clientVersion);
        if (existing.isPresent()) {
            if (clientVersion.equals(existing.get().version())) return existing.get();
            killStale(existing.get().pid(), startTimeout);
        }
        spawn(paths);
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

    /** Spawn a fresh engine, detached — mirrors {@link CachePruneScheduler}'s spawn-and-forget pattern. */
    private static void spawn(EnginePaths.Paths paths) throws IOException {
        String jkExe = CachePruneScheduler.resolveJkExe()
                .orElseThrow(() -> new IOException("could not resolve the running jk binary's path"));
        JkEngineConfig config = JkEngineConfig.resolve();
        Files.createDirectories(paths.dir());
        rotateLog(paths.log());
        List<String> command = new ArrayList<>();
        // The child detaches ITSELF into its own session (setsid(2) via PosixDetach, first thing
        // in the engine role) — without that it stays in THIS client's process group, and a
        // Ctrl-C/SIGTERM aimed at the client (or its whole group) would take down the engine and
        // every other build it is hosting.
        command.add(jkExe);
        command.add("--engine-server");
        // Size the engine process's own heap (docs/engine.md "Memory target") — the spawner is
        // the only place that can, since a process can't shrink its own -Xmx. The -Xms pre-sizing
        // matters for a long-lived process (no growth churn). Native image consumes -Xm* runtime
        // options from argv (position-independent) before main(); placed after --engine-server so
        // that if they ever *aren't* consumed, the engine still starts (unsized) instead of
        // failing on an unknown verb. The native binary is already built with --gc=serial.
        if (config.heapCapped() && isNativeImage()) {
            command.add("-Xms" + config.minHeapMb() + "m");
            command.add("-Xmx" + config.maxHeapMb() + "m");
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        // On a JVM (installDist) the equivalent knob is the start script's JVM-options env var —
        // appended last so it wins over any blanket JK_OPTS the user exported. SerialGC matches
        // the native binary: lowest footprint/latency, and a ≤256 MiB heap is well inside its
        // comfort zone.
        if (config.heapCapped() && !isNativeImage()) {
            String opts = System.getenv("JK_OPTS");
            String sizing = "-XX:+UseSerialGC -Xms" + config.minHeapMb() + "m -Xmx" + config.maxHeapMb() + "m";
            pb.environment().put("JK_OPTS", (opts == null || opts.isBlank() ? "" : opts + " ") + sizing);
        }
        // Merge stderr into stdout inside the child (one fd, no interleaving risk from two
        // independently-opened streams onto the same file), then route that to the log — a fresh
        // file every start, per docs/engine.md.
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.to(paths.log().toFile()));
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();
        p.getOutputStream().close(); // EOF immediately; the engine doesn't read stdin
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
