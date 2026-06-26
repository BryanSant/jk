// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Reads a Gradle version catalog ({@code gradle/libs.versions.toml}) and
 * resolves type-safe accessors — {@code libs.junit.platform.launcher},
 * {@code libs.bundles.testing} — back to concrete {@code group:artifact:version}
 * coordinates so {@link GradleImporter} can turn catalog-based dependency
 * declarations into jk.toml entries.
 *
 * <p>Accessor mapping mirrors Gradle: an alias's {@code -}, {@code _} and
 * {@code .} separators are all folded to {@code .}, so the alias
 * {@code junit-platform-launcher} is reached as
 * {@code libs.junit.platform.launcher}. Library values may use the table form
 * ({@code { module = "g:a", version.ref = "x" }} or {@code { group, name,
 * version }}) or the string form ({@code "g:a:v"}); versions resolve through
 * the {@code [versions]} table, including rich {@code strictly}/{@code require}/
 * {@code prefer} forms.
 */
public final class GradleVersionCatalog {

    /** accessor (dot-folded) → {@code group:artifact:version}. */
    private final Map<String, String> coordinates;
    /** bundle accessor (dot-folded) → module library accessors. */
    private final Map<String, List<String>> bundles;

    private GradleVersionCatalog(Map<String, String> coordinates, Map<String, List<String>> bundles) {
        this.coordinates = coordinates;
        this.bundles = bundles;
    }

    /**
     * Walk up from {@code projectDir} looking for {@code gradle/libs.versions.toml},
     * checking the project directory and then its parent. Returns the first
     * match, or empty once the search would climb above the project's parent
     * directory.
     */
    public static Optional<Path> locate(Path projectDir) {
        if (projectDir == null) return Optional.empty();
        Path dir = projectDir.toAbsolutePath().normalize();
        Path stopAfter = dir.getParent(); // the project's parent dir is the last place we look
        while (dir != null) {
            Path candidate = dir.resolve("gradle").resolve("libs.versions.toml");
            if (Files.isRegularFile(candidate)) return Optional.of(candidate);
            if (dir.equals(stopAfter)) break;
            dir = dir.getParent();
        }
        return Optional.empty();
    }

    /** Locate and parse the catalog for {@code projectDir}; empty if none found or it fails to parse. */
    public static Optional<GradleVersionCatalog> forProject(Path projectDir) {
        return locate(projectDir).flatMap(catalog -> {
            try {
                return Optional.of(parse(catalog));
            } catch (IOException e) {
                return Optional.empty();
            }
        });
    }

    public static GradleVersionCatalog parse(Path catalogFile) throws IOException {
        return fromToml(Toml.parse(catalogFile));
    }

    static GradleVersionCatalog fromToml(TomlParseResult toml) {
        Map<String, String> versions = new LinkedHashMap<>();
        TomlTable versionsTable = toml.getTable("versions");
        if (versionsTable != null) {
            for (String key : versionsTable.keySet()) {
                String v = readVersion(versionsTable.get(key));
                if (v != null) versions.put(key, v);
            }
        }

        Map<String, String> coordinates = new LinkedHashMap<>();
        TomlTable libraries = toml.getTable("libraries");
        if (libraries != null) {
            flattenLibraries(libraries, "", versions, coordinates);
        }

        Map<String, List<String>> bundles = new LinkedHashMap<>();
        TomlTable bundlesTable = toml.getTable("bundles");
        if (bundlesTable != null) {
            for (String key : bundlesTable.keySet()) {
                TomlArray arr = bundlesTable.getArray(key);
                if (arr == null) continue;
                List<String> modules = new ArrayList<>();
                for (int i = 0; i < arr.size(); i++) {
                    if (arr.get(i) instanceof String s) modules.add(accessor(s));
                }
                bundles.put(accessor(key), modules);
            }
        }
        return new GradleVersionCatalog(coordinates, bundles);
    }

    /** Resolve a library accessor (catalog name already stripped) to {@code g:a:v}. */
    public Optional<String> resolveLibrary(String accessorPath) {
        return Optional.ofNullable(coordinates.get(accessorPath));
    }

    /** Resolve a bundle accessor (catalog name already stripped) to its module coordinates. */
    public Optional<List<String>> resolveBundle(String accessorPath) {
        List<String> modules = bundles.get(accessorPath);
        if (modules == null) return Optional.empty();
        List<String> coords = new ArrayList<>();
        for (String module : modules) {
            String coord = coordinates.get(module);
            if (coord != null) coords.add(coord);
        }
        return Optional.of(coords);
    }

    // --- parsing helpers ----------------------------------------------------

    private static void flattenLibraries(
            TomlTable table, String prefix, Map<String, String> versions, Map<String, String> out) {
        for (String key : table.keySet()) {
            String path = prefix.isEmpty() ? accessor(key) : prefix + "." + accessor(key);
            Object value = table.get(key);
            if (value instanceof String s) {
                if (!s.isBlank()) out.put(path, s.trim());
            } else if (value instanceof TomlTable lib) {
                if (lib.contains("module") || lib.contains("group") || lib.contains("name")) {
                    String coord = coordFromTable(lib, versions);
                    if (coord != null) out.put(path, coord);
                } else {
                    // A namespace table produced by a dotted alias (e.g. junit.jupiter).
                    flattenLibraries(lib, path, versions, out);
                }
            }
        }
    }

    private static String coordFromTable(TomlTable lib, Map<String, String> versions) {
        String group;
        String name;
        String module = lib.getString("module");
        if (module != null) {
            int sep = module.indexOf(':');
            if (sep < 0) return null;
            group = module.substring(0, sep);
            name = module.substring(sep + 1);
        } else {
            group = lib.getString("group");
            name = lib.getString("name");
        }
        if (group == null || group.isBlank() || name == null || name.isBlank()) return null;
        String ga = group.trim() + ":" + name.trim();
        String version = resolveLibraryVersion(lib, versions);
        // A version-less entry (BOM-managed) can't become a jk.toml dep on its own.
        return version == null ? null : ga + ":" + version;
    }

    private static String resolveLibraryVersion(TomlTable lib, Map<String, String> versions) {
        if (lib.contains("version.ref")) {
            String ref = lib.getString("version.ref");
            return ref == null ? null : versions.get(ref);
        }
        return readVersion(lib.get("version"));
    }

    /** A version value: a plain string, or a rich {@code {strictly|require|prefer}} table. */
    private static String readVersion(Object value) {
        if (value instanceof String s) {
            return s.isBlank() ? null : s.trim();
        }
        if (value instanceof TomlTable t) {
            for (String field : List.of("strictly", "require", "prefer")) {
                if (t.contains(field)) {
                    String s = t.getString(field);
                    if (s != null && !s.isBlank()) return s.trim();
                }
            }
        }
        return null;
    }

    /** Fold an alias segment to its accessor form: {@code - _ .} all become {@code .}. */
    private static String accessor(String alias) {
        return alias.replace('-', '.').replace('_', '.');
    }
}
