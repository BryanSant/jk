// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkspaceMergeTest {

    @Test
    void merge_combines_module_deps_into_root() {
        JkBuild root = newProject("root", Map.of(Scope.MAIN, List.of(dep("a", "com.foo:a", "1.0"))));
        JkBuild moduleA = newProject("a", Map.of(Scope.MAIN, List.of(dep("b", "com.foo:b", "2.0"))));
        JkBuild moduleB = newProject(
                "b", Map.of(Scope.TEST, List.of(dep("junit-jupiter", "org.junit.jupiter:junit-jupiter", "6.1.0"))));

        JkBuild merged = WorkspaceMerge.merge(root, List.of(moduleA, moduleB));

        assertThat(merged.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactlyInAnyOrder("com.foo:a", "com.foo:b");
        assertThat(merged.dependencies().of(Scope.TEST))
                .extracting(Dependency::module)
                .containsExactly("org.junit.jupiter:junit-jupiter");
    }

    @Test
    void root_declaration_wins_on_module_conflict() {
        JkBuild root = newProject("root", Map.of(Scope.MAIN, List.of(dep("bar", "com.foo:bar", "3.0"))));
        JkBuild module = newProject("module", Map.of(Scope.MAIN, List.of(dep("bar", "com.foo:bar", "1.0"))));

        JkBuild merged = WorkspaceMerge.merge(root, List.of(module));
        Dependency surviving = merged.dependencies().of(Scope.MAIN).getFirst();
        assertThat(surviving.version().raw()).isEqualTo("3.0");
    }

    @Test
    void empty_modules_returns_root_unchanged() {
        JkBuild root = newProject("root", Map.of());
        assertThat(WorkspaceMerge.merge(root, List.of())).isSameAs(root);
    }

    @Test
    void workspace_dep_resolves_against_sibling_artifact() {
        // Module dep `jk-core.workspace = true` materializes as a placeholder
        // module `workspace:jk-core`; the merge step rewrites it to the
        // sibling's actual coord (and then dedupes since it's internal).
        JkBuild root = workspaceRoot("jk", List.of("jk-core", "jk-cli"));
        JkBuild core = newProject("jk-core", Map.of());
        JkBuild cli = newProject("jk-cli", Map.of(Scope.MAIN, List.of(workspacePlaceholder("jk-core"))));

        JkBuild merged = WorkspaceMerge.merge(root, List.of(core, cli));

        // The placeholder gets rewritten to dev.jkbuild:jk-core, which is
        // then dropped as workspace-internal.
        assertThat(merged.dependencies().of(Scope.MAIN)).isEmpty();
    }

    @Test
    void workspace_dep_resolves_against_workspace_dependencies_when_no_sibling() {
        Workspace.WorkspaceDependency wsDep = new Workspace.WorkspaceDependency(
                "org.junit.jupiter", "junit-jupiter", VersionSelector.parse("6.1.0"), null);
        JkBuild root = JkBuild.builder(new JkBuild.Project("dev.jkbuild", "jk", "0.1.0", 0))
                .workspace(new Workspace(List.of("core"), Map.of("junit-jupiter", wsDep)))
                .build();

        JkBuild core = newProject("jk-core", Map.of(Scope.TEST, List.of(workspacePlaceholder("junit-jupiter"))));

        JkBuild merged = WorkspaceMerge.merge(root, List.of(core));

        var testDeps = merged.dependencies().of(Scope.TEST);
        assertThat(testDeps).hasSize(1);
        assertThat(testDeps.getFirst().library()).isEqualTo("junit-jupiter");
        assertThat(testDeps.getFirst().module()).isEqualTo("org.junit.jupiter:junit-jupiter");
    }

    @Test
    void unresolved_workspace_dep_throws() {
        JkBuild root = workspaceRoot("jk", List.of("core"));
        JkBuild core = newProject("jk-core", Map.of(Scope.MAIN, List.of(workspacePlaceholder("does-not-exist"))));

        assertThatThrownBy(() -> WorkspaceMerge.merge(root, List.of(core)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist");
    }

    // --- helpers -----------------------------------------------------------

    private static JkBuild newProject(String artifact, Map<Scope, List<Dependency>> depsByScope) {
        EnumMap<Scope, List<Dependency>> by = new EnumMap<>(Scope.class);
        depsByScope.forEach(by::put);
        return new JkBuild(new JkBuild.Project("dev.jkbuild", artifact, "0.1.0", 0), new JkBuild.Dependencies(by));
    }

    private static JkBuild workspaceRoot(String artifact, List<String> modules) {
        return JkBuild.builder(new JkBuild.Project("dev.jkbuild", artifact, "0.1.0", 0))
                .workspace(new Workspace(modules))
                .build();
    }

    private static Dependency dep(String name, String module, String version) {
        return Dependency.of(name, module, VersionSelector.parse(version));
    }

    /**
     * Mirrors what {@code JkBuildParser} emits for {@code <name>.workspace = true} when there is no
     * matching [workspace.dependencies] entry at parse time.
     */
    private static Dependency workspacePlaceholder(String name) {
        return new Dependency(name, "workspace:" + name, new VersionSelector.Latest("workspace"), null, null, false);
    }
}
