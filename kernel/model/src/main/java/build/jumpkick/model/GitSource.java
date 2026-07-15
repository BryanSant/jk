// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.model;

import java.util.Objects;

/**
 * A git-sourced dependency declaration (PRD §11.1).
 *
 * <p>The {@code canonicalUrl} is the result of {@code GitUrl.canonicalize} — host-shorthands
 * expanded, default ports dropped, {@code .git} suffix stripped. The {@code originalUrl} preserves
 * what the user wrote so diagnostics can quote it back accurately.
 *
 * <p>The coordinate ({@code group}, {@code name}) and version are always read from the cloned
 * repo's {@code jk.toml} at materialization time — there are no override fields. For a
 * {@link GitRefSpec.Tag} ref, version is derived from the tag name; for a {@link GitRefSpec.Branch}
 * ref, version is a {@code <branch>-SNAPSHOT} string; for a {@link GitRefSpec.Rev} ref, version is
 * a tag-anchored timestamp pseudo-version.
 *
 * <p>Every ref type is materialized once and pinned in {@code jk.lock} like any other coordinate.
 * A {@link GitRefSpec.Branch} ref is never re-resolved by an ordinary build — only an explicit
 * {@code jk update --git}/{@code jk fetch} moves its pin forward. There is no local freshness-window
 * cache standing in for that pin; the lockfile is the pin.
 *
 * <p>{@code shallow} — when {@code true}, jk uses a shallow clone (depth 1) for the initial bare
 * clone. This is set for explicit {@code tag = "..."} table entries, where a single tagged commit
 * is all that is needed. URL-embedded refs ({@code url@name} or {@code url#sha}) always use a full
 * clone ({@code shallow = false}), even when the embedded ref resolves to a tag.
 *
 * <p>{@code path} optionally points at a subdirectory inside a monorepo; the resolver narrows the
 * sparse checkout to that path. {@code submodules} follows {@code .gitmodules} (default true).
 * {@code verifySignature} (default false) enforces signed-commit / signed-tag checks against {@code
 * keys.jk} (PRD §11.3).
 */
public record GitSource(
        String canonicalUrl,
        String originalUrl,
        GitRefSpec ref,
        String path,
        boolean submodules,
        boolean verifySignature,
        boolean shallow) {

    public GitSource {
        Objects.requireNonNull(canonicalUrl, "canonicalUrl");
        Objects.requireNonNull(originalUrl, "originalUrl");
        Objects.requireNonNull(ref, "ref");
        // path nullable
    }

    /**
     * {@code shallow} defaults to {@code true} for {@link GitRefSpec.Tag} refs (the common
     * explicit-tag case); callers that need URL-embedded-tag semantics should use the full
     * constructor with {@code shallow = false}.
     */
    public GitSource(
            String canonicalUrl,
            String originalUrl,
            GitRefSpec ref,
            String path,
            boolean submodules,
            boolean verifySignature) {
        this(canonicalUrl, originalUrl, ref, path, submodules, verifySignature, ref instanceof GitRefSpec.Tag);
    }

    /** Default options: submodules=true, verifySignature=false, no path. */
    public static GitSource of(String canonicalUrl, String originalUrl, GitRefSpec ref) {
        return new GitSource(canonicalUrl, originalUrl, ref, null, true, false);
    }

    public GitSource withPath(String path) {
        return new GitSource(canonicalUrl, originalUrl, ref, path, submodules, verifySignature, shallow);
    }

    public GitSource withSubmodules(boolean submodules) {
        return new GitSource(canonicalUrl, originalUrl, ref, path, submodules, verifySignature, shallow);
    }

    public GitSource withVerifySignature(boolean verifySignature) {
        return new GitSource(canonicalUrl, originalUrl, ref, path, submodules, verifySignature, shallow);
    }
}
