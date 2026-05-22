// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.compat;

import dev.buildjk.hocon.BuildJkParser;
import dev.buildjk.model.BuildJk;
import dev.buildjk.model.Dependency;
import dev.buildjk.model.RepositorySpec;
import dev.buildjk.model.Scope;
import dev.buildjk.model.VersionSelector;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BuildJkRendererTest {

    @Test
    void renders_a_minimal_project_block() {
        BuildJk model = new BuildJk(
                new BuildJk.Project("com.example", "widget", "1.0.0", "21"),
                BuildJk.Dependencies.empty());
        String out = BuildJkRenderer.render(model);
        assertThat(out).isEqualTo("""
                project {
                  group    = "com.example"
                  artifact = "widget"
                  version  = "1.0.0"
                  jdk      = "21"
                }
                """);
    }

    @Test
    void renders_deps_grouped_by_scope_sorted_within() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, List.of(
                new Dependency("org.springframework.boot:spring-boot-starter-web",
                        VersionSelector.parse("=3.4.0")),
                new Dependency("com.fasterxml.jackson.core:jackson-databind",
                        VersionSelector.parse("=2.18.2"))));
        byScope.put(Scope.TEST, List.of(
                new Dependency("org.junit.jupiter:junit-jupiter",
                        VersionSelector.parse("=5.11.0"))));

        BuildJk model = new BuildJk(
                new BuildJk.Project("com.example", "widget", "1.0.0", "21"),
                new BuildJk.Dependencies(byScope));
        String out = BuildJkRenderer.render(model);

        // Main first, then test. Within main, modules sorted alphabetically.
        int mainIdx = out.indexOf("dependencies.main");
        int testIdx = out.indexOf("dependencies.test");
        assertThat(mainIdx).isLessThan(testIdx).isGreaterThan(0);

        int jacksonIdx = out.indexOf("jackson-databind");
        int springIdx = out.indexOf("spring-boot-starter-web");
        assertThat(jacksonIdx).isLessThan(springIdx);
    }

    @Test
    void renders_repositories_block_when_present() {
        BuildJk model = new BuildJk(
                new BuildJk.Project("com.example", "widget", "1.0.0", "21"),
                BuildJk.Dependencies.empty(),
                List.of(new RepositorySpec("sonatype",
                        URI.create("https://s01.oss.sonatype.org/content/repositories/snapshots/"))));
        String out = BuildJkRenderer.render(model);
        assertThat(out).contains(
                "repositories.sonatype { url = \"https://s01.oss.sonatype.org/content/repositories/snapshots/\" }");
    }

    @Test
    void output_round_trips_through_the_parser() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, List.of(
                new Dependency("com.example:lib", VersionSelector.parse("=1.0.0"))));
        byScope.put(Scope.PLATFORM, List.of(
                new Dependency("org.springframework.boot:spring-boot-dependencies",
                        VersionSelector.parse("=3.4.0"))));

        BuildJk model = new BuildJk(
                new BuildJk.Project("com.example", "widget", "1.0.0", "21"),
                new BuildJk.Dependencies(byScope),
                List.of(new RepositorySpec("internal",
                        URI.create("https://nexus.example.com/repository/maven-public/"))));
        String out = BuildJkRenderer.render(model);

        BuildJk reparsed = BuildJkParser.parse(out);
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
