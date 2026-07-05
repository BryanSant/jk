// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.publish;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.GitSource;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PublishablePomTest {

    @Test
    void minimal_publishable_pom_has_required_fields_only() {
        JkBuild project =
                new JkBuild(new JkBuild.Project("com.example", "widget", "1.0.0", 21), JkBuild.Dependencies.empty());
        String xml = PublishablePom.render(project, null).xml();

        assertThat(xml).contains("<groupId>com.example</groupId>");
        assertThat(xml).contains("<artifactId>widget</artifactId>");
        assertThat(xml).contains("<version>1.0.0</version>");
        assertThat(xml).contains("<packaging>jar</packaging>");
        // Stripped per PRD §21.2.
        assertThat(xml).doesNotContain("<repositories>");
        assertThat(xml).doesNotContain("<build>");
        assertThat(xml).doesNotContain("<profiles>");
    }

    @Test
    void branch_git_dependency_is_skipped_for_publish() {
        // Even though a branch-tracked git dep is locked in jk.lock, it's still not a stable
        // reference for external consumers of the published artifact.
        GitSource source =
                GitSource.of("https://github.com/acme/lib", "https://github.com/acme/lib", new GitRefSpec.Branch("main"));
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, List.of(Dependency.git("lib", "com.example:lib", source)));
        String xml = PublishablePom.render(
                        new JkBuild(
                                new JkBuild.Project("com.example", "widget", "1.0.0", 21),
                                new JkBuild.Dependencies(byScope)),
                        null)
                .xml();

        // No broken <version>=git</version>, no phantom dependency.
        assertThat(xml).doesNotContain("=git");
        assertThat(xml).doesNotContain("<artifactId>lib</artifactId>");
    }

    @Test
    void emits_dependency_management_for_platform_scope() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(
                Scope.PLATFORM,
                List.of(new Dependency(
                        "org.springframework.boot:spring-boot-dependencies", VersionSelector.parse("=3.4.0"))));

        String xml = PublishablePom.render(
                        new JkBuild(
                                new JkBuild.Project("com.example", "widget", "1.0.0", 21),
                                new JkBuild.Dependencies(byScope)),
                        null)
                .xml();

        assertThat(xml).contains("<dependencyManagement>");
        assertThat(xml).contains("<scope>import</scope>");
        assertThat(xml).contains("<type>pom</type>");
    }

    @Test
    void processor_scope_is_dropped_for_publish() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(
                Scope.PROCESSOR,
                List.of(new Dependency("org.projectlombok:lombok", VersionSelector.parse("=1.18.30"))));
        String xml = PublishablePom.render(
                        new JkBuild(
                                new JkBuild.Project("com.example", "widget", "1.0.0", 21),
                                new JkBuild.Dependencies(byScope)),
                        null)
                .xml();
        // Compile-time only; not part of the consumer-facing artifact surface.
        assertThat(xml).doesNotContain("lombok");
        // And there's definitely no <build> block.
        assertThat(xml).doesNotContain("<build>");
    }

    @Test
    void metadata_block_renders_license_developer_scm() {
        var meta = new PublishablePom.Metadata(
                "widget",
                "A widget",
                "https://example.com/widget",
                List.of(new PublishablePom.License("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0")),
                List.of(new PublishablePom.Developer("bsant", "Bryan Sant", "bsant@example.com")),
                new PublishablePom.Scm("https://github.com/example/widget", null, null));

        String xml = PublishablePom.render(
                        new JkBuild(
                                new JkBuild.Project("com.example", "widget", "1.0.0", 21),
                                JkBuild.Dependencies.empty()),
                        meta)
                .xml();

        assertThat(xml).contains("<name>widget</name>");
        assertThat(xml).contains("<description>A widget</description>");
        assertThat(xml).contains("<url>https://example.com/widget</url>");
        assertThat(xml).contains("<name>Apache-2.0</name>");
        assertThat(xml).contains("<id>bsant</id>");
        assertThat(xml).contains("<email>bsant@example.com</email>");
        assertThat(xml).contains("<url>https://github.com/example/widget</url>");
    }

    @Test
    void project_description_is_emitted_when_metadata_omits_it() {
        JkBuild.Project p = JkBuild.Project.builder("com.example", "widget", "1.0.0")
                .jdkMajor(21)
                .java(21)
                .description("A widget from jk.toml.")
                .build();
        String xml = PublishablePom.render(new JkBuild(p, JkBuild.Dependencies.empty()), null)
                .xml();
        assertThat(xml).contains("<description>A widget from jk.toml.</description>");
    }

    @Test
    void metadata_description_overrides_project_description() {
        JkBuild.Project p = JkBuild.Project.builder("com.example", "widget", "1.0.0")
                .jdkMajor(21)
                .java(21)
                .description("from jk.toml")
                .build();
        var meta = new PublishablePom.Metadata(null, "from publish call", null, List.of(), List.of(), null);
        String xml = PublishablePom.render(new JkBuild(p, JkBuild.Dependencies.empty()), meta)
                .xml();
        assertThat(xml).contains("<description>from publish call</description>");
        assertThat(xml).doesNotContain("from jk.toml");
    }

    @Test
    void standard_scopes_carry_their_maven_scope_tags() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, List.of(new Dependency("g:main", VersionSelector.parse("=1"))));
        byScope.put(Scope.RUNTIME, List.of(new Dependency("g:rt", VersionSelector.parse("=1"))));
        byScope.put(Scope.PROVIDED, List.of(new Dependency("g:prov", VersionSelector.parse("=1"))));
        byScope.put(Scope.TEST, List.of(new Dependency("g:test", VersionSelector.parse("=1"))));

        String xml = PublishablePom.render(
                        new JkBuild(
                                new JkBuild.Project("com.example", "widget", "1.0.0", 21),
                                new JkBuild.Dependencies(byScope)),
                        null)
                .xml();

        // MAIN → compile (no <scope> tag, Maven default).
        assertThat(xml).contains("""
                    <dependency>
                      <groupId>g</groupId>
                      <artifactId>main</artifactId>
                      <version>1</version>
                    </dependency>
                """);
        assertThat(xml).contains("<scope>runtime</scope>");
        assertThat(xml).contains("<scope>provided</scope>");
        assertThat(xml).contains("<scope>test</scope>");
    }
}
