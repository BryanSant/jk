// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.model;

import java.util.Objects;

/**
 * A git-sourced dependency declaration (PRD §11.1).
 *
 * <p>The {@code canonicalUrl} is the result of {@code GitUrl.canonicalize}
 * — host-shorthands expanded, default ports dropped, {@code .git} suffix
 * stripped. The {@code originalUrl} preserves what the user wrote so
 * diagnostics can quote it back accurately.
 *
 * <p>{@code path} optionally points at a subdirectory inside a monorepo;
 * the resolver narrows the sparse checkout to that path. {@code submodules}
 * follows {@code .gitmodules} (default true). {@code verifySignature}
 * (default false) enforces signed-commit / signed-tag checks against
 * {@code keys.jk} (PRD §11.3).
 */
public record GitSource(
        String canonicalUrl,
        String originalUrl,
        GitRefSpec ref,
        String path,
        boolean submodules,
        boolean verifySignature) {

    public GitSource {
        Objects.requireNonNull(canonicalUrl, "canonicalUrl");
        Objects.requireNonNull(originalUrl, "originalUrl");
        Objects.requireNonNull(ref, "ref");
        // path nullable
    }

    /** Default options: submodules=true, verifySignature=false, no path. */
    public static GitSource of(String canonicalUrl, String originalUrl, GitRefSpec ref) {
        return new GitSource(canonicalUrl, originalUrl, ref, null, true, false);
    }

    public GitSource withPath(String path) {
        return new GitSource(canonicalUrl, originalUrl, ref, path, submodules, verifySignature);
    }

    public GitSource withSubmodules(boolean submodules) {
        return new GitSource(canonicalUrl, originalUrl, ref, path, submodules, verifySignature);
    }

    public GitSource withVerifySignature(boolean verifySignature) {
        return new GitSource(canonicalUrl, originalUrl, ref, path, submodules, verifySignature);
    }
}
