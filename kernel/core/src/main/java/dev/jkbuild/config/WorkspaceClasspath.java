// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Dependency;
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
 * Resolve workspace-internal dependency jars for a single member's build
 * (PRD §13.3 composite-build semantics).
 *
 * <p>External deps come from {@code jk.lock} via {@code ClasspathResolver}.
 * Workspace-internal coords are filtered out of the lockfile by
 * {@code WorkspaceMerge}, so they must be re-injected here: for each
 * sibling member whose project coord matches an entry in the current
 * member's {@code dependencies.<scope>}, add the sibling's main jar
 * (under the workspace's shared {@code target/} per {@link BuildLayout})
 * to the classpath.
 */
public final class WorkspaceClasspath {

    private WorkspaceClasspath() {}

    /**
     * @param memberDir the member being built
     * @param member the parsed manifest of {@code memberDir}
     * @param scopes the scopes whose deps should contribute (typically
     *     {@code MAIN} for compile, {@code MAIN}+{@code TEST} for tests)
     */
    public static Result resolve(Path projectDir, JkBuild project, Set<Scope> scopes)
            throws IOException {
        // Case 1: project IS the workspace root — add all member jars implicitly.
        // Members' compiled outputs are always applicable to the root's classpath;
        // no explicit dependency declaration is needed.
        if (project.isWorkspaceRoot()) {
            return resolveForRoot(projectDir, project);
        }

        // Case 2: project is a workspace member — add declared sibling deps.
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
        Map<String, Path>   siblingDirByModule  = new HashMap<>();
        Map<String, Path>   siblingJarByModule  = new HashMap<>();
        Map<String, Path>   siblingJarByName    = new HashMap<>();
        Map<String, String> siblingCoordByName  = new HashMap<>(); // name → full coord
        for (String memberName : rootManifest.workspace().members()) {
            Path siblingDir = root.resolve(memberName);
            Path siblingManifest = siblingDir.resolve("jk.toml");
            if (!Files.exists(siblingManifest)) continue;
            JkBuild sibling;
            try {
                sibling = JkBuildParser.parse(siblingManifest);
            } catch (RuntimeException ignored) {
                continue;
            }
            String moduleCoord = sibling.project().group() + ":" + sibling.project().name();
            Path jar = BuildLayout.of(siblingDir, sibling).mainJar();
            siblingDirByModule.put(moduleCoord, siblingDir);
            siblingJarByModule.put(moduleCoord, jar);
            siblingJarByName.put(sibling.project().name(), jar);
            siblingCoordByName.put(sibling.project().name(), moduleCoord);
        }

        // Collect the full transitive workspace closure via BFS.  Direct deps
        // seed the queue; each discovered sibling's own workspace deps are then
        // enqueued so that e.g. io→core→model are all on the classpath even
        // though the member's jk.toml only declares the direct dep (core).
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
            try { sibBuild = JkBuildParser.parse(sibToml); }
            catch (RuntimeException ignored) { continue; }
            for (Scope scope : Set.of(Scope.MAIN)) { // only MAIN propagates transitively
                for (Dependency dep : sibBuild.dependencies().of(scope)) {
                    String depModule = resolveWorkspaceRef(dep.module(), siblingCoordByName);
                    if (siblingJarByModule.containsKey(depModule) && visited.add(depModule)) {
                        queue.add(depModule);
                    }
                }
            }
        }

        List<Path> jars = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<Path> siblingLockfiles = new ArrayList<>();
        for (String module : visited) {
            Path siblingJar = siblingJarByModule.get(module);
            if (siblingJar == null) continue;
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
        return new Result(jars, missing, siblingLockfiles);
    }

    /** Resolve a {@code workspace:<name>} dep reference to its full {@code group:name} coord. */
    private static String resolveWorkspaceRef(String module, Map<String, String> coordByName) {
        if (!module.startsWith("workspace:")) return module;
        String name = module.substring("workspace:".length());
        String coord = coordByName.get(name);
        return coord != null ? coord : module;
    }

    /**
     * When the workspace root itself has source code, all member jars are
     * automatically on its classpath — no explicit dep declaration needed.
     */
    private static Result resolveForRoot(Path root, JkBuild rootManifest)
            throws IOException {
        List<Path> jars = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String memberName : rootManifest.workspace().members()) {
            Path siblingDir = root.resolve(memberName);
            Path siblingManifest = siblingDir.resolve("jk.toml");
            if (!Files.exists(siblingManifest)) continue;
            JkBuild sibling;
            try {
                sibling = JkBuildParser.parse(siblingManifest);
            } catch (RuntimeException ignored) {
                continue;
            }
            Path jar = BuildLayout.of(siblingDir, sibling).mainJar();
            if (Files.exists(jar)) {
                jars.add(jar);
            } else {
                missing.add(sibling.project().group() + ":" + sibling.project().name()
                        + " (expected at " + jar + ")");
            }
        }
        return new Result(jars, missing, List.of());
    }

    public record Result(List<Path> jars, List<String> missingSiblingJars,
                         List<Path> siblingLockfiles) {
        public Result {
            jars = List.copyOf(jars);
            missingSiblingJars = List.copyOf(missingSiblingJars);
            siblingLockfiles = List.copyOf(siblingLockfiles);
        }
        /** Back-compat constructor for callers that don't use sibling lockfiles. */
        public Result(List<Path> jars, List<String> missingSiblingJars) {
            this(jars, missingSiblingJars, List.of());
        }
    }
}
