// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

class JarPackagerTest {

    @Test
    void writes_sorted_entries(@TempDir Path tempDir) throws IOException {
        Path input = tempDir.resolve("classes");
        Files.createDirectories(input.resolve("z/sub"));
        Files.createDirectories(input.resolve("a"));
        Files.writeString(input.resolve("z/sub/Z.class"), "z");
        Files.writeString(input.resolve("a/A.class"), "a");
        Files.writeString(input.resolve("Root.class"), "r");

        Path jar = tempDir.resolve("out.jar");
        new JarPackager().packageJar(JarPackager.JarRequest.of(input, jar));

        List<String> entries = listEntries(jar);
        // META-INF/* entries come first (jar plumbing), then our files alphabetized.
        List<String> ours = entries.stream().filter(e -> !e.startsWith("META-INF/")).toList();
        assertThat(ours).containsExactly("Root.class", "a/A.class", "z/sub/Z.class");
    }

    @Test
    void reproducible_byte_for_byte(@TempDir Path tempDir) throws IOException {
        Path input = tempDir.resolve("classes");
        Files.createDirectories(input);
        Files.writeString(input.resolve("Hello.class"), "stable-content");

        Path jarA = tempDir.resolve("a.jar");
        Path jarB = tempDir.resolve("b.jar");
        new JarPackager().packageJar(JarPackager.JarRequest.of(input, jarA));
        // Bump mtime of source files between runs to make sure the packager
        // doesn't pick up wall-clock timestamps.
        Files.setLastModifiedTime(input.resolve("Hello.class"),
                FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        new JarPackager().packageJar(JarPackager.JarRequest.of(input, jarB));

        assertThat(Files.readAllBytes(jarA)).isEqualTo(Files.readAllBytes(jarB));
    }

    @Test
    void manifest_entry_is_pinned_to_the_fixed_epoch_not_wall_clock(@TempDir Path tempDir) throws IOException {
        // Regression guard: the JarOutputStream(out, manifest) convenience
        // constructor stamps the manifest with the *current* time, so an
        // otherwise-identical jar churns every build. The manifest must carry
        // the same pinned timestamp as every other entry.
        Path input = tempDir.resolve("classes");
        Files.createDirectories(input);
        Files.writeString(input.resolve("Hello.class"), "x");

        Path jar = tempDir.resolve("out.jar");
        new JarPackager().packageJar(JarPackager.JarRequest.of(input, jar));

        try (JarFile jf = new JarFile(jar.toFile())) {
            long manifestTime = jf.getJarEntry("META-INF/MANIFEST.MF").getTime();
            long dataTime = jf.getJarEntry("Hello.class").getTime();
            assertThat(manifestTime)
                    .as("manifest pinned to the same fixed epoch as data entries")
                    .isEqualTo(dataTime);
        }
    }

    @Test
    void manifest_carries_only_what_we_set(@TempDir Path tempDir) throws IOException {
        Path input = tempDir.resolve("classes");
        Files.createDirectories(input);
        Files.writeString(input.resolve("x.txt"), "data");

        Path jar = tempDir.resolve("out.jar");
        new JarPackager().packageJar(
                JarPackager.JarRequest.of(input, jar).withMainClass("com.example.Main"));

        try (JarFile jf = new JarFile(jar.toFile())) {
            Attributes attrs = jf.getManifest().getMainAttributes();
            assertThat(attrs.getValue(Attributes.Name.MANIFEST_VERSION)).isEqualTo("1.0");
            assertThat(attrs.getValue(Attributes.Name.MAIN_CLASS)).isEqualTo("com.example.Main");
            // We deliberately don't write Created-By / Build-Jdk.
            assertThat(attrs.getValue("Created-By")).isNull();
            assertThat(attrs.getValue("Build-Jdk")).isNull();
        }
    }

    @Test
    void custom_manifest_attributes_are_written(@TempDir Path tempDir) throws IOException {
        Path input = tempDir.resolve("classes");
        Files.createDirectories(input);
        Files.writeString(input.resolve("x.txt"), "data");

        Path jar = tempDir.resolve("out.jar");
        new JarPackager().packageJar(JarPackager.JarRequest.of(input, jar)
                .withMainClass("com.example.Main")
                .withAttributes(java.util.Map.of(
                        "Implementation-Title", "widget",
                        "Implementation-Version", "1.0.0")));

        try (JarFile jf = new JarFile(jar.toFile())) {
            Attributes attrs = jf.getManifest().getMainAttributes();
            assertThat(attrs.getValue(Attributes.Name.MAIN_CLASS)).isEqualTo("com.example.Main");
            assertThat(attrs.getValue("Implementation-Title")).isEqualTo("widget");
            assertThat(attrs.getValue("Implementation-Version")).isEqualTo("1.0.0");
        }
    }

    @Test
    void entry_contents_round_trip(@TempDir Path tempDir) throws IOException {
        Path input = tempDir.resolve("classes");
        Files.createDirectories(input);
        Files.writeString(input.resolve("hello.txt"), "round-trip");

        Path jar = tempDir.resolve("out.jar");
        new JarPackager().packageJar(JarPackager.JarRequest.of(input, jar));

        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry entry = jf.getJarEntry("hello.txt");
            assertThat(entry).isNotNull();
            try (InputStream in = jf.getInputStream(entry)) {
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(content).isEqualTo("round-trip");
            }
        }
    }

    private static List<String> listEntries(Path jar) throws IOException {
        List<String> result = new ArrayList<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            jf.stream().forEach(e -> result.add(e.getName()));
        }
        return result;
    }
}
