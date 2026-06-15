// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.util.Objects;

/**
 * A declared dependency in {@code jk.toml} (or a {@code //jk dep} script
 * directive). Carries the user-chosen short {@code library} handle (the
 * manifest key), the resolved {@code module} (group:artifact), a version
 * selector, and an optional source override (git or local path).
 *
 * <p>Terminology: {@code library} is the short local handle the user types; the
 * Maven artifactId segment of {@code module} is exposed as {@link #name()}
 * (Gradle's "name" for that coordinate segment).
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
        String library,
        String module,
        VersionSelector version,
        GitSource gitSource,
        String pathSource,
        String sha256,
        boolean pinned,
        boolean optional) {

    public Dependency {
        Objects.requireNonNull(library, "library");
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(version, "version");
        if (!module.contains(":") || module.indexOf(':') != module.lastIndexOf(':')) {
            throw new IllegalArgumentException(
                    "dependency module must be 'group:artifact' (got: " + module + ")");
        }
        int sourceBits = (gitSource != null ? 1 : 0)
                + (pathSource != null ? 1 : 0)
                + (sha256 != null ? 1 : 0);
        if (sourceBits > 1) {
            throw new IllegalArgumentException(
                    "dependency cannot set more than one of git, path, or sha256 sources");
        }
        // Derive pinned from the resolution mode, regardless of the
        // value the caller passed. Source-backed deps are always pinned;
        // for coord deps, only an Exact selector pins.
        pinned = derivePinned(version, gitSource, pathSource, sha256);
    }

    /**
     * Back-compat constructor for the pre-{@code optional} 7-arg shape — every
     * existing factory and caller routes through here, defaulting to a
     * non-optional (always-resolved) dependency.
     */
    public Dependency(String library, String module, VersionSelector version,
                      GitSource gitSource, String pathSource, String sha256, boolean pinned) {
        this(library, module, version, gitSource, pathSource, sha256, pinned, false);
    }

    /** A copy of this dependency flagged optional (feature-gated) or not. */
    public Dependency withOptional(boolean optional) {
        return new Dependency(library, module, version, gitSource, pathSource, sha256, pinned, optional);
    }

    /** Maven-coord constructor (no source override). Library defaults to artifactId. */
    public Dependency(String module, VersionSelector version) {
        this(artifactOf(module), module, version, null, null, null, false);
    }

    /**
     * Legacy three-arg constructor preserved for non-core callers that
     * passed an explicit {@code pinned} flag. The flag is now derived, so
     * the parameter is ignored.
     */
    public Dependency(String module, VersionSelector version, boolean pinnedIgnored) {
        this(artifactOf(module), module, version, null, null, null, false);
    }

    /** Maven-coord with an explicit library handle. */
    public static Dependency of(String library, String module, VersionSelector version) {
        return new Dependency(library, module, version, null, null, null, false);
    }

    /** Git-sourced constructor; version is a synthetic marker. */
    public static Dependency git(String module, GitSource source) {
        return new Dependency(artifactOf(module), module,
                VersionSelector.parse("=git"), source, null, null, false);
    }

    /** Git-sourced with explicit library handle. */
    public static Dependency git(String library, String module, GitSource source) {
        return new Dependency(library, module, VersionSelector.parse("=git"),
                source, null, null, false);
    }

    /** Local-path sourced; version is a synthetic marker. */
    public static Dependency path(String library, String module, String path) {
        Objects.requireNonNull(path, "path");
        return new Dependency(library, module, VersionSelector.parse("=path"),
                null, path, null, false);
    }

    /** CAS file-sourced; pinned to an exact version. */
    public static Dependency file(String library, String module, String version, String sha256) {
        Objects.requireNonNull(sha256, "sha256");
        return new Dependency(library, module, VersionSelector.parse("=" + version),
                null, null, sha256, false);
    }

    public boolean isGit() {
        return gitSource != null;
    }

    public boolean isPath() {
        return pathSource != null;
    }

    public boolean isFile() {
        return sha256 != null;
    }

    public String group() {
        return module.substring(0, module.indexOf(':'));
    }

    /** The Maven artifactId segment of {@code module} (Gradle's "name"). */
    public String name() {
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
                                        String pathSource,
                                        String sha256) {
        if (gitSource != null || pathSource != null || sha256 != null) return true;
        return version instanceof VersionSelector.Exact;
    }
}
