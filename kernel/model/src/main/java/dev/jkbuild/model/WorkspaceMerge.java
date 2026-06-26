// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a synthetic {@link JkBuild} that combines a workspace root with
 * its modules so the existing {@code LockOrchestrator} can resolve the
 * whole workspace in a single pass.
 *
 * <p>In addition to scope-aggregated deduping (root wins, then declaration
 * order), this also resolves the {@code workspace = true} placeholder
 * deps the parser emits for child manifests:
 * <ol>
 *   <li>If a workspace sibling has a matching {@code [project].artifact},
 *       the dep is rewritten to that sibling's coord (the dep then dedupes
 *       away as workspace-internal).</li>
 *   <li>Else if {@code [workspace.dependencies]} declares an entry under
 *       that name, the dep is rewritten to that entry's coord.</li>
 *   <li>Else: a parse error pointing at the unresolved name.</li>
 * </ol>
 */
public final class WorkspaceMerge {

    /** Synthetic module prefix the parser uses for unresolved workspace deps. */
    private static final String UNRESOLVED_PREFIX = "workspace:";

    private WorkspaceMerge() {}

    /**
     * Apply workspace context to a single module's manifest. Resolves any
     * {@code workspace:*} placeholders against the workspace siblings and
     * the root's {@code [workspace.dependencies]}, then filters out any
     * dep whose resolved coordinate matches a workspace sibling — those
     * are not on Maven Central; their jars are injected by
     * {@link dev.jkbuild.config.WorkspaceClasspath} at compile time from
     * the workspace's shared {@code target/} directory.
     *
     * <p>Returns a JkBuild shaped like {@code module} (same project,
     * repositories, profiles, features) but with the dep list trimmed to
     * only external Maven coords. Lock orchestration sees a clean,
     * resolvable set; classpath construction (which keeps the original
     * parsed module) still sees the sibling refs.
     */
    public static JkBuild applyToModule(JkBuild root, JkBuild module, Collection<JkBuild> allModules) {
        if (allModules.isEmpty()) return module;

        Map<String, JkBuild> siblingByArtifact = new LinkedHashMap<>();
        Set<String> internal = new HashSet<>();
        for (JkBuild m : allModules) {
            siblingByArtifact.put(m.project().name(), m);
            internal.add(m.project().group() + ":" + m.project().name());
        }
        Map<String, Workspace.WorkspaceDependency> wsDeps =
                root.workspace() != null ? root.workspace().dependencies() : Map.of();

        // First pass: resolve this module's own deps, strip sibling refs, and
        // track which siblings are direct dependencies (for export propagation).
        Set<String> dependedSiblingNames = new LinkedHashSet<>();
        Map<Scope, List<Dependency>> resolvedByScope = new EnumMap<>(Scope.class);
        for (Scope scope : Scope.values()) {
            List<Dependency> resolved = new ArrayList<>();
            for (Dependency d : module.dependencies().of(scope)) {
                Dependency r = resolve(d, siblingByArtifact, wsDeps);
                if (internal.contains(r.module())) {
                    dependedSiblingNames.add(r.name());
                    continue;
                }
                resolved.add(r);
            }
            if (!resolved.isEmpty()) resolvedByScope.put(scope, resolved);
        }

        // Second pass: pull each direct sibling's export deps into this module's
        // main scope so they land in this module's lockfile and compile classpath.
        for (String siblingName : dependedSiblingNames) {
            JkBuild sibling = siblingByArtifact.get(siblingName);
            if (sibling == null) continue;
            for (Dependency d : sibling.dependencies().of(Scope.EXPORT)) {
                Dependency r = resolve(d, siblingByArtifact, wsDeps);
                if (internal.contains(r.module())) continue;
                List<Dependency> mainList = resolvedByScope.computeIfAbsent(Scope.MAIN, k -> new ArrayList<>());
                if (mainList.stream().noneMatch(e -> e.module().equals(r.module()))) {
                    mainList.add(r);
                }
            }
        }

        return new JkBuild(
                module.project(),
                new JkBuild.Dependencies(resolvedByScope),
                module.repositories(),
                module.profiles(),
                module.features(),
                module.workspace(),
                module.manifest(),
                module.plugins(),
                module.nativeConfig());
    }

    public static JkBuild merge(JkBuild root, Collection<JkBuild> modules) {
        if (modules.isEmpty()) return root;

        // Build the sibling lookup: artifact → JkBuild (full manifest).
        Map<String, JkBuild> siblingByArtifact = new LinkedHashMap<>();
        Set<String> internal = new HashSet<>();
        for (JkBuild module : modules) {
            String coord = module.project().group() + ":" + module.project().name();
            siblingByArtifact.put(module.project().name(), module);
            internal.add(coord);
        }

        Map<String, Workspace.WorkspaceDependency> wsDeps =
                root.workspace() != null ? root.workspace().dependencies() : Map.of();

        Map<Scope, List<Dependency>> mergedByScope = new EnumMap<>(Scope.class);
        for (Scope scope : Scope.values()) {
            Map<String, Dependency> dedup = new LinkedHashMap<>();
            for (Dependency d : root.dependencies().of(scope)) {
                Dependency resolved = resolve(d, siblingByArtifact, wsDeps);
                if (internal.contains(resolved.module())) continue;
                dedup.putIfAbsent(resolved.module(), resolved);
            }
            for (JkBuild module : modules) {
                for (Dependency d : module.dependencies().of(scope)) {
                    Dependency resolved = resolve(d, siblingByArtifact, wsDeps);
                    if (internal.contains(resolved.module())) continue;
                    dedup.putIfAbsent(resolved.module(), resolved);
                }
            }
            if (!dedup.isEmpty()) {
                mergedByScope.put(scope, new ArrayList<>(dedup.values()));
            }
        }
        return new JkBuild(
                root.project(),
                new JkBuild.Dependencies(mergedByScope),
                root.repositories(),
                root.profiles(),
                root.features(),
                root.workspace(),
                root.manifest(),
                root.plugins(),
                root.nativeConfig());
    }

    /**
     * Rewrites a placeholder {@code workspace:<name>} dep into the real
     * coord, using the sibling list first and then the workspace's
     * shared-dep table. Non-placeholder deps pass through unchanged.
     */
    private static Dependency resolve(
            Dependency d, Map<String, JkBuild> siblingByArtifact, Map<String, Workspace.WorkspaceDependency> wsDeps) {
        if (!d.module().startsWith(UNRESOLVED_PREFIX)) return d;
        String name = d.library();
        // Sibling lookup first. Modules typically name siblings as
        // jk-core, jk-cli, etc. — the dep handle is expected to match the
        // sibling's name directly.
        JkBuild sibling = siblingByArtifact.get(name);
        if (sibling != null) {
            JkBuild.Project p = sibling.project();
            String module = p.group() + ":" + p.name();
            return Dependency.of(name, module, VersionSelector.parse("=" + p.version()));
        }
        Workspace.WorkspaceDependency ws = wsDeps.get(name);
        if (ws != null) {
            if (ws.gitSource() != null) {
                return Dependency.git(name, ws.module(), ws.gitSource());
            }
            if (ws.pathSource() != null) {
                return Dependency.path(name, ws.module(), ws.pathSource());
            }
            return Dependency.of(name, ws.module(), ws.version());
        }
        throw new IllegalStateException("no workspace dependency or sibling named `" + name + "`");
    }
}
