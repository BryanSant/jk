// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellCommandTest {

    @Test
    void shell_errors_instead_of_spawning_a_blocking_subshell(@TempDir Path tempDir) throws IOException {
        // jk shell only makes sense attached to a terminal. A test runner (Gradle
        // or jk's own worker JVM) has no console, so the command must error (exit
        // 2) rather than spawn an interactive $SHELL that blocks on a stdin which
        // never EOFs. Deterministic regardless of ambient JAVA_HOME/JK_JDK — it
        // does not depend on whether a JDK happens to resolve in the environment.
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        int exit = Jk.execute(
                "shell",
                "-C",
                project.toString(),
                "--jdks-dir",
                tempDir.resolve("jdks").toString());
        assertThat(exit).isEqualTo(2);
    }
}
