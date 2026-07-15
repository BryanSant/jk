// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolHealthTest {

    @Test
    void jdk_release_file_drives_version_and_distribution(@TempDir Path tempDir) throws Exception {
        Path home = jdkLayout(tempDir, "21.0.5", "Eclipse Adoptium");

        assertThat(ToolHealth.detectVersion(ToolSpec.jdk("21.0.5", "tem"), home))
                .contains("21.0.5");
        assertThat(ToolHealth.detectDistribution(ToolSpec.jdk("21.0.5", "tem"), home))
                .contains("tem");

        assertThat(ToolHealth.isHealthy(ToolSpec.jdk("21.0.5", "tem"), home)).isTrue();
        assertThat(ToolHealth.isHealthy(ToolSpec.jdk("21.0.6", "tem"), home)).isFalse();
        assertThat(ToolHealth.isHealthy(ToolSpec.jdk("21.0.5", "graalce"), home))
                .isFalse();
    }

    @Test
    void maven_version_read_from_lib_artifact_filename(@TempDir Path tempDir) throws Exception {
        Path home = mavenLayout(tempDir, "3.9.9");

        assertThat(ToolHealth.detectVersion(ToolSpec.maven("3.9.9"), home)).contains("3.9.9");
        assertThat(ToolHealth.isHealthy(ToolSpec.maven("3.9.9"), home)).isTrue();
        assertThat(ToolHealth.isHealthy(ToolSpec.maven("3.9.8"), home)).isFalse();
    }

    @Test
    void gradle_version_read_from_launcher_jar_filename(@TempDir Path tempDir) throws Exception {
        Path home = gradleLayout(tempDir, "9.5.1");

        assertThat(ToolHealth.detectVersion(ToolSpec.gradle("9.5.1"), home)).contains("9.5.1");
        assertThat(ToolHealth.isHealthy(ToolSpec.gradle("9.5.1"), home)).isTrue();
        assertThat(ToolHealth.isHealthy(ToolSpec.gradle("9.0.0"), home)).isFalse();
    }

    @Test
    void kotlin_version_strips_build_suffix_from_manifest(@TempDir Path tempDir) throws Exception {
        Path home = kotlinLayout(tempDir, "2.3.21-release-298");

        // The manifest reports `2.3.21-release-298`; ToolHealth trims to `2.3.21`.
        assertThat(ToolHealth.detectVersion(ToolSpec.kotlin("2.3.21"), home)).contains("2.3.21");
        assertThat(ToolHealth.isHealthy(ToolSpec.kotlin("2.3.21"), home)).isTrue();
    }

    @Test
    void missing_binary_fails_health_check(@TempDir Path tempDir) throws Exception {
        Path home = mavenLayout(tempDir, "3.9.9");
        Files.delete(home.resolve("bin").resolve("mvn"));
        assertThat(ToolHealth.isHealthy(ToolSpec.maven("3.9.9"), home)).isFalse();
    }

    @Test
    void missing_release_file_fails_jdk_check(@TempDir Path tempDir) throws Exception {
        Path home = jdkLayout(tempDir, "21.0.5", "Eclipse Adoptium");
        Files.delete(home.resolve("release"));
        assertThat(ToolHealth.isHealthy(ToolSpec.jdk("21.0.5", null), home)).isFalse();
    }

    // --- fixture builders ---------------------------------------------------

    static Path jdkLayout(Path root, String version, String implementor) throws Exception {
        Path home = root.resolve("jdk-" + version);
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/bin/sh\n");
        Files.writeString(
                home.resolve("release"),
                "JAVA_VERSION=\"" + version + "\"\n" + "IMPLEMENTOR=\"" + implementor + "\"\n");
        return home;
    }

    static Path mavenLayout(Path root, String version) throws Exception {
        Path home = root.resolve("apache-maven-" + version);
        Files.createDirectories(home.resolve("bin"));
        Files.createDirectories(home.resolve("lib"));
        Files.writeString(home.resolve("bin").resolve("mvn"), "#!/bin/sh\n");
        Files.writeString(home.resolve("lib").resolve("maven-core-" + version + ".jar"), "");
        return home;
    }

    static Path gradleLayout(Path root, String version) throws Exception {
        Path home = root.resolve("gradle-" + version);
        Files.createDirectories(home.resolve("bin"));
        Files.createDirectories(home.resolve("lib"));
        Files.writeString(home.resolve("bin").resolve("gradle"), "#!/bin/sh\n");
        Files.writeString(home.resolve("lib").resolve("gradle-launcher-" + version + ".jar"), "");
        return home;
    }

    static Path kotlinLayout(Path root, String manifestVersion) throws Exception {
        Path home = root.resolve("kotlinc");
        Files.createDirectories(home.resolve("bin"));
        Files.createDirectories(home.resolve("lib"));
        Files.writeString(home.resolve("bin").resolve("kotlinc"), "#!/bin/sh\n");

        // Build a kotlin-compiler.jar with the manifest the probe reads.
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, manifestVersion);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos, mf)) {
            jos.putNextEntry(new ZipEntry("placeholder"));
            jos.write("x".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        Files.write(home.resolve("lib").resolve("kotlin-compiler.jar"), baos.toByteArray());
        return home;
    }
}
