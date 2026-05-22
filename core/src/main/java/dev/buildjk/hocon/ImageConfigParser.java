// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.hocon;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigSyntax;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;
// dev.buildjk.image.ImageConfig is in the :image module which depends on
// :core, so we cannot reference its concrete type from here. We return the
// parsed values as a {@link ImageConfigData} record that :image converts.

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the {@code image { ... }} block (PRD §22.2). Returns plain data
 * — concrete {@code ImageConfig} construction lives in the {@code :image}
 * module so :core stays free of jib-core dependencies.
 */
public final class ImageConfigParser {

    public record ImageConfigData(
            String base, String user, List<Integer> ports,
            Map<String, String> env, Map<String, String> labels,
            String registry, String tag, List<String> platforms,
            String mainClass) {}

    private ImageConfigParser() {}

    public static ImageConfigData parse(Path buildJk) throws IOException {
        return parse(Files.readString(buildJk));
    }

    public static ImageConfigData parse(String hocon) {
        Config config = ConfigFactory.parseString(hocon,
                ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF));
        if (!config.hasPath("image")) {
            return new ImageConfigData(null, null, List.of(), Map.of(), Map.of(),
                    null, null, List.of(), null);
        }
        Config image = config.getConfig("image");
        return new ImageConfigData(
                optionalString(image, "base"),
                optionalString(image, "user"),
                optionalIntList(image, "ports"),
                optionalStringMap(image, "env"),
                optionalStringMap(image, "labels"),
                optionalString(image, "registry"),
                optionalString(image, "tag"),
                optionalStringList(image, "platforms"),
                optionalString(image, "main-class"));
    }

    private static String optionalString(Config config, String path) {
        return config.hasPath(path) ? config.getString(path) : null;
    }

    private static List<String> optionalStringList(Config config, String path) {
        return config.hasPath(path) ? List.copyOf(config.getStringList(path)) : List.of();
    }

    private static List<Integer> optionalIntList(Config config, String path) {
        if (!config.hasPath(path)) return List.of();
        List<Integer> result = new ArrayList<>();
        for (Integer i : config.getIntList(path)) result.add(i);
        return result;
    }

    private static Map<String, String> optionalStringMap(Config config, String path) {
        if (!config.hasPath(path)) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigValue> e : config.getConfig(path).root().entrySet()) {
            if (e.getValue().valueType() == ConfigValueType.STRING) {
                out.put(e.getKey(), (String) e.getValue().unwrapped());
            } else {
                out.put(e.getKey(), e.getValue().unwrapped().toString());
            }
        }
        return out;
    }
}
