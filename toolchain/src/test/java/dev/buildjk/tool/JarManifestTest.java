// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

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
}
