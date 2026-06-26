// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.repo.RepoArtifactStore;
import dev.jkbuild.util.JkVersion;
import dev.jkbuild.worker.WorkerJar;
import dev.jkbuild.worker.WorkerJarNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Worker-jar location: system-property override → repos/local/ → repos/central/ → clear error.
 *
 * <p>Tests use a temp cache root so the developer's real ~/.jk/cache is never touched. The
 * coordinate-based lookup resolves {@code dev.jkbuild:jk-kotlin-compiler:<version>} from the named
 * repo stores; the worker jar content is irrelevant to location, only its presence at the expected
 * m2 path (with .sha256 sidecar) matters.
 */
class KotlinWorkerSetupTest {

    private static final String VERSION = JkVersion.VERSION;
    private static final String M2_PATH =
            "dev/jkbuild/jk-kotlin-compiler/" + VERSION + "/jk-kotlin-compiler-" + VERSION + ".jar";

    @Test
    void locates_worker_in_repos_local(@TempDir Path dir) throws IOException {
        Cas cas = new Cas(dir);
        // Populate repos/local/ with a stand-in worker jar + sidecar.
        RepoArtifactStore local = new RepoArtifactStore(dir, "local");
        Path artifact = dir.resolve("repos/local").resolve(M2_PATH);
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "stand-in worker jar");
        Files.writeString(Path.of(artifact + ".sha256"), "deadbeef");

        assertThat(WorkerJar.KOTLIN_COMPILER.locate(cas)).isEqualTo(artifact);
    }

    @Test
    void throws_with_clear_hint_when_absent(@TempDir Path dir) {
        Cas cas = new Cas(dir);
        assertThatThrownBy(() -> WorkerJar.KOTLIN_COMPILER.locate(cas)).isInstanceOf(WorkerJarNotFoundException.class);
    }

    @Test
    void system_property_overrides_repos_lookup(@TempDir Path dir) throws IOException {
        Path jar = Files.writeString(dir.resolve("override.jar"), "x");
        Cas emptyCas = new Cas(dir.resolve("cas"));
        String prev = System.getProperty(KotlinWorkerSetup.WORKER_JAR_PROPERTY);
        System.setProperty(KotlinWorkerSetup.WORKER_JAR_PROPERTY, jar.toString());
        try {
            assertThat(WorkerJar.KOTLIN_COMPILER.locate(emptyCas)).isEqualTo(jar);
        } finally {
            if (prev == null) System.clearProperty(KotlinWorkerSetup.WORKER_JAR_PROPERTY);
            else System.setProperty(KotlinWorkerSetup.WORKER_JAR_PROPERTY, prev);
        }
    }
}
