// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.resolver;

import dev.buildjk.lock.Lockfile;
import dev.buildjk.model.BuildJk;
import dev.buildjk.model.Dependency;
import dev.buildjk.model.Scope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Renders a Cargo-style dependency tree from a {@link BuildJk} project and
 * its resolved {@link Lockfile}. Revisited subtrees are marked with
 * {@code (*)} so diamond-shaped graphs don't explode the output.
 */
public final class DependencyTree {

    private DependencyTree() {}

    public static String render(BuildJk project, Lockfile lock) {
        return render(project, lock, Integer.MAX_VALUE);
    }

    public static String render(BuildJk project, Lockfile lock, int maxDepth) {
        Map<String, Lockfile.Package> byModule = indexByModule(lock);
        StringBuilder out = new StringBuilder();
        out.append(project.project().artifact())
                .append(" v").append(project.project().version())
                .append('\n');

        List<String> roots = collectRoots(project);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < roots.size(); i++) {
            renderNode(byModule, roots.get(i), 0, maxDepth,
                    i == roots.size() - 1, "", seen, out);
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
            Set<String> seen,
            StringBuilder out) {

        Lockfile.Package pkg = byModule.get(module);
        String label = pkg != null
                ? module + " v" + pkg.version()
                : module + " (missing)";
        boolean alreadyShown = !seen.add(module);
        String marker = alreadyShown ? " (*)" : "";
        String connector = isLast ? "└── " : "├── ";

        out.append(prefix).append(connector).append(label).append(marker).append('\n');

        if (alreadyShown || pkg == null || depth >= maxDepth) return;

        String childPrefix = prefix + (isLast ? "    " : "│   ");
        List<String> children = pkg.deps().stream()
                .map(DependencyTree::stripVersion)
                .sorted()
                .toList();
        for (int i = 0; i < children.size(); i++) {
            renderNode(byModule, children.get(i), depth + 1, maxDepth,
                    i == children.size() - 1, childPrefix, seen, out);
        }
    }

    static Map<String, Lockfile.Package> indexByModule(Lockfile lock) {
        Map<String, Lockfile.Package> result = new HashMap<>();
        for (Lockfile.Package pkg : lock.packages()) {
            result.put(pkg.name(), pkg);
        }
        return result;
    }

    static List<String> collectRoots(BuildJk project) {
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
