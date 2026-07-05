// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.util.Objects;

/**
 * A declared dependency in {@code jk.toml} (or a {@code //jk dep} script directive). Carries the
 * user-chosen short {@code library} handle (the manifest key), the resolved {@code module}
 * (group:artifact), a version selector, and an optional git source override.
 *
 * <p>Terminology: {@code library} is the short local handle the user types; the Maven artifactId
 * segment of {@code module} is exposed as {@link #name()} (Gradle's "name" for that coordinate
 * segment).
 *
 * <p>There is no local-path dependency source: a local, hand-edited sibling project is always a
 * {@code [workspace] modules} entry, resolved via {@code WorkspaceClasspath}, never a {@code
 * Dependency}.
 *
 * <p>For git deps the version field carries the synthetic marker {@code "git"} so the record's
 * non-null invariant holds; consumers gate on {@link #isGit()} rather than reading the marker.
 *
 * <h2>Source discriminators (no magic-string sniffing)</h2>
 *
 * <p>A dependency's <em>source kind</em> is authoritative through named predicates, never through
 * callers pattern-matching the {@code module} string:
 *
 * <ul>
 *   <li>{@link #isGit()} — a git-sourced dep ({@code gitSource != null}).
 *   <li>{@link #isFile()} — a CAS file-sourced dep ({@code sha256 != null}).
 *   <li>{@link #isWorkspace()} — an unresolved workspace-sibling placeholder.
 *   <li>otherwise — a Maven coordinate.
 * </ul>
 *
 * <p>Two source kinds cannot carry a real {@code group:artifact} at parse time, so they encode a
 * synthetic {@code module} to satisfy the coordinate invariant: git deps use {@link #GIT_PREFIX}
 * (write-only — nothing reads it back; discriminate via {@link #isGit()}), and unresolved workspace
 * siblings use {@link #WORKSPACE_PREFIX} + the sibling name (read back via {@link #isWorkspaceRef}
 * / {@link #workspaceName} because the placeholder legitimately travels as a bare module string
 * through classpath/tree/graph consumers before {@code WorkspaceMerge} rewrites it). Both sentinels
 * are defined here <em>once</em>; consumers never spell the literal.
 *
 * <p>The {@code pinned} flag is <b>derived</b> from the resolution mode:
 *
 * <ul>
 *   <li>Exact selector ({@code =1.2.3}) → pinned.
 *   <li>Git source → pinned (the source itself is the pin).
 *   <li>Caret, Tilde, Range, Latest → floating.
 * </ul>
 */
