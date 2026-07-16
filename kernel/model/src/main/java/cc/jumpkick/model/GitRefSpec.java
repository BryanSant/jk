// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Objects;

/**
 * What ref of a git repository to resolve (PRD §11.1). One of:
 *
 * <p>Every ref type is materialized once and pinned in {@code jk.lock} as an ordinary locked
 * artifact. The only behavioral difference between them is what {@code jk update --git}/{@code jk
 * fetch} do when explicitly re-resolving: a {@link Tag} or {@link Rev} is expected to stay put (and
 * re-resolving to a different SHA is a tag-rewrite event); a {@link Branch} is expected to move to
 * its current tip. An ordinary build never re-resolves any of them — it just reads the existing
 * lockfile pin.
 *
 * <ul>
 *   <li>{@link Tag} — an annotated or lightweight tag (e.g. {@code v1.2.3}). When declared via an
 *       explicit {@code tag = "..."} table field, jk does a shallow clone (depth 1) to minimise
 *       bandwidth. URL-embedded tag refs ({@code url@v1.2.3}) always do a full clone.
 *   <li>{@link Branch} — a moving branch ref (e.g. {@code main}). Its tip is resolved once and
 *       pinned like any other ref; {@code jk update --git}/{@code jk fetch} re-resolve it to the
 *       current tip on demand.
 *   <li>{@link Rev} — an explicit 40-char (or abbreviated) commit SHA. Always does a full clone.
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
