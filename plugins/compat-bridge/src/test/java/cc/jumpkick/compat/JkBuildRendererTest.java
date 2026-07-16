// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.Workspace;
import java.net.URI;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JkBuildRendererTest {

    @Test
    void renders_a_minimal_project_block() {
        JkBuild model =
                new JkBuild(new JkBuild.Project("com.example", "widget", "1.0.0", 21), JkBuild.Dependencies.empty());
        String out = JkBuildRenderer.render(model);
        assertThat(out).isEqualTo("""
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = "21"
                java     = 21
                """);
    }

    @Test
    void renders_application_and_native_blocks_when_set() {
        JkBuild model = JkBuild.builder(JkBuild.Project.builder("com.example", "widget", "1.0.0")
                        .jdkMajor(21)
                        .kotlin(VersionSelector.parseFloating("=2.3.21"))
                        .build())
                .application(new JkBuild.Application("com.example.App", true))
                .nativeConfig(new JkBuild.NativeConfig(null, null, List.of(), null, false))
                .build();
        String out = JkBuildRenderer.render(model);
        assertThat(out).contains("kotlin   = \"=2.3.21\"");
        assertThat(out).contains("[application]");
        assertThat(out).contains("main       = \"com.example.App\"");
        assertThat(out).contains("shadow-jar = true");
        assertThat(out).contains("[native]");
    }

    @Test
    void renders_description_when_set() {
        JkBuild model = new JkBuild(
                JkBuild.Project.builder("com.example", "widget", "1.0.0")
                        .jdkMajor(21)
                        .java(21)
                        .description("A tiny widget library.")
                        .build(),
                JkBuild.Dependencies.empty());
        String out = JkBuildRenderer.render(model);
        assertThat(out).contains("description = \"A tiny widget library.\"");

        JkBuild reparsed = JkBuildParser.parse(out);
        assertThat(reparsed.project().description()).isEqualTo("A tiny widget library.");
    }

    @Test
    void m2install_round_trips() {
        JkBuild model = JkBuild.of(JkBuild.Project.builder("com.example", "widget", "1.0.0")
                .jdkMajor(21)
                .java(21)
                .m2install(true)
                .build());
        String out = JkBuildRenderer.render(model);
        assertThat(out).contains("m2install = true");

        JkBuild reparsed = JkBuildParser.parse(out);
        assertThat(reparsed.project().m2install()).isTrue();
    }

    @Test
    void application_presence_alone_drives_is_application() {
        // [application] declared (even without main) → isApplication() true.
        JkBuild withApplication = JkBuild.builder(JkBuild.Project.builder("com.example", "app", "1.0.0")
                        .jdkMajor(21)
                        .java(21)
                        .build())
                .application(new JkBuild.Application(null, false))
                .build();
        assertThat(JkBuildRenderer.render(withApplication)).contains("[application]");
        assertThat(JkBuildParser.parse(JkBuildRenderer.render(withApplication)).isApplication())
                .isTrue();

        // No [application] at all → isApplication() false, nothing emitted.
        JkBuild lib = JkBuild.of(new JkBuild.Project("com.example", "lib", "1.0.0", 21));
        assertThat(JkBuildRenderer.render(lib)).doesNotContain("[application]");
        assertThat(JkBuildParser.parse(JkBuildRenderer.render(lib)).isApplication())
                .isFalse();
    }

    @Test
    void renders_and_round_trips_manifest_table() {
        var manifest = new LinkedHashMap<String, String>();
        manifest.put("Implementation-Title", "jk-test-runner");
        manifest.put("Implementation-Version", "1.0.0");
        JkBuild model = JkBuild.of(new JkBuild.Project("com.example", "widget", "1.0.0", 21))
                .withManifest(manifest);

        String out = JkBuildRenderer.render(model);
        assertThat(out).contains("[manifest]");
        assertThat(out).contains("\"Implementation-Title\" = \"jk-test-runner\"");
        assertThat(out).contains("\"Implementation-Version\" = \"1.0.0\"");

        JkBuild reparsed = JkBuildParser.parse(out);
        assertThat(reparsed.manifest())
                .containsExactly(
                        Map.entry("Implementation-Title", "jk-test-runner"),
                        Map.entry("Implementation-Version", "1.0.0"));
    }

    @Test
    void renders_deps_grouped_by_scope_sorted_within() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(
                Scope.MAIN,
                List.of(
                        new Dependency(
                                "org.springframework.boot:spring-boot-starter-web", VersionSelector.parse("3.4.0")),
                        new Dependency(
                                "com.fasterxml.jackson.core:jackson-databind", VersionSelector.parse("2.18.2"))));
        byScope.put(
                Scope.TEST,
                List.of(new Dependency("org.junit.jupiter:junit-jupiter", VersionSelector.parse("5.11.0"))));

        JkBuild model = new JkBuild(
                new JkBuild.Project("com.example", "widget", "1.0.0", 21), new JkBuild.Dependencies(byScope));
        String out = JkBuildRenderer.render(model);

        // Main scope sub-table appears before the test sub-table.
        int mainIdx = out.indexOf("[dependencies]");
        int testIdx = out.indexOf("[test-dependencies]");
        assertThat(mainIdx).isLessThan(testIdx).isGreaterThan(0);

        // Inline-table format with name-as-key. `artifact` field omitted when
        // the artifactId matches the key.
        assertThat(out)
                .contains("jackson-databind = { group = \"com.fasterxml.jackson.core\", version = \"=2.18.2\" }");
        assertThat(out)
                .contains("spring-boot-starter-web = { group = \"org.springframework.boot\", version = \"=3.4.0\" }");
        assertThat(out).contains("junit-jupiter = { group = \"org.junit.jupiter\", version = \"=5.11.0\" }");

        // Within a scope, sort by short name (alphabetical): jackson before spring.
        int jacksonIdx = out.indexOf("jackson-databind");
        int springIdx = out.indexOf("spring-boot-starter-web");
        assertThat(jacksonIdx).isLessThan(springIdx);
    }

    @Test
    void git_source_renders_without_group_or_name_and_round_trips() {
        // JkBuildParser rejects `group`/`name` alongside `git` — the coordinate is pure
        // discovery from the cloned repo's own jk.toml — so the renderer must never emit them there.
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        cc.jumpkick.model.GitSource git = cc.jumpkick.model.GitSource.of(
                "github.com/acme/widgets",
                "https://github.com/acme/widgets",
                new cc.jumpkick.model.GitRefSpec.Tag("v1.0.0"));
        byScope.put(Scope.MAIN, List.of(Dependency.git("widgets", "git:widgets", git)));

        JkBuild model = new JkBuild(
                new JkBuild.Project("com.example", "widget", "1.0.0", 21), new JkBuild.Dependencies(byScope));
        String out = JkBuildRenderer.render(model);

        assertThat(out).contains("widgets = { git = \"https://github.com/acme/widgets\", tag = \"v1.0.0\" }");
        assertThat(out).doesNotContain("group =");

        JkBuild reparsed = JkBuildParser.parse(out);
        assertThat(reparsed.dependencies().of(Scope.MAIN)).hasSize(1);
    }

    @Test
    void renders_repositories_block_when_present() {
        JkBuild model = new JkBuild(
                new JkBuild.Project("com.example", "widget", "1.0.0", 21),
                JkBuild.Dependencies.empty(),
                List.of(new RepositorySpec(
                        "sonatype", URI.create("https://s01.oss.sonatype.org/content/repositories/snapshots/"))));
        String out = JkBuildRenderer.render(model);
        assertThat(out).contains("[repositories]");
        assertThat(out).contains("sonatype = \"https://s01.oss.sonatype.org/content/repositories/snapshots/\"");
    }

    @Test
    void renders_workspace_block() {
        JkBuild model = JkBuild.builder(new JkBuild.Project("com.example", "widget-parent", "1.0.0", 21))
                .workspace(new Workspace(List.of("core", "app")))
                .build();
        String out = JkBuildRenderer.render(model);
        assertThat(out).contains("[workspace]");
        assertThat(out).contains("modules = [\"core\", \"app\"]");

        JkBuild reparsed = JkBuildParser.parse(out);
        assertThat(reparsed.isWorkspaceRoot()).isTrue();
        assertThat(reparsed.workspace().modules()).containsExactly("core", "app");
    }

    @Test
    void pinned_and_floating_deps_render_with_distinct_version_literals() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        // Exact pin via `=` prefix; caret-floating via leading `^`.
        byScope.put(
                Scope.MAIN,
                List.of(
                        new Dependency("com.example:pinned", VersionSelector.parse("=1.0.0")),
                        new Dependency("com.example:floating", VersionSelector.parseFloating("^2.0.0"))));

        JkBuild model = new JkBuild(
                new JkBuild.Project("com.example", "widget", "1.0.0", 21), new JkBuild.Dependencies(byScope));
        String out = JkBuildRenderer.render(model);

        // Exact pins retain the leading `=`; caret selectors emit the bare
        // version (parseFloating re-parses a bare version as caret).
        assertThat(out).contains("pinned = { group = \"com.example\", version = \"=1.0.0\" }");
        assertThat(out).contains("floating = { group = \"com.example\", version = \"2.0.0\" }");

        // Round-trip: re-parsing yields the same selector kinds.
        JkBuild reparsed = JkBuildParser.parse(out);
        assertThat(reparsed.dependencies().of(Scope.MAIN))
                .extracting(Dependency::version)
                .anyMatch(v -> v instanceof VersionSelector.Exact)
                .anyMatch(v -> v instanceof VersionSelector.Caret);
    }

    @Test
    void output_round_trips_through_the_parser() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, List.of(new Dependency("com.example:lib", VersionSelector.parse("1.0.0"))));
        byScope.put(
                Scope.PLATFORM,
                List.of(new Dependency(
                        "org.springframework.boot:spring-boot-dependencies", VersionSelector.parse("3.4.0"))));

        JkBuild model = new JkBuild(
                new JkBuild.Project("com.example", "widget", "1.0.0", 21),
                new JkBuild.Dependencies(byScope),
                List.of(new RepositorySpec(
                        "internal", URI.create("https://nexus.example.com/repository/maven-public/"))));
        String out = JkBuildRenderer.render(model);

        JkBuild reparsed = JkBuildParser.parse(out);
        assertThat(reparsed.project().group()).isEqualTo("com.example");
        assertThat(reparsed.project().name()).isEqualTo("widget");
        assertThat(reparsed.project().version()).isEqualTo("1.0.0");
        assertThat(reparsed.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactly("com.example:lib");
        // [platform-dependencies] is a first-class parser table now (spring-boot plan §3.3) —
        // rendered BOM entries survive the render → reparse round trip.
        assertThat(reparsed.dependencies().of(Scope.PLATFORM))
                .extracting(Dependency::module)
                .containsExactly("org.springframework.boot:spring-boot-dependencies");
        assertThat(reparsed.repositories()).extracting(RepositorySpec::name).containsExactly("internal");
    }
}
