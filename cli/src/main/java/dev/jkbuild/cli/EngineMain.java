// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

/**
 * Dedicated engine entrypoint — the {@code jk-engine} native binary's {@code main} (slim-client
 * Stage 4). Its whole {@code main()} IS the engine-server loop: no verb routing, no {@code
 * --engine-server} flag. The same code also backs {@code jk --engine-server} (see {@link Jk#main}),
 * which stays as the JVM-dist path and the fallback when no dedicated binary is installed — one
 * implementation, two ways in. See {@code docs/engine.md} ("Two artifacts").
 *
 * <p>Lives in the cli module for now because the engine role reuses CLI-side plumbing
 * ({@code PosixDetach}, JLine's signal registry); Stage 5's dependency cut may rehome it.
 */
public final class EngineMain {

    private EngineMain() {}

    /**
     * {@code args} are deliberately ignored: the only argv a spawner passes besides the program
     * name are native-image {@code -Xms}/{@code -Xmx} runtime options, which the image runtime
     * consumes before {@code main()} runs (and which are harmlessly inert on the off-chance they
     * reach us un-consumed — better an unsized engine than a dead one).
     */
    public static void main(String[] args) {
        System.exit(run());
    }

    /**
     * The engine server's whole life: resolve identity/config from the same env this process
     * inherited from its spawner, serve until shutdown, then return. All engine-lifecycle logging
     * goes to {@code System.err} — the spawner already redirected this process's stdout/stderr to
     * the engine's log file, so nothing here writes to a real terminal.
     */
    static int run() {
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
            dev.jkbuild.engine.EngineServer server =
                    new dev.jkbuild.engine.EngineServer(paths, config, Jk.VERSION, System.err::println);
            server.run();
            return 0;
        } catch (java.io.IOException e) {
            System.err.println("jk engine: failed to start: " + e.getMessage());
            return 1;
        }
    }
}
