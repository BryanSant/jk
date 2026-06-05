// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile.incremental;

import dev.jkbuild.cache.Cas;


import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * ABI snapshot of a compile classpath — every dependency class's
 * {@link ClassAbi} hash + inlinable-constant flag — so the incremental compiler
 * can tell which dependency classes actually changed when the classpath moves,
 * and recompile only the sources that reference them (instead of a full rebuild
 * on any classpath change). The union also covers directory entries (e.g. a
 * mixed module's Kotlin output dir), giving Java incremental awareness of
 * cross-language ABI changes.
 */
public final class JavaClasspathAbi {

    private JavaClasspathAbi() {}

    /** Per-class facts for a dependency: ABI hash + whether it inlines constants. */
    public record DepFacts(String abi, boolean constants) {}

    /** Union of every classpath entry's {@code internalName → DepFacts} (later entries win on dups). */
    public static Map<String, DepFacts> union(java.util.List<Path> classpath, Cas cas, Path cacheDir)
            throws IOException {
        Map<String, DepFacts> all = new TreeMap<>();
        for (Path entry : classpath) all.putAll(snapshotEntry(entry, cas, cacheDir));
        return all;
    }

    private static Map<String, DepFacts> snapshotEntry(Path entry, Cas cas, Path cacheDir)
            throws IOException {
        if (!Files.exists(entry)) return Map.of();
        if (Files.isDirectory(entry)) return scanDir(entry);   // mutable content → never cache
        // A jar under the CAS is content-addressed (its path encodes its bytes), so
        // its snapshot is immutable and cacheable; anything else is scanned fresh.
        var cas256 = cas.hashFromPath(entry);
        if (cas256.isEmpty()) return scanJar(entry);
        // Cache file uses .txt extension; old .json files are silently ignored (cold start).
        Path cacheFile = cacheDir.resolve(cas256.get() + ".txt");
        if (Files.isRegularFile(cacheFile)) {
            try {
                return readDepFacts(Files.readAllBytes(cacheFile));
            } catch (RuntimeException ignored) { /* corrupt → recompute */ }
        }
        Map<String, DepFacts> snapshot = scanJar(entry);
        Files.createDirectories(cacheDir);
        Files.write(cacheFile, writeDepFacts(snapshot).getBytes(StandardCharsets.UTF_8));
        return snapshot;
    }

    private static Map<String, DepFacts> scanJar(Path jar) throws IOException {
        Map<String, DepFacts> out = new TreeMap<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String name = e.getName();
                if (!name.endsWith(".class") || name.endsWith("module-info.class")) continue;
                byte[] bytes;
                try (var in = jf.getInputStream(e)) {
                    bytes = in.readAllBytes();
                }
                out.put(name.substring(0, name.length() - ".class".length()), factsOf(bytes));
            }
        }
        return out;
    }

    private static Map<String, DepFacts> scanDir(Path dir) throws IOException {
        Map<String, DepFacts> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!p.toString().endsWith(".class")) continue;
                String rel = dir.relativize(p).toString().replace(File.separatorChar, '/');
                if (rel.endsWith("module-info.class")) continue;
                out.put(rel.substring(0, rel.length() - ".class".length()),
                        factsOf(Files.readAllBytes(p)));
            }
        }
        return out;
    }

    private static DepFacts factsOf(byte[] bytes) {
        return new DepFacts(ClassAbi.hash(bytes), ClassAbi.definesInlinableConstant(bytes));
    }

    // --- tab-delimited serialization (replaces Jackson) ---------------------
    // Format: one entry per line — <internalName>\t<abiHex>\t<0|1>

    public static String writeDepFacts(Map<String, DepFacts> map) {
        StringBuilder sb = new StringBuilder(map.size() * 80);
        for (Map.Entry<String, DepFacts> e : map.entrySet()) {
            sb.append(e.getKey()).append('\t')
              .append(e.getValue().abi()).append('\t')
              .append(e.getValue().constants() ? '1' : '0').append('\n');
        }
        return sb.toString();
    }

    public static Map<String, DepFacts> readDepFacts(byte[] bytes) throws IOException {
        Map<String, DepFacts> out = new TreeMap<>();
        try (BufferedReader br = new BufferedReader(
                new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\t", 3);
                if (parts.length < 3) continue;
                out.put(parts[0], new DepFacts(parts[1], "1".equals(parts[2])));
            }
        }
        return out;
    }
}
