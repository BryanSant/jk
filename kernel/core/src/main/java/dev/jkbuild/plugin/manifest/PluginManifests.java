// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.manifest;

import dev.jkbuild.config.JkBuildParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Parses {@code jk-plugin.toml} manifests (build-plugins plan §3.1). Engine-side only — this is
 * a tomlj parse, and the manifest layer never runs client-side (the same method-level
 * native-image reachability discipline as {@code JkBuildParser}).
 */
public final class PluginManifests {

    private PluginManifests() {}

    public static PluginManifest parse(String toml, String displayPath) {
        TomlParseResult result = Toml.parse(toml);
        if (result.hasErrors()) {
            throw new JkBuildParseException(displayPath + " has invalid TOML: "
                    + result.errors().getFirst().getMessage());
        }
        TomlTable plugin = result.getTable("plugin");
        if (plugin == null) {
            throw new JkBuildParseException(displayPath + " is missing the required [plugin] table");
        }
        String id = requireString(plugin, "id", displayPath);
        String table = requireString(plugin, "table", displayPath);
        String version = plugin.getString("version");
        String jkCompat = plugin.getString("jk-compat");

        Map<String, PluginManifest.SchemaKey> schema = new LinkedHashMap<>();
        TomlTable schemaTable = result.getTable("schema");
        if (schemaTable != null) {
            for (String key : schemaTable.keySet()) {
                Object raw = schemaTable.get(key);
                if (!(raw instanceof TomlTable spec)) {
                    throw new JkBuildParseException(
                            displayPath + ".schema." + key + " must be a table (type = \"…\", …)");
                }
                String typeRaw = spec.getString("type");
                if (typeRaw == null) {
                    throw new JkBuildParseException(displayPath + ".schema." + key + " requires `type`");
                }
                var type = PluginManifest.SchemaKey.Type.parse(typeRaw, displayPath + ".schema." + key);
                boolean required = Boolean.TRUE.equals(spec.getBoolean("required"));
                Object defaultValue = defaultFor(spec, type, displayPath + ".schema." + key);
                schema.put(key, new PluginManifest.SchemaKey(
                        key, type, required, defaultValue, spec.getString("example"), spec.getString("hint")));
            }
        }
        return new PluginManifest(id, table, version, jkCompat, schema);
    }

    private static Object defaultFor(TomlTable spec, PluginManifest.SchemaKey.Type type, String where) {
        if (!spec.contains("default")) return null;
        return switch (type) {
            case STRING -> spec.getString("default");
            case BOOL -> spec.getBoolean("default");
            case INT -> spec.getLong("default");
            case STRING_LIST -> {
                TomlArray arr = spec.getArray("default");
                if (arr == null) throw new JkBuildParseException(where + ".default must be an array");
                List<String> out = new ArrayList<>(arr.size());
                for (int i = 0; i < arr.size(); i++) out.add(String.valueOf(arr.get(i)));
                yield out;
            }
        };
    }

    private static String requireString(TomlTable table, String key, String displayPath) {
        String v = table.getString(key);
        if (v == null || v.isBlank()) {
            throw new JkBuildParseException(displayPath + ".plugin." + key + " is required");
        }
        return v;
    }
}
