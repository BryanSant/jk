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
            String main,
            /** {@code image.docker-executable} — override for the docker/podman CLI. */
            String dockerExecutable) {}

    private ImageConfigParser() {}

    /**
     * Merge two {@link ImageConfigData} layers: {@code project} wins over {@code global} for every
     * field. String fields use the project value when non-blank; list fields use the project value
     * when non-empty; map fields are union-merged with project keys overriding global keys.
     */
    public static ImageConfigData merge(ImageConfigData project, ImageConfigData global) {
        String base = nonBlank(project.base()) != null ? project.base() : global.base();
        String user = nonBlank(project.user()) != null ? project.user() : global.user();
        String registry = nonBlank(project.registry()) != null ? project.registry() : global.registry();
        String tag = nonBlank(project.tag()) != null ? project.tag() : global.tag();
        String main = nonBlank(project.main()) != null ? project.main() : global.main();
        List<Integer> ports = !project.ports().isEmpty() ? project.ports() : global.ports();
        List<String> platforms = !project.platforms().isEmpty() ? project.platforms() : global.platforms();
        Map<String, String> env = new LinkedHashMap<>(global.env());
        env.putAll(project.env());
        Map<String, String> labels = new LinkedHashMap<>(global.labels());
        labels.putAll(project.labels());
        String dockerExecutable = nonBlank(project.dockerExecutable()) != null
                ? project.dockerExecutable() : global.dockerExecutable();
        return new ImageConfigData(base, user, ports, Map.copyOf(env), Map.copyOf(labels),
                registry, tag, platforms, main, dockerExecutable);
    }

    private static String nonBlank(String s) {
        return (s != null && !s.isBlank()) ? s : null;
    }

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
            return new ImageConfigData(null, null, List.of(), Map.of(), Map.of(), null, null, List.of(), null, null);
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
                image.getString("main"),
                image.getString("docker-executable"));
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
