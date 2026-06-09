// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.worker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

/**
 * The shared parent-side mechanics for driving a child-JVM worker: fork the
 * process, read its merged stdout/stderr line by line, split structured
 * protocol lines (those carrying the worker's {@code prefix}) from passthrough
 * chatter, and wait for exit.
 *
 * <p>This collapses the fork + read-loop that each launch site used to
 * hand-roll. Callers build the command (the codec, spec format, and per-message
 * handling stay theirs) and supply two line callbacks.
 */
public final class WorkerProcess {

    private WorkerProcess() {}

    /**
     * Fork {@code command}, stream its output, and return the process exit code.
     *
     * @param command      full process command line (java exe, classpath/jar, main, args)
     * @param prefix       marker identifying protocol lines (e.g. {@code "##JKGIT:"})
     * @param onProtocol   receives each protocol line with the prefix stripped
     * @param onPassthrough receives each non-protocol line verbatim; may be {@code null} to drop them
     */
    public static int run(List<String> command, String prefix,
                          Consumer<String> onProtocol, Consumer<String> onPassthrough)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(prefix)) {
                    onProtocol.accept(line.substring(prefix.length()));
                } else if (onPassthrough != null) {
                    onPassthrough.accept(line);
                }
            }
        }
        return process.waitFor();
    }
}
