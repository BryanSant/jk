// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import cc.jumpkick.resolver.Versions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A set of version strings, represented as a disjoint union of ranges. Closed under intersection,
 * union, and complement — the algebra PubGrub needs to reason about positive and negative terms.
 *
 * <p>Total order on versions comes from {@link Versions#compare(String, String)}, which is "good
 * enough" for the v0.1 first PubGrub port. Full Maven version ordering (qualifier precedence: alpha
 * &lt; beta &lt; rc &lt; ga &lt; sp) ships as a follow-up.
 */
public sealed interface VersionSet permits VersionSet.Empty, VersionSet.All, VersionSet.Range, VersionSet.Union {

    /** The empty set. */
    Empty EMPTY = Empty.INSTANCE;

    /** The universe. */
    All ALL = All.INSTANCE;

    boolean contains(String version);

    VersionSet intersect(VersionSet other);

    /** Union of two sets, producing a {@link Union} when ranges don't merge. */
    VersionSet union(VersionSet other);

    /** Set complement (with respect to the universe). */
    VersionSet complement();

    boolean isEmpty();

    /** True iff this is the universe set. */
    default boolean isAll() {
        return false;
    }

    /** True iff this set is a (non-strict) subset of {@code other}. */
    default boolean subsetOf(VersionSet other) {
        // a ⊆ b iff a ∩ ¬b = ∅
        return intersect(other.complement()).isEmpty();
    }

    // --- constructors ------------------------------------------------------

    static VersionSet exact(String version) {
        Objects.requireNonNull(version, "version");
        return new Range(version, true, version, true);
    }

    static VersionSet atLeast(String min, boolean inclusive) {
        Objects.requireNonNull(min, "min");
        return new Range(min, inclusive, null, false);
    }

    static VersionSet lessThan(String max, boolean inclusive) {
        Objects.requireNonNull(max, "max");
        return new Range(null, false, max, inclusive);
    }

    static VersionSet between(String min, boolean minInclusive, String max, boolean maxInclusive) {
        return new Range(min, minInclusive, max, maxInclusive);
    }

    // --- variants ----------------------------------------------------------

    final class Empty implements VersionSet {
        static final Empty INSTANCE = new Empty();

        private Empty() {}

        @Override
        public boolean contains(String version) {
            return false;
        }

        @Override
        public VersionSet intersect(VersionSet other) {
            return this;
        }

        @Override
        public VersionSet union(VersionSet other) {
            return other;
        }

        @Override
        public VersionSet complement() {
            return ALL;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public String toString() {
            return "∅";
        }
    }

    final class All implements VersionSet {
        static final All INSTANCE = new All();

        private All() {}

        @Override
        public boolean contains(String version) {
            return true;
        }

        @Override
        public VersionSet intersect(VersionSet other) {
            return other;
        }

        @Override
        public VersionSet union(VersionSet other) {
            return this;
        }

        @Override
        public VersionSet complement() {
            return EMPTY;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public boolean isAll() {
            return true;
        }

        @Override
        public String toString() {
            return "*";
        }
    }

    /**
     * A single range. {@code null} bounds mean unbounded on that side. Constructor normalizes
     * degenerate ranges to {@link #EMPTY}.
     */
    record Range(String min, boolean minInclusive, String max, boolean maxInclusive) implements VersionSet {

        public Range {
            // Reject inverted bounds at construction time so callers don't
            // have to remodule to check; ALL / EMPTY are the singletons for
            // unbounded / empty.
            if (min != null && max != null) {
                int cmp = Versions.compare(min, max);
                if (cmp > 0 || (cmp == 0 && (!minInclusive || !maxInclusive))) {
                    throw new IllegalArgumentException("empty range: "
                            + bracket(minInclusive, '[', '(')
                            + min
                            + ","
                            + max
                            + bracket(maxInclusive, ']', ')'));
                }
            }
        }

        @Override
        public boolean contains(String version) {
            if (min != null) {
                int cmp = Versions.compare(version, min);
                if (cmp < 0 || (cmp == 0 && !minInclusive)) return false;
            }
            if (max != null) {
                int cmp = Versions.compare(version, max);
                if (cmp > 0 || (cmp == 0 && !maxInclusive)) return false;
            }
            return true;
        }

        @Override
        public VersionSet intersect(VersionSet other) {
            return switch (other) {
                case Empty ignored -> EMPTY;
                case All ignored -> this;
                case Range r -> intersectRange(r);
                case Union u -> u.intersect(this);
            };
        }

        private VersionSet intersectRange(Range other) {
            String lo;
            boolean loInc;
            if (min == null) {
                lo = other.min;
                loInc = other.minInclusive;
            } else if (other.min == null) {
                lo = min;
                loInc = minInclusive;
            } else {
                int cmp = Versions.compare(min, other.min);
                if (cmp > 0) {
                    lo = min;
                    loInc = minInclusive;
                } else if (cmp < 0) {
                    lo = other.min;
                    loInc = other.minInclusive;
                } else {
                    lo = min;
                    loInc = minInclusive && other.minInclusive;
                }
            }
            String hi;
            boolean hiInc;
            if (max == null) {
                hi = other.max;
                hiInc = other.maxInclusive;
            } else if (other.max == null) {
                hi = max;
                hiInc = maxInclusive;
            } else {
                int cmp = Versions.compare(max, other.max);
                if (cmp < 0) {
                    hi = max;
                    hiInc = maxInclusive;
                } else if (cmp > 0) {
                    hi = other.max;
                    hiInc = other.maxInclusive;
                } else {
                    hi = max;
                    hiInc = maxInclusive && other.maxInclusive;
                }
            }
            // Empty if lo > hi, or lo == hi with either side exclusive.
            if (lo != null && hi != null) {
                int cmp = Versions.compare(lo, hi);
                if (cmp > 0) return EMPTY;
                if (cmp == 0 && (!loInc || !hiInc)) return EMPTY;
            }
            return new Range(lo, loInc, hi, hiInc);
        }

