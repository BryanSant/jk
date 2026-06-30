// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.util.Objects;

/**
 * What ref of a git repository to resolve (PRD §11.1). One of:
 *
 * <ul>
 *   <li>{@link Tag} — an annotated or lightweight tag (e.g. {@code v1.2.3}). Immutable; when
 *       declared via an explicit {@code tag = "..."} table field, jk does a shallow clone (depth 1)
 *       to minimise bandwidth. URL-embedded tag refs ({@code url@v1.2.3}) always do a full clone.
 *   <li>{@link Branch} — a moving branch ref (e.g. {@code main}). Mutable; built from source as a
 *       composite dependency and never locked.
 *   <li>{@link Rev} — an explicit 40-char (or abbreviated) commit SHA. Always immutable and pinned
 *       in {@code jk.lock}. Always does a full clone.
 * </ul>
 *
 * <p>Whether the clone is shallow is governed by {@link GitSource#shallow()}, not purely by this
 * ref type — a URL-embedded tag ({@code url@v1.2.3}) carries {@link Tag} here but has
 * {@code shallow = false}.
 */
public sealed interface GitRefSpec {

    /** Token suitable for embedding in canonical lockfile/source strings. */
    String token();

    /** Whether the spec is intrinsically reproducible (a full SHA). */
    default boolean isPin() {
        return false;
    }

    /**
     * Whether the spec names an effectively-immutable ref ({@link Tag} or {@link Rev}). Immutable
     * git deps are materialized and pinned in {@code jk.lock}. A {@link Branch} is a moving target:
     * it is built from source on demand and injected onto the classpath like a {@code path}
     * dependency, never locked.
     */
    default boolean isImmutable() {
        return true;
    }

    record Tag(String name) implements GitRefSpec {
        public Tag {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String token() {
            return "tag=" + name;
        }
    }

    record Branch(String name) implements GitRefSpec {
        public Branch {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String token() {
            return "branch=" + name;
        }

        @Override
        public boolean isImmutable() {
            return false;
        }
    }

    record Rev(String sha) implements GitRefSpec {
        public Rev {
            Objects.requireNonNull(sha, "sha");
        }

        @Override
        public String token() {
            return "rev=" + sha;
        }

        @Override
        public boolean isPin() {
            return true;
        }
    }
}
