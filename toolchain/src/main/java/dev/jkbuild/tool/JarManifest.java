// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.tool;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
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
                mf.read(new ByteArrayInputStream(body));
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

    /**
     * Scan {@code META-INF/maven/&lt;group&gt;/&lt;artifact&gt;/} for embedded
     * Maven metadata. Returns one entry per group:artifact pair found,
     * carrying the {@code pom.xml} bytes (if present) and the
     * {@code pom.properties} content (if present). Shaded ("uber") jars use
     * this layout to record what they bundled.
     */
    public static List<EmbeddedPom> scanEmbeddedPoms(Path jar) throws IOException {
        Objects.requireNonNull(jar, "jar");
        // group:artifact → builder
        var byCoord = new LinkedHashMap<String, EmbeddedPom.Builder>();
        try (InputStream in = Files.newInputStream(jar);
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (!name.startsWith("META-INF/maven/")) continue;
                String tail = name.substring("META-INF/maven/".length());
                int slash = tail.lastIndexOf('/');
                if (slash < 0) continue;
                String dir = tail.substring(0, slash);
                String file = tail.substring(slash + 1);
                int dirSlash = dir.lastIndexOf('/');
                if (dirSlash < 0) continue;
                String group = dir.substring(0, dirSlash);
                String artifact = dir.substring(dirSlash + 1);
                String coord = group + ":" + artifact;
                EmbeddedPom.Builder b = byCoord.computeIfAbsent(coord,
                        k -> new EmbeddedPom.Builder(group, artifact));
                if (file.equals("pom.xml")) {
                    b.pomXml = zis.readAllBytes();
                } else if (file.equals("pom.properties")) {
                    String props = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    b.pomProperties = props;
                }
            }
        }
        List<EmbeddedPom> out = new ArrayList<>(byCoord.size());
        for (EmbeddedPom.Builder b : byCoord.values()) out.add(b.build());
        return out;
    }

    /** Whether the jar contains a top-level {@code module-info.class} (a real Java module). */
    public static boolean hasModuleInfo(Path jar) throws IOException {
        try (InputStream in = Files.newInputStream(jar);
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals("module-info.class")) return true;
            }
        }
        return false;
    }

    /**
     * One {@code META-INF/maven/<g>/<a>/} folder's contents. {@code pomXml}
     * is the raw bytes of {@code pom.xml} (suitable for
     * {@code PomImporter.importFromBytes}); {@code pomProperties} is the raw
     * key-value text of {@code pom.properties}.
     */
    public record EmbeddedPom(String group, String artifact,
                              byte[] pomXml, String pomProperties) {
        public boolean hasPomXml() { return pomXml != null && pomXml.length > 0; }
        public String coord() { return group + ":" + artifact; }

        static final class Builder {
            final String group;
            final String artifact;
            byte[] pomXml;
            String pomProperties;
            Builder(String group, String artifact) {
                this.group = group;
                this.artifact = artifact;
            }
            EmbeddedPom build() { return new EmbeddedPom(group, artifact, pomXml, pomProperties); }
        }
    }
}
