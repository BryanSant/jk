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

class ToolCommandTest {

    @Test
    void dir_prints_tools_root(@TempDir Path tempDir) {
        Path tools = tempDir.resolve("tools");
        String stdout = capture(() -> Jk.execute("tool", "dir", "--tools-dir", tools.toString()));
        assertThat(stdout.trim()).isEqualTo(tools.toString());
    }

    @Test
    void list_reports_empty_when_nothing_installed(@TempDir Path tempDir) {
        String stdout = capture(() -> Jk.execute(
                "tool",
                "list",
                "--state-dir",
                tempDir.toString(),
                "--bin-dir",
                tempDir.resolve("bin").toString()));
        assertThat(stdout).contains("No tools installed");
    }

    @Test
    void list_reports_installed_tools(@TempDir Path tempDir) throws Exception {
        writeEnvJson(tempDir, "widget", "com.example:widget-cli:1.0.0");
        writeEnvJson(tempDir, "alpha", "org.foo:alpha:0.1.0");
        // Add a launcher for one but not the other.
        Path bin = tempDir.resolve("bin");
        Files.createDirectories(bin);
        Files.writeString(bin.resolve("widget"), "#!/bin/sh\n");

        String stdout = capture(
                () -> Jk.execute("tool", "list", "--state-dir", tempDir.toString(), "--bin-dir", bin.toString()));
        // Sorted alphabetically: alpha first, then widget.
        assertThat(stdout)
                .containsSubsequence("alpha", "org.foo:alpha:0.1.0", "widget", "com.example:widget-cli:1.0.0");
        assertThat(stdout).contains(bin.resolve("widget").toString());
    }

    @Test
    void uninstall_removes_env_and_launcher(@TempDir Path tempDir) throws Exception {
        writeEnvJson(tempDir, "widget", "com.example:widget-cli:1.0.0");
        Path bin = tempDir.resolve("bin");
        Files.createDirectories(bin);
        Path launcher = bin.resolve("widget");
        Files.writeString(launcher, "#!/bin/sh\n");

        int exit = Jk.execute(
                "tool", "uninstall", "widget", "--state-dir", tempDir.toString(), "--bin-dir", bin.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(launcher).doesNotExist();
        assertThat(tempDir.resolve("tools/envs/widget")).doesNotExist();
    }

    @Test
    void uninstall_unknown_tool_is_a_noop(@TempDir Path tempDir) {
        String stdout = capture(() -> Jk.execute(
                "tool",
                "uninstall",
                "ghost",
                "--state-dir",
                tempDir.toString(),
                "--bin-dir",
                tempDir.resolve("bin").toString()));
        assertThat(stdout).contains("not installed");
    }

    @Test
    void exec_is_a_hidden_alias_of_run() {
        // `jk tool exec --help` renders `jk tool run`'s help (dotnet muscle memory).
        String stdout = capture(() -> Jk.execute("tool", "exec", "--help"));
        assertThat(stdout).contains("jk tool run");
        // Hidden per the hidden-surface policy: the parent's help lists only `run`.
        String toolHelp = capture(() -> Jk.execute("tool", "--help"));
        assertThat(toolHelp).doesNotContain("exec");
    }

    private static void writeEnvJson(Path home, String bin, String coord) throws Exception {
        Path envDir = home.resolve("tools/envs/").resolve(bin);
        Files.createDirectories(envDir);
        Files.writeString(
                envDir.resolve("env.json"),
                "{\n  \"binName\": \"" + bin + "\",\n  \"primary\": \"" + coord + "\"\n}\n");
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
