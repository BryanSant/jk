// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.tool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads {@code META-INF/MANIFEST.MF} entries from a jar without unpacking.
 * Used by {@link ToolResolver} to discover the {@code Main-Class} for an
 * installed tool.
 */
public final class JarManifest {

    private JarManifest() {}

    /** Read the {@code Main-Class} attribute, or empty if the jar has none. */
    public static Optional<String> mainClass(Path jar) throws IOException {
        try (InputStream in = Files.newInputStream(jar);
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")) continue;
                byte[] body = zis.readAllBytes();
                Manifest mf = new Manifest();
                mf.read(new java.io.ByteArrayInputStream(body));
                Attributes main = mf.getMainAttributes();
                String value = main.getValue(Attributes.Name.MAIN_CLASS);
                return value == null || value.isBlank()
                        ? Optional.empty() : Optional.of(value.trim());
            }
        }
        return Optional.empty();
    }

    /** Probe the entire manifest as a UTF-8 string (for diagnostics). */
    public static Optional<String> rawManifest(Path jar) throws IOException {
        try (InputStream in = Files.newInputStream(jar);
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                    return Optional.of(new String(zis.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return Optional.empty();
    }
}
