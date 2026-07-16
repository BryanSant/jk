// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrustCommandTest {

    @Test
    void add_list_remove_flow(@TempDir Path state) {
        assertThat(Jk.execute("trust", "add", "--state-dir", state.toString(), "https://github.com/acme/"))
                .isEqualTo(0);
        String listed = capture(() -> Jk.execute("trust", "list", "--state-dir", state.toString()));
        assertThat(listed).contains("https://github.com/acme/");

        assertThat(Jk.execute("trust", "remove", "--state-dir", state.toString(), "https://github.com/acme/"))
                .isEqualTo(0);
        String empty = capture(() -> Jk.execute("trust", "list", "--state-dir", state.toString()));
        assertThat(empty).contains("No trusted sources");
    }

    @Test
    void add_rejects_non_urls(@TempDir Path state) {
        assertThat(Jk.execute("trust", "add", "--state-dir", state.toString(), "not-a-url"))
                .isEqualTo(64);
    }

    @Test
    void remove_of_unknown_prefix_is_a_usage_error(@TempDir Path state) {
        assertThat(Jk.execute("trust", "remove", "--state-dir", state.toString(), "https://nope.dev/"))
                .isEqualTo(64);
    }

    @Test
    void import_jbang_reads_the_json_list(@TempDir Path tmp) throws Exception {
        Path json = tmp.resolve("trusted-sources.json");
        Files.writeString(json, """
                [
                  // comment line
                  "https://github.com/jbangdev/",
                  "https://gist.github.com/max/"
                ]
                """);
        Path state = tmp.resolve("state");
        int exit = Jk.execute("trust", "import", "--jbang", "--file", json.toString(), "--state-dir", state.toString());
        assertThat(exit).isEqualTo(0);
        String listed = capture(() -> Jk.execute("trust", "list", "--state-dir", state.toString()));
        assertThat(listed).contains("https://github.com/jbangdev/").contains("https://gist.github.com/max/");
    }

    @Test
    void import_without_jbang_flag_is_a_usage_error(@TempDir Path state) {
        assertThat(Jk.execute("trust", "import", "--state-dir", state.toString()))
                .isEqualTo(64);
    }

    private static String capture(Runnable body) {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            body.run();
        } finally {
            System.setOut(original);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
