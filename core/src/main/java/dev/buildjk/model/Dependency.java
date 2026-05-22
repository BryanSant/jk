// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.model;

import java.util.Objects;

/**
 * A declared dependency in {@code build.jk}. Either a Maven coord
 * (module + version selector) or a git-sourced one (module + git source;
 * the version is filled at resolve time from the repo's own build.jk).
 *
 * <p>For git deps the version field carries the synthetic marker
 * {@code "git"} so the record's non-null invariant holds; consumers
 * gate on {@link #isGit()} rather than reading the marker.
 */
public record Dependency(String module, VersionSelector version, GitSource gitSource) {

    public Dependency {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(version, "version");
        if (!module.contains(":") || module.indexOf(':') != module.lastIndexOf(':')) {
            throw new IllegalArgumentException(
                    "dependency module must be 'group:artifact' (got: " + module + ")");
        }
    }

    /** Maven-coord constructor (no git source). */
    public Dependency(String module, VersionSelector version) {
        this(module, version, null);
    }

    /** Git-sourced constructor; version is a synthetic marker. */
    public static Dependency git(String module, GitSource source) {
        return new Dependency(module, VersionSelector.parse("=git"), source);
    }

    public boolean isGit() {
        return gitSource != null;
    }

    public String group() {
        return module.substring(0, module.indexOf(':'));
    }

    public String artifact() {
        return module.substring(module.indexOf(':') + 1);
    }
}
