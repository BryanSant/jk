// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.resolver;

import dev.buildjk.lock.Lockfile;
import dev.buildjk.model.BuildJk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Inverse dependency walker. Given a lockfile, lists every path from a
 * declared root down to a target module — the data behind {@code jk why}.
 *
 * <p>Paths are emitted leaf-up: each {@link Path} starts at a root the
 * user declared in {@code build.jk} and ends at the target module.
 */
public final class Provenance {

    private Provenance() {}

    /**
     * @return list of paths from declared roots to {@code targetModule}.
     *         Empty if the target isn't in the lockfile or is unreachable.
     */
    public static List<Path> pathsTo(BuildJk project, Lockfile lock, String targetModule) {
        Objects.requireNonNull(targetModule, "targetModule");

        Map<String, Lockfile.Package> byModule = DependencyTree.indexByModule(lock);
        if (!byModule.containsKey(targetModule)) {
            return List.of();
        }

        // Reverse adjacency: dep → list of (parent, parent-version)
        Map<String, Set<String>> reverseDeps = new HashMap<>();
        for (Lockfile.Package pkg : lock.packages()) {
            for (String depRef : pkg.deps()) {
                String depModule = DependencyTree.stripVersion(depRef);
                reverseDeps.computeIfAbsent(depModule, k -> new TreeSet<>())
                        .add(pkg.name());
            }
        }

        Set<String> declaredRoots = new LinkedHashSet<>(DependencyTree.collectRoots(project));

        List<Path> paths = new ArrayList<>();
        walkUp(targetModule, byModule, reverseDeps, declaredRoots, new ArrayList<>(), paths);
        return paths;
    }

    private static void walkUp(
            String current,
            Map<String, Lockfile.Package> byModule,
            Map<String, Set<String>> reverseDeps,
            Set<String> declaredRoots,
            List<Step> stack,
            List<Path> out) {

        Lockfile.Package pkg = byModule.get(current);
        String version = pkg != null ? pkg.version() : "?";
        stack.addLast(new Step(current, version));

        try {
            if (declaredRoots.contains(current)) {
                // Reverse for display: declared root first, target last.
                List<Step> path = new ArrayList<>(stack);
                java.util.Collections.reverse(path);
                out.add(new Path(List.copyOf(path)));
                return;
            }
            Set<String> parents = reverseDeps.get(current);
            if (parents == null || parents.isEmpty()) return;
            for (String parent : parents) {
                // Avoid cycles in the lockfile.
                boolean alreadyOnStack = stack.stream().anyMatch(s -> s.module().equals(parent));
                if (alreadyOnStack) continue;
                walkUp(parent, byModule, reverseDeps, declaredRoots, stack, out);
            }
        } finally {
            stack.removeLast();
        }
    }

    /** A path from a declared root (first) down to the target (last). */
    public record Path(List<Step> steps) {
        public Path {
            Objects.requireNonNull(steps, "steps");
            steps = List.copyOf(steps);
        }

        public String render() {
            return steps.stream()
                    .map(s -> s.module() + " v" + s.version())
                    .collect(Collectors.joining(" -> "));
        }
    }

    public record Step(String module, String version) {}
}
