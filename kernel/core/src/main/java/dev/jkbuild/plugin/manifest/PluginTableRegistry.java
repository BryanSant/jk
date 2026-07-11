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

    private static final List<String> BUILT_IN = List.of("spring-boot.jk-plugin.toml");

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

    /** The manifest owning jk.toml table {@code table}, when a plugin declares it. */
    public static Optional<PluginManifest> byTable(String table) {
        return Optional.ofNullable(BY_TABLE.get(table));
    }

    /** Schema-validate {@code table} (the raw {@code [<manifest.table>]} TOML) into a config. */
    public static PluginConfig validate(PluginManifest manifest, TomlTable table) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (PluginManifest.SchemaKey key : manifest.schema().values()) {
            Object value = read(manifest.table(), key, table);
            if (value == null) value = key.normalizedDefault();
            if (value == null) {
                if (key.required()) throw new JkBuildParseException(requiredMessage(manifest.table(), key));
                continue; // tri-state: absent stays absent
            }
            values.put(key.name(), value);
        }
        return new PluginConfig(manifest.id(), values);
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
                byTable.put(manifest.table(), manifest);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to load built-in plugin manifest " + resource, e);
            }
        }
        return byTable;
    }
}
