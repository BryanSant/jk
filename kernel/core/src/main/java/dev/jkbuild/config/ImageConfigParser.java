// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Parses the {@code [image]} block (PRD §22.2). Returns plain data — concrete {@code ImageConfig}
 * construction lives in the {@code :image} module so :core stays free of jib-core dependencies.
 */
public final class ImageConfigParser {

    public record ImageConfigData(
            String base,
            String user,
            List<Integer> ports,
            Map<String, String> env,
            Map<String, String> labels,
            String registry,
            String tag,
            List<String> platforms,
            String main) {}

    private ImageConfigParser() {}

    public static ImageConfigData parse(Path file) throws IOException {
        return parse(Files.readString(file));
    }

    public static ImageConfigData parse(String toml) {
        TomlParseResult result = Toml.parse(toml);
        if (result.hasErrors()) {
            throw new JkBuildParseException(
                    "failed to parse jk.toml: " + result.errors().getFirst().getMessage());
        }
        TomlTable image = result.getTable("image");
        if (image == null) {
            return new ImageConfigData(null, null, List.of(), Map.of(), Map.of(), null, null, List.of(), null);
        }
        return new ImageConfigData(
                image.getString("base"),
                image.getString("user"),
                optionalIntList(image, "ports"),
                optionalStringMap(image, "env"),
                optionalStringMap(image, "labels"),
                image.getString("registry"),
                image.getString("tag"),
                optionalStringList(image, "platforms"),
                image.getString("main"));
    }

    private static List<String> optionalStringList(TomlTable table, String key) {
        TomlArray arr = table.getArray(key);
        if (arr == null) return List.of();
        List<String> result = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            Object element = arr.get(i);
            if (!(element instanceof String s)) {
                throw new JkBuildParseException("expected `image." + key + "` to be a list of strings");
            }
            result.add(s);
        }
        return List.copyOf(result);
    }

    private static List<Integer> optionalIntList(TomlTable table, String key) {
        TomlArray arr = table.getArray(key);
        if (arr == null) return List.of();
        List<Integer> result = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            Object element = arr.get(i);
            if (!(element instanceof Long l)) {
                throw new JkBuildParseException("expected `image." + key + "` to be a list of integers");
            }
            result.add(l.intValue());
        }
        return List.copyOf(result);
    }

    private static Map<String, String> optionalStringMap(TomlTable parent, String key) {
        TomlTable t = parent.getTable(key);
        if (t == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : t.keySet()) {
            Object v = t.get(k);
            out.put(k, v == null ? "" : v.toString());
        }
        return out;
    }
}
