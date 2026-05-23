// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver.pubgrub;

import java.util.List;
import java.util.Objects;

/**
 * A PubGrub <i>incompatibility</i>: a non-empty list of {@link Term}s such
 * that at least one term must NOT be satisfied by any solution. Carries a
 * {@link Cause} so we can later render English diagnostics by walking the
 * derivation DAG back to root causes.
 */
public record Incompatibility(List<Term> terms, Cause cause) {

    public Incompatibility {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(cause, "cause");
        if (terms.isEmpty()) {
            throw new IllegalArgumentException("incompatibility must have at least one term");
        }
        terms = List.copyOf(terms);
    }

    /**
     * Why this incompatibility exists. Each cause carries enough context to
     * be rendered as a sentence in the failure message.
     */
    public sealed interface Cause {

        /** "The root project requires the root package itself." */
        record Root(String rootPkg, String rootVersion) implements Cause {}

        /** "Package {@code from} {@code fromVersions} depends on {@code to}." */
        record Dependency(Term from, Term to) implements Cause {}

        /** No versions of {@code package_} satisfy the requested set. */
        record NoVersions(String pkg, VersionSet requested) implements Cause {}

        /**
         * Conflict-resolution derived this from two prior incompatibilities.
         * Used by the diagnostic renderer to reconstruct the explanation tree.
         */
        record Derived(Incompatibility a, Incompatibility b) implements Cause {}
    }

    @Override
    public String toString() {
        return terms.toString() + " (because " + cause + ")";
    }
}
