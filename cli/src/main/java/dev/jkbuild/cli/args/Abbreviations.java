// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.args;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure unique-prefix ("abbreviation") resolution, shared by every place jk matches a typed token
 * against a fixed set of names: top-level verbs, subcommands, and long options. The rules, in order:
 *
 * <ol>
 *   <li><b>Exact</b> — a token equal to a name always wins, so one name being a prefix of another
 *       ({@code init} vs {@code install}) never shadows the exact spelling.
 *   <li><b>Unique prefix</b> — exactly one name starts with the token ⇒ resolve to it ({@code b} ⇒
 *       {@code build}).
 *   <li><b>Ambiguous</b> — more than one distinct target matches ⇒ the caller reports the
 *       candidates ({@code ex} ⇒ {@code explain}, {@code export}).
 *   <li><b>None</b> — no name starts with the token.
 * </ol>
 *
 * <p>The candidate map may bind several names to the same target (a command and its aliases, or an
 * option and its short form); such names collapse to one target by reference identity, so {@code j}
 * matching both {@code jdk} and {@code jdks} is <em>unique</em>, not ambiguous.
 */
public final class Abbreviations {

    private Abbreviations() {}

    public enum Kind {
        EXACT,
        UNIQUE_PREFIX,
        AMBIGUOUS,
        NONE
    }

    /**
     * @param value the resolved target for {@link Kind#EXACT}/{@link Kind#UNIQUE_PREFIX}, else null
     * @param candidates the matching names (sorted) — one for an exact hit, all prefix matches for
     *     an ambiguous one, empty for none
     */
    public record Result<T>(Kind kind, T value, List<String> candidates) {
        /** True when a single target was selected (exact or unique prefix). */
        public boolean resolved() {
            return kind == Kind.EXACT || kind == Kind.UNIQUE_PREFIX;
        }
    }

    /** Resolve {@code token} against {@code byName} (iteration order preserved for stable messages). */
    public static <T> Result<T> resolve(String token, Map<String, T> byName) {
        T exact = byName.get(token);
        if (exact != null) {
            return new Result<>(Kind.EXACT, exact, List.of(token));
        }
        List<String> names = new ArrayList<>();
        List<T> distinct = new ArrayList<>();
        for (Map.Entry<String, T> e : byName.entrySet()) {
            if (e.getKey().startsWith(token)) {
                names.add(e.getKey());
                if (!containsByIdentity(distinct, e.getValue())) distinct.add(e.getValue());
            }
        }
        if (distinct.isEmpty()) return new Result<>(Kind.NONE, null, List.of());
        names.sort(String::compareTo);
        if (distinct.size() == 1) return new Result<>(Kind.UNIQUE_PREFIX, distinct.get(0), List.copyOf(names));
        return new Result<>(Kind.AMBIGUOUS, null, List.copyOf(names));
    }

    private static <T> boolean containsByIdentity(List<T> list, T value) {
        for (T t : list) {
            if (t == value) return true;
        }
        return false;
    }
}
