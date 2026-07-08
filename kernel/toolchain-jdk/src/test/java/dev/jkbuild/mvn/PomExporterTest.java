// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.model.JkBuild;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PomExporterTest {

    private static JkBuild parse(String toml) {
        return JkBuildParser.parse(toml);
    }

    @Test
    void coords_release_and_dependency() {
        JkBuild b = parse("""
                [project]
                group = "com.example"
                name  = "app"
                version = "1.2.3"
                java = 21

                [dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "33.0.0-jre" }
                """);

        String xml = PomExporter.export(b).xml();

        assertThat(xml).contains("<groupId>com.example</groupId>");
        assertThat(xml).contains("<artifactId>app</artifactId>");
        assertThat(xml).contains("<version>1.2.3</version>");
        assertThat(xml).contains("<maven.compiler.release>21</maven.compiler.release>");
        assertThat(xml).contains("<artifactId>guava</artifactId>");
    }

    @Test
    void locked_version_wins_over_selector() {
        JkBuild b = parse("""
                [project]
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 21

                [dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "^33.0.0-jre" }
                """);

        String xml = PomExporter.export(b, Map.of("com.google.guava:guava", "33.4.0-jre"))
                .xml();

        assertThat(xml).contains("<version>33.4.0-jre</version>");
        assertThat(xml).doesNotContain("33.0.0-jre");
    }

    @Test
    void jdk_emits_toolchains_plugin_and_main_emits_jar_plugin() {
        JkBuild b = parse("""
                [project]
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                jdk  = 21
                java = 21

                [application]
                main = "com.example.Main"
                """);

        String xml = PomExporter.export(b).xml();

        assertThat(xml).contains("<artifactId>toolchains-maven-plugin</artifactId>");
        assertThat(xml).contains("<artifactId>maven-jar-plugin</artifactId>");
        assertThat(xml).contains("<mainClass>com.example.Main</mainClass>");
    }

    @Test
    void kotlin_shadow_and_native_emit_plugins() {
        JkBuild b = parse("""
                [project]
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 21
                kotlin = "2.3.21"

                [application]
                main       = "com.example.Main"
                shadow-jar = true

                [native]
                always = true
                """);

        String xml = PomExporter.export(b).xml();

        assertThat(xml).contains("<artifactId>kotlin-maven-plugin</artifactId>");
        assertThat(xml).contains("<artifactId>maven-shade-plugin</artifactId>");
        assertThat(xml).contains("<artifactId>native-maven-plugin</artifactId>");
    }

    @Test
    void processor_scope_becomes_annotation_processor_path() {
        JkBuild b = parse("""
                [project]
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 21

                [processor-dependencies]
                mapstruct-ap = { group = "org.mapstruct", name = "mapstruct-processor", version = "1.6.3" }
                """);

        String xml = PomExporter.export(b).xml();

        assertThat(xml).contains("<annotationProcessorPaths>");
        assertThat(xml).contains("<artifactId>mapstruct-processor</artifactId>");
    }

    @Test
    void workspace_root_is_pom_packaging_with_modules() {
        JkBuild root = parse("""
                [project]
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                java = 21

                [workspace]
                modules = ["mod-a", "mod-b"]
                """);

        String xml = PomExporter.export(root).xml();

        assertThat(xml).contains("<packaging>pom</packaging>");
        assertThat(xml).contains("<module>mod-a</module>");
        assertThat(xml).contains("<module>mod-b</module>");
    }

    @Test
    void git_dependency_warns() {
        JkBuild b = parse("""
                [project]
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 21

                [dependencies]
                acme = { git = "https://example.com/acme.git", tag = "v1.0" }
                """);

        PomExporter.Result r = PomExporter.export(b);

        assertThat(r.report().issues()).anyMatch(i -> i.message().toLowerCase().contains("git"));
    }
}
