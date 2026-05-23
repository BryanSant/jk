// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.model;

import java.util.Objects;

/**
 * A declared dependency in {@code jk.toml} (or a {@code //jk dep} script
 * directive). Either a Maven coord (module + version selector) or a
 * git-sourced one (module + git source; the version is filled at resolve
 * time from the repo's own {@code jk.toml}).
 *
 * <p>For git deps the version field carries the synthetic marker
 * {@code "git"} so the record's non-null invariant holds; consumers
 * gate on {@link #isGit()} rather than reading the marker.
 *
 * <p>The {@code pinned} flag records the user's intent:
 * <ul>
 *   <li>{@code pinned = true} — declared as {@code group:artifact:version}
 *       (the {@code :} form). The version never floats; {@code jk update}
 *       leaves it alone unless the user edits the manifest.</li>
 *   <li>{@code pinned = false} — declared as {@code group:artifact@version}
 *       (the {@code @} form). The constraint floats per its grammar:
 *       {@code jk update} re-resolves it against the latest registry state.</li>
 * </ul>
 */
public record Dependency(String module, VersionSelector version, GitSource gitSource, boolean pinned) {

    public Dependency {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(version, "version");
        if (!module.contains(":") || module.indexOf(':') != module.lastIndexOf(':')) {
            throw new IllegalArgumentException(
                    "dependency module must be 'group:artifact' (got: " + module + ")");
        }
    }

    /** Maven-coord constructor (no git source). Defaults to pinned. */
    public Dependency(String module, VersionSelector version) {
        this(module, version, null, true);
    }

    /** Maven-coord constructor with explicit pinned flag (no git source). */
    public Dependency(String module, VersionSelector version, boolean pinned) {
        this(module, version, null, pinned);
    }

    /** Git-sourced constructor; version is a synthetic marker. Pinned: SHA in lockfile. */
    public static Dependency git(String module, GitSource source) {
        return new Dependency(module, VersionSelector.parse("=git"), source, true);
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
