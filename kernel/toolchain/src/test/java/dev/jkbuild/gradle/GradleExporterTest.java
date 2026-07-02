// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.model.JkBuild;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GradleExporterTest {

    private static JkBuild parse(String toml) {
        return JkBuildParser.parse(toml);
    }

    @Test
    void application_project_emits_plugins_toolchain_and_mainClass() {
        JkBuild b = parse("""
                [project]
                group = "com.example"
                name  = "app"
                version = "1.2.3"
                jdk  = 21
                java = 21
                main = "com.example.Main"

                [dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "33.0.0-jre" }
                """);

        String kts = GradleExporter.export(b, Map.of()).buildFiles().get("");

        assertThat(kts).contains("plugins {");
        assertThat(kts).contains("    java");
        assertThat(kts).contains("    application");
        assertThat(kts).contains("group = \"com.example\"");
        assertThat(kts).contains("version = \"1.2.3\"");
        assertThat(kts).contains("implementation(\"com.google.guava:guava:33.0.0-jre\")");
        assertThat(kts).contains("languageVersion = JavaLanguageVersion.of(21)");
        assertThat(kts).contains("mainClass = \"com.example.Main\"");
        assertThat(kts).contains("mavenCentral()");
    }

    @Test
    void locked_version_overrides_declared_selector() {
        JkBuild b = parse("""
                [project]
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 21

                [dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "^33.0.0-jre" }
                """);

        String kts = GradleExporter.export(b, Map.of("com.google.guava:guava", "33.4.0-jre"))
                .buildFiles()
                .get("");

        assertThat(kts).contains("implementation(\"com.google.guava:guava:33.4.0-jre\")");
        assertThat(kts).doesNotContain("33.0.0-jre");
    }

    @Test
    void floating_selector_without_lock_warns() {
        JkBuild b = parse("""
                [project]
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 21

                [dependencies]
                guava = { group = "com.google.guava", name = "guava", version = "^33.0.0-jre" }
                """);

        GradleExporter.Result r = GradleExporter.export(b, Map.of());

        assertThat(r.buildFiles().get("")).contains("guava:33.0.0-jre");
        assertThat(r.report().issues()).anyMatch(i -> i.message().contains("guava"));
    }

    @Test
    void kotlin_shadow_and_native_emit_their_plugins() {
        JkBuild b = parse("""
                [project]
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 21
                main = "com.example.Main"
                kotlin = "2.3.21"
                shadow = true
                native = true
                """);

        String kts = GradleExporter.export(b, Map.of()).buildFiles().get("");

        assertThat(kts).contains("kotlin(\"jvm\") version \"2.3.21\"");
        assertThat(kts).contains("id(\"com.gradleup.shadow\")");
        assertThat(kts).contains("id(\"org.graalvm.buildtools.native\")");
    }

    @Test
    void settings_includes_foojay_and_workspace_modules() {
        JkBuild root = parse("""
                [project]
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                java = 21

                [workspace]
                modules = ["mod-a", "mod-b"]
                """);
        JkBuild a = parse("""
                [project]
                group = "com.example"
                name  = "mod-a"
                version = "1.0.0"
                java = 21
                """);

        GradleExporter.Result r = GradleExporter.export(root, Map.of("mod-a", a), Map.of());

        assertThat(r.settings()).contains("rootProject.name = \"root\"");
        assertThat(r.settings()).contains("foojay-resolver-convention");
        assertThat(r.settings()).contains("include(\":mod-a\")");
        assertThat(r.buildFiles()).containsKey("");
        assertThat(r.buildFiles()).containsKey("mod-a");
    }

    // Note: inline `path = "..."` dependencies were removed from the manifest schema (path targets
    // are now declared as workspace modules), so there is no path→includeBuild export case to test.

    @Test
    void git_dependency_is_dropped_with_warning() {
        JkBuild b = parse("""
                [project]
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                java = 21

                [dependencies]
                acme = { git = "https://example.com/acme.git", tag = "v1.0" }
                """);

        GradleExporter.Result r = GradleExporter.export(b, Map.of());

        assertThat(r.report().issues()).anyMatch(i -> i.message().contains("git-sourced"));
    }
}
