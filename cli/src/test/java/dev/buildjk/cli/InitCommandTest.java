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
    void existing_jk_toml_emits_styled_error(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), "# existing");
        var captured = new ByteArrayOutputStream();
        var prevErr = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Jk.execute("init", tempDir.toString());
        } finally {
            System.setErr(prevErr);
        }
        assertThat(exit).isEqualTo(2);
        assertThat(Files.readString(tempDir.resolve("jk.toml"))).isEqualTo("# existing");

        var err = captured.toString(StandardCharsets.UTF_8);
        assertThat(err).contains("Jk");
        assertThat(err).contains("Failed to initialize a new project");
        assertThat(err).contains("already exists");
        // Project name is the leaf of the target dir.
        assertThat(err).contains(tempDir.getFileName().toString());
        // Yellow + hot pink ANSI codes present.
        assertThat(err).contains("\033[38;2;255;105;180m"); // hot pink "Jk"
        assertThat(err).contains("\033[38;2;234;179;8m");   // yellow ⚠ + name
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
    void wizard_preset_name_is_empty_when_no_positional() {
        assertThat(InitCommand.wizardPresetName(null, Path.of("/home/bob/myapp"))).isEmpty();
    }

    @Test
    void wizard_preset_name_uses_cwd_leaf_for_dot_arg() {
        assertThat(InitCommand.wizardPresetName(Path.of("."), Path.of("/home/bob/myapp")))
                .contains("myapp");
    }

    @Test
    void wizard_preset_name_uses_arg_leaf_for_named_arg() {
        // Relative arg.
        assertThat(InitCommand.wizardPresetName(Path.of("my-project"), Path.of("/home/bob")))
                .contains("my-project");
        // Absolute path: still use the leaf, not the full path.
        assertThat(InitCommand.wizardPresetName(Path.of("/tmp/foo/my-project"), Path.of("/home/bob")))
                .contains("my-project");
    }

    @Test
    void wizard_preset_name_falls_back_when_dot_at_filesystem_root() {
        // Defensive: filesystem root has no leaf name; don't blow up.
        assertThat(InitCommand.wizardPresetName(Path.of("."), Path.of("/"))).isEmpty();
    }

    @Test
    void resolve_target_with_dot_uses_cwd() {
        assertThat(InitCommand.resolveTarget(Path.of("."), Path.of("/home/bob/myapp"), "myapp"))
                .isEqualTo(Path.of("/home/bob/myapp"));
    }

    @Test
    void resolve_target_with_relative_arg_resolves_against_cwd() {
        assertThat(InitCommand.resolveTarget(
                Path.of("widget"), Path.of("/home/bob"), "widget"))
                .isEqualTo(Path.of("/home/bob/widget"));
    }

    @Test
    void resolve_target_with_absolute_arg_uses_it_as_is() {
        assertThat(InitCommand.resolveTarget(
                Path.of("/tmp/foo/widget"), Path.of("/home/bob"), "widget"))
                .isEqualTo(Path.of("/tmp/foo/widget"));
    }

    @Test
    void resolve_target_with_no_arg_creates_subdir_named_after_project() {
        assertThat(InitCommand.resolveTarget(null, Path.of("/home/bob"), "myapp"))
                .isEqualTo(Path.of("/home/bob/myapp"));
    }

    @Test
    void named_positional_uses_arg_as_artifact_name(@TempDir Path tempDir) throws IOException {
        // `jk init <abs-path>/my-project` → artifact = "my-project" (the leaf).
        Path target = tempDir.resolve("my-project");
        int exit = Jk.execute("init", "--group", "com.example", target.toString());
        assertThat(exit).isEqualTo(0);

        BuildJk parsed = BuildJkParser.parse(target.resolve("jk.toml"));
        assertThat(parsed.project().artifact()).isEqualTo("my-project");
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
