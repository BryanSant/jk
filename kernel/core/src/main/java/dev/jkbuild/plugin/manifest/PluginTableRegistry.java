// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.manifest;

import dev.jkbuild.config.JkBuildParseException;
import dev.jkbuild.model.PluginConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * The set of installed build plugins, keyed by the jk.toml table each owns (build-plugins plan
 * P1). In P1 the installed set is exactly the built-in manifests shipped as classpath resources
 * (the spring-boot blueprint); P5 adds third-party manifests resolved via {@code [plugins]}.
 *
 * <p>{@link #validate} turns a raw plugin-owned table into a schema-checked
 * {@link PluginConfig}: required keys enforced (with the manifest's example/hint feeding the
 * message), defaults applied, types coerced — so downstream consumers never re-validate.
 *
 * <p>An unknown top-level table stays ignored in P1, exactly like today (the "unowned table is
 * an error" UX arrives with P5, when third-party plugins make the installed set user-visible).
 */
public final class PluginTableRegistry {

    private static final List<String> BUILT_IN = List.of(
            "spring-boot.jk-plugin.toml",
            "android.jk-plugin.toml",
            "protobuf.jk-plugin.toml",
            "shrink.jk-plugin.toml");

    /**
     * A plugin's template/data resource, addressed relative to its manifest ({@code
     * <id>/<relPath>} next to the built-in manifests). P5 extends resolution to third-party
     * plugin jars; built-in plugins bake their files at build time.
     */
    public static String resourceText(PluginManifest manifest, String relPath) {
        String resource = manifest.id() + "/" + relPath;
        try (java.io.InputStream in = PluginTableRegistry.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new dev.jkbuild.config.JkBuildParseException(
                        "plugin " + manifest.id() + " names a missing resource: " + relPath);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new dev.jkbuild.config.JkBuildParseException(
                    "plugin " + manifest.id() + " resource " + relPath + " is unreadable: " + e.getMessage());
        }
    }

    private static final Map<String, PluginManifest> BY_TABLE = loadBuiltIns();

    private PluginTableRegistry() {}

    /** Every installed manifest, in registration order. */
    public static List<PluginManifest> manifests() {
        return List.copyOf(BY_TABLE.values());
    }

    /** True when {@code id} names a first-party manifest shipped inside jk itself. */
    public static boolean isBuiltIn(String id) {
        for (PluginManifest m : BY_TABLE.values()) {
            if (m.id().equals(id)) return true;
        }
        return false;
    }

    /**
     * The manifests a build of {@code moduleDir} sees: built-ins plus every {@code [plugins]}
     * declaration whose manifest is locked + materialized ({@link PluginManifestStore}). An
     * unresolved declaration is silently absent — its table validates on the next parse after the
     * engine materializes (sync/lock/build pre-flight). A third-party manifest claiming a built-in
     * id or an already-owned table is a parse error, not a shadow.
     */
    public static List<PluginManifest> manifestsFor(
            java.nio.file.Path moduleDir, List<dev.jkbuild.model.PluginDeclaration> decls) {
        if (decls == null || decls.isEmpty()) return manifests();
        List<PluginManifest> out = new java.util.ArrayList<>(manifests());
        java.util.Set<String> ids = new java.util.HashSet<>();
        java.util.Set<String> tables = new java.util.HashSet<>();
        for (PluginManifest m : out) {
            ids.add(m.id());
            tables.add(m.table());
        }
        for (dev.jkbuild.model.PluginDeclaration decl : decls) {
            PluginManifest m = PluginManifestStore.manifestFor(moduleDir, decl).orElse(null);
            if (m == null) continue;
            if (!ids.add(m.id())) {
                throw new dev.jkbuild.config.JkBuildParseException("plugin " + decl.coordinateWithVersion()
                        + " declares id `" + m.id() + "`, which is already provided by another installed plugin");
            }
            if (!tables.add(m.table())) {
                throw new dev.jkbuild.config.JkBuildParseException("plugin " + decl.coordinateWithVersion()
                        + " claims table [" + m.table() + "], which is already owned by another installed plugin");
            }
            out.add(m);
        }
        return out;
    }

    /** The manifest owning jk.toml table {@code table}, when a plugin declares it. */
    public static Optional<PluginManifest> byTable(String table) {
        return Optional.ofNullable(BY_TABLE.get(table));
    }

    /** Schema-validate {@code table} (the raw {@code [<manifest.table>]} TOML) into a config. */
    public static PluginConfig validate(PluginManifest manifest, TomlTable table) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (PluginManifest.SchemaKey key : manifest.schema().values()) {
            // A schema key sharing its name with a sub-table group: the TOML value decides which
            // spelling this module used — a nested table is the group (handled below), a string
            // is the reference read here.
            if (manifest.subTables().containsKey(key.name()) && readTable(table, key.name()) != null) continue;
            Object value = read(manifest.table(), key, table);
            if (value == null) value = key.normalizedDefault();
            if (value == null) {
                if (key.required()) throw new JkBuildParseException(requiredMessage(manifest.table(), key));
                continue; // tri-state: absent stays absent
            }
            values.put(key.name(), value);
        }
        // Declared nested-table groups ([sub-tables.<name>]): each entry validates against its
        // sub-schema and rides the config as a nested map, resolved by name from a schema key
        // (signing = "release") and flattened into the effective config by VariantApply.
        for (PluginManifest.SubTable group : manifest.subTables().values()) {
            TomlTable groupTable = readTable(table, group.table());
            if (groupTable == null) continue;
            Map<String, PluginManifest.SchemaKey> subSchema = manifest.subSchemas().get(group.schema());
            String whereBase = manifest.table() + "." + group.table();
            Map<String, Map<String, Object>> entries = new LinkedHashMap<>();
            for (String entry : groupTable.keySet()) {
                TomlTable entryTable = readTable(groupTable, entry);
                if (entryTable == null) {
                    throw new JkBuildParseException("[" + whereBase + "]." + entry + " must be a table");
                }
                entries.put(entry, validateSub(whereBase + "." + entry, subSchema, entryTable));
            }
            values.put(group.table(), entries);
        }
        return new PluginConfig(manifest.id(), values);
    }

    /**
     * Schema-validate a variant overlay ({@code [variants.<dim>.<value>.<plugin-table>]}) — the
     * partial-table cousin of {@link #validate}: keys coerce against the plugin's top-level
     * schema, but nothing is required and no defaults apply (an overlay only carries what it
     * sets). Nested groups (signing definitions) don't belong in overlays — reference them by
     * name via their schema key instead ({@code signing = "release"}).
     */
    public static Map<String, Object> validateOverlay(PluginManifest manifest, String where, TomlTable table) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String raw : table.keySet()) {
            // Group DEFINITIONS don't belong in overlays; the string REFERENCE spelling does.
            if (manifest.subTables().containsKey(raw) && readTable(table, raw) != null) {
                throw new JkBuildParseException("[" + where + "." + raw + "] — define ["
                        + manifest.table() + "." + raw + ".<name>] groups on the plugin table and reference"
                        + " them from the overlay by name (" + raw + " = \"<name>\")");
            }
            PluginManifest.SchemaKey key = manifest.schema().get(raw);
            if (key == null) {
                throw new JkBuildParseException("[" + where + "]." + raw + " is not a ["
                        + manifest.table() + "] key (schema: " + manifest.schema().keySet() + ")");
            }
            Object value = read(where, key, table);
            if (value != null) values.put(key.name(), value);
        }
        return values;
    }

    private static TomlTable readTable(TomlTable parent, String key) {
        Object raw = parent.contains(key) ? parent.get(key) : null;
        return raw instanceof TomlTable t ? t : null;
    }

    /** One nested entry against its sub-schema — same coercion + required semantics as the table. */
    private static Map<String, Object> validateSub(
            String where, Map<String, PluginManifest.SchemaKey> subSchema, TomlTable entryTable) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (PluginManifest.SchemaKey key : subSchema.values()) {
            Object value = read(where, key, entryTable);
            if (value == null) value = key.normalizedDefault();
            if (value == null) {
                if (key.required()) {
                    throw new JkBuildParseException("[" + where + "] requires `" + key.name() + "`"
                            + (key.example() != null ? " (e.g. " + key.name() + " = \"" + key.example() + "\")" : ""));
                }
                continue;
            }
            out.put(key.name(), value);
        }
        return out;
    }

    private static Object read(String tableName, PluginManifest.SchemaKey key, TomlTable table) {
        if (!table.contains(key.name())) return null;
        String where = "[" + tableName + "]." + key.name();
        return switch (key.type()) {
            case STRING -> {
                String v = getOr(() -> table.getString(key.name()), where + " must be a string");
                if (v == null || v.isBlank()) yield null;
                yield v;
            }
            case BOOL -> {
                Boolean v = getOr(() -> table.getBoolean(key.name()), where + " must be a boolean");
                if (v == null) throw new JkBuildParseException(where + " must be a boolean");
                yield v;
            }
            case INT -> {
                Long v = getOr(() -> table.getLong(key.name()), where + " must be an integer");
                if (v == null) throw new JkBuildParseException(where + " must be an integer");
                yield v;
            }
            case STRING_LIST -> {
                TomlArray arr = getOr(() -> table.getArray(key.name()), where + " must be an array of strings");
                if (arr == null) throw new JkBuildParseException(where + " must be an array of strings");
                List<String> out = new ArrayList<>(arr.size());
                for (int i = 0; i < arr.size(); i++) {
                    Object val = arr.get(i);
                    if (!(val instanceof String str)) {
                        throw new JkBuildParseException(where + " must be an array of strings");
                    }
                    out.add(str);
                }
                yield out;
            }
        };
    }

    /** tomlj throws {@code TomlInvalidTypeException} on mistyped keys — normalize to a parse error. */
    private static <T> T getOr(java.util.function.Supplier<T> read, String message) {
        try {
            return read.get();
        } catch (org.tomlj.TomlInvalidTypeException e) {
            throw new JkBuildParseException(message);
        }
    }

    /** Message parity with the old hand-written parsers: example + hint when the schema has them. */
    private static String requiredMessage(String tableName, PluginManifest.SchemaKey key) {
        StringBuilder sb = new StringBuilder("[" + tableName + "]." + key.name() + " is required");
        if (key.example() != null) {
            sb.append(" (e.g. ").append(key.name()).append(" = \"").append(key.example()).append("\")");
        }
        if (key.hint() != null) sb.append(" — ").append(key.hint());
        return sb.toString();
    }

    private static Map<String, PluginManifest> loadBuiltIns() {
        Map<String, PluginManifest> byTable = new LinkedHashMap<>();
        for (String resource : BUILT_IN) {
            try (InputStream in = PluginTableRegistry.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("missing built-in plugin manifest resource: " + resource);
                }
                PluginManifest manifest =
                        PluginManifests.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), resource);
                if (manifest.code() != null && manifest.code().worker() == null) {
                    throw new IllegalStateException("built-in plugin manifest " + resource
                            + " must name its registered worker jar ([code] worker)");
                }
                byTable.put(manifest.table(), manifest);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to load built-in plugin manifest " + resource, e);
            }
        }
        return byTable;
    }
}
