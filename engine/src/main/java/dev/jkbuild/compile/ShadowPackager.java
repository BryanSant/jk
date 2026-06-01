// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Packages a project's compiled classes plus all of its runtime dependency
 * jars into a single self-contained "shadow" (fat / uber) jar, the way
 * Gradle's shadow plugin or the Maven shade plugin do.
 *
 * <p>Merge rules, chosen to be safe across real-world jars:
 * <ul>
 *   <li>Project classes/resources are written first and <em>win</em> on
 *       conflict; a duplicate path from a later jar is skipped (first wins,
 *       deps processed in the given order).</li>
 *   <li>We write our own manifest (Main-Class + any {@code [manifest]}
 *       attributes); per-jar {@code META-INF/MANIFEST.MF} are dropped.</li>
 *   <li>Signature files ({@code META-INF/*.SF|*.RSA|*.DSA|*.EC},
 *       {@code META-INF/SIG-*}) are dropped — they're invalid once classes
 *       from other jars are merged in.</li>
 *   <li>{@code META-INF/services/*} provider files are <em>concatenated</em>
 *       across all inputs rather than letting one shadow the others.</li>
 *   <li>Entries are written in sorted order with a fixed timestamp, so the
 *       output is reproducible (mirrors {@link JarPackager}).</li>
 * </ul>
 */
public final class ShadowPackager {

    public Path packageShadow(ShadowRequest request) throws IOException {
        Files.createDirectories(request.outputJar().getParent());
        Manifest manifest = buildManifest(request);

        Set<String> written = new HashSet<>();
        // Service-provider files merged across inputs; TreeMap → deterministic.
        Map<String, ByteArrayOutputStream> services = new TreeMap<>();

        try (OutputStream out = Files.newOutputStream(request.outputJar());
             JarOutputStream jos = new JarOutputStream(out, manifest)) {
            written.add("META-INF/MANIFEST.MF");

            // 1. Project classes + resources win.
            List<Path> files = collectFiles(request.classesDir());
            files.sort(Comparator.comparing(p -> normalize(request.classesDir(), p)));
            for (Path file : files) {
                String name = normalize(request.classesDir(), file);
                if (name.equals("META-INF/MANIFEST.MF")) continue;
                if (isServiceFile(name)) {
                    accumulate(services, name, Files.readAllBytes(file));
                    continue;
                }
                if (written.add(name)) {
                    writeEntry(jos, name, Files.readAllBytes(file), request.timestampEpochSeconds());
                }
            }

            // 2. Dependency jars, in declared order (earlier wins).
            for (Path depJar : request.dependencyJars()) {
                if (!Files.isRegularFile(depJar)) continue;
                try (JarFile jf = new JarFile(depJar.toFile())) {
                    List<JarEntry> entries = new ArrayList<>();
                    jf.stream().filter(e -> !e.isDirectory()).forEach(entries::add);
                    entries.sort(Comparator.comparing(JarEntry::getName));
                    for (JarEntry e : entries) {
                        String name = e.getName();
                        if (name.equals("META-INF/MANIFEST.MF") || isSignatureFile(name)) continue;
                        byte[] data;
                        try (InputStream in = jf.getInputStream(e)) {
                            data = in.readAllBytes();
                        }
                        if (isServiceFile(name)) {
                            accumulate(services, name, data);
                            continue;
                        }
                        if (written.add(name)) {
                            writeEntry(jos, name, data, request.timestampEpochSeconds());
                        }
                    }
                }
            }

            // 3. Merged service-provider files.
            for (Map.Entry<String, ByteArrayOutputStream> e : services.entrySet()) {
                writeEntry(jos, e.getKey(), e.getValue().toByteArray(), request.timestampEpochSeconds());
            }
        }
        return request.outputJar();
    }

    private static void accumulate(Map<String, ByteArrayOutputStream> services, String name, byte[] data)
            throws IOException {
        ByteArrayOutputStream buf = services.computeIfAbsent(name, k -> new ByteArrayOutputStream());
        if (buf.size() > 0) buf.write('\n');
        buf.write(data);
    }

    private static void writeEntry(JarOutputStream jos, String name, byte[] data, long epochSeconds)
            throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTimeLocal(LocalDateTime.ofEpochSecond(epochSeconds, 0, ZoneOffset.UTC));
        jos.putNextEntry(entry);
        jos.write(data);
        jos.closeEntry();
    }

    private static boolean isServiceFile(String name) {
        return name.startsWith("META-INF/services/") && !name.endsWith("/");
    }

    private static boolean isSignatureFile(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA")
                || upper.endsWith(".EC") || upper.startsWith("META-INF/SIG-");
    }

    private static Manifest buildManifest(ShadowRequest request) {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (request.mainClass() != null && !request.mainClass().isBlank()) {
            attrs.put(Attributes.Name.MAIN_CLASS, request.mainClass());
        }
        for (Map.Entry<String, String> e : request.attributes().entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) continue;
            attrs.put(new Attributes.Name(e.getKey()), e.getValue());
        }
        return manifest;
    }

    private static List<Path> collectFiles(Path root) throws IOException {
        if (!Files.exists(root)) return List.of();
        List<Path> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(result::add);
        }
        return result;
    }

    private static String normalize(Path root, Path file) {
        return root.relativize(file).toString().replace(File.separatorChar, '/');
    }

    /** Inputs for {@link #packageShadow(ShadowRequest)}. */
    public record ShadowRequest(
            Path classesDir,
            List<Path> dependencyJars,
            Path outputJar,
            String mainClass,
            Map<String, String> attributes,
            long timestampEpochSeconds) {

        public ShadowRequest {
            Objects.requireNonNull(classesDir, "classesDir");
            Objects.requireNonNull(outputJar, "outputJar");
            dependencyJars = dependencyJars == null ? List.of() : List.copyOf(dependencyJars);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
