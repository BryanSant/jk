// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.worker;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.plugin.protocol.Ndjson;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link WorkerProcess} against a real forked JVM ({@link EchoWorkerMain}) — the first
 * end-to-end coverage of the fork + read-loop and the two-way stdin conversation the test-runner's
 * pull mode relies on.
 */
class WorkerProcessTest {

    private static List<String> cmd(String... extra) {
        String javaExe = System.getProperty("java.home") + "/bin/java";
        List<String> c = new ArrayList<>(
                List.of(javaExe, "-cp", System.getProperty("java.class.path"), "dev.jkbuild.worker.EchoWorkerMain"));
        c.addAll(List.of(extra));
        return c;
    }

    @Test
    void run_splits_protocol_from_passthrough_and_returns_exit_code() throws Exception {
        var events = new ArrayList<String>();
        var chatter = new ArrayList<String>();

        int exit = WorkerProcess.run(cmd("oneshot"), "##T:", json -> events.add(Ndjson.str(json, "e")), chatter::add);

        assertThat(exit).isZero();
        assertThat(events).containsExactly("a", "b");
        assertThat(chatter).contains("plain chatter");
    }

    @Test
    void run_drops_passthrough_when_sink_is_null() throws Exception {
        var events = new ArrayList<String>();
        int exit = WorkerProcess.run(cmd("oneshot"), "##T:", json -> events.add(Ndjson.str(json, "e")), null);
        assertThat(exit).isZero();
        assertThat(events).containsExactly("a", "b");
    }

    @Test
    void converse_drives_a_pull_queue_over_stdin() throws Exception {
        var queue = new ArrayDeque<>(List.of("alpha", "beta", "gamma"));
        var ran = new ArrayList<String>();
        var chatter = new ArrayList<String>();

        int exit = WorkerProcess.converse(
                cmd(),
                "##T:",
                (json, convo) -> {
                    String event = Ndjson.str(json, "e");
                    if ("ready".equals(event)) {
                        String next = queue.pollFirst();
                        if (next != null) {
                            convo.send("RUN " + next);
                        } else {
                            convo.send("DONE");
                            convo.closeInput();
                        }
                    } else if ("ran".equals(event)) {
                        ran.add(Ndjson.str(json, "what"));
                    }
                },
                chatter::add);

        assertThat(exit).isZero();
        assertThat(ran).containsExactly("alpha", "beta", "gamma");
        assertThat(chatter).contains("plain chatter line");
    }

    @Test
    void converse_exits_cleanly_when_queue_is_empty_immediately() throws Exception {
        // First ready → empty queue → DONE/closeInput, no RUN ever sent.
        var ran = new ArrayList<String>();
        int exit = WorkerProcess.converse(
                cmd(),
                "##T:",
                (json, convo) -> {
                    if ("ready".equals(Ndjson.str(json, "e"))) {
                        convo.send("DONE");
                        convo.closeInput();
                    } else if ("ran".equals(Ndjson.str(json, "e"))) {
                        ran.add(Ndjson.str(json, "what"));
                    }
                },
                null);
        assertThat(exit).isZero();
        assertThat(ran).isEmpty();
    }
}
