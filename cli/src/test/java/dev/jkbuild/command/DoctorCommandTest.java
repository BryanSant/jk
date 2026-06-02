// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisabledOnOs(OS.WINDOWS) // symlink creation requires Developer Mode on Windows
class DoctorCommandTest {

    @Test
    void prunes_broken_links_in_build_tool_tree(@TempDir Path tempDir) throws Exception {
        // Synthetic maven install pointing at a missing target.
        Path mavenSlug = tempDir.resolve("maven");
        Files.createDirectories(mavenSlug);
        Path link = mavenSlug.resolve("3.9.9");
        Files.createSymbolicLink(link, tempDir.resolve("nonexistent"));

        String stdout = capture(() -> Jk.execute("doctor", "--tools-dir", tempDir.toString()));
        assertThat(stdout).contains("pruned: maven 3.9.9");
        assertThat(stdout).contains("1 pruned");
        assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    void reports_ok_for_healthy_local_install(@TempDir Path tempDir) throws Exception {
        // A real (not-link) gradle dir under the build-tools tree.
        Path home = tempDir.resolve("gradle").resolve("9.5.1");
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin/gradle"), "#!/bin/sh\n");

        String stdout = capture(() -> Jk.execute("doctor", "--tools-dir", tempDir.toString()));
        assertThat(stdout).contains("ok:       gradle 9.5.1");
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
