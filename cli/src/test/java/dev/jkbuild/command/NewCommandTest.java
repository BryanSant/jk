// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;

import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NewCommandTest {

    @Test
    void flag_mode_writes_files(@TempDir Path tempDir) throws IOException {
        int exit = Jk.execute(
                "new", "--group", "com.example", "--name", "widget", "--jdk", "25", tempDir.toString());
        assertThat(exit).isEqualTo(0);

        Path buildFile = tempDir.resolve("jk.toml");
        Path lockFile = tempDir.resolve("jk.lock");
        assertThat(buildFile).exists();
        assertThat(lockFile).exists();

        JkBuild parsed = JkBuildParser.parse(buildFile);
        assertThat(parsed.project().group()).isEqualTo("com.example");
        assertThat(parsed.project().name()).isEqualTo("widget");
        assertThat(parsed.project().version()).isEqualTo("0.1.0");
        assertThat(parsed.project().jdk()).isEqualTo(25);
        assertThat(parsed.project().java()).isEqualTo(25);
        assertThat(parsed.project().isKotlin()).isFalse();

        Lockfile lock = LockfileReader.read(lockFile);
        assertThat(lock.version()).isEqualTo(Lockfile.CURRENT_VERSION);
        assertThat(lock.artifacts()).isEmpty();
    }

    @Test
    void existing_jk_toml_emits_styled_error(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), "# existing");
        var captured = new ByteArrayOutputStream();
        var prevErr = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Jk.execute("new", tempDir.toString());
        } finally {
            System.setErr(prevErr);
        }
        assertThat(exit).isEqualTo(2);
        assertThat(Files.readString(tempDir.resolve("jk.toml"))).isEqualTo("# existing");

        var err = captured.toString(StandardCharsets.UTF_8);
        assertThat(err).contains("Failed to create project");
        assertThat(err).contains("already exists");
        // Project name is the leaf of the target dir.
        assertThat(err).contains(tempDir.getFileName().toString());
        // Some ANSI escape codes are present (styled output).
        assertThat(err).contains("\033[");
    }

    @Test
    void shadow_implies_executable(@TempDir Path tempDir) throws IOException {
        // --shadow used to require an explicit --main; now it just implies
        // --executable and the generated Main FQCN is derived from the group.
        int exit = Jk.execute(
                "new",
                "--group", "com.example",
                "--name", "widget",
                "--shadow",
                "--layout", "traditional",
                tempDir.toString());
        assertThat(exit).isEqualTo(0);

        JkBuild parsed = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.project().main()).isEqualTo("com.example.Main");
        assertThat(parsed.project().isRunnable()).isTrue();
    }

    @Test
    void executable_writes_derived_main_field(@TempDir Path tempDir) throws IOException {
        int exit = Jk.execute(
                "new",
                "--group", "com.example",
                "--name", "widget",
                "--executable",
                "--layout", "traditional",
                tempDir.toString());
        assertThat(exit).isEqualTo(0);

        JkBuild parsed = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.project().main()).isEqualTo("com.example.Main");
        assertThat(parsed.project().isRunnable()).isTrue();
    }

    @Test
    void library_when_no_main(@TempDir Path tempDir) throws IOException {
        int exit = Jk.execute(
                "new",
                "--group", "com.example",
                "--name", "widget",
                tempDir.toString());
        assertThat(exit).isEqualTo(0);

        JkBuild parsed = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.project().main()).isNull();
        assertThat(parsed.project().isRunnable()).isFalse();
    }

    @Test
    void kotlin_lang_writes_kt_sample(@TempDir Path tempDir) throws IOException {
        int exit = Jk.execute(
                "new",
                "--group", "com.example",
                "--name", "widget",
                "--lang", "kotlin",
                "--executable",
                tempDir.toString());
        assertThat(exit).isEqualTo(0);

        // Default layout is simple: Main.kt lands at ./src/Main.kt with no package.
        Path app = tempDir.resolve("src/Main.kt");
        assertThat(app).exists();
        assertThat(Files.readString(app)).contains("fun main()");
    }

    @Test
    void wizard_preset_name_is_empty_when_no_positional() {
        assertThat(NewCommand.wizardPresetName(null, Path.of("/home/bob/myapp"))).isEmpty();
    }

    @Test
    void wizard_preset_name_uses_cwd_leaf_for_dot_arg() {
        assertThat(NewCommand.wizardPresetName(Path.of("."), Path.of("/home/bob/myapp")))
                .contains("myapp");
    }

    @Test
    void wizard_preset_name_uses_arg_leaf_for_named_arg() {
        // Relative arg.
        assertThat(NewCommand.wizardPresetName(Path.of("my-project"), Path.of("/home/bob")))
                .contains("my-project");
        // Absolute path: still use the leaf, not the full path.
        assertThat(NewCommand.wizardPresetName(Path.of("/tmp/foo/my-project"), Path.of("/home/bob")))
                .contains("my-project");
    }

    @Test
    void wizard_preset_name_falls_back_when_dot_at_filesystem_root() {
        // Defensive: filesystem root has no leaf name; don't blow up.
        assertThat(NewCommand.wizardPresetName(Path.of("."), Path.of("/"))).isEmpty();
    }

    @Test
    void resolve_target_with_dot_uses_cwd() {
        assertThat(NewCommand.resolveTarget(Path.of("."), Path.of("/home/bob/myapp"), "myapp"))
                .isEqualTo(Path.of("/home/bob/myapp"));
    }

    @Test
    void resolve_target_with_relative_arg_resolves_against_cwd() {
        assertThat(NewCommand.resolveTarget(
                Path.of("widget"), Path.of("/home/bob"), "widget"))
                .isEqualTo(Path.of("/home/bob/widget"));
    }

    @Test
    void resolve_target_with_absolute_arg_uses_it_as_is() {
        assertThat(NewCommand.resolveTarget(
                Path.of("/tmp/foo/widget"), Path.of("/home/bob"), "widget"))
                .isEqualTo(Path.of("/tmp/foo/widget"));
    }

    @Test
    void resolve_target_with_no_arg_creates_subdir_named_after_project() {
        assertThat(NewCommand.resolveTarget(null, Path.of("/home/bob"), "myapp"))
                .isEqualTo(Path.of("/home/bob/myapp"));
    }

    @Test
    void named_positional_uses_arg_as_project_name(@TempDir Path tempDir) throws IOException {
        // `jk init <abs-path>/my-project` → name = "my-project" (the leaf).
        Path target = tempDir.resolve("my-project");
        int exit = Jk.execute("new", "--group", "com.example", target.toString());
        assertThat(exit).isEqualTo(0);

        JkBuild parsed = JkBuildParser.parse(target.resolve("jk.toml"));
        assertThat(parsed.project().name()).isEqualTo("my-project");
    }

    @Test
    void new_inside_workspace_registers_member_and_skips_lock(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "dev.jkbuild"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                members = ["core"]
                """);
        Path app = tempDir.resolve("app");

        int exit = Jk.execute("new", "--name", "app", app.toString());
        assertThat(exit).isEqualTo(0);

        // Member project scaffolded, but with NO per-member lock (root owns it).
        assertThat(app.resolve("jk.toml")).exists();
        assertThat(app.resolve("jk.lock")).doesNotExist();

        // Group inherited from the workspace root.
        JkBuild member = JkBuildParser.parse(app.resolve("jk.toml"));
        assertThat(member.project().group()).isEqualTo("dev.jkbuild");
        assertThat(member.project().name()).isEqualTo("app");

        // Registered in the root [workspace].members.
        JkBuild root = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(root.workspace().members()).containsExactly("core", "app");
    }

    @Test
    void non_tty_skips_wizard(@TempDir Path tempDir) throws IOException {
        var captured = new ByteArrayOutputStream();
        var prevOut = System.out;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = Jk.execute("new", "--name", "foo", tempDir.toString());
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
