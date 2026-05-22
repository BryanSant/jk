// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.resolver.pubgrub;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders an {@link UnsatisfiableException}'s root-cause incompatibility
 * DAG into an English explanation. Approach (a simplification of PubGrub
 * paper §7):
 *
 * <ol>
 *   <li>Walk the DAG post-order so deeper facts are stated first.</li>
 *   <li>Leaf causes ({@link Incompatibility.Cause.Dependency},
 *       {@link Incompatibility.Cause.NoVersions},
 *       {@link Incompatibility.Cause.Root}) render as bullets.</li>
 *   <li>{@link Incompatibility.Cause.Derived} nodes render as
 *       {@code "therefore: ..."} sentences after their inputs.</li>
 *   <li>Incompatibilities shared between multiple Derived branches get
 *       a numeric label like {@code (#1)} on their first emission so a
 *       later branch can refer to them by number without re-emitting.</li>
 * </ol>
 */
public final class Diagnostics {

    private Diagnostics() {}

    public static String render(Incompatibility rootCause) {
        Map<Incompatibility, Integer> incomingEdges = new HashMap<>();
        countIncomingEdges(rootCause, incomingEdges, new HashSet<>());

        // Anything referenced from more than one parent gets a number so
        // its second mention is a back-reference instead of a re-rendering.
        Map<Incompatibility, Integer> numbered = new LinkedHashMap<>();
        int nextNumber = 1;
        for (Map.Entry<Incompatibility, Integer> e : incomingEdges.entrySet()) {
            if (e.getValue() > 1) {
                numbered.put(e.getKey(), nextNumber++);
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("× Cannot resolve dependencies.\n\n");
        Set<Incompatibility> emitted = new HashSet<>();
        renderInco(rootCause, out, "  ", emitted, numbered);
        out.append('\n');
        out.append("These constraints are unsatisfiable together.\n");
        return out.toString();
    }

    private static void countIncomingEdges(
            Incompatibility inco,
            Map<Incompatibility, Integer> counts,
            Set<Incompatibility> visited) {
        if (!visited.add(inco)) return;
        if (inco.cause() instanceof Incompatibility.Cause.Derived d) {
            counts.merge(d.a(), 1, Integer::sum);
            counts.merge(d.b(), 1, Integer::sum);
            countIncomingEdges(d.a(), counts, visited);
            countIncomingEdges(d.b(), counts, visited);
        }
    }

    private static void renderInco(
            Incompatibility inco,
            StringBuilder out,
            String prefix,
            Set<Incompatibility> emitted,
            Map<Incompatibility, Integer> numbered) {

        if (emitted.contains(inco)) {
            Integer ref = numbered.get(inco);
            if (ref != null) {
                out.append(prefix).append("(see #").append(ref).append(")\n");
            }
            return;
        }
        emitted.add(inco);
        String label = numbered.containsKey(inco) ? "#" + numbered.get(inco) + " " : "";

        switch (inco.cause()) {
            case Incompatibility.Cause.Derived d -> {
                renderInco(d.a(), out, prefix, emitted, numbered);
                renderInco(d.b(), out, prefix, emitted, numbered);
                out.append(prefix).append(label).append("therefore: ")
                        .append(describeConclusion(inco)).append('\n');
            }
            case Incompatibility.Cause.Dependency dep ->
                    out.append(prefix).append("- ").append(label)
                            .append(describe(dep.from())).append(" depends on ")
                            .append(describe(dep.to())).append('\n');
            case Incompatibility.Cause.NoVersions nv ->
                    out.append(prefix).append("- ").append(label)
                            .append("no versions of ").append(nv.pkg())
                            .append(" match ").append(nv.requested()).append('\n');
            case Incompatibility.Cause.Root r ->
                    out.append(prefix).append("- ").append(label)
                            .append("the root project ").append(r.rootPkg())
                            .append(' ').append(r.rootVersion()).append('\n');
        }
    }

    private static String describeConclusion(Incompatibility inco) {
        List<Term> terms = inco.terms();
        if (terms.isEmpty()) return "this combination is impossible";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) sb.append(" and ");
            sb.append(describe(terms.get(i)));
        }
        sb.append(" cannot all hold");
        return sb.toString();
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
