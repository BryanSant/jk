// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver.pubgrub;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 *
 * <p>When {@code ansi} is true the output includes ANSI 24-bit color codes
 * matching jk's dark theme: red ‼ header, blue/cyan package coordinates,
 * yellow version constraints. Colors are hardcoded here so the resolver
 * module has no compile-time dependency on the CLI theme layer.
 */
public final class Diagnostics {

    // ANSI 24-bit color codes — colors match JkDarkTheme.
    private static final String RESET   = "\033[m";
    private static final String RED     = "\033[38;2;233;30;99m";  // NORMAL_RED  — ‼ header
    private static final String PRIMARY = "\033[38;2;63;81;181m";  // PRIMARY     — group part of group:artifact
    private static final String CYAN    = "\033[38;2;0;188;212m";  // NORMAL_CYAN — artifact part, bare packages
    private static final String BLUE    = "\033[38;2;83;109;254m"; // BRIGHT_BLUE — version constraints

    private Diagnostics() {}

    public static String render(Incompatibility rootCause) {
        return render(rootCause, Map.of(), false);
    }

    /**
     * @param rootDepNames maps {@code group:artifact} modules of the user's
     *                     root deps to the short name they used as the
     *                     {@code [dependencies.<scope>]} table key. When a
     *                     {@link Incompatibility.Cause.NoVersions} cause
     *                     names a module that's in this map and the artifact
     *                     portion of the module matches the name, the
     *                     renderer appends a hint pointing at the
     *                     artifact-defaulted-from-key behavior.
     */
    public static String render(Incompatibility rootCause, Map<String, String> rootDepNames) {
        return render(rootCause, rootDepNames, false);
    }

    public static String render(Incompatibility rootCause, Map<String, String> rootDepNames,
                                boolean ansi) {
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

        Set<String> hintedFor = new LinkedHashSet<>();
        appendArtifactHints(rootCause, rootDepNames, hintedFor, out, new HashSet<>());

        return out.toString();
    }

    /**
     * Walks the incompatibility DAG and emits a hint paragraph for each
     * {@code NoVersions(unknownPackage=true)} cause that corresponds to a
     * root dep whose key matches the artifact portion of the failed module.
     */
    private static void appendArtifactHints(
            Incompatibility inco,
            Map<String, String> rootDepNames,
            Set<String> hintedFor,
            StringBuilder out,
            Set<Incompatibility> visited) {
        if (!visited.add(inco)) return;
        if (inco.cause() instanceof Incompatibility.Cause.Derived d) {
            appendArtifactHints(d.a(), rootDepNames, hintedFor, out, visited);
            appendArtifactHints(d.b(), rootDepNames, hintedFor, out, visited);
            return;
        }
        if (!(inco.cause() instanceof Incompatibility.Cause.NoVersions nv)) return;
        if (!nv.unknownPackage()) return;

        String pkg = nv.pkg();
        String name = rootDepNames.get(pkg);
        if (name == null) return;

        int colon = pkg.indexOf(':');
        if (colon < 0) return;
        String group = pkg.substring(0, colon);
        String artifact = pkg.substring(colon + 1);
        if (!artifact.equals(name)) return;
        if (!hintedFor.add(pkg)) return;

        out.append('\n');
        out.append("Hint: the dep `").append(name)
                .append("` resolves to `").append(pkg).append("`, which Maven Central does not\n");
        out.append("recognize. If it is published under a different name, set\n");
        out.append("`name` explicitly:\n\n");
        out.append("  ").append(name).append(" = { group = \"").append(group)
                .append("\", name = \"<correct-name>\", version = \"...\" }\n");
        out.append("\n");
        out.append("(jk defaults `name` to the table key when omitted — see\n");
        out.append("docs/artifact-coord-design.md §\"Footgun: name defaulting\".)\n");
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
                out.append(prefix).append(label).append("therefore: ")
                        .append(describeConclusion(inco, ansi)).append('\n');
            }
            case Incompatibility.Cause.Dependency dep ->
                    out.append(prefix).append("- ").append(label)
                            .append(describe(dep.from(), ansi)).append(" depends on ")
                            .append(describe(dep.to(), ansi)).append('\n');
            case Incompatibility.Cause.NoVersions nv ->
                    out.append(prefix).append("- ").append(label)
                            .append("no versions of ").append(colorPkg(nv.pkg(), ansi))
                            .append(" match ").append(colorVersion(nv.requested().toString(), ansi))
                            .append('\n');
            case Incompatibility.Cause.Root r ->
                    out.append(prefix).append("- ").append(label)
                            .append("the root project ").append(colorPkg(r.rootPkg(), ansi))
                            .append(' ').append(colorVersion(r.rootVersion(), ansi)).append('\n');
        }
    }

    private static String describeConclusion(Incompatibility inco, boolean ansi) {
        List<Term> terms = inco.terms();
        if (terms.isEmpty()) return "this combination is impossible";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) sb.append(" and ");
            sb.append(describe(terms.get(i), ansi));
        }
        sb.append(" cannot all hold");
        return sb.toString();
    }

    /** Compact user-facing version of {@link Term#toString()}. */
    private static String describe(Term term, boolean ansi) {
        String prefix = term.positive() ? "" : "not ";
        VersionSet vs = term.versions();
        if (vs instanceof VersionSet.Range r
                && r.min() != null && r.max() != null
                && r.minInclusive() && r.maxInclusive()
                && r.min().equals(r.max())) {
            return prefix + colorPkg(term.pkg(), ansi) + " " + colorVersion(r.min(), ansi);
        }
        return prefix + colorPkg(term.pkg(), ansi) + " " + colorVersion(vs.toString(), ansi);
    }

    /**
     * Color a package name. {@code group:artifact} coords get group in primary and
     * artifact in cyan; bare names (like {@code <root>}) get cyan wholesale.
     */
    private static String colorPkg(String pkg, boolean ansi) {
        if (!ansi) return pkg;
        int colon = pkg.indexOf(':');
        if (colon < 0) return CYAN + pkg + RESET;
        return PRIMARY + pkg.substring(0, colon) + RESET + ":" + CYAN + pkg.substring(colon + 1) + RESET;
    }

    private static String colorVersion(String version, boolean ansi) {
        if (!ansi) return version;
        return BLUE + version + RESET;
    }
}
