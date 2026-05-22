// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.hocon.BuildJkParser;
import dev.buildjk.lock.Lockfile;
import dev.buildjk.lock.LockfileReader;
import dev.buildjk.model.BuildJk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InitCommandTest {

    @Test
    void writes_build_jk_and_lockfile(@TempDir Path tempDir) throws IOException {
        int exit = Jk.execute(
                "init", "--group", "com.example", "--name", "widget", "--jdk", "25", tempDir.toString());
        assertThat(exit).isEqualTo(0);

        Path buildFile = tempDir.resolve("build.jk");
        Path lockFile = tempDir.resolve("jk.lock");
        assertThat(buildFile).exists();
        assertThat(lockFile).exists();

        BuildJk parsed = BuildJkParser.parse(buildFile);
        assertThat(parsed.project().group()).isEqualTo("com.example");
        assertThat(parsed.project().artifact()).isEqualTo("widget");
        assertThat(parsed.project().version()).isEqualTo("0.1.0");
        assertThat(parsed.project().jdk()).isEqualTo("25");

        Lockfile lock = LockfileReader.read(lockFile);
        assertThat(lock.version()).isEqualTo(Lockfile.CURRENT_VERSION);
        assertThat(lock.packages()).isEmpty();
    }

    @Test
    void refuses_to_overwrite_existing(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("build.jk"), "# existing");
        int exit = Jk.execute("init", tempDir.toString());
        assertThat(exit).isEqualTo(2);
        assertThat(Files.readString(tempDir.resolve("build.jk"))).isEqualTo("# existing");
    }

    @Test
    void lib_and_bin_are_mutually_exclusive(@TempDir Path tempDir) {
        int exit = Jk.execute("init", "--lib", "--bin", tempDir.toString());
        assertThat(exit).isEqualTo(64);
    }
}
