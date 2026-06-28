// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver.pubgrub;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders an {@link UnsatisfiableException}'s root-cause incompatibility DAG into an English
 * explanation. Approach (a simplification of PubGrub paper §7):
 *
 * <ol>
 *   <li>Walk the DAG post-order so deeper facts are stated first.
 *   <li>Leaf causes ({@link Incompatibility.Cause.Dependency}, {@link
 *       Incompatibility.Cause.NoVersions}, {@link Incompatibility.Cause.Root}) render as bullets.
 *   <li>{@link Incompatibility.Cause.Derived} nodes render as {@code "therefore: ..."} sentences
 *       after their inputs.
 *   <li>Incompatibilities shared between multiple Derived branches get a numeric label like {@code
 *       (#1)} on their first emission so a later branch can refer to them by number without
 *       re-emitting.
 * </ol>
 *
 * <p>When {@code ansi} is true the output includes ANSI 24-bit color codes matching jk's dark
 * theme: red ‼ header, blue/cyan package coordinates, yellow version constraints. Colors are
 * hardcoded here so the resolver module has no compile-time dependency on the CLI theme layer.
 */
public final class Diagnostics {

    // ANSI 24-bit color codes — colors match JkDarkTheme coord-* roles.
    private static final String RESET = "\033[m";
    private static final String RED    = "\033[38;2;233;30;99m";   // NORMAL_RED   — ‼ header
    private static final String GROUP  = "\033[38;2;0;188;212m";   // NORMAL_CYAN  — coordGroup (group segment)
    private static final String NAME   = "\033[38;2;24;255;255m";  // BRIGHT_CYAN  — coordName  (artifact segment)
    private static final String VER    = "\033[38;2;236;239;241m"; // BRIGHT_WHITE — coordVersion

    /** The synthetic root package injected by PubGrubResolver — not meaningful to users. */
    private static final String ROOT_PKG = "<root>";

    private Diagnostics() {}

    public static String render(Incompatibility rootCause) {
        return render(rootCause, Map.of(), false);
    }

    /** Retained for call-site compatibility; {@code rootDepNames} is no longer used. */
    public static String render(Incompatibility rootCause, Map<String, String> rootDepNames) {
        return render(rootCause, rootDepNames, false);
    }

    /** Retained for call-site compatibility; {@code rootDepNames} is no longer used. */
    public static String render(Incompatibility rootCause, Map<String, String> rootDepNames, boolean ansi) {
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
        String bang = ansi ? RED + "‼" + RESET : "‼";
        out.append(bang).append(" Cannot resolve dependencies.\n\n");
        Set<Incompatibility> emitted = new HashSet<>();
        renderInco(rootCause, out, "  ", emitted, numbered, ansi);
        out.append('\n');
        out.append("These constraints are unsatisfiable together.\n");

        return out.toString();
    }

    private static void countIncomingEdges(
            Incompatibility inco, Map<Incompatibility, Integer> counts, Set<Incompatibility> visited) {
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
            Map<Incompatibility, Integer> numbered,
            boolean ansi) {

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
                renderInco(d.a(), out, prefix, emitted, numbered, ansi);
                renderInco(d.b(), out, prefix, emitted, numbered, ansi);
                out.append(prefix)
                        .append(label)
                        .append("therefore, ")
                        .append(describeConclusion(inco, ansi))
                        .append('\n');
            }
            case Incompatibility.Cause.Dependency dep -> {
                String from = isRoot(dep.from()) ? "The root" : cap(describe(dep.from(), ansi));
                out.append(prefix)
                        .append(label)
                        .append(from)
                        .append(" depends on ")
                        .append(describe(dep.to(), ansi))
                        .append('\n');
            }
            case Incompatibility.Cause.NoVersions nv ->
                out.append(prefix)
                        .append(label)
                        .append("No versions of ")
                        .append(colorPkg(nv.pkg(), ansi))
                        .append(" match ")
                        .append(colorVersion(stripBraces(nv.requested().toString()), ansi))
                        .append('\n');
            case Incompatibility.Cause.Root r ->
                out.append(prefix)
                        .append(label)
                        .append("The root project\n");
        }
    }

    private static String describeConclusion(Incompatibility inco, boolean ansi) {
        List<Term> terms = inco.terms();
        if (terms.isEmpty()) return "this combination is impossible";
        // All-root conclusion: plain prose instead of exposing the synthetic <root> token.
        boolean allRoot = terms.stream().allMatch(t -> ROOT_PKG.equals(t.pkg()));
        if (allRoot) return "the root project's requirements cannot be resolved";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) sb.append(" and ");
            sb.append(describeForConclusion(terms.get(i), ansi));
        }
        sb.append(" cannot be resolved");
        return sb.toString();
    }

    /** Compact user-facing version of {@link Term#toString()}. */
    private static String describe(Term term, boolean ansi) {
        if (isRoot(term)) return "the root";
        String prefix = term.positive() ? "" : "not ";
        VersionSet vs = term.versions();
        if (vs instanceof VersionSet.Range r
                && r.min() != null
                && r.max() != null
                && r.minInclusive()
                && r.maxInclusive()
                && r.min().equals(r.max())) {
            return prefix + colorPkg(term.pkg(), ansi) + " " + colorVersion(r.min(), ansi);
        }
        return prefix + colorPkg(term.pkg(), ansi) + " " + colorVersion(stripBraces(vs.toString()), ansi);
    }

    /** Like {@link #describe} but used inside conclusion sentences (already lower-case context). */
    private static String describeForConclusion(Term term, boolean ansi) {
        if (isRoot(term)) return "the root";
        return describe(term, ansi);
    }

    /** True when the term refers to the synthetic PubGrub root package. */
    private static boolean isRoot(Term term) {
        return ROOT_PKG.equals(term.pkg());
    }

    private static boolean isRoot(Incompatibility.Cause.Dependency dep) {
        return ROOT_PKG.equals(dep.from().pkg());
    }

    /**
     * Color a package coordinate. {@code group:artifact} gets group in coordGroup and artifact in
     * coordName; bare/synthetic names fall back to coordName.
     */
    private static String colorPkg(String pkg, boolean ansi) {
        if (!ansi) return pkg;
        int colon = pkg.indexOf(':');
        if (colon < 0) return NAME + pkg + RESET;
        return GROUP + pkg.substring(0, colon) + RESET + ":" + NAME + pkg.substring(colon + 1) + RESET;
    }

    private static String colorVersion(String version, boolean ansi) {
        if (!ansi) return version;
        return VER + version + RESET;
    }

    /** Strip PubGrub's {@code {…}} wrapper from single-version VersionSet strings. */
    private static String stripBraces(String s) {
        if (s.length() >= 2 && s.charAt(0) == '{' && s.charAt(s.length() - 1) == '}') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** Capitalize the first character of a string (leaves ANSI-prefixed strings alone). */
    private static String cap(String s) {
        if (s.isEmpty()) return s;
        // If the string starts with an ANSI escape, capitalize the visible first char after the reset.
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
