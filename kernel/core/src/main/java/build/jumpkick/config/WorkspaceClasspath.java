// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.config;

import build.jumpkick.layout.BuildLayout;
import build.jumpkick.model.Dependency;
import build.jumpkick.model.JkBuild;
import build.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Resolve workspace-internal dependency jars for a single module's build (PRD §13.3 composite-build
 * semantics).
 *
 * <p>External deps come from {@code jk.lock} via {@code ClasspathResolver}. Workspace-internal
 * coords are filtered out of the lockfile by {@code WorkspaceMerge}, so they must be re-injected
 * here: for each sibling module whose project coord matches an entry in the current module's {@code
 * dependencies.<scope>}, add the sibling's main jar (under the workspace's shared {@code target/}
 * per {@link BuildLayout}) to the classpath.
 */
public final class WorkspaceClasspath {

    private WorkspaceClasspath() {}

    /**
     * @param moduleDir the module being built
     * @param module the parsed manifest of {@code moduleDir}
     * @param scopes the scopes whose deps should contribute (typically {@code MAIN} for compile,
     *     {@code MAIN}+{@code TEST} for tests)
     */
    public static Result resolve(Path projectDir, JkBuild project, Set<Scope> scopes) throws IOException {
        // Locate the workspace root — this project is either the root itself or a member.
        Path root;
        JkBuild rootManifest;
        if (project.isWorkspaceRoot()) {
            root = projectDir;
            rootManifest = project;
        } else {
            var rootOpt = WorkspaceLocator.findRoot(projectDir);
            if (rootOpt.isEmpty()) {
                return new Result(List.of(), List.of());
            }
            root = rootOpt.get();
            rootManifest = JkBuildParser.parse(root.resolve("jk.toml"));
            if (!rootManifest.isWorkspaceRoot()) {
                return new Result(List.of(), List.of());
            }
        }

        // Every build unit in the workspace — the members AND the buildable root — is a
        // resolvable sibling. Inter-unit dependencies are explicit (`x = { workspace = true }`)
        // and may point in any acyclic direction: member→member, member→root, or root→member
        // (Cargo/uv style; no implicit "root depends on all members"). The unit doing the
        // resolving is excluded from its own sibling set.
        Path self = projectDir.toAbsolutePath().normalize();
        Map<String, Path> siblingDirByModule = new HashMap<>();
        Map<String, Path> siblingJarByModule = new HashMap<>();
        Map<String, JkBuild> siblingManifestByCoord = new HashMap<>();
        Map<String, String> siblingCoordByName = new HashMap<>(); // name → full coord
        List<Path> unitDirs = new ArrayList<>();
        for (String moduleName : rootManifest.workspace().modules()) {
            unitDirs.add(root.resolve(moduleName));
        }
        unitDirs.add(root); // the root is a unit too
        for (Path unitDir : unitDirs) {
            if (unitDir.toAbsolutePath().normalize().equals(self)) continue; // exclude self
            Path manifest = unitDir.resolve("jk.toml");
            if (!Files.exists(manifest)) continue;
            JkBuild unit;
            try {
                unit = unitDir.equals(root) ? rootManifest : JkBuildParser.parse(manifest);
            } catch (RuntimeException ignored) {
                continue;
            }
            String coord = unit.project().group() + ":" + unit.project().name();
            siblingDirByModule.put(coord, unitDir);
            siblingJarByModule.put(coord, BuildLayout.of(unitDir, unit).mainJar());
            siblingManifestByCoord.put(coord, unit);
            siblingCoordByName.put(unit.project().name(), coord);
        }

        // Collect the full transitive workspace closure via BFS.  Direct deps
        // seed the queue; each discovered sibling's own workspace deps are then
        // enqueued so that e.g. io→core→model are all on the classpath even
        // though the module's jk.toml only declares the direct dep (core).
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        for (Scope scope : scopes) {
            for (Dependency dep : project.dependencies().of(scope)) {
                String module = resolveWorkspaceRef(dep.module(), siblingCoordByName);
                if (siblingJarByModule.containsKey(module) && visited.add(module)) {
                    queue.add(module);
                }
            }
        }
        while (!queue.isEmpty()) {
            String coord = queue.poll();
            JkBuild sibBuild = siblingManifestByCoord.get(coord);
            if (sibBuild == null) continue;
            // MAIN and EXPORT propagate transitively: a sibling's exported deps
            // (api semantics) ride along to anything that depends on it, and MAIN
            // deps stay visible down the workspace chain (io→core→model).
            for (Scope scope : Set.of(Scope.EXPORT, Scope.MAIN)) {
                for (Dependency dep : sibBuild.dependencies().of(scope)) {
                    String depModule = resolveWorkspaceRef(dep.module(), siblingCoordByName);
                    if (siblingJarByModule.containsKey(depModule) && visited.add(depModule)) {
                        queue.add(depModule);
                    }
                }
            }
        }

        List<Path> jars = new ArrayList<>();
        List<Path> closureJars = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<Path> siblingLockfiles = new ArrayList<>();
        for (String module : visited) {
            Path siblingJar = siblingJarByModule.get(module);
            if (siblingJar == null) continue;
            // The full declared closure, whether or not the jar is built yet —
            // an IDE module graph depends on declared edges, not on compiled
            // artifacts (IntelliJ compiles the modules itself).
            closureJars.add(siblingJar);
            if (Files.exists(siblingJar)) {
                jars.add(siblingJar);
            } else {
                missing.add(module + " (expected at " + siblingJar + ")");
            }
            // Collect the sibling's lockfile so the caller can include its
            // external transitive deps on the compile classpath (e.g. tomlj
            // declared in jk-core is needed by jk-io via the transitive chain).
            Path sibDir = siblingDirByModule.get(module);
            if (sibDir != null) {
                Path lockFile = sibDir.resolve("jk.lock");
                if (Files.exists(lockFile)) siblingLockfiles.add(lockFile);
            }
        }
        return new Result(jars, missing, siblingLockfiles, closureJars);
    }

    /** Resolve a {@code workspace:<name>} dep reference to its full {@code group:name} coord. */
    private static String resolveWorkspaceRef(String module, Map<String, String> coordByName) {
        if (!Dependency.isWorkspaceRef(module)) return module;
        String name = Dependency.workspaceName(module);
        String coord = coordByName.get(name);
        return coord != null ? coord : module;
    }

    public record Result(
            List<Path> jars,
            List<String> missingSiblingJars,
            List<Path> siblingLockfiles,
            List<Path> siblingClosureJars) {
        public Result {
            jars = List.copyOf(jars);
            missingSiblingJars = List.copyOf(missingSiblingJars);
            siblingLockfiles = List.copyOf(siblingLockfiles);
            siblingClosureJars = List.copyOf(siblingClosureJars);
        }

        /**
         * Back-compat constructor for callers that don't distinguish the declared closure from the
         * built jars (build/run): the closure defaults to {@code jars}.
         */
        public Result(List<Path> jars, List<String> missingSiblingJars, List<Path> siblingLockfiles) {
            this(jars, missingSiblingJars, siblingLockfiles, jars);
        }

        /** Back-compat constructor for callers that don't use sibling lockfiles. */
        public Result(List<Path> jars, List<String> missingSiblingJars) {
            this(jars, missingSiblingJars, List.of(), jars);
        }
    }
}
