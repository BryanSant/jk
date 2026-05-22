// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.model;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceMergeTest {

    @Test
    void merge_combines_member_deps_into_root() {
        BuildJk root = newProject("root", Map.of(Scope.MAIN, List.of(
                dep("com.foo:a", "1.0"))));
        BuildJk memberA = newProject("a", Map.of(Scope.MAIN, List.of(
                dep("com.foo:b", "2.0"))));
        BuildJk memberB = newProject("b", Map.of(Scope.TEST, List.of(
                dep("org.junit.jupiter:junit-jupiter", "6.1.0"))));

        BuildJk merged = WorkspaceMerge.merge(root, List.of(memberA, memberB));

        assertThat(merged.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactlyInAnyOrder("com.foo:a", "com.foo:b");
        assertThat(merged.dependencies().of(Scope.TEST))
                .extracting(Dependency::module)
                .containsExactly("org.junit.jupiter:junit-jupiter");
    }

    @Test
    void root_declaration_wins_on_module_conflict() {
        BuildJk root = newProject("root", Map.of(Scope.MAIN, List.of(
                dep("com.foo:bar", "3.0"))));
        BuildJk member = newProject("member", Map.of(Scope.MAIN, List.of(
                dep("com.foo:bar", "1.0"))));

        BuildJk merged = WorkspaceMerge.merge(root, List.of(member));
        Dependency surviving = merged.dependencies().of(Scope.MAIN).getFirst();
        assertThat(surviving.version().raw()).isEqualTo("3.0");
    }

    @Test
    void empty_members_returns_root_unchanged() {
        BuildJk root = newProject("root", Map.of());
        assertThat(WorkspaceMerge.merge(root, List.of())).isSameAs(root);
    }

    // --- helpers -----------------------------------------------------------

    private static BuildJk newProject(String artifact, Map<Scope, List<Dependency>> depsByScope) {
        EnumMap<Scope, List<Dependency>> by = new EnumMap<>(Scope.class);
        depsByScope.forEach(by::put);
        return new BuildJk(
                new BuildJk.Project("com.example", artifact, "0.1.0", null),
                new BuildJk.Dependencies(by));
    }

    private static Dependency dep(String module, String version) {
        return new Dependency(module, VersionSelector.parse(version));
    }
}
