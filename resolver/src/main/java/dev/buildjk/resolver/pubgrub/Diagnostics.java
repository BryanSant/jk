// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.resolver.pubgrub;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Renders an {@link UnsatisfiableException}'s root-cause incompatibility
 * tree into an English explanation. v0.1 implementation: walk the cause
 * DAG and emit one bullet per leaf cause ({@link Incompatibility.Cause.Dependency},
 * {@link Incompatibility.Cause.NoVersions}, or {@link Incompatibility.Cause.Root}).
 * Deduplicated and printed in deterministic insertion order.
 *
 * <p>Polish in line with the PRD §8.2 example (numbered sentences with
 * back-references, "other versions considered", suggestions) is a
 * future-stage upgrade.
 */
public final class Diagnostics {

    private Diagnostics() {}

    public static String render(Incompatibility rootCause) {
        Set<String> sentences = new LinkedHashSet<>();
        collect(rootCause, sentences);

        StringBuilder out = new StringBuilder();
        out.append("× Cannot resolve dependencies:\n\n");
        for (String sentence : sentences) {
            out.append("  - ").append(sentence).append('\n');
        }
        if (!sentences.isEmpty()) out.append('\n');
        out.append("These constraints are unsatisfiable together.\n");
        return out.toString();
    }

    private static void collect(Incompatibility inco, Set<String> out) {
        switch (inco.cause()) {
            case Incompatibility.Cause.Derived d -> {
                collect(d.a(), out);
                collect(d.b(), out);
            }
            case Incompatibility.Cause.Dependency dep ->
                    out.add(describe(dep.from()) + " depends on " + describe(dep.to()));
            case Incompatibility.Cause.NoVersions nv ->
                    out.add("no versions of " + nv.pkg() + " match " + nv.requested());
            case Incompatibility.Cause.Root r ->
                    out.add("the root project " + r.rootPkg() + " " + r.rootVersion());
        }
    }

    /** Compact user-facing version of {@link Term#toString()}. */
    private static String describe(Term term) {
        String prefix = term.positive() ? "" : "not ";
        VersionSet vs = term.versions();
        if (vs instanceof VersionSet.Range r
                && r.min() != null && r.max() != null
                && r.minInclusive() && r.maxInclusive()
                && r.min().equals(r.max())) {
            return prefix + term.pkg() + " " + r.min();
        }
        return prefix + term.pkg() + " " + vs;
    }
}
