// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.Scope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

        Map<String, Path> siblingJarByModule = new HashMap<>();
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
            // Each member owns its own target/ — sibling jars are at <siblingDir>/target/.
            Path jar = BuildLayout.of(siblingDir, sibling).mainJar();
            siblingJarByModule.put(moduleCoord, jar);
        }

        List<Path> jars = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Scope scope : scopes) {
            for (Dependency dep : project.dependencies().of(scope)) {
                Path siblingJar = siblingJarByModule.get(dep.module());
                if (siblingJar == null) continue;
                if (Files.exists(siblingJar)) {
                    if (!jars.contains(siblingJar)) jars.add(siblingJar);
                } else {
                    missing.add(dep.module() + " (expected at " + siblingJar + ")");
                }
            }
        }
        return new Result(jars, missing);
    }

    /**
     * When the workspace root itself has source code, all member jars are
     * automatically on its classpath — no explicit dep declaration needed.
     * Members missing their jars are reported as errors so the root build
     * fails clearly instead of compiling against stale or absent types.
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
        return new Result(jars, missing);
    }

    public record Result(List<Path> jars, List<String> missingSiblingJars) {
        public Result {
            jars = List.copyOf(jars);
            missingSiblingJars = List.copyOf(missingSiblingJars);
        }
    }
}