public record Dependency(
        String library,
        String module,
        VersionSelector version,
        GitSource gitSource,
        String sha256,
        boolean pinned,
        boolean optional) {

    /**
     * Synthetic {@code module} prefix for an unresolved workspace-sibling placeholder ({@code
     * workspace:<name>}). This one legitimately travels as a bare module string through classpath,
     * dependency-tree, and build-graph consumers until {@code WorkspaceMerge} rewrites it to the
     * sibling's real coord — so it is read back via {@link #isWorkspaceRef}/{@link #workspaceName}.
     */
    public static final String WORKSPACE_PREFIX = "workspace:";

    /**
     * Synthetic {@code module} prefix for a bare-name git dep whose real coordinate isn't known at
     * parse time ({@code git:<name>}). Write-only: nothing reads it back — git deps discriminate via
     * {@link #isGit()}. It exists only to satisfy the {@code group:artifact} invariant.
     */
    public static final String GIT_PREFIX = "git:";

    public Dependency {
        Objects.requireNonNull(library, "library");
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(version, "version");
        if (!module.contains(":") || module.indexOf(':') != module.lastIndexOf(':')) {
            throw new IllegalArgumentException("dependency module must be 'group:artifact' (got: " + module + ")");
        }
        int sourceBits = (gitSource != null ? 1 : 0) + (sha256 != null ? 1 : 0);
        if (sourceBits > 1) {
            throw new IllegalArgumentException("dependency cannot set more than one of git or sha256 sources");
        }
        // Derive pinned from the resolution mode, regardless of the
        // value the caller passed. Source-backed deps are always pinned;
        // for coord deps, only an Exact selector pins.
        pinned = derivePinned(version, gitSource, sha256);
    }

    /**
     * Back-compat constructor for the pre-{@code optional} 6-arg shape — every existing factory and
     * caller routes through here, defaulting to a non-optional (always-resolved) dependency.
     */
    public Dependency(String library, String module, VersionSelector version, GitSource gitSource, String sha256, boolean pinned) {
        this(library, module, version, gitSource, sha256, pinned, false);
    }

    /** A copy of this dependency flagged optional (feature-gated) or not. */
    public Dependency withOptional(boolean optional) {
        return new Dependency(library, module, version, gitSource, sha256, pinned, optional);
    }

    /** Maven-coord constructor (no source override). Library defaults to artifactId. */
    public Dependency(String module, VersionSelector version) {
        this(artifactOf(module), module, version, null, null, false);
    }

    /**
     * Legacy three-arg constructor preserved for non-core callers that passed an explicit {@code
     * pinned} flag. The flag is now derived, so the parameter is ignored.
     */
    public Dependency(String module, VersionSelector version, boolean pinnedIgnored) {
        this(artifactOf(module), module, version, null, null, false);
    }

    /** Maven-coord with an explicit library handle. */
    public static Dependency of(String library, String module, VersionSelector version) {
        return new Dependency(library, module, version, null, null, false);
    }

    /** Git-sourced constructor; version is a synthetic marker. */
    public static Dependency git(String module, GitSource source) {
        return new Dependency(artifactOf(module), module, VersionSelector.parse("=git"), source, null, false);
    }

    /** Git-sourced with explicit library handle. */
    public static Dependency git(String library, String module, GitSource source) {
        return new Dependency(library, module, VersionSelector.parse("=git"), source, null, false);
    }

    /**
     * Bare-name git dep whose real coordinate isn't known at parse time — the {@code module} is the
     * synthetic {@link #GIT_PREFIX}{@code <name>} placeholder. Callers must not spell the prefix.
     */
    public static Dependency gitByName(String name, GitSource source) {
        return git(name, GIT_PREFIX + name, source);
    }

    /**
     * Unresolved workspace-sibling placeholder — the {@code module} is {@link #WORKSPACE_PREFIX}{@code
     * <name>} and the version a synthetic {@code Latest("workspace")} marker. {@code WorkspaceMerge}
     * rewrites this to the sibling's real coord (or errors) before the resolver ever sees it.
     */
    public static Dependency workspace(String name) {
        return new Dependency(name, workspaceRef(name), new VersionSelector.Latest("workspace"), null, null, false);
    }

    /** CAS file-sourced; pinned to an exact version. */
    public static Dependency file(String library, String module, String version, String sha256) {
        Objects.requireNonNull(sha256, "sha256");
        return new Dependency(library, module, VersionSelector.parse("=" + version), null, sha256, false);
    }

    public boolean isGit() {
        return gitSource != null;
    }

    public boolean isFile() {
        return sha256 != null;
    }

    /** True when this is an unresolved {@code workspace:<name>} sibling placeholder. */
    public boolean isWorkspace() {
        return isWorkspaceRef(module);
    }

    /** The sibling name of a workspace placeholder, or {@code null} if this isn't one. */
    public String workspaceName() {
        return workspaceName(module);
    }

    /** Whether a bare module string is a {@code workspace:<name>} placeholder. */
    public static boolean isWorkspaceRef(String module) {
        return module != null && module.startsWith(WORKSPACE_PREFIX);
    }

    /** The sibling name inside a {@code workspace:<name>} module string, or {@code null}. */
    public static String workspaceName(String module) {
        return isWorkspaceRef(module) ? module.substring(WORKSPACE_PREFIX.length()) : null;
    }

    /** Build the synthetic {@code workspace:<name>} module string for a sibling. */
    public static String workspaceRef(String name) {
        return WORKSPACE_PREFIX + name;
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

    private static boolean derivePinned(VersionSelector version, GitSource gitSource, String sha256) {
        if (gitSource != null || sha256 != null) return true;
        return version instanceof VersionSelector.Exact;
    }
}
