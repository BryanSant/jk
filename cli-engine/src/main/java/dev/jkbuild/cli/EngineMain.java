// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

/**
 * Dedicated engine entrypoint — the engine JVM's {@code main}. The engine is a plain Java app,
 * never a native image: the native {@code jk} client spawns it on the jk-managed JDK as {@code
 * java -XX:+UseSerialGC -Xms…/-Xmx… -cp ~/.jk/versions/<v>/lib/jk-engine.jar
 * dev.jkbuild.cli.EngineMain}. Its
 * whole {@code main()} IS the engine-server loop: no verb routing, no {@code --engine-server}
 * flag. The same code also backs {@code jk --engine-server} (see {@link Jk#main}), which stays as
 * the JVM-dist path — one implementation, two ways in. See {@code docs/engine.md} ("Two
 * artifacts").
 *
 * <p>Lives in the {@code :cli-engine} module (Stage 5's dependency cut): the engine role links the
 * full kernel, which the thin client deliberately no longer does. {@code jk --engine-server}
 * reaches {@link #run} through the {@code InProcessEngine} ServiceLoader seam, present on the JVM
 * dist's classpath and absent from the client native image.
 */
public final class EngineMain {

    private EngineMain() {}

    /**
     * {@code args} are deliberately ignored: on the {@code java -cp} spawn line every option is a
     * real JVM flag consumed before {@code main()} runs, and a {@code JK_ENGINE_EXE} wrapper that
     * fails to consume the {@code -Xms}/{@code -Xmx} the spawner appends leaves them harmlessly
     * inert here — better an unsized engine than a dead one.
     */
    public static void main(String[] args) {
        // --job: one-shot child mode (engine-versioning-plan §3) — serve exactly one request
        // over stdio for a parent daemon delegating a pinned-version build, then exit. Every
        // other arg stays deliberately ignored (see below).
        if (args.length > 0 && "--job".equals(args[0])) {
            System.exit(runJob());
        }
        // --aot-training: the sidecar trainer (docs/engine.md) — an isolated, self-terminating
        // engine run whose only purpose is recording an AOT cache. Spawned BY the main engine,
        // never by hand; binds only throwaway paths under a private temp dir.
        if (args.length > 0 && "--aot-training".equals(args[0])) {
            System.exit(runAotTraining());
        }
        System.exit(run());
    }

