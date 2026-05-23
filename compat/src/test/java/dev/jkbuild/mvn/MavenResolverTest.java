// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.mvn;

import dev.jkbuild.compat.BuildTool;
import dev.jkbuild.compat.ToolDistribution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MavenResolverTest {

    @Test
    void no_wrapper_returns_default(@TempDir Path projectDir) throws Exception {
        ToolDistribution dist = new MavenResolver().resolve(projectDir);
        assertThat(dist.tool()).isEqualTo(BuildTool.MAVEN);
        assertThat(dist.version()).isEqualTo(MavenResolver.DEFAULT_VERSION);
        assertThat(dist.archiveType()).isEqualTo("zip");
        assertThat(dist.downloadUri().toString())
                .contains("apache-maven-" + MavenResolver.DEFAULT_VERSION + "-bin.zip");
    }

    @Test
    void wrapper_zip_url_is_honored(@TempDir Path projectDir) throws Exception {
        writeWrapper(projectDir,
                "distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.6/apache-maven-3.9.6-bin.zip\n");

        ToolDistribution dist = new MavenResolver().resolve(projectDir);
        assertThat(dist.version()).isEqualTo("3.9.6");
        assertThat(dist.archiveType()).isEqualTo("zip");
        assertThat(dist.sha256()).isNull();
    }

    @Test
    void wrapper_tar_gz_extension_detected(@TempDir Path projectDir) throws Exception {
        writeWrapper(projectDir,
                "distributionUrl=https://archive.apache.org/dist/maven/maven-3/3.9.8/binaries/apache-maven-3.9.8-bin.tar.gz\n");

        ToolDistribution dist = new MavenResolver().resolve(projectDir);
        assertThat(dist.version()).isEqualTo("3.9.8");
        assertThat(dist.archiveType()).isEqualTo("tar.gz");
    }

    @Test
    void wrapper_sha256_is_propagated(@TempDir Path projectDir) throws Exception {
        writeWrapper(projectDir,
                "distributionUrl=https://example.invalid/apache-maven-3.9.9-bin.zip\n"
                + "distributionSha256Sum=  abcdef1234567890  \n");

        ToolDistribution dist = new MavenResolver().resolve(projectDir);
        assertThat(dist.sha256()).isEqualTo("abcdef1234567890");
    }

    @Test
    void wrapper_with_unparseable_url_falls_back_to_sentinel(@TempDir Path projectDir) throws Exception {
        writeWrapper(projectDir,
                "distributionUrl=https://corporate.example/maven-3.9.9.zip\n");

        ToolDistribution dist = new MavenResolver().resolve(projectDir);
        // URL doesn't match apache-maven-<v>-bin pattern; we keep the URL and tag the version.
        assertThat(dist.version()).isEqualTo("wrapper");
        assertThat(dist.downloadUri().toString()).endsWith("/maven-3.9.9.zip");
    }

    private static void writeWrapper(Path projectDir, String content) throws Exception {
        Path props = projectDir.resolve(".mvn/wrapper/maven-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, content);
    }
}
