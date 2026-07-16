// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The command half of the harness (plan row 11): registration is recorded, describe declares it,
 * {@code op=command} runs the body against the spec's args and streams {@code command-out} lines, and
 * the body's return value is the worker's exit code.
 */
class BuildPluginHarnessCommandTest {

    /** A fixture plugin declaring one command that echoes its args and config. */
    private static final BuildPlugin FIXTURE = ctx -> ctx.command(PluginCommandSpec.named("hello-fixture")
            .description("echo the command args")
            .run(exec -> {
                exec.label("greeting");
                exec.out("hello " + String.join("+", exec.args()) + " from " + exec.config().id());
                return exec.args().contains("--fail") ? 3 : 0;
            }));

    @Test
    void describe_declares_the_verb(@TempDir Path dir) throws Exception {
        var out = capture();
        int exit = BuildPluginHarness.run(FIXTURE, List.of(spec(dir, "describe", List.of()).toString()), out.writer);
        assertThat(exit).isZero();
        assertThat(out.lines()).anyMatch(l ->
                l.contains("\"t\":\"command\"") && l.contains("hello-fixture") && l.contains("echo the command args"));
    }

    @Test
    void verb_op_runs_the_body_and_returns_its_exit(@TempDir Path dir) throws Exception {
        var out = capture();
        int exit = BuildPluginHarness.run(
                FIXTURE, List.of(spec(dir, "command", List.of("a", "b")).toString()), out.writer);
        assertThat(exit).isZero();
        assertThat(out.lines()).anyMatch(l -> l.contains("\"t\":\"command-out\"") && l.contains("hello a+b from fx"));

        var failing = capture();
        exit = BuildPluginHarness.run(
                FIXTURE, List.of(spec(dir, "command", List.of("--fail")).toString()), failing.writer);
        assertThat(exit).isEqualTo(3);
    }

    @Test
    void unknown_verb_reports_an_error(@TempDir Path dir) throws Exception {
        var out = capture();
        Path spec = dir.resolve("bad.spec");
        Files.write(spec, List.of("{\"t\":\"op\",\"op\":\"command\",\"step\":\"nope\",\"plugin\":\"fx\"}"));
        int exit = BuildPluginHarness.run(FIXTURE, List.of(spec.toString()), out.writer);
        assertThat(exit).isEqualTo(65);
        assertThat(out.lines()).anyMatch(l -> l.contains("unknown-command"));
    }

    private static Path spec(Path dir, String op, List<String> args) throws Exception {
        Path spec = dir.resolve(op + ".spec");
        StringBuilder argsArr = new StringBuilder("[");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) argsArr.append(',');
            argsArr.append('"').append(args.get(i)).append('"');
        }
        argsArr.append(']');
        Files.write(spec, List.of(
                "{\"t\":\"op\",\"op\":\"" + op + "\",\"step\":\"hello-fixture\",\"plugin\":\"fx\"}",
                "{\"t\":\"config\",\"key\":\"version\",\"kind\":\"string\",\"value\":\"1.0\"}",
                "{\"t\":\"project\",\"group\":\"g\",\"name\":\"n\",\"version\":\"1\",\"javaRelease\":25,"
                        + "\"nativeDeclared\":false,\"kotlin\":false}",
                "{\"t\":\"command-args\",\"values\":" + argsArr + "}"));
        return spec;
    }

    private static Captured capture() {
        var buffer = new ByteArrayOutputStream();
        return new Captured(buffer, new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##T:"));
    }

    private record Captured(ByteArrayOutputStream buffer, ProtocolWriter writer) {
        List<String> lines() {
            return List.of(buffer.toString(StandardCharsets.UTF_8).split("\n"));
        }
    }
}