    /** One request over stdin/stdout; engine-lifecycle logging stays on stderr. */
    static int runJob() {
        try {
            dev.jkbuild.engine.EngineServer server = new dev.jkbuild.engine.EngineServer(
                    dev.jkbuild.engine.EnginePaths.current(),
                    dev.jkbuild.config.JkEngineConfig.resolve(),
                    null,
                    Jk.VERSION,
                    System.err::println);
            server.serveJob(
                    new java.io.BufferedReader(new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8)),
                    new java.io.BufferedWriter(new java.io.OutputStreamWriter(System.out, java.nio.charset.StandardCharsets.UTF_8)));
            return 0;
        } catch (RuntimeException e) {
            System.err.println("jk engine (job): " + e.getMessage());
            return 1;
        }
    }

    /**
     * The engine server's whole life: resolve identity/config from the same env this process
     * inherited from its spawner, serve until shutdown, then return. All engine-lifecycle logging
     * goes to {@code System.err} — the spawner already redirected this process's stdout/stderr to
     * the engine's log file, so nothing here writes to a real terminal.
     */
    public static int run() {
        // The engine must survive its spawner's terminal: first detach into our own POSIX session
        // (setsid(2) via FFM — see PosixDetach; no-op on Windows), then ignore terminal-generated
        // SIGINT/SIGHUP as belt-and-suspenders for the platforms/windows-of-time detach can't
        // cover. Cancelling an individual build is a wire-level concern (BUILD_CANCEL), never a
        // signal; SIGTERM stays lethal on purpose so `kill <pid>` still works.
        dev.jkbuild.cli.engine.PosixDetach.intoOwnSession();
        org.jline.utils.Signals.register("INT", () -> {});
        org.jline.utils.Signals.register("HUP", () -> {});
        try {
            dev.jkbuild.engine.EnginePaths.Paths paths = dev.jkbuild.engine.EnginePaths.current();
            dev.jkbuild.config.JkEngineConfig config = dev.jkbuild.config.JkEngineConfig.resolve();
            dev.jkbuild.config.JkHttpConfig httpConfig =
                    dev.jkbuild.config.JkHttpConfig.resolve().orElse(null);
            dev.jkbuild.engine.EngineServer server = new dev.jkbuild.engine.EngineServer(
                    paths, config, httpConfig, Jk.VERSION, System.err::println);
            // The spawner asks for an AOT cache with -Djk.aot.train.output=<path> when none exists
            // yet (see EngineClient.spawn). The server invokes the factory only after WINNING its
            // election — a losing redundant spawn never trains — and owns the child end-to-end.
            String aotOut = System.getProperty("jk.aot.train.output");
            if (aotOut != null && !aotOut.isBlank() && !java.nio.file.Files.exists(java.nio.file.Path.of(aotOut))) {
                server.aotTrainerSpawner(() -> spawnAotTrainer(aotOut));
            }
            server.run();
            return 0;
        } catch (java.io.IOException e) {
            System.err.println("jk engine: failed to start: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Launch the sidecar AOT trainer: this engine's own JVM and classpath, re-entered at {@code
     * --aot-training}, with {@code -XX:AOTCacheOutput} so the recording assembles at its clean
     * exit. {@code -XX:+UseSerialGC} matches the serving spawn line (EngineClient.spawn) — the
     * assembled cache must be recorded under the same GC it will later be mapped under. Output
     * inherits this engine's stdout/stderr, which the spawner already pointed at the engine log.
     */
    private static Process spawnAotTrainer(String aotOut) {
        try {
            String javaExe = ProcessHandle.current()
                    .info()
                    .command()
                    .orElseGet(() -> java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java")
                            .toString());
            ProcessBuilder pb = new ProcessBuilder(
                    javaExe,
                    "-XX:+UseSerialGC",
                    "-XX:AOTCacheOutput=" + aotOut,
                    "-cp",
                    System.getProperty("java.class.path"),
                    EngineMain.class.getName(),
                    "--aot-training");
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            return pb.start();
        } catch (java.io.IOException e) {
            System.err.println("jk engine: could not spawn the AOT training sidecar: " + e.getMessage());
            return null;
        }
    }

    /**
     * How long the trainer serves before stopping itself. A sub-second run yields an empty
     * recording the assembler rejects; three seconds captures the whole startup path (which is
     * what the cache accelerates) without meaningfully extending the doubled-RSS window.
     */
    private static final long AOT_TRAINING_UPTIME_MS = 3_000;

    /**
     * The sidecar trainer's whole life: bring up a REAL engine server — full startup class-loading
     * fidelity, that's the profile worth recording — against a private temp state dir (its socket,
     * lock, generations, and endpoint pointer all live and die there; it can never win, lose, or
     * even see the real engine's election), idle briefly, then stop cleanly so the JVM assembles
     * the {@code .aot} at exit. The temp dir is removed on the way out.
     */
    static int runAotTraining() {
        java.nio.file.Path tmp = null;
        try {
            tmp = java.nio.file.Files.createTempDirectory("jk-aot-train-");
            dev.jkbuild.engine.EnginePaths.Paths paths = dev.jkbuild.engine.EnginePaths.resolve(tmp);
            dev.jkbuild.engine.EngineServer server = new dev.jkbuild.engine.EngineServer(
                    paths, dev.jkbuild.config.JkEngineConfig.resolve(), null, Jk.VERSION, System.err::println);
            Thread serving = new Thread(
                    () -> {
                        try {
                            server.run();
                        } catch (java.io.IOException e) {
                            System.err.println("jk engine (aot-training): " + e.getMessage());
                        }
                    },
                    "jk-aot-training");
            serving.start();
            // Serving is observable as the endpoint pointer appearing in OUR temp dir.
            java.nio.file.Path endpoint = dev.jkbuild.engine.EnginePaths.endpoint(paths);
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
            while (!java.nio.file.Files.exists(endpoint) && serving.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            Thread.sleep(AOT_TRAINING_UPTIME_MS);
            server.close();
            serving.join(java.time.Duration.ofSeconds(30).toMillis());
            return 0;
        } catch (Exception e) {
            System.err.println("jk engine (aot-training): " + e.getMessage());
            return 1;
        } finally {
            if (tmp != null) deleteRecursively(tmp);
        }
    }

    private static void deleteRecursively(java.nio.file.Path root) {
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    java.nio.file.Files.deleteIfExists(p);
                } catch (java.io.IOException ignored) {
                    // best-effort cleanup; the OS reaps temp dirs eventually
                }
            });
        } catch (java.io.IOException ignored) {
            // best-effort cleanup
        }
    }
}
