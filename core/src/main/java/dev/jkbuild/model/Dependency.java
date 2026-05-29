// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.util.Objects;

/**
 * A declared dependency in {@code jk.toml} (or a {@code //jk dep} script
 * directive). Carries the user-chosen short {@code name} (the manifest
 * key), the resolved {@code module} (group:artifact), a version selector,
 * and an optional source override (git or local path).
 *
 * <p>For git deps the version field carries the synthetic marker
 * {@code "git"} so the record's non-null invariant holds; consumers
 * gate on {@link #isGit()} rather than reading the marker.
 *
 * <p>For local-path deps the version field carries the synthetic marker
 * {@code "path"}; consumers gate on {@link #isPath()}.
 *
 * <p>The {@code pinned} flag is <b>derived</b> from the resolution mode:
 * <ul>
 *   <li>Exact selector ({@code =1.2.3}) → pinned.</li>
 *   <li>Git source / path source → pinned (the source itself is the pin).</li>
 *   <li>Caret, Tilde, Range, Latest → floating.</li>
 * </ul>
 */
public record Dependency(
        String name,
        String module,
        VersionSelector version,
        GitSource gitSource,
        String pathSource,
        boolean pinned) {

    public Dependency {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(version, "version");
        if (!module.contains(":") || module.indexOf(':') != module.lastIndexOf(':')) {
            throw new IllegalArgumentException(
                    "dependency module must be 'group:artifact' (got: " + module + ")");
        }
        if (gitSource != null && pathSource != null) {
            throw new IllegalArgumentException(
                    "dependency cannot set both git and path sources");
        }
        // Derive pinned from the resolution mode, regardless of the
        // value the caller passed. Source-backed deps are always pinned;
        // for coord deps, only an Exact selector pins.
        pinned = derivePinned(version, gitSource, pathSource);
    }

    /** Maven-coord constructor (no source override). Name defaults to artifactId. */
    public Dependency(String module, VersionSelector version) {
        this(artifactOf(module), module, version, null, null, false);
    }

    /**
     * Legacy three-arg constructor preserved for non-core callers that
     * passed an explicit {@code pinned} flag. The flag is now derived, so
     * the parameter is ignored.
     */
    public Dependency(String module, VersionSelector version, boolean pinnedIgnored) {
        this(artifactOf(module), module, version, null, null, false);
    }

    /** Maven-coord with an explicit short name. */
    public static Dependency of(String name, String module, VersionSelector version) {
        return new Dependency(name, module, version, null, null, false);
    }

    /** Git-sourced constructor; version is a synthetic marker. */
    public static Dependency git(String module, GitSource source) {
        return new Dependency(artifactOf(module), module,
                VersionSelector.parse("=git"), source, null, false);
    }

    /** Git-sourced with explicit short name. */
    public static Dependency git(String name, String module, GitSource source) {
        return new Dependency(name, module, VersionSelector.parse("=git"),
                source, null, false);
    }

    /** Local-path sourced; version is a synthetic marker. */
    public static Dependency path(String name, String module, String path) {
        Objects.requireNonNull(path, "path");
        return new Dependency(name, module, VersionSelector.parse("=path"),
                null, path, false);
    }

    public boolean isGit() {
        return gitSource != null;
    }

    public boolean isPath() {
        return pathSource != null;
    }

    public String group() {
        return module.substring(0, module.indexOf(':'));
    }

    public String artifact() {
        return module.substring(module.indexOf(':') + 1);
    }

    private static String artifactOf(String module) {
        Objects.requireNonNull(module, "module");
        int idx = module.indexOf(':');
        if (idx < 0) return module;
        return module.substring(idx + 1);
    }

    private static boolean derivePinned(VersionSelector version,
                                        GitSource gitSource,
                                        String pathSource) {
        if (gitSource != null || pathSource != null) return true;
        return version instanceof VersionSelector.Exact;
    }
}
