// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import build.jumpkick.cache.Cas;
import build.jumpkick.repo.RepoArtifactStore;
import build.jumpkick.model.JkVersion;
import build.jumpkick.worker.PluginJar;
import build.jumpkick.worker.PluginJarNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Worker-jar location: system-property override → repos/local/ → repos/central/ → clear error.
 *
 * <p>Tests use a temp cache root so the developer's real ~/.jk/cache is never touched. The
 * coordinate-based lookup resolves {@code build.jumpkick:jk-kotlin-compiler:<version>} from the named
 * repo stores; the worker jar content is irrelevant to location, only its presence at the expected
 * m2 path (with .sha256 sidecar) matters.
 */
class KotlinWorkerSetupTest {

    private static final String VERSION = JkVersion.VERSION;
    private static final String M2_PATH =
            "build/jumpkick/jk-kotlin-compiler/" + VERSION + "/jk-kotlin-compiler-" + VERSION + ".jar";

    @Test
    void locates_worker_in_repos_local(@TempDir Path dir) throws IOException {
        Cas cas = new Cas(dir);
        // Populate repos/local/ with a stand-in worker jar + sidecar.
        RepoArtifactStore local = new RepoArtifactStore(dir, "local");
        Path artifact = dir.resolve("repos/local").resolve(M2_PATH);
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "stand-in worker jar");
        Files.writeString(Path.of(artifact + ".sha256"), "deadbeef");

        withoutOverride(() -> assertThat(PluginJar.KOTLIN_COMPILER.locate(cas)).isEqualTo(artifact));
    }

    @Test
    void throws_with_clear_hint_when_absent(@TempDir Path dir) {
        Cas cas = new Cas(dir);
        withoutOverride(() -> assertThatThrownBy(() -> PluginJar.KOTLIN_COMPILER.locate(cas))
                .isInstanceOf(PluginJarNotFoundException.class));
    }

    /**
     * The engine test JVM sets the worker-jar override so the KSP/Room/Hilt gates can fork real
     * kotlinc (see kernel/engine/build.gradle.kts); these two tests exercise the repos lookup
     * BELOW the override, so it must be absent for their duration.
     */
    private static void withoutOverride(Runnable body) {
        String prev = System.getProperty(KotlinWorkerSetup.WORKER_JAR_PROPERTY);
        System.clearProperty(KotlinWorkerSetup.WORKER_JAR_PROPERTY);
        try {
            body.run();
        } finally {
            if (prev != null) System.setProperty(KotlinWorkerSetup.WORKER_JAR_PROPERTY, prev);
        }
    }

    @Test
    void system_property_overrides_repos_lookup(@TempDir Path dir) throws IOException {
        Path jar = Files.writeString(dir.resolve("override.jar"), "x");
        Cas emptyCas = new Cas(dir.resolve("cas"));
        String prev = System.getProperty(KotlinWorkerSetup.WORKER_JAR_PROPERTY);
        System.setProperty(KotlinWorkerSetup.WORKER_JAR_PROPERTY, jar.toString());
        try {
            assertThat(PluginJar.KOTLIN_COMPILER.locate(emptyCas)).isEqualTo(jar);
        } finally {
            if (prev == null) System.clearProperty(KotlinWorkerSetup.WORKER_JAR_PROPERTY);
            else System.setProperty(KotlinWorkerSetup.WORKER_JAR_PROPERTY, prev);
        }
    }
}
