// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCommandTest {

    @Test
    void dir_prints_tools_root(@TempDir Path tempDir) {
        String stdout = capture(() -> Jk.execute("tool", "dir", "--home", tempDir.toString()));
        assertThat(stdout.trim()).isEqualTo(tempDir.resolve("tools").toString());
    }

    @Test
    void list_reports_empty_when_nothing_installed(@TempDir Path tempDir) {
        String stdout = capture(() -> Jk.execute("tool", "list", "--home", tempDir.toString()));
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

        String stdout = capture(() -> Jk.execute("tool", "list", "--home", tempDir.toString()));
        // Sorted alphabetically: alpha first, then widget.
        assertThat(stdout).containsSubsequence("alpha", "org.foo:alpha:0.1.0",
                "widget", "com.example:widget-cli:1.0.0");
        assertThat(stdout).contains(bin.resolve("widget").toString());
    }

    @Test
    void uninstall_removes_env_and_launcher(@TempDir Path tempDir) throws Exception {
        writeEnvJson(tempDir, "widget", "com.example:widget-cli:1.0.0");
        Path launcher = tempDir.resolve("bin").resolve("widget");
        Files.createDirectories(launcher.getParent());
        Files.writeString(launcher, "#!/bin/sh\n");

        int exit = Jk.execute("tool", "uninstall", "widget", "--home", tempDir.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(launcher).doesNotExist();
        assertThat(tempDir.resolve("tools/envs/widget")).doesNotExist();
    }

    @Test
    void uninstall_unknown_tool_is_a_noop(@TempDir Path tempDir) {
        String stdout = capture(() -> Jk.execute("tool", "uninstall", "ghost",
                "--home", tempDir.toString()));
        assertThat(stdout).contains("not installed");
    }

    private static void writeEnvJson(Path home, String bin, String coord) throws Exception {
        Path envDir = home.resolve("tools/envs/").resolve(bin);
        Files.createDirectories(envDir);
        Files.writeString(envDir.resolve("env.json"),
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
