// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.discovery;

import dev.jkbuild.jdk.JdkHit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GradleProbeTest {

    @Test
    void discovers_jdks_nested_one_level_under_gradle_jdks(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve(".gradle/jdks");
        // Gradle layout: ~/.gradle/jdks/<hash>/<jdk-dir>/
        makeJdk(root.resolve("eclipse_temurin-21-amd64-linux.2/jdk-21.0.5+11"), "21.0.5");
        makeJdk(root.resolve("oracle-25-amd64-linux.7/graalvm-jdk-25.0.3"), "25.0.3");

        List<JdkHit> hits = new GradleProbe(root).discoverAllJdks();

        assertThat(hits).hasSize(2);
        assertThat(hits).allSatisfy(h -> assertThat(h.source()).isEqualTo("gradle"));
        assertThat(hits).extracting(JdkHit::version).containsExactlyInAnyOrder("21.0.5", "25.0.3");
    }

    @Test
    void empty_when_root_absent(@TempDir Path tmp) throws IOException {
        assertThat(new GradleProbe(tmp.resolve("nope")).discoverAllJdks()).isEmpty();
    }

    private static void makeJdk(Path home, String version) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake\n");
        Files.writeString(home.resolve("release"),
                "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
    }
}
