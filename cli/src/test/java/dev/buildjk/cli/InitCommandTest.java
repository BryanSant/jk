// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.config.BuildJkParser;
import dev.buildjk.lock.Lockfile;
import dev.buildjk.lock.LockfileReader;
import dev.buildjk.model.BuildJk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InitCommandTest {

    @Test
    void flag_mode_writes_files(@TempDir Path tempDir) throws IOException {
        int exit = Jk.execute(
                "init", "--group", "com.example", "--name", "widget", "--jdk", "25", tempDir.toString());
        assertThat(exit).isEqualTo(0);

        Path buildFile = tempDir.resolve("jk.toml");
        Path lockFile = tempDir.resolve("jk.lock");
        assertThat(buildFile).exists();
        assertThat(lockFile).exists();

        BuildJk parsed = BuildJkParser.parse(buildFile);
        assertThat(parsed.project().group()).isEqualTo("com.example");
        assertThat(parsed.project().artifact()).isEqualTo("widget");
        assertThat(parsed.project().version()).isEqualTo("0.1.0");
        assertThat(parsed.project().jdk()).isEqualTo("25");
        assertThat(parsed.project().language()).isEqualTo("java");

        Lockfile lock = LockfileReader.read(lockFile);
        assertThat(lock.version()).isEqualTo(Lockfile.CURRENT_VERSION);
        assertThat(lock.packages()).isEmpty();
    }

    @Test
    void refuses_to_overwrite_existing(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), "# existing");
        int exit = Jk.execute("init", tempDir.toString());
        assertThat(exit).isEqualTo(2);
        assertThat(Files.readString(tempDir.resolve("jk.toml"))).isEqualTo("# existing");
    }

    @Test
    void shadow_requires_main(@TempDir Path tempDir) {
        int exit = Jk.execute("init", "--shadow", tempDir.toString());
        assertThat(exit).isEqualTo(64);
    }

    @Test
    void runnable_with_main_writes_main_field(@TempDir Path tempDir) throws IOException {
        int exit = Jk.execute(
                "init",
                "--group", "com.example",
                "--name", "widget",
                "--main", "com.example.App",
                tempDir.toString());
        assertThat(exit).isEqualTo(0);

        BuildJk parsed = BuildJkParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.project().main()).isEqualTo("com.example.App");
        assertThat(parsed.project().isRunnable()).isTrue();
    }

    @Test
    void library_when_no_main(@TempDir Path tempDir) throws IOException {
        int exit = Jk.execute(
                "init",
                "--group", "com.example",
                "--name", "widget",
                tempDir.toString());
        assertThat(exit).isEqualTo(0);

        BuildJk parsed = BuildJkParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.project().main()).isNull();
        assertThat(parsed.project().isRunnable()).isFalse();
    }

    @Test
    void kotlin_lang_writes_kt_sample(@TempDir Path tempDir) throws IOException {
        int exit = Jk.execute(
                "init",
                "--group", "com.example",
                "--name", "widget",
                "--lang", "kotlin",
                "--main", "com.example.App",
                tempDir.toString());
        assertThat(exit).isEqualTo(0);

        Path app = tempDir.resolve("src/main/kotlin/com/example/App.kt");
        assertThat(app).exists();
        assertThat(Files.readString(app)).contains("fun main()");
    }

    @Test
    void non_tty_skips_wizard(@TempDir Path tempDir) throws IOException {
        var captured = new ByteArrayOutputStream();
        var prevOut = System.out;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Jk.execute("init", "--name", "foo", tempDir.toString());
        } finally {
            System.setOut(prevOut);
        }
        assertThat(exit).isEqualTo(0);
        assertThat(tempDir.resolve("jk.toml")).exists();
        // No JLine raw-mode escape sequences should appear on stdout in flag mode.
        var output = captured.toString(StandardCharsets.UTF_8);
        assertThat(output).doesNotContain("[?1049h"); // alt-screen toggle
        assertThat(output).doesNotContain("[6n"); // device status report (raw mode cursor query)
    }
}
