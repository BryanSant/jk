// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarManifestTest {

    @Test
    void reads_main_class_from_manifest(@TempDir Path tempDir) throws Exception {
        Path jar = tempDir.resolve("tool.jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "com.example.Main");
        try (var fos = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(fos, mf)) {
            jos.putNextEntry(new ZipEntry("com/example/Main.class"));
            jos.write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            jos.closeEntry();
        }
        assertThat(JarManifest.mainClass(jar)).contains("com.example.Main");
    }

    @Test
    void absent_main_class_returns_empty(@TempDir Path tempDir) throws Exception {
        Path jar = tempDir.resolve("lib.jar");
        ByteArrayOutputStream manifestBytes = new ByteArrayOutputStream();
        new Manifest().write(manifestBytes);
        try (var fos = Files.newOutputStream(jar);
                ZipOutputStream zos = new ZipOutputStream(fos)) {
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zos.write(manifestBytes.toByteArray());
            zos.closeEntry();
        }
        assertThat(JarManifest.mainClass(jar)).isEmpty();
    }

    @Test
    void manifest_with_no_manifest_entry_returns_empty(@TempDir Path tempDir) throws Exception {
        Path jar = tempDir.resolve("manifestless.jar");
        try (var fos = Files.newOutputStream(jar);
                ZipOutputStream zos = new ZipOutputStream(fos)) {
            zos.putNextEntry(new ZipEntry("only/some/Class.class"));
            zos.write("x".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        assertThat(JarManifest.mainClass(jar)).isEmpty();
    }

    @Test
    void scans_embedded_maven_pom_layout(@TempDir Path tempDir) throws Exception {
        // Shaded jars carry META-INF/maven/<g>/<a>/{pom.xml,pom.properties}
        // for each artifact that was bundled. Build a jar with two such
        // groups and verify both surface.
        Path jar = tempDir.resolve("shaded.jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (var fos = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(fos, mf)) {
            jos.putNextEntry(new ZipEntry("META-INF/maven/com.example/widget/pom.xml"));
            jos.write("<project><groupId>com.example</groupId></project>".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
            jos.putNextEntry(new ZipEntry("META-INF/maven/com.example/widget/pom.properties"));
            jos.write("version=1.0\n".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
            jos.putNextEntry(new ZipEntry("META-INF/maven/org.other/lib/pom.xml"));
            jos.write("<project><groupId>org.other</groupId></project>".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        var poms = JarManifest.scanEmbeddedPoms(jar);
        assertThat(poms).hasSize(2);

        var widget = poms.stream()
                .filter(p -> p.coord().equals("com.example:widget"))
                .findFirst()
                .orElseThrow();
        assertThat(widget.hasPomXml()).isTrue();
        assertThat(new String(widget.pomXml(), StandardCharsets.UTF_8)).contains("com.example");
        assertThat(widget.pomProperties()).contains("version=1.0");

        var other = poms.stream()
                .filter(p -> p.coord().equals("org.other:lib"))
                .findFirst()
                .orElseThrow();
        assertThat(other.hasPomXml()).isTrue();
        assertThat(other.pomProperties()).isNull();
    }

    @Test
    void detects_module_info(@TempDir Path tempDir) throws Exception {
        Path jar = tempDir.resolve("module.jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (var fos = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(fos, mf)) {
            jos.putNextEntry(new ZipEntry("module-info.class"));
            // Bytes don't need to be a valid module-info; the probe is name-only.
            jos.write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            jos.closeEntry();
        }
        assertThat(JarManifest.hasModuleInfo(jar)).isTrue();
    }

    @Test
    void jar_without_module_info_returns_false(@TempDir Path tempDir) throws Exception {
        Path jar = tempDir.resolve("plain.jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (var fos = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(fos, mf)) {
            jos.putNextEntry(new ZipEntry("com/example/Foo.class"));
            jos.write(new byte[] {0});
            jos.closeEntry();
        }
        assertThat(JarManifest.hasModuleInfo(jar)).isFalse();
    }
}
