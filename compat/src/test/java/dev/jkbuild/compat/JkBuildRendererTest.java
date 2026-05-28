// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compat;

import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.Features;
import dev.jkbuild.model.Profiles;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;
import dev.jkbuild.model.Workspace;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JkBuildRendererTest {

    @Test
    void renders_a_minimal_project_block() {
        JkBuild model = new JkBuild(
                new JkBuild.Project("com.example", "widget", "1.0.0", 21),
                JkBuild.Dependencies.empty());
        String out = JkBuildRenderer.render(model);
        assertThat(out).isEqualTo("""
                [project]
                group    = "com.example"
                artifact = "widget"
                version  = "1.0.0"
                jdk      = 21
                java     = 21
                """);
    }

    @Test
    void renders_main_shadow_and_native_when_set() {
        JkBuild model = new JkBuild(
                new JkBuild.Project("com.example", "widget", "1.0.0", 21, 0, 2,
                        "com.example.App", true, JkBuild.NativeMode.SUPPORTED, null),
                JkBuild.Dependencies.empty());
        String out = JkBuildRenderer.render(model);
        assertThat(out).contains("kotlin   = 2");
        assertThat(out).contains("main     = \"com.example.App\"");
        assertThat(out).contains("shadow   = true");
        assertThat(out).contains("native   = true");
    }

    @Test
    void renders_description_when_set() {
        JkBuild model = new JkBuild(
                new JkBuild.Project("com.example", "widget", "1.0.0", 21, 21, 0,
                        null, false, JkBuild.NativeMode.DISABLED,
                        "A tiny widget library."),
                JkBuild.Dependencies.empty());
        String out = JkBuildRenderer.render(model);
        assertThat(out).contains("description = \"A tiny widget library.\"");

        JkBuild reparsed = JkBuildParser.parse(out);
        assertThat(reparsed.project().description()).isEqualTo("A tiny widget library.");
    }

    @Test
    void renders_deps_grouped_by_scope_sorted_within() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, List.of(
                new Dependency("org.springframework.boot:spring-boot-starter-web",
                        VersionSelector.parse("3.4.0")),
                new Dependency("com.fasterxml.jackson.core:jackson-databind",
                        VersionSelector.parse("2.18.2"))));
        byScope.put(Scope.TEST, List.of(
                new Dependency("org.junit.jupiter:junit-jupiter",
                        VersionSelector.parse("5.11.0"))));

        JkBuild model = new JkBuild(
                new JkBuild.Project("com.example", "widget", "1.0.0", 21),
                new JkBuild.Dependencies(byScope));
        String out = JkBuildRenderer.render(model);

        // Main scope entry comes before test scope entry in the array order.
        int mainIdx = out.indexOf("main = [");
        int testIdx = out.indexOf("test = [");
        assertThat(mainIdx).isLessThan(testIdx).isGreaterThan(0);

        int jacksonIdx = out.indexOf("jackson-databind");
        int springIdx = out.indexOf("spring-boot-starter-web");
        assertThat(jacksonIdx).isLessThan(springIdx);
    }

    @Test
    void renders_repositories_block_when_present() {
        JkBuild model = new JkBuild(
                new JkBuild.Project("com.example", "widget", "1.0.0", 21),
                JkBuild.Dependencies.empty(),
                List.of(new RepositorySpec("sonatype",
                        URI.create("https://s01.oss.sonatype.org/content/repositories/snapshots/"))));
        String out = JkBuildRenderer.render(model);
        assertThat(out).contains("[repositories]");
        assertThat(out).contains(
                "sonatype = \"https://s01.oss.sonatype.org/content/repositories/snapshots/\"");
    }

    @Test
    void renders_workspace_block() {
        JkBuild model = new JkBuild(
                new JkBuild.Project("com.example", "widget-parent", "1.0.0", 21),
                JkBuild.Dependencies.empty(),
                List.of(),
                Profiles.empty(),
                Features.empty(),
                new Workspace(List.of("core", "app")));
        String out = JkBuildRenderer.render(model);
        assertThat(out).contains("[workspace]");
        assertThat(out).contains("members = [\"core\", \"app\"]");

        JkBuild reparsed = JkBuildParser.parse(out);
        assertThat(reparsed.isWorkspaceRoot()).isTrue();
        assertThat(reparsed.workspace().members()).containsExactly("core", "app");
    }

    @Test
    void floating_deps_render_with_at_separator() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, List.of(
                new Dependency("com.example:pinned", VersionSelector.parse("1.0.0"), true),
                new Dependency("com.example:floating",
                        VersionSelector.parseFloating("^2.0.0"), false)));

        JkBuild model = new JkBuild(
                new JkBuild.Project("com.example", "widget", "1.0.0", 21),
                new JkBuild.Dependencies(byScope));
        String out = JkBuildRenderer.render(model);

        assertThat(out).contains("\"com.example:pinned:1.0.0\"");
        assertThat(out).contains("\"com.example:floating@^2.0.0\"");
    }

    @Test
    void output_round_trips_through_the_parser() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, List.of(
                new Dependency("com.example:lib", VersionSelector.parse("1.0.0"))));
        byScope.put(Scope.PLATFORM, List.of(
                new Dependency("org.springframework.boot:spring-boot-dependencies",
                        VersionSelector.parse("3.4.0"))));

        JkBuild model = new JkBuild(
                new JkBuild.Project("com.example", "widget", "1.0.0", 21),
                new JkBuild.Dependencies(byScope),
                List.of(new RepositorySpec("internal",
                        URI.create("https://nexus.example.com/repository/maven-public/"))));
        String out = JkBuildRenderer.render(model);

        JkBuild reparsed = JkBuildParser.parse(out);
        assertThat(reparsed.project().group()).isEqualTo("com.example");
        assertThat(reparsed.project().artifact()).isEqualTo("widget");
        assertThat(reparsed.project().version()).isEqualTo("1.0.0");
        assertThat(reparsed.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactly("com.example:lib");
        assertThat(reparsed.dependencies().of(Scope.PLATFORM))
                .extracting(Dependency::module)
                .containsExactly("org.springframework.boot:spring-boot-dependencies");
        assertThat(reparsed.repositories()).extracting(RepositorySpec::name).containsExactly("internal");
    }
}
