// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile.incremental;

import dev.jkbuild.cache.Cas;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.io.IOException;
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

    private static final ObjectMapper JSON = JsonMapper.builder().build();

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
        Path cacheFile = cacheDir.resolve(cas256.get() + ".json");
        if (Files.isRegularFile(cacheFile)) {
            try {
                return JSON.readValue(Files.readAllBytes(cacheFile),
                        new TypeReference<TreeMap<String, DepFacts>>() {});
            } catch (RuntimeException ignored) { /* corrupt → recompute */ }
        }
        Map<String, DepFacts> snapshot = scanJar(entry);
        Files.createDirectories(cacheDir);
        Files.write(cacheFile, JSON.writeValueAsBytes(snapshot));
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
}
