// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile;

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
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShadowPackagerTest {

    @Test
    void merges_classes_deps_services_and_drops_signatures(@TempDir Path tmp) throws IOException {
        // Project classes dir.
        Path classes = tmp.resolve("classes");
        Files.createDirectories(classes.resolve("app"));
        Files.writeString(classes.resolve("app/Main.class"), "APPMAIN");
        Files.createDirectories(classes.resolve("META-INF/services"));
        Files.writeString(classes.resolve("META-INF/services/com.example.SPI"), "app.Provider");

        // A dependency jar with a class, a service file, and a (bogus) signature.
        Path dep = tmp.resolve("dep.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(dep))) {
            putEntry(jos, "lib/Helper.class", "LIBHELPER");
            putEntry(jos, "META-INF/services/com.example.SPI", "lib.Provider");
            putEntry(jos, "META-INF/FOO.SF", "signature");
            putEntry(jos, "META-INF/FOO.RSA", "signature");
        }

        Path out = tmp.resolve("app-all.jar");
        new ShadowPackager()
                .packageShadow(new ShadowPackager.ShadowRequest(
                        classes, List.of(dep), out, "app.Main", Map.of("Implementation-Title", "app"), 0L));

        try (JarFile jf = new JarFile(out.toFile())) {
            Manifest mf = jf.getManifest();
            assertThat(mf.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS))
                    .isEqualTo("app.Main");
            assertThat(mf.getMainAttributes().getValue("Implementation-Title")).isEqualTo("app");

            assertThat(jf.getJarEntry("app/Main.class")).isNotNull();
            assertThat(jf.getJarEntry("lib/Helper.class")).isNotNull();
            // Signature files are dropped.
            assertThat(jf.getJarEntry("META-INF/FOO.SF")).isNull();
            assertThat(jf.getJarEntry("META-INF/FOO.RSA")).isNull();

            // Service file merges both providers.
            String svc = new String(
                    jf.getInputStream(jf.getJarEntry("META-INF/services/com.example.SPI"))
                            .readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(svc).contains("app.Provider").contains("lib.Provider");
        }
    }

    @Test
    void project_class_wins_on_conflict(@TempDir Path tmp) throws IOException {
        Path classes = tmp.resolve("classes");
        Files.createDirectories(classes.resolve("x"));
        Files.writeString(classes.resolve("x/A.class"), "PROJECT");
        Path dep = tmp.resolve("dep.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(dep))) {
            putEntry(jos, "x/A.class", "DEP");
        }
        Path out = tmp.resolve("out.jar");
        new ShadowPackager()
                .packageShadow(new ShadowPackager.ShadowRequest(classes, List.of(dep), out, null, Map.of(), 0L));

        try (JarFile jf = new JarFile(out.toFile())) {
            String content =
                    new String(jf.getInputStream(jf.getJarEntry("x/A.class")).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).isEqualTo("PROJECT");
        }
    }

    @Test
    void reproducible_and_excludes_freshness_stamps(@TempDir Path tmp) throws IOException {
        Path classes = tmp.resolve("classes");
        Files.createDirectories(classes.resolve("app"));
        Files.writeString(classes.resolve("app/Main.class"), "APPMAIN");
        Files.writeString(classes.resolve(".jstamp"), "stamp-run-1");
        Path dep = tmp.resolve("dep.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(dep))) {
            putEntry(jos, "lib/Helper.class", "LIBHELPER");
        }

        Path a = tmp.resolve("a-all.jar");
        new ShadowPackager()
                .packageShadow(new ShadowPackager.ShadowRequest(classes, List.of(dep), a, "app.Main", Map.of(), 0L));
        // The freshness stamp's content changes every build; the fat jar must
        // not bundle it (and the manifest must be pinned), or the jar churns.
        Files.writeString(classes.resolve(".jstamp"), "stamp-run-2-different");
        Path b = tmp.resolve("b-all.jar");
        new ShadowPackager()
                .packageShadow(new ShadowPackager.ShadowRequest(classes, List.of(dep), b, "app.Main", Map.of(), 0L));

        assertThat(Files.readAllBytes(a)).isEqualTo(Files.readAllBytes(b));
        try (JarFile jf = new JarFile(a.toFile())) {
            assertThat(jf.getJarEntry(".jstamp")).as("freshness stamp excluded").isNull();
            assertThat(jf.getJarEntry("META-INF/MANIFEST.MF").getTime())
                    .as("manifest pinned to the same fixed epoch as data entries")
                    .isEqualTo(jf.getJarEntry("app/Main.class").getTime());
        }
    }

    private static void putEntry(JarOutputStream jos, String name, String content) throws IOException {
        jos.putNextEntry(new JarEntry(name));
        jos.write(content.getBytes(StandardCharsets.UTF_8));
        jos.closeEntry();
    }
}
