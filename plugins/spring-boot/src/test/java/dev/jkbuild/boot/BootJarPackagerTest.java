// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootJarPackagerTest {

    @Test
    void produces_the_boot_executable_layout(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Files.createDirectories(classes.resolve("com/example"));
        Files.write(classes.resolve("com/example/App.class"), new byte[] {1, 2, 3});
        Files.write(classes.resolve("application.properties"), "server.port=8080".getBytes(StandardCharsets.UTF_8));

        Path dep = writeJar(tmp.resolve("spring-core-7.0.1.jar"), "org/springframework/core/Marker.class");
        Path snap = writeJar(tmp.resolve("acme-1.0-SNAPSHOT.jar"), "com/acme/Marker.class");
        Path loader = writeJar(
                tmp.resolve("spring-boot-loader-4.0.0.jar"),
                "org/springframework/boot/loader/launch/JarLauncher.class");

        Path out = tmp.resolve("app.jar");
        new BootJarPackager()
                .packageBootJar(new BootJarPackager.BootJarRequest(
                        classes,
                        List.of(
                                new BootJarPackager.Lib("spring-core-7.0.1.jar", dep, false),
                                new BootJarPackager.Lib("acme-1.0-SNAPSHOT.jar", snap, true)),
                        loader,
                        out,
                        "com.example.App",
                        "4.0.0",
                        Map.of("Implementation-Title", "app"),
                        0L));

        try (JarFile jar = new JarFile(out.toFile())) {
            Attributes attrs = jar.getManifest().getMainAttributes();
            assertThat(attrs.getValue("Main-Class"))
                    .isEqualTo("org.springframework.boot.loader.launch.JarLauncher");
            assertThat(attrs.getValue("Start-Class")).isEqualTo("com.example.App");
            assertThat(attrs.getValue("Spring-Boot-Version")).isEqualTo("4.0.0");
            assertThat(attrs.getValue("Spring-Boot-Classes")).isEqualTo("BOOT-INF/classes/");
            assertThat(attrs.getValue("Spring-Boot-Lib")).isEqualTo("BOOT-INF/lib/");
            assertThat(attrs.getValue("Spring-Boot-Classpath-Index")).isEqualTo("BOOT-INF/classpath.idx");
            assertThat(attrs.getValue("Spring-Boot-Layers-Index")).isEqualTo("BOOT-INF/layers.idx");
            assertThat(attrs.getValue("Implementation-Title")).isEqualTo("app");

            // Loader exploded at the root; app content under BOOT-INF/classes/.
            assertThat(jar.getEntry("org/springframework/boot/loader/launch/JarLauncher.class")).isNotNull();
            assertThat(jar.getEntry("BOOT-INF/classes/com/example/App.class")).isNotNull();
            assertThat(jar.getEntry("BOOT-INF/classes/application.properties")).isNotNull();

            // Nested jars are STORED — Boot's loader random-accesses them.
            JarEntry nested = (JarEntry) jar.getEntry("BOOT-INF/lib/spring-core-7.0.1.jar");
            assertThat(nested).isNotNull();
            assertThat(nested.getMethod()).isEqualTo(ZipEntry.STORED);

            String classpathIdx = new String(
                    jar.getInputStream(jar.getEntry("BOOT-INF/classpath.idx")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(classpathIdx)
                    .isEqualTo("- \"BOOT-INF/lib/spring-core-7.0.1.jar\"\n"
                            + "- \"BOOT-INF/lib/acme-1.0-SNAPSHOT.jar\"\n");

            String layersIdx = new String(
                    jar.getInputStream(jar.getEntry("BOOT-INF/layers.idx")).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(layersIdx)
                    .isEqualTo("- \"dependencies\":\n"
                            + "  - \"BOOT-INF/lib/spring-core-7.0.1.jar\"\n"
                            + "- \"spring-boot-loader\":\n"
                            + "  - \"org/\"\n"
                            + "- \"snapshot-dependencies\":\n"
                            + "  - \"BOOT-INF/lib/acme-1.0-SNAPSHOT.jar\"\n"
                            + "- \"application\":\n"
                            + "  - \"BOOT-INF/classes/\"\n"
                            + "  - \"BOOT-INF/classpath.idx\"\n"
                            + "  - \"BOOT-INF/layers.idx\"\n"
                            + "  - \"META-INF/\"\n");
        }
    }

    @Test
    void colliding_lib_file_names_get_disambiguated(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path a = writeJar(Files.createDirectories(tmp.resolve("group-a")).resolve("util-1.0.jar"), "a/A.class");
        Path b = writeJar(Files.createDirectories(tmp.resolve("group-b")).resolve("util-1.0.jar"), "b/B.class");
        Path loader = writeJar(tmp.resolve("loader.jar"), "org/springframework/boot/loader/launch/JarLauncher.class");

        Path out = tmp.resolve("app.jar");
        new BootJarPackager()
                .packageBootJar(new BootJarPackager.BootJarRequest(
                        classes,
                        List.of(
                                new BootJarPackager.Lib("util-1.0.jar", a, false),
                                new BootJarPackager.Lib("util-1.0.jar", b, false)),
                        loader,
                        out,
                        "com.example.App",
                        "4.0.0",
                        Map.of(),
                        0L));

        try (JarFile jar = new JarFile(out.toFile())) {
            assertThat(jar.getEntry("BOOT-INF/lib/util-1.0.jar")).isNotNull();
            assertThat(jar.getEntry("BOOT-INF/lib/group-b-util-1.0.jar")).isNotNull();
        }
    }

    @Test
    void embeds_build_info_and_sbom_when_supplied(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path loader = writeJar(tmp.resolve("loader.jar"), "org/springframework/boot/loader/launch/JarLauncher.class");
        // The packager treats the SBOM as opaque bytes (the engine renders CycloneDX upstream).
        byte[] sbom = ("{\"bomFormat\": \"CycloneDX\", \"components\": [{"
                + "\"purl\": \"pkg:maven/org.springframework/spring-core@7.0.1\","
                + " \"hashes\": [{\"alg\": \"SHA-256\", \"content\": \"abc123\"}]}]}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        Path out = tmp.resolve("app.jar");
        new BootJarPackager()
                .packageBootJar(new BootJarPackager.BootJarRequest(
                        classes,
                        List.of(),
                        loader,
                        out,
                        "com.example.App",
                        "4.0.0",
                        Map.of(),
                        Map.of("group", "com.example", "artifact", "shop", "name", "shop", "version", "1.0.0"),
                        sbom,
                        0L));

        try (JarFile jar = new JarFile(out.toFile())) {
            String buildInfo = new String(
                    jar.getInputStream(jar.getEntry("BOOT-INF/classes/META-INF/build-info.properties"))
                            .readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(buildInfo)
                    .isEqualTo("build.artifact=shop\n"
                            + "build.group=com.example\n"
                            + "build.name=shop\n"
                            + "build.version=1.0.0\n");

            String sbomJson = new String(
                    jar.getInputStream(jar.getEntry("BOOT-INF/classes/META-INF/sbom/application.cdx.json"))
                            .readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(sbomJson).contains("\"bomFormat\": \"CycloneDX\"");
            assertThat(sbomJson).contains("\"purl\": \"pkg:maven/org.springframework/spring-core@7.0.1\"");
            assertThat(sbomJson).contains("\"content\": \"abc123\"");

            Attributes attrs = jar.getManifest().getMainAttributes();
            assertThat(attrs.getValue("Sbom-Format")).isEqualTo("CycloneDX");
            assertThat(attrs.getValue("Sbom-Location"))
                    .isEqualTo("BOOT-INF/classes/META-INF/sbom/application.cdx.json");
        }
    }

    /** A minimal jar containing one empty entry (+ nothing else). */
    private static Path writeJar(Path path, String entryName) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(path))) {
            jos.putNextEntry(new JarEntry(entryName));
            jos.write(new byte[] {0xC, 0xA});
            jos.closeEntry();
        }
        return path;
    }
}
