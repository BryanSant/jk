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
 * Builds a synthetic {@link JkBuild} that combines a workspace root with its modules so the
 * existing {@code LockOrchestrator} can resolve the whole workspace in a single pass.
 *
 * <p>In addition to scope-aggregated deduping (root wins, then declaration order), this also
 * resolves the {@code workspace = true} placeholder deps the parser emits for child manifests:
 *
 * <ol>
 *   <li>If a workspace sibling has a matching {@code [project].artifact}, the dep is rewritten to
 *       that sibling's coord (the dep then dedupes away as workspace-internal).
 *   <li>Else if {@code [workspace.dependencies]} declares an entry under that name, the dep is
 *       rewritten to that entry's coord.
 *   <li>Else: a parse error pointing at the unresolved name.
 * </ol>
 */
public final class WorkspaceMerge {

    private WorkspaceMerge() {}

    /**
     * Apply workspace context to a single module's manifest. Resolves any {@code workspace:*}
     * placeholders against the workspace siblings and the root's {@code [workspace.dependencies]},
     * then filters out any dep whose resolved coordinate matches a workspace sibling — those are not
     * on Maven Central; their jars are injected by {@link dev.jkbuild.config.WorkspaceClasspath} at
     * compile time from the workspace's shared {@code target/} directory.
     *
     * <p>Returns a JkBuild shaped like {@code module} (same project, repositories, profiles,
     * features) but with the dep list trimmed to only external Maven coords. Lock orchestration sees
     * a clean, resolvable set; classpath construction (which keeps the original parsed module) still
     * sees the sibling refs.
     */
    public static JkBuild applyToModule(JkBuild root, JkBuild module, Collection<JkBuild> allModules) {
        if (allModules.isEmpty()) return Variants.unionDependencies(module);

        // Lock scopes see the UNION of every variant value's dependency overlays — one lockfile
        // covers every variant (Variants.unionDependencies; the build folds only the selected
        // value's deps). Siblings union too: their variant-only externals fold transitively.
        module = Variants.unionDependencies(module);
        List<JkBuild> unionModules = new ArrayList<>(allModules.size());
        for (JkBuild m : allModules) unionModules.add(Variants.unionDependencies(m));
        allModules = unionModules;

        Map<String, JkBuild> siblingByArtifact = new LinkedHashMap<>();
        Set<String> internal = new HashSet<>();
        for (JkBuild m : allModules) {
            siblingByArtifact.put(m.project().name(), m);
            internal.add(m.project().group() + ":" + m.project().name());
        }
        // The workspace root is itself a unit members may depend on (Cargo/uv style:
        // a member can `<root> = { workspace = true }`), so it joins the sibling set.
        siblingByArtifact.put(root.project().name(), root);
        internal.add(root.project().group() + ":" + root.project().name());
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

        // Second pass: walk the sibling graph TRANSITIVELY and pull every reachable sibling's
        // externals (MAIN + EXPORT) into this module's main scope, so they land in the lockfile
        // and the runtime closure. Maven's compile scope is transitive — and a self-contained
        // artifact (an APK dexes and links its whole graph) hard-requires it: NiA's app was
        // missing appcompat's THEME resources because only direct siblings' EXPORT deps used to
        // propagate (A5f finding 19).
        Set<String> visited = new LinkedHashSet<>(dependedSiblingNames);
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(dependedSiblingNames);
        while (!queue.isEmpty()) {
            JkBuild sibling = siblingByArtifact.get(queue.poll());
            if (sibling == null) continue;
            for (Scope scope : List.of(Scope.MAIN, Scope.EXPORT)) {
                for (Dependency d : sibling.dependencies().of(scope)) {
                    Dependency r = resolve(d, siblingByArtifact, wsDeps);
                    if (internal.contains(r.module())) {
                        if (visited.add(r.name())) queue.add(r.name());
                        continue;
                    }
                    List<Dependency> mainList =
                            resolvedByScope.computeIfAbsent(Scope.MAIN, k -> new ArrayList<>());
                    if (mainList.stream().noneMatch(e -> e.module().equals(r.module()))) {
                        mainList.add(r);
                    }
                }
            }
        }

        JkBuild.Builder out = JkBuild.builder(module.project())
                .dependencies(new JkBuild.Dependencies(resolvedByScope))
                .repositories(module.repositories())
                .profiles(module.profiles())
                .features(module.features())
                .workspace(module.workspace())
                .manifest(module.manifest())
                .plugins(module.plugins())
                .application(module.application().orElse(null))
                .nativeConfig(module.nativeConfig().orElse(null))
                // The module's plugin tables and [build] block ride along: dropping them made an
                // [android] workspace module lock as standard-jvm (wrong KMP variants — Room 2.8
                // resolved -jvm) and lose its ksp-options/kotlin-plugins/order-after at lock time.
                .build(module.build())
                .format(module.format())
                .variants(module.variants());
        for (PluginConfig config : module.pluginConfigs().values()) {
            out.pluginConfig(config);
        }
        return out.build();
    }

    public static JkBuild merge(JkBuild root, Collection<JkBuild> modules) {
        if (modules.isEmpty()) return Variants.unionDependencies(root);

        // Union variant dep overlays into every manifest before folding (see applyToModule).
        root = Variants.unionDependencies(root);
        List<JkBuild> unionModules = new ArrayList<>(modules.size());
        for (JkBuild m : modules) unionModules.add(Variants.unionDependencies(m));
        modules = unionModules;

        // Build the sibling lookup: artifact → JkBuild (full manifest).
        Map<String, JkBuild> siblingByArtifact = new LinkedHashMap<>();
        Set<String> internal = new HashSet<>();
        for (JkBuild module : modules) {
            String coord = module.project().group() + ":" + module.project().name();
            siblingByArtifact.put(module.project().name(), module);
            internal.add(coord);
        }
        // The root is itself a workspace unit members may depend on.
        siblingByArtifact.put(root.project().name(), root);
        internal.add(root.project().group() + ":" + root.project().name());

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
        return JkBuild.builder(root.project())
                .dependencies(new JkBuild.Dependencies(mergedByScope))
                .repositories(root.repositories())
                .profiles(root.profiles())
                .features(root.features())
                .workspace(root.workspace())
                .manifest(root.manifest())
                .plugins(root.plugins())
                .application(root.application().orElse(null))
                .nativeConfig(root.nativeConfig().orElse(null))
                .build();
    }

    /**
     * Rewrites a placeholder {@code workspace:<name>} dep into the real coord, using the sibling list
     * first and then the workspace's shared-dep table. Non-placeholder deps pass through unchanged.
     */
    private static Dependency resolve(
            Dependency d, Map<String, JkBuild> siblingByArtifact, Map<String, Workspace.WorkspaceDependency> wsDeps) {
        if (!d.isWorkspace()) return d;
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
            return Dependency.of(name, ws.module(), ws.version());
        }
        throw new IllegalStateException("no workspace dependency or sibling named `" + name + "`");
    }
}
