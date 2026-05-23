// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.mvn;

import dev.buildjk.model.BuildJk;
import dev.buildjk.model.Dependency;
import dev.buildjk.model.Features;
import dev.buildjk.model.Profiles;
import dev.buildjk.model.RepositorySpec;
import dev.buildjk.model.Scope;
import dev.buildjk.model.VersionSelector;
import dev.buildjk.model.Workspace;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PomExporterTest {

    @Test
    void minimal_project_emits_a_publishable_pom() {
        BuildJk model = new BuildJk(
                new BuildJk.Project("com.example", "widget", "1.0.0", "21"),
                BuildJk.Dependencies.empty());

        String xml = PomExporter.export(model).xml();
        assertThat(xml).contains("<modelVersion>4.0.0</modelVersion>");
        assertThat(xml).contains("<groupId>com.example</groupId>");
        assertThat(xml).contains("<artifactId>widget</artifactId>");
        assertThat(xml).contains("<version>1.0.0</version>");
        assertThat(xml).contains("<packaging>jar</packaging>");
        assertThat(xml).contains("<maven.compiler.release>21</maven.compiler.release>");
    }

    @Test
    void dependencies_emit_with_correct_scopes() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, List.of(
                new Dependency("com.example:lib", VersionSelector.parse("=1.0.0"))));
        byScope.put(Scope.RUNTIME, List.of(
                new Dependency("com.mysql:mysql-connector-j", VersionSelector.parse("=9.0.0"))));
        byScope.put(Scope.PROVIDED, List.of(
                new Dependency("jakarta.servlet:jakarta.servlet-api",
                        VersionSelector.parse("=6.1.0"))));
        byScope.put(Scope.TEST, List.of(
                new Dependency("org.junit.jupiter:junit-jupiter",
                        VersionSelector.parse("=5.11.0"))));

        String xml = PomExporter.export(new BuildJk(
                new BuildJk.Project("com.example", "widget", "1.0.0", "21"),
                new BuildJk.Dependencies(byScope))).xml();

        // Main scope has no <scope> element (Maven default == compile).
        assertThat(xml).contains("""
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                """);
        // Other scopes carry their <scope> tag.
        assertThat(xml).contains("<scope>runtime</scope>");
        assertThat(xml).contains("<scope>provided</scope>");
        assertThat(xml).contains("<scope>test</scope>");
    }

    @Test
    void platform_deps_become_dependency_management_import_scope() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.PLATFORM, List.of(
                new Dependency("org.springframework.boot:spring-boot-dependencies",
                        VersionSelector.parse("=3.4.0"))));

        String xml = PomExporter.export(new BuildJk(
                new BuildJk.Project("com.example", "widget", "1.0.0", "21"),
                new BuildJk.Dependencies(byScope))).xml();

        assertThat(xml).contains("<dependencyManagement>");
        assertThat(xml).contains("<scope>import</scope>");
        assertThat(xml).contains("<type>pom</type>");
        assertThat(xml).contains("<artifactId>spring-boot-dependencies</artifactId>");
    }

    @Test
    void processor_deps_become_annotation_processor_paths() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.PROCESSOR, List.of(
                new Dependency("org.projectlombok:lombok", VersionSelector.parse("=1.18.30"))));

        String xml = PomExporter.export(new BuildJk(
                new BuildJk.Project("com.example", "widget", "1.0.0", "21"),
                new BuildJk.Dependencies(byScope))).xml();

        assertThat(xml).contains("<artifactId>maven-compiler-plugin</artifactId>");
        assertThat(xml).contains("<annotationProcessorPaths>");
        assertThat(xml).contains("<artifactId>lombok</artifactId>");
        assertThat(xml).contains("<release>21</release>");
    }

    @Test
    void workspace_root_emits_pom_packaging_and_modules() {
        BuildJk model = new BuildJk(
                new BuildJk.Project("com.example", "widget-parent", "1.0.0", "21"),
                BuildJk.Dependencies.empty(),
                List.of(),
                Profiles.empty(),
                Features.empty(),
                new Workspace(List.of("core", "app")));

        String xml = PomExporter.export(model).xml();
        assertThat(xml).contains("<packaging>pom</packaging>");
        assertThat(xml).contains("<module>core</module>");
        assertThat(xml).contains("<module>app</module>");
    }

    @Test
    void repositories_are_emitted_with_id_and_url() {
        BuildJk model = new BuildJk(
                new BuildJk.Project("com.example", "widget", "1.0.0", "21"),
                BuildJk.Dependencies.empty(),
                List.of(new RepositorySpec("internal",
                        URI.create("https://nexus.example.com/repository/maven-public/"))));

        String xml = PomExporter.export(model).xml();
        assertThat(xml).contains("<id>internal</id>");
        assertThat(xml).contains("<url>https://nexus.example.com/repository/maven-public/</url>");
    }

    @Test
    void caret_selector_emits_fidelity_warning_but_pins_to_version() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, List.of(
                new Dependency("com.example:lib", VersionSelector.parse("^2.0.0"))));

        var result = PomExporter.export(new BuildJk(
                new BuildJk.Project("com.example", "widget", "1.0.0", "21"),
                new BuildJk.Dependencies(byScope)));

        assertThat(result.xml()).contains("<version>2.0.0</version>");
        assertThat(result.report().issues())
                .anyMatch(i -> i.message().contains("caret")
                        && i.message().contains("com.example:lib"));
    }

    @Test
    void exported_pom_round_trips_through_importer() {
        Map<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, List.of(
                new Dependency("com.fasterxml.jackson.core:jackson-databind",
                        VersionSelector.parse("=2.18.2")),
                new Dependency("org.springframework.boot:spring-boot-starter-web",
                        VersionSelector.parse("=3.4.0"))));
        byScope.put(Scope.RUNTIME, List.of(
                new Dependency("com.mysql:mysql-connector-j", VersionSelector.parse("=9.0.0"))));
        byScope.put(Scope.PROVIDED, List.of(
                new Dependency("jakarta.servlet:jakarta.servlet-api",
                        VersionSelector.parse("=6.1.0"))));
        byScope.put(Scope.TEST, List.of(
                new Dependency("org.junit.jupiter:junit-jupiter",
                        VersionSelector.parse("=5.11.0"))));
        byScope.put(Scope.PLATFORM, List.of(
                new Dependency("org.springframework.boot:spring-boot-dependencies",
                        VersionSelector.parse("=3.4.0"))));

        BuildJk original = new BuildJk(
                new BuildJk.Project("com.example", "widget", "1.0.0", "21"),
                new BuildJk.Dependencies(byScope));

        String xml = PomExporter.export(original).xml();
        var reimported = PomImporter.importFromBytes(xml.getBytes(StandardCharsets.UTF_8));

        BuildJk re = reimported.buildJk();
        assertThat(re.project().group()).isEqualTo("com.example");
        assertThat(re.project().artifact()).isEqualTo("widget");
        assertThat(re.project().version()).isEqualTo("1.0.0");
        assertThat(re.project().jdk()).isEqualTo("21");
        assertThat(re.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactlyInAnyOrder(
                        "com.fasterxml.jackson.core:jackson-databind",
                        "org.springframework.boot:spring-boot-starter-web");
        assertThat(re.dependencies().of(Scope.RUNTIME))
                .extracting(Dependency::module)
                .containsExactly("com.mysql:mysql-connector-j");
        assertThat(re.dependencies().of(Scope.PROVIDED))
                .extracting(Dependency::module)
                .containsExactly("jakarta.servlet:jakarta.servlet-api");
        assertThat(re.dependencies().of(Scope.TEST))
                .extracting(Dependency::module)
                .containsExactly("org.junit.jupiter:junit-jupiter");
        assertThat(re.dependencies().of(Scope.PLATFORM))
                .extracting(Dependency::module)
                .containsExactly("org.springframework.boot:spring-boot-dependencies");
    }

    @Test
    void xml_escapes_special_chars_in_text() {
        BuildJk model = new BuildJk(
                new BuildJk.Project("com.example", "widget", "1.0.0", "21"),
                BuildJk.Dependencies.empty(),
                List.of(new RepositorySpec("amp",
                        URI.create("https://example.com/path?a=1&b=2"))));
        String xml = PomExporter.export(model).xml();
        assertThat(xml).contains("&amp;");
        assertThat(xml).doesNotContain("?a=1&b=2");
    }
}
