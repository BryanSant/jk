// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeImageDriverTest {

    private static final Path BIN = Path.of("/opt/graalvm/bin/native-image");

    @Test
    void executable_command_ends_with_the_main_class_and_has_no_shared_flag() {
        var req = new NativeImageDriver.Request(
                Path.of("/opt/graalvm"),
                List.of(Path.of("app.jar")),
                "com.example.Main",
                Path.of("target/widget"),
                List.of("--verbose"));
        List<String> cmd = NativeImageDriver.buildCommand(BIN, req);

        assertThat(cmd).doesNotContain("--shared");
        assertThat(cmd).contains("--no-fallback", "--verbose");
        assertThat(cmd.getLast()).isEqualTo("com.example.Main");
        assertThat(cmd)
                .containsSequence(
                        "-o", Path.of("target/widget").toAbsolutePath().toString());
    }

    @Test
    void shared_library_command_has_shared_flag_and_no_main_class() {
        var req = new NativeImageDriver.Request(
                Path.of("/opt/graalvm"),
                List.of(Path.of("app.jar")),
                null,
                Path.of("target/libwidget"),
                List.of(), /*shared*/
                true);
        List<String> cmd = NativeImageDriver.buildCommand(BIN, req);

        assertThat(cmd).contains("--shared");
        // No trailing main class: the command ends at the output / fixed flags.
        assertThat(cmd).doesNotContain("com.example.Main");
        assertThat(cmd.getLast()).isEqualTo("--no-fallback");
        assertThat(cmd)
                .containsSequence(
                        "-o", Path.of("target/libwidget").toAbsolutePath().toString());
    }

    @Test
    void executable_request_requires_a_main_class() {
        assertThat(catchThrowable(() -> new NativeImageDriver.Request(
                        Path.of("/opt/graalvm"), List.of(), null, Path.of("target/x"), List.of())))
                .isInstanceOf(NullPointerException.class);
    }

    private static Throwable catchThrowable(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
