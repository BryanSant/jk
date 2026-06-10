// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.util.Objects;

/**
 * A third-party plugin declared in {@code [plugins]} of {@code jk.toml}.
 *
 * <p>Format:
 * <pre>{@code
 * [plugins]
 * my-plugin = { group = "com.example", name = "my-jk-plugin", version = "1.2.0" }
 * }</pre>
 *
 * <p>The plugin jar is resolved from the project's declared Maven repos (or
 * Maven Central), its SHA-256 is pinned in {@code jk.lock}, and it is
 * fetched to the CAS by {@code jk sync}. Third-party plugins always run
 * with {@code isolation = process} (Posture A, §3.8 of the plugin-refactor
 * design doc).
 *
 * @param alias      the key in {@code [plugins]} (e.g. {@code "my-plugin"})
 * @param group      Maven groupId (e.g. {@code "com.example"})
 * @param name       Maven artifactId (e.g. {@code "my-jk-plugin"})
 * @param version    exact version (e.g. {@code "1.2.0"})
 */
public record PluginDeclaration(String alias, String group, String name, String version) {

    public PluginDeclaration {
        Objects.requireNonNull(alias,   "alias");
        Objects.requireNonNull(group,   "group");
        Objects.requireNonNull(name,    "name");
        Objects.requireNonNull(version, "version");
        if (group.isBlank()) throw new IllegalArgumentException("plugin '" + alias + "': group must not be blank");
        if (name.isBlank())  throw new IllegalArgumentException("plugin '" + alias + "': name must not be blank");
        if (version.isBlank()) throw new IllegalArgumentException("plugin '" + alias + "': version must not be blank");
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
