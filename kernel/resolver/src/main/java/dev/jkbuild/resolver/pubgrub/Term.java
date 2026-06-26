// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver.pubgrub;

import java.util.Objects;

/**
 * A statement about a package: it must (or must NOT) be at one of the versions in {@link
 * #versions()}. Pure data; algebra is implemented as operations that return new {@link Term}s.
 *
 * <p>Following the PubGrub paper:
 *
 * <ul>
 *   <li>Positive term {@code [p, V]}: package {@code p} is at some version in {@code V}.
 *   <li>Negative term {@code [p, ¬V]}: package {@code p} is at some version <i>not</i> in {@code V}
 *       (or absent — encoded by the empty version set on the inverted view).
 * </ul>
 */
public record Term(String pkg, VersionSet versions, boolean positive) {

    public Term {
        Objects.requireNonNull(pkg, "pkg");
        Objects.requireNonNull(versions, "versions");
    }

    public static Term positive(String pkg, VersionSet versions) {
        return new Term(pkg, versions, true);
    }

    public static Term negative(String pkg, VersionSet versions) {
        return new Term(pkg, versions, false);
    }

    public Term invert() {
        return new Term(pkg, versions, !positive);
    }

    /** The version-set view of this term ({@code V} if positive, {@code ¬V} if negative). */
    public VersionSet effectiveVersions() {
        return positive ? versions : versions.complement();
    }

    /** True iff this term and {@code other} together describe an unsatisfiable assignment. */
    public boolean contradicts(Term other) {
        if (!pkg.equals(other.pkg)) return false;
        return intersect(other).isEmpty();
    }

    /** {@code this ∩ other} as version sets, packaged as a positive Term. */
    public Term intersect(Term other) {
        if (!pkg.equals(other.pkg)) {
            throw new IllegalArgumentException(
                    "cannot intersect terms about different packages: " + pkg + " vs " + other.pkg);
        }
        VersionSet vs = effectiveVersions().intersect(other.effectiveVersions());
        return new Term(pkg, vs, true);
    }

    public boolean isEmpty() {
        return effectiveVersions().isEmpty();
    }

    /** Per PubGrub paper §3: relationship between this term and another. */
    public Relation relation(Term other) {
        if (!pkg.equals(other.pkg)) {
            throw new IllegalArgumentException(
                    "cannot relate terms about different packages: " + pkg + " vs " + other.pkg);
        }
        VersionSet a = effectiveVersions();
        VersionSet b = other.effectiveVersions();
        boolean aSubsetB = a.subsetOf(b);
        boolean bSubsetA = b.subsetOf(a);
        boolean disjoint = a.intersect(b).isEmpty();

        if (aSubsetB && bSubsetA) return Relation.SATISFIES; // equal sets
        if (aSubsetB) return Relation.SATISFIES; // this ⊆ other
        if (disjoint) return Relation.CONTRADICTS;
        return Relation.OVERLAPS;
    }

    /** Result of {@link #relation(Term)}. */
    public enum Relation {
        /** This term implies the other ({@code this ⇒ other}). */
        SATISFIES,
        /** This term contradicts the other (no shared satisfying version). */
        CONTRADICTS,
        /** Neither — there's overlap but neither implies the other. */
        OVERLAPS
    }

    @Override
    public String toString() {
        return (positive ? "" : "¬") + pkg + " " + versions;
    }
}
