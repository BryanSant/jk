// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
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
        // Case 1: project IS the workspace root — add all module jars implicitly.
        // Modules' compiled outputs are always applicable to the root's classpath;
        // no explicit dependency declaration is needed.
        if (project.isWorkspaceRoot()) {
            return resolveForRoot(projectDir, project);
        }

        // Case 2: project is a workspace module — add declared sibling deps.
        var rootOpt = WorkspaceLocator.findRoot(projectDir);
        if (rootOpt.isEmpty()) {
            return new Result(List.of(), List.of());
        }
        Path root = rootOpt.get();
        JkBuild rootManifest = JkBuildParser.parse(root.resolve("jk.toml"));
        if (!rootManifest.isWorkspaceRoot()) {
            return new Result(List.of(), List.of());
        }

        // Build bidirectional index: module-coord and bare-name → sibling dir + jar
        Map<String, Path> siblingDirByModule = new HashMap<>();
        Map<String, Path> siblingJarByModule = new HashMap<>();
        Map<String, Path> siblingJarByName = new HashMap<>();
        Map<String, String> siblingCoordByName = new HashMap<>(); // name → full coord
        for (String moduleName : rootManifest.workspace().modules()) {
            Path siblingDir = root.resolve(moduleName);
            Path siblingManifest = siblingDir.resolve("jk.toml");
            if (!Files.exists(siblingManifest)) continue;
            JkBuild sibling;
            try {
                sibling = JkBuildParser.parse(siblingManifest);
            } catch (RuntimeException ignored) {
                continue;
            }
            String moduleCoord =
                    sibling.project().group() + ":" + sibling.project().name();
            Path jar = BuildLayout.of(siblingDir, sibling).mainJar();
            siblingDirByModule.put(moduleCoord, siblingDir);
            siblingJarByModule.put(moduleCoord, jar);
            siblingJarByName.put(sibling.project().name(), jar);
            siblingCoordByName.put(sibling.project().name(), moduleCoord);
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
            Path sibDir = siblingDirByModule.get(coord);
            if (sibDir == null) continue;
            Path sibToml = sibDir.resolve("jk.toml");
            if (!Files.exists(sibToml)) continue;
            JkBuild sibBuild;
            try {
                sibBuild = JkBuildParser.parse(sibToml);
            } catch (RuntimeException ignored) {
                continue;
            }
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

    /**
     * When the workspace root itself has source code, all module jars are automatically on its
     * classpath — no explicit dep declaration needed.
     */
    private static Result resolveForRoot(Path root, JkBuild rootManifest) throws IOException {
        List<Path> jars = new ArrayList<>();
        List<Path> closureJars = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String moduleName : rootManifest.workspace().modules()) {
            Path siblingDir = root.resolve(moduleName);
            Path siblingManifest = siblingDir.resolve("jk.toml");
            if (!Files.exists(siblingManifest)) continue;
            JkBuild sibling;
            try {
                sibling = JkBuildParser.parse(siblingManifest);
            } catch (RuntimeException ignored) {
                continue;
            }
            Path jar = BuildLayout.of(siblingDir, sibling).mainJar();
            closureJars.add(jar);
            if (Files.exists(jar)) {
                jars.add(jar);
            } else {
                missing.add(sibling.project().group() + ":" + sibling.project().name() + " (expected at " + jar + ")");
            }
        }
        return new Result(jars, missing, List.of(), closureJars);
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
