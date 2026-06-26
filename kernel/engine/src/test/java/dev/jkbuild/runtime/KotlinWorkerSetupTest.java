// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.worker.WorkerJarNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Worker-jar location: system-property override → CAS-by-SHA → clear error.
 * (Runtime's test JVM doesn't set the property, so the CAS path is exercised
 * directly; the worker jar's content is irrelevant to location, only its
 * presence at {@code cas.pathFor(expectedSha)}.)
 */
class KotlinWorkerSetupTest {

    @Test
    void locates_worker_in_cas_by_expected_sha(@TempDir Path dir) throws IOException {
        Cas cas = new Cas(dir.resolve("cas"));
        Path target = cas.pathFor(expectedHash());
        Files.createDirectories(target.getParent());
        Files.writeString(target, "stand-in worker jar");

        assertThat(KotlinWorkerSetup.locateWorkerJar(cas)).isEqualTo(target);
    }

    @Test
    void throws_with_sideload_hint_when_absent(@TempDir Path dir) {
        Cas cas = new Cas(dir.resolve("cas"));
        assertThatThrownBy(() -> KotlinWorkerSetup.locateWorkerJar(cas))
                .isInstanceOf(WorkerJarNotFoundException.class)
                .satisfies(ex -> {
                    WorkerJarNotFoundException e = (WorkerJarNotFoundException) ex;
                    assertThat(e.sha()).isEqualTo(expectedHashUnchecked());
                });
    }

    @Test
    void system_property_overrides_the_cas(@TempDir Path dir) throws IOException {
        Path jar = Files.writeString(dir.resolve("override.jar"), "x");
        Cas emptyCas = new Cas(dir.resolve("cas"));
        String prev = System.getProperty(KotlinWorkerSetup.WORKER_JAR_PROPERTY);
        System.setProperty(KotlinWorkerSetup.WORKER_JAR_PROPERTY, jar.toString());
        try {
            assertThat(KotlinWorkerSetup.locateWorkerJar(emptyCas)).isEqualTo(jar);
        } finally {
            if (prev == null) System.clearProperty(KotlinWorkerSetup.WORKER_JAR_PROPERTY);
            else System.setProperty(KotlinWorkerSetup.WORKER_JAR_PROPERTY, prev);
        }
    }

    private static String expectedHash() throws IOException {
        try (InputStream in = KotlinWorkerSetup.class.getResourceAsStream("/META-INF/jk-kotlin-compiler-sha256.txt")) {
            assertThat(in).as("worker-sha resource present").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private static String expectedHashUnchecked() {
        try {
            return expectedHash();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
