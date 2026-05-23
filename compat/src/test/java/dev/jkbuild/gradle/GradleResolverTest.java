// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.gradle;

import dev.jkbuild.compat.BuildTool;
import dev.jkbuild.compat.ToolDistribution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GradleResolverTest {

    @Test
    void no_wrapper_returns_default(@TempDir Path projectDir) throws Exception {
        ToolDistribution dist = new GradleResolver().resolve(projectDir);
        assertThat(dist.tool()).isEqualTo(BuildTool.GRADLE);
        assertThat(dist.version()).isEqualTo(GradleResolver.DEFAULT_VERSION);
        assertThat(dist.archiveType()).isEqualTo("zip");
    }

    @Test
    void wrapper_bin_zip_is_honored(@TempDir Path projectDir) throws Exception {
        writeWrapper(projectDir,
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.0.0-bin.zip\n");

        ToolDistribution dist = new GradleResolver().resolve(projectDir);
        assertThat(dist.version()).isEqualTo("9.0.0");
        assertThat(dist.downloadUri().toString()).contains("gradle-9.0.0-bin.zip");
    }

    @Test
    void wrapper_all_variant_recognized(@TempDir Path projectDir) throws Exception {
        writeWrapper(projectDir,
                "distributionUrl=https://services.gradle.org/distributions/gradle-8.10-all.zip\n");

        ToolDistribution dist = new GradleResolver().resolve(projectDir);
        assertThat(dist.version()).isEqualTo("8.10");
    }

    @Test
    void wrapper_sha256_is_propagated(@TempDir Path projectDir) throws Exception {
        writeWrapper(projectDir,
                "distributionUrl=https://services.gradle.org/distributions/gradle-9.5.1-bin.zip\n"
                + "distributionSha256Sum=deadbeef\n");

        ToolDistribution dist = new GradleResolver().resolve(projectDir);
        assertThat(dist.sha256()).isEqualTo("deadbeef");
    }

    private static void writeWrapper(Path projectDir, String content) throws Exception {
        Path props = projectDir.resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, content);
    }
}
