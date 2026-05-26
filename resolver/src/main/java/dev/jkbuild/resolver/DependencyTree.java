// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver;

import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.Scope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * Renders a Cargo-style dependency tree from a {@link JkBuild} project and
 * its resolved {@link Lockfile}. Revisited subtrees are marked with an
 * up-arrow ({@code ⎋}) so diamond-shaped graphs don't explode the output —
 * the arrow points the reader back to the earlier expansion.
 */
public final class DependencyTree {

    /**
     * Optional ANSI styling for tree output. Each operator wraps the
     * matching piece of text with whatever escape sequence the caller
     * prefers. Defaults to {@link #plain()} (identity everywhere) so
     * tests and non-color consumers get raw ASCII back.
     *
     * <p>The fields map directly to the rendered shape:
     * <pre>
     *   {rail}└── {/rail}{group}{group}{/group}:{artifact}{artifact}{/artifact}:{version}{version}{/version}
     * </pre>
     */
    public record Styling(
            UnaryOperator<String> rail,
            UnaryOperator<String> group,
            UnaryOperator<String> artifact,
            UnaryOperator<String> version) {
        public static Styling plain() {
            return new Styling(UnaryOperator.identity(), UnaryOperator.identity(),
                    UnaryOperator.identity(), UnaryOperator.identity());
        }
    }

    private DependencyTree() {}

    public static String render(JkBuild project, Lockfile lock) {
        return render(project, lock, Integer.MAX_VALUE);
    }

    public static String render(JkBuild project, Lockfile lock, int maxDepth) {
        return render(project, lock, maxDepth, Styling.plain());
    }

    /** Back-compat overload — rail-only styling. */
    public static String render(JkBuild project, Lockfile lock, int maxDepth,
                                UnaryOperator<String> railStyler) {
        return render(project, lock, maxDepth, new Styling(
                railStyler, UnaryOperator.identity(),
                UnaryOperator.identity(), UnaryOperator.identity()));
    }

    /**
     * Render with full styling. Labels are emitted in {@code group:artifact:version}
     * shape (the Maven coordinate convention Java developers expect), with each
     * segment passed through its corresponding {@link Styling} operator. Rail
     * connectors ({@code ├── }, {@code └── }, {@code │   }, {@code "    "}) are
     * passed through {@link Styling#rail}.
     */
    public static String render(JkBuild project, Lockfile lock, int maxDepth,
                                Styling styling) {
        Map<String, Lockfile.Package> byModule = indexByModule(lock);
        StringBuilder out = new StringBuilder();
        // Root project: group:artifact:version, styled like every other line.
        out.append(formatCoord(
                project.project().group(),
                project.project().artifact(),
                project.project().version(),
                styling)).append('\n');

        List<String> roots = collectRoots(project);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < roots.size(); i++) {
            renderNode(byModule, roots.get(i), 0, maxDepth,
                    i == roots.size() - 1, "", styling, seen, out);
        }
        return out.toString();
    }

    private static void renderNode(
            Map<String, Lockfile.Package> byModule,
            String module,
            int depth,
            int maxDepth,
            boolean isLast,
            String prefix,
            Styling styling,
            Set<String> seen,
            StringBuilder out) {

        Lockfile.Package pkg = byModule.get(module);
        // module is "group:artifact"; split for per-segment styling.
        int colon = module.indexOf(':');
        String groupId = colon > 0 ? module.substring(0, colon) : module;
        String artifactId = colon > 0 ? module.substring(colon + 1) : "";

        String label;
        if (pkg != null) {
            label = formatCoord(groupId, artifactId, pkg.version(), styling);
        } else {
            // No version available — emit "group:artifact (missing)" so the
            // (missing) marker is unmistakable and stays unstyled.
            label = styling.group().apply(groupId) + ":"
                    + styling.artifact().apply(artifactId) + " (missing)";
        }
        boolean alreadyShown = !seen.add(module);
        String marker = alreadyShown ? " ⎋" : "";
        // ╰── for the last child (rounded arc); ├── for the rest.
        // Standard "rounded tree" convention used by eza, tre, etc.
        String connector = isLast ? "╰── " : "├── ";

        out.append(prefix).append(styling.rail().apply(connector))
                .append(label).append(marker).append('\n');

        if (alreadyShown || pkg == null || depth >= maxDepth) return;

        String childPrefix = prefix + styling.rail().apply(isLast ? "    " : "│   ");
        List<String> children = pkg.deps().stream()
                .map(DependencyTree::stripVersion)
                .sorted()
                .toList();
        for (int i = 0; i < children.size(); i++) {
            renderNode(byModule, children.get(i), depth + 1, maxDepth,
                    i == children.size() - 1, childPrefix, styling, seen, out);
        }
    }

    private static String formatCoord(String group, String artifact, String version,
                                      Styling styling) {
        return styling.group().apply(group)
                + ":" + styling.artifact().apply(artifact)
                + ":" + styling.version().apply(version);
    }

    static Map<String, Lockfile.Package> indexByModule(Lockfile lock) {
        Map<String, Lockfile.Package> result = new HashMap<>();
        for (Lockfile.Package pkg : lock.packages()) {
            result.put(pkg.name(), pkg);
        }
        return result;
    }

    static List<String> collectRoots(JkBuild project) {
        return Stream.of(Scope.values())
                .flatMap(s -> project.dependencies().of(s).stream())
                .map(Dependency::module)
                .sorted()
                .distinct()
                .collect(ArrayList::new, List::add, List::addAll);
    }

    /** Strip the {@code @version} suffix from a lockfile dep ref. */
    static String stripVersion(String depRef) {
        int at = depRef.indexOf('@');
        return at > 0 ? depRef.substring(0, at) : depRef;
    }

    // Sorting helper exposed for tests that want a deterministic order.
    static Comparator<Lockfile.Package> byName() {
        return Comparator.comparing(Lockfile.Package::name);
    }
}
