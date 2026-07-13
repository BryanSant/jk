// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

/**
 * Dedicated engine entrypoint — the engine JVM's {@code main}. The engine is a plain Java app,
 * never a native image: the native {@code jk} client spawns it on the jk-managed JDK as {@code
 * java -XX:+UseSerialGC -Xms…/-Xmx… -cp ~/.jk/lib/jk-engine-<version>.jar
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
            server.run();
            return 0;
        } catch (java.io.IOException e) {
            System.err.println("jk engine: failed to start: " + e.getMessage());
            return 1;
        }
    }
}
