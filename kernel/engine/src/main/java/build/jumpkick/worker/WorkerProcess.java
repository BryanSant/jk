// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.worker;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The shared parent-side mechanics for driving a child-JVM worker: fork the process, read its
 * merged stdout/stderr line by line, split structured protocol lines (those carrying the worker's
 * {@code prefix}) from passthrough chatter, and wait for exit.
 *
 * <p>This collapses the fork + read-loop that each launch site used to hand-roll. Callers build the
 * command (the codec, spec format, and per-message handling stay theirs) and supply line callbacks.
 *
 * <p>Two shapes:
 *
 * <ul>
 *   <li>{@link #run} — fire-and-read: the parent only consumes output.
 *   <li>{@link #converse} — two-way: each protocol line is handed a {@link Conversation} the
 *       callback can use to send commands back to the worker's stdin (e.g. a pull-queue feeding
 *       {@code RUN}/{@code DONE} in response to the worker's {@code ready} events).
 * </ul>
 */
public final class WorkerProcess {

    private WorkerProcess() {}

    /** A handle for talking back to a running worker over its stdin. */
    public interface Conversation {
        /** Send one line to the worker's stdin (a newline is appended and flushed). */
        void send(String line);

        /** Close the worker's stdin, signalling end-of-input (EOF). */
        void closeInput();
    }

    /**
     * Fork {@code command}, stream its output, and return the process exit code. The parent only
     * reads — there is no stdin interaction.
     *
     * @param command full process command line (java exe, classpath/jar, main, args)
     * @param prefix marker identifying protocol lines (e.g. {@code "##JKGIT:"})
     * @param onProtocol receives each protocol line with the prefix stripped
     * @param onPassthrough receives each non-protocol line verbatim; may be {@code null} to drop them
     */
    public static int run(
            List<String> command, String prefix, Consumer<String> onProtocol, Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return run(command, java.util.Map.of(), prefix, onProtocol, onPassthrough);
    }

    /**
     * As {@link #run(List, String, Consumer, Consumer)}, adding {@code extraEnv} to the child's
     * environment.
     */
    public static int run(
            List<String> command,
            java.util.Map<String, String> extraEnv,
            String prefix,
            Consumer<String> onProtocol,
            Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return converse(command, extraEnv, prefix, (json, convo) -> onProtocol.accept(json), onPassthrough);
    }

    /**
     * Fork {@code command} and drive a two-way conversation: each protocol line is delivered to
     * {@code onProtocol} along with a {@link Conversation} for sending commands back to the worker's
     * stdin.
     *
     * <p>Reading stdout and writing stdin both happen on the calling thread, so the worker must
     * alternate (await input → emit → await input) rather than flood stdout while blocked on stdin —
     * which the pull protocol does (it emits {@code ready}, then waits for the next command). Run one
     * {@code converse} per worker on its own thread for parallel pull queues.
     *
     * @param onPassthrough receives each non-protocol line; may be {@code null} to drop
     */
    public static int converse(
            List<String> command,
            String prefix,
            BiConsumer<String, Conversation> onProtocol,
            Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        return converse(command, java.util.Map.of(), prefix, onProtocol, onPassthrough);
    }

    /**
     * As {@link #converse(List, String, BiConsumer, Consumer)}, adding {@code extraEnv} to the
     * child's environment.
     */
    public static int converse(
            List<String> command,
            java.util.Map<String, String> extraEnv,
            String prefix,
            BiConsumer<String, Conversation> onProtocol,
            Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (extraEnv != null && !extraEnv.isEmpty()) pb.environment().putAll(extraEnv);
        // Hold a worker slot for the child's whole lifetime so no more than the
        // memory plan's parallelism run at once (open gate when unconfigured).
        try (WorkerSlots.Lease lease = WorkerSlots.acquire()) {
            return converse(pb, prefix, onProtocol, onPassthrough);
        }
    }

    private static int converse(
            ProcessBuilder pb,
            String prefix,
            BiConsumer<String, Conversation> onProtocol,
            Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        Process process = pb.start();
        try (BufferedReader reader =
                        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter stdin =
                        new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            Conversation convo = new Conversation() {
                @Override
                public void send(String line) {
                    try {
                        stdin.write(line);
                        stdin.write('\n');
                        stdin.flush();
                    } catch (IOException ignored) {
                        // Worker is gone / pipe broken; the read loop will end.
                    }
                }

                @Override
                public void closeInput() {
                    try {
                        stdin.close();
                    } catch (IOException ignored) {
                        // Already closed is fine.
                    }
                }
            };
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(prefix)) {
                    onProtocol.accept(line.substring(prefix.length()), convo);
                } else if (onPassthrough != null) {
                    onPassthrough.accept(line);
                }
            }
        }
        return process.waitFor();
    }
}