        @Override
        public VersionSet union(VersionSet other) {
            return switch (other) {
                case Empty ignored -> this;
                case All ignored -> ALL;
                case Range r -> unionRange(r);
                case Union u -> u.union(this);
            };
        }

        private VersionSet unionRange(Range other) {
            // If they overlap or touch, merge.
            if (overlapsOrTouches(other)) {
                String lo;
                boolean loInc;
                if (min == null || other.min == null) {
                    lo = null;
                    loInc = false;
                } else {
                    int cmp = Versions.compare(min, other.min);
                    if (cmp < 0) {
                        lo = min;
                        loInc = minInclusive;
                    } else if (cmp > 0) {
                        lo = other.min;
                        loInc = other.minInclusive;
                    } else {
                        lo = min;
                        loInc = minInclusive || other.minInclusive;
                    }
                }
                String hi;
                boolean hiInc;
                if (max == null || other.max == null) {
                    hi = null;
                    hiInc = false;
                } else {
                    int cmp = Versions.compare(max, other.max);
                    if (cmp > 0) {
                        hi = max;
                        hiInc = maxInclusive;
                    } else if (cmp < 0) {
                        hi = other.max;
                        hiInc = other.maxInclusive;
                    } else {
                        hi = max;
                        hiInc = maxInclusive || other.maxInclusive;
                    }
                }
                return new Range(lo, loInc, hi, hiInc);
            }
            // Disjoint: keep both, sorted.
            return Range.compareLow(this, other) < 0 ? Union.of(List.of(this, other)) : Union.of(List.of(other, this));
        }

        @Override
        public VersionSet complement() {
            // Universe minus [min..max].
            List<VersionSet> parts = new ArrayList<>(2);
            if (min != null) {
                parts.add(new Range(null, false, min, !minInclusive));
            }
            if (max != null) {
                parts.add(new Range(max, !maxInclusive, null, false));
            }
            if (parts.isEmpty()) return EMPTY; // we were ALL
            if (parts.size() == 1) return parts.getFirst();
            return Union.of(parts);
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        private boolean overlapsOrTouches(Range other) {
            // Touches: this.max == other.min (and at least one is inclusive)
            // Overlaps: intersection is non-empty
            if (!intersectRange(other).isEmpty()) return true;
            if (max != null
                    && other.min != null
                    && Versions.compare(max, other.min) == 0
                    && (maxInclusive || other.minInclusive)) {
                return true;
            }
            return min != null
                    && other.max != null
                    && Versions.compare(min, other.max) == 0
                    && (minInclusive || other.maxInclusive);
        }

        static int compareLow(Range a, Range b) {
            if (a.min == null && b.min == null) return 0;
            if (a.min == null) return -1;
            if (b.min == null) return 1;
            return Versions.compare(a.min, b.min);
        }

        @Override
        public String toString() {
            if (min != null && max != null && minInclusive && maxInclusive && Versions.compare(min, max) == 0) {
                return "{" + min + "}";
            }
            return (minInclusive ? "[" : "(")
                    + (min == null ? "-∞" : min)
                    + ","
                    + (max == null ? "+∞" : max)
                    + (maxInclusive ? "]" : ")");
        }

        private static char bracket(boolean inclusive, char inc, char exc) {
            return inclusive ? inc : exc;
        }
    }

    /** Disjoint union of two or more ranges, sorted by lower bound. */
    record Union(List<VersionSet> parts) implements VersionSet {

        public Union {
            Objects.requireNonNull(parts, "parts");
            if (parts.size() < 2) {
                throw new IllegalArgumentException("Union must have at least two parts; got: " + parts);
            }
            parts = List.copyOf(parts);
        }

        static VersionSet of(List<VersionSet> parts) {
            if (parts.isEmpty()) return EMPTY;
            if (parts.size() == 1) return parts.getFirst();
            return new Union(parts);
        }

        @Override
        public boolean contains(String version) {
            for (VersionSet part : parts) {
                if (part.contains(version)) return true;
            }
            return false;
        }

        @Override
        public VersionSet intersect(VersionSet other) {
            List<VersionSet> hits = new ArrayList<>();
            for (VersionSet part : parts) {
                VersionSet intersect = part.intersect(other);
                if (!intersect.isEmpty()) hits.add(intersect);
            }
            return Union.of(hits);
        }

        @Override
        public VersionSet union(VersionSet other) {
            VersionSet result = other;
            for (VersionSet part : parts) {
                result = result.union(part);
            }
            return result;
        }

        @Override
        public VersionSet complement() {
            // ¬(A ∪ B) = ¬A ∩ ¬B
            VersionSet result = ALL;
            for (VersionSet part : parts) {
                result = result.intersect(part.complement());
            }
            return result;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public String toString() {
            return parts.stream().map(Object::toString).collect(Collectors.joining(" ∪ "));
        }
    }
}
