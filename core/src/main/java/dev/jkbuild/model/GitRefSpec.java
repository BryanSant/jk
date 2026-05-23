// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.util.Objects;

/**
 * What ref of a git repository to resolve (PRD §11.1). One of:
 *
 * <ul>
 *   <li>{@link Tag} — an annotated or lightweight tag (e.g. {@code v1.2.3}).</li>
 *   <li>{@link Branch} — a moving branch ref (e.g. {@code main}).</li>
 *   <li>{@link Rev} — an explicit 40-char SHA, the canonical pin.</li>
 * </ul>
 *
 * <p>Tags and branches are mutable; only a {@link Rev} is fully reproducible.
 * The resolver always pins to a SHA in the lockfile regardless of the spec
 * (PRD §11.2).
 */
public sealed interface GitRefSpec {

    /** Token suitable for embedding in canonical lockfile/source strings. */
    String token();

    /** Whether the spec is intrinsically reproducible (a full SHA). */
    default boolean isPin() { return false; }

    record Tag(String name) implements GitRefSpec {
        public Tag { Objects.requireNonNull(name, "name"); }
        @Override public String token() { return "tag=" + name; }
    }

    record Branch(String name) implements GitRefSpec {
        public Branch { Objects.requireNonNull(name, "name"); }
        @Override public String token() { return "branch=" + name; }
    }

    record Rev(String sha) implements GitRefSpec {
        public Rev {
            Objects.requireNonNull(sha, "sha");
            if (sha.length() != 40 || !sha.chars().allMatch(c
                    -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                throw new IllegalArgumentException(
                        "rev must be a 40-char lowercase hex SHA (got: " + sha + ")");
            }
        }
        @Override public String token() { return "rev=" + sha; }
        @Override public boolean isPin() { return true; }
    }
}
