// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkGarbageTest {

    private static Path dir(Path root, String name) throws IOException {
        Path d = root.resolve(name);
        Files.createDirectories(d.resolve("bin"));
        Files.writeString(d.resolve("bin").resolve("java"), "x");
        return d;
    }

    @Test
    void enqueue_then_drain_deletes_and_clears_queue(@TempDir Path tmp) throws IOException {
        Path jdks = tmp.resolve("jdks");
        Files.createDirectories(jdks);
        Path old = dir(jdks, "temurin-25.0.3");

        JdkGarbage g = new JdkGarbage(jdks);
        g.enqueue(old);
        assertThat(jdks.resolve(".to-be-removed")).exists();

        g.drain();
        assertThat(old).doesNotExist();
        assertThat(jdks.resolve(".to-be-removed")).doesNotExist(); // nothing left → queue removed
    }

    @Test
    void never_enqueues_or_deletes_outside_the_jdk_root(@TempDir Path tmp) throws IOException {
        Path jdks = tmp.resolve("jdks");
        Files.createDirectories(jdks);
        Path outside = tmp.resolve("outside");
        Files.createDirectories(outside);

        JdkGarbage g = new JdkGarbage(jdks);
        g.enqueue(outside);
        assertThat(jdks.resolve(".to-be-removed")).doesNotExist(); // refused
        g.drain();
        assertThat(outside).exists();
    }

    @Test
    void drain_is_a_no_op_without_a_queue(@TempDir Path tmp) {
        assertThatCode(() -> new JdkGarbage(tmp).drain()).doesNotThrowAnyException();
    }
}
