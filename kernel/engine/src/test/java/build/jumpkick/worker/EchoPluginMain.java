// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.worker;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * A cooperating subprocess used by {@link PluginProcessTest} to exercise {@link PluginProcess}
 * against a real forked JVM. Two modes:
 *
 * <ul>
 *   <li>{@code oneshot} — emit a passthrough line and two protocol events, then exit. Drives {@link
 *       PluginProcess#run}.
 *   <li>(default) <b>pull</b> — emit a passthrough line and an initial {@code ready}, then loop on
 *       stdin: {@code RUN <x>} echoes a {@code ran} event for {@code x} followed by another {@code
 *       ready}; {@code DONE} or EOF exits. Drives {@link PluginProcess#converse}.
 * </ul>
 *
 * Protocol prefix is {@code ##T:}.
 */
public final class EchoPluginMain {

    private EchoPluginMain() {}

    public static void main(String[] args) throws IOException {
        PrintStream out =
                new PrintStream(new FileOutputStream(FileDescriptor.out), /* autoFlush */ true, StandardCharsets.UTF_8);

        if (args.length > 0 && args[0].equals("oneshot")) {
            out.println("plain chatter");
            out.println("##T:{\"e\":\"a\"}");
            out.println("##T:{\"e\":\"b\"}");
            return;
        }

        // pull mode
        out.println("plain chatter line");
        out.println("##T:{\"e\":\"ready\"}");
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals("DONE")) break;
                if (line.startsWith("RUN ")) {
                    String what = line.substring(4);
                    out.println("##T:{\"e\":\"ran\",\"what\":\"" + what + "\"}");
                    out.println("##T:{\"e\":\"ready\"}");
                }
            }
        }
    }
}
