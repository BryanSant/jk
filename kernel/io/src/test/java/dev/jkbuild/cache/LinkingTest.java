// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LinkingTest {

    @Test
    void same_filesystem_creates_a_hard_link(@TempDir Path tempDir) throws IOException {
        Path src = Files.writeString(tempDir.resolve("src"), "hello");
        Path dst = tempDir.resolve("dst");

        Linking.linkOrCopy(src, dst);

        assertThat(Files.exists(dst)).isTrue();
        assertThat(Files.readString(dst)).isEqualTo("hello");
        // Same-fs link → same inode (BasicFileAttributes.fileKey() is the
        // best portable proxy we have for "shared inode").
        var srcKey = Files.readAttributes(src, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        var dstKey = Files.readAttributes(dst, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        assertThat(dstKey).as("hard-link should share an inode with the source").isEqualTo(srcKey);
    }

    @Test
    void replaces_existing_target(@TempDir Path tempDir) throws IOException {
        Path src = Files.writeString(tempDir.resolve("src"), "new");
        Path dst = Files.writeString(tempDir.resolve("dst"), "old");

        Linking.linkOrCopy(src, dst);

        assertThat(Files.readString(dst)).isEqualTo("new");
    }

    @Test
    void creates_parent_directories(@TempDir Path tempDir) throws IOException {
        Path src = Files.writeString(tempDir.resolve("src"), "hi");
        Path dst = tempDir.resolve("a").resolve("b").resolve("c").resolve("dst");

        Linking.linkOrCopy(src, dst);

        assertThat(Files.exists(dst)).isTrue();
        assertThat(Files.readString(dst)).isEqualTo("hi");
    }
}
