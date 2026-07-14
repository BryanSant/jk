// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.util.Map;
import java.util.Objects;

/**
 * A third-party plugin declared in {@code [plugins]} of {@code jk.toml}.
 *
 * <p>Supports two TOML forms — both produce the same {@code PluginDeclaration}:
 *
 * <p><b>Inline (compact):</b>
 *
 * <pre>{@code
 * [plugins]
 * my-plugin = { group = "com.example", name = "my-jk-plugin", version = "1.2.0" }
 * }</pre>
 *
 * <p><b>Sub-table (multi-line, supports plugin config):</b>
 *
 * <pre>{@code
 * [plugins.my-plugin]
 * group      = "com.example"
 * name       = "my-jk-plugin"
 * version    = "1.2.0"
 * output-dir = "dist"
 * debug      = true
 * }</pre>
 *
 * <p>Any key other than {@code group}, {@code name}, and {@code version} is collected into {@link
 * #config} and delivered to the plugin as its {@code [plugins.<alias>]} config.
 * Values are plain JDK types: {@link String}, {@link Long} (integers), {@link Double} (floats),
 * {@link Boolean}, {@link java.util.List} (arrays), and nested {@link java.util.Map}&lt;String,
 * Object&gt; (sub-tables).
 *
 * @param alias the key in {@code [plugins]} (e.g. {@code "my-plugin"})
 * @param group Maven groupId (e.g. {@code "com.example"})
 * @param name Maven artifactId (e.g. {@code "my-jk-plugin"})
 * @param version exact version (e.g. {@code "1.2.0"})
 * @param config plugin-specific config keys, converted to plain JDK types
 */
public record PluginDeclaration(String alias, String group, String name, String version, Map<String, Object> config) {

    public PluginDeclaration {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        if (group.isBlank()) throw new IllegalArgumentException("plugin '" + alias + "': group must not be blank");
        if (name.isBlank()) throw new IllegalArgumentException("plugin '" + alias + "': name must not be blank");
        if (version.isBlank()) throw new IllegalArgumentException("plugin '" + alias + "': version must not be blank");
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    /** Maven {@code group:name} coordinate without version. */
    public String coordinate() {
        return group + ":" + name;
    }

    /** Maven {@code group:name:version} coordinate. */
    public String coordinateWithVersion() {
        return group + ":" + name + ":" + version;
    }
}
