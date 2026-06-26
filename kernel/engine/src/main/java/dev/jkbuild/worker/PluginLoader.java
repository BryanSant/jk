// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.worker;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Forks a jk plugin worker JVM and bridges its NDJSON event stream back to the caller. A worker is
 * launched as {@code <java> <jvmFlags> -cp <classpath> dev.jkbuild.plugin.worker.PluginWorkerMain
 * <args>}: {@code PluginWorkerMain} {@code ServiceLoader}-loads the single {@link
 * dev.jkbuild.plugin.Plugin} on that classpath, runs it, and the plugin emits {@code
 * <prefix>}-tagged protocol lines on stdout.
 *
 * <p>jk has no host JVM and the CLI is a closed-world native image, so plugins never run in-process
 * — every worker is its own JVM, dispatched directly. The caller supplies the worker's classpath
 * (which must carry the plugin jar plus whatever it needs — e.g. the user's test classes + engines
 * for the test runner), the JVM tuning flags ({@link dev.jkbuild.worker.JvmOptions}), and the
 * protocol prefix the plugin emits.
 */
public final class PluginLoader {

    private PluginLoader() {}

    /** Fully-qualified main class every worker jar runs under (vendored from plugin-api). */
    static final String WORKER_MAIN = "dev.jkbuild.plugin.worker.PluginWorkerMain";

    /**
     * Fork a worker and stream its events. Returns the worker's exit code.
     *
     * @param javaExe the JVM to launch (the project-pinned JDK's {@code java})
     * @param classpath the worker's classpath (must include the plugin jar)
     * @param jvmFlags heap/GC/etc. tuning flags (see {@link dev.jkbuild.worker.JvmOptions})
     * @param prefix the protocol-line marker the plugin emits (its manifest prefix)
     * @param args program args passed after {@code PluginWorkerMain}
     */
    public static int run(
            Path javaExe,
            String classpath,
            List<String> jvmFlags,
            String prefix,
            List<String> args,
            Consumer<String> onProtocol,
            Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return WorkerProcess.run(command(javaExe, classpath, jvmFlags, args), prefix, onProtocol, onPassthrough);
    }

    /**
     * As {@link #run}, but drives a two-way conversation (pull protocol): each protocol line arrives
     * with a {@link WorkerProcess.Conversation} the caller can use to send commands back to the
     * worker's stdin.
     */
    public static int converse(
            Path javaExe,
            String classpath,
            List<String> jvmFlags,
            String prefix,
            List<String> args,
            BiConsumer<String, WorkerProcess.Conversation> onProtocol,
            Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return WorkerProcess.converse(command(javaExe, classpath, jvmFlags, args), prefix, onProtocol, onPassthrough);
    }

    private static List<String> command(Path javaExe, String classpath, List<String> jvmFlags, List<String> args) {
        var cmd = new ArrayList<String>();
        cmd.add(javaExe.toString());
        cmd.addAll(jvmFlags);
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(WORKER_MAIN);
        cmd.addAll(args);
        return cmd;
    }
}
