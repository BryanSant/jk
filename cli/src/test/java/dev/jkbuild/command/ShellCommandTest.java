// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ShellCommandTest {

    @Test
    void shell_errors_when_no_pin(@TempDir Path tempDir) throws IOException {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        int exit = Jk.execute("shell",
                "-C", project.toString(),
                "--jdks-dir", tempDir.resolve("jdks").toString());
        assertThat(exit).isEqualTo(2);
    }
}
