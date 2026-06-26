// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.util.Objects;

/**
 * A git-sourced dependency declaration (PRD §11.1).
 *
 * <p>The {@code canonicalUrl} is the result of {@code GitUrl.canonicalize} — host-shorthands
 * expanded, default ports dropped, {@code .git} suffix stripped. The {@code originalUrl} preserves
 * what the user wrote so diagnostics can quote it back accurately.
 *
 * <p>{@code path} optionally points at a subdirectory inside a monorepo; the resolver narrows the
 * sparse checkout to that path. {@code submodules} follows {@code .gitmodules} (default true).
 * {@code verifySignature} (default false) enforces signed-commit / signed-tag checks against {@code
 * keys.jk} (PRD §11.3).
 *
 * <p>{@code overrideGroup} / {@code overrideArtifact} / {@code overrideVersion} are the optional
 * discovery overrides (docs/git-source-deps.md §"Discovery with override"). Materialization
 * discovers the coordinate from the cloned repo's {@code [project]} and derives the version from
 * the ref; a non-null override replaces the corresponding discovered/derived value. All-null is
 * pure discovery.
 *
 * <p>{@code fetch} is the freshness policy for a <em>branch</em> ref's remote tip (immutable
 * tag/rev ignore it): {@code "always"}/{@code "0"} re-resolve every build, otherwise a window
 * ({@code "30m"}, {@code "12h"}, {@code "48h"}, {@code "3d"}, …) within which a previously-resolved
 * tip is reused. {@code null} means the default window (12h). See {@code GitFetcher}.
 */
public record GitSource(
        String canonicalUrl,
        String originalUrl,
        GitRefSpec ref,
        String path,
        boolean submodules,
        boolean verifySignature,
        String overrideGroup,
        String overrideArtifact,
        String overrideVersion,
        String fetch) {

    public GitSource {
        Objects.requireNonNull(canonicalUrl, "canonicalUrl");
        Objects.requireNonNull(originalUrl, "originalUrl");
        Objects.requireNonNull(ref, "ref");
        // path + overrides + fetch nullable
    }

    /** Without coordinate/version overrides — the common case. */
    public GitSource(
            String canonicalUrl,
            String originalUrl,
            GitRefSpec ref,
            String path,
            boolean submodules,
            boolean verifySignature) {
        this(canonicalUrl, originalUrl, ref, path, submodules, verifySignature, null, null, null, null);
    }

    /** Default options: submodules=true, verifySignature=false, no path, no overrides. */
    public static GitSource of(String canonicalUrl, String originalUrl, GitRefSpec ref) {
        return new GitSource(canonicalUrl, originalUrl, ref, null, true, false);
    }

    public GitSource withPath(String path) {
        return new GitSource(
                canonicalUrl,
                originalUrl,
                ref,
                path,
                submodules,
                verifySignature,
                overrideGroup,
                overrideArtifact,
                overrideVersion,
                fetch);
    }

    public GitSource withSubmodules(boolean submodules) {
        return new GitSource(
                canonicalUrl,
                originalUrl,
                ref,
                path,
                submodules,
                verifySignature,
                overrideGroup,
                overrideArtifact,
                overrideVersion,
                fetch);
    }

    public GitSource withVerifySignature(boolean verifySignature) {
        return new GitSource(
                canonicalUrl,
                originalUrl,
                ref,
                path,
                submodules,
                verifySignature,
                overrideGroup,
                overrideArtifact,
                overrideVersion,
                fetch);
    }

    /** Attach discovery overrides; any argument may be {@code null} to keep discovery. */
    public GitSource withOverrides(String group, String artifact, String version) {
        return new GitSource(
                canonicalUrl, originalUrl, ref, path, submodules, verifySignature, group, artifact, version, fetch);
    }

    /** Attach the branch-tip freshness policy ({@code null} = default 12h window). */
    public GitSource withFetch(String fetch) {
        return new GitSource(
                canonicalUrl,
                originalUrl,
                ref,
                path,
                submodules,
                verifySignature,
                overrideGroup,
                overrideArtifact,
                overrideVersion,
                fetch);
    }

    /** True when a coordinate or version override is set (vs. pure discovery). */
    public boolean hasOverrides() {
        return overrideGroup != null || overrideArtifact != null || overrideVersion != null;
    }
}
