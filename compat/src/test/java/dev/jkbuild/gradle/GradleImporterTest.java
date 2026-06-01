// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.gradle;

import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GradleImporterTest {

    @Test
    void kotlin_dsl_basic_project_imports_cleanly() {
        var result = importScript("""
                plugins {
                    id("java")
                    id("application")
                }

                group = "com.example"
                version = "1.2.3"

                java {
                    sourceCompatibility = JavaVersion.VERSION_21
                }

                dependencies {
                    implementation("org.springframework.boot:spring-boot-starter-web:3.4.0")
                    runtimeOnly("com.mysql:mysql-connector-j:9.0.0")
                    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
                }
                """, "widget");

        var p = result.jkBuild().project();
        assertThat(p.group()).isEqualTo("com.example");
        assertThat(p.artifact()).isEqualTo("widget");
        assertThat(p.version()).isEqualTo("1.2.3");
        assertThat(p.jdk()).isEqualTo(21);

        var deps = result.jkBuild().dependencies();
        assertThat(deps.of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactly("org.springframework.boot:spring-boot-starter-web");
        assertThat(deps.of(Scope.RUNTIME))
                .extracting(Dependency::module)
                .containsExactly("com.mysql:mysql-connector-j");
        assertThat(deps.of(Scope.TEST))
                .extracting(Dependency::module)
                .containsExactly("org.junit.jupiter:junit-jupiter");

        // All versions are exact pins.
        for (Scope s : Scope.values()) {
            for (Dependency d : deps.of(s)) {
                assertThat(d.version()).isInstanceOf(VersionSelector.Exact.class);
            }
        }
    }

    @Test
    void groovy_dsl_single_quoted_strings_recognised() {
        var result = importScript("""
                plugins {
                    id 'java'
                }

                group 'com.example'
                version '1.0'

                dependencies {
                    implementation 'com.google.guava:guava:33.0.0-jre'
                    testImplementation 'junit:junit:4.13.2'
                }
                """, "widget");

        assertThat(result.jkBuild().project().group()).isEqualTo("com.example");
        assertThat(result.jkBuild().dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactly("com.google.guava:guava");
        assertThat(result.jkBuild().dependencies().of(Scope.TEST))
                .extracting(Dependency::module)
                .containsExactly("junit:junit");
    }

    @Test
    void platform_dependency_becomes_platform_scope() {
        var result = importScript("""
                plugins { id("java") }
                dependencies {
                    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.4.0"))
                    implementation("org.springframework.boot:spring-boot-starter-web")
                }
                """, "widget");

        assertThat(result.jkBuild().dependencies().of(Scope.PLATFORM))
                .extracting(Dependency::module)
                .containsExactly("org.springframework.boot:spring-boot-dependencies");
    }

    @Test
    void project_dependency_warns_about_workspace_conversion() {
        var result = importScript("""
                dependencies {
                    implementation(project(":core"))
                }
                """, "app");

        // Project ref is not emitted as a normal dep.
        assertThat(result.jkBuild().dependencies().of(Scope.MAIN)).isEmpty();
        assertThat(result.report().issues())
                .anyMatch(i -> i.message().contains(":core") && i.message().contains("workspace"));
    }

    @Test
    void unknown_configuration_emits_tier3_error() {
        var result = importScript("""
                dependencies {
                    bespokeThing("com.example:secret:1.0")
                }
                """, "app");

        assertThat(result.jkBuild().dependencies().of(Scope.MAIN)).isEmpty();
        assertThat(result.report().hasErrors()).isTrue();
        assertThat(result.report().issues())
                .anyMatch(i -> i.message().contains("bespokeThing"));
    }

    @Test
    void java_toolchain_language_version_form_recognised() {
        var result = importScript("""
                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(17)
                    }
                }
                """, "app");
        assertThat(result.jkBuild().project().jdk()).isEqualTo(17);
    }

    @Test
    void kotlin_jvm_toolchain_form_recognised() {
        var result = importScript("""
                kotlin {
                    jvmToolchain(21)
                }
                """, "app");
        assertThat(result.jkBuild().project().jdk()).isEqualTo(21);
    }

    @Test
    void custom_repository_is_imported_skipping_central() {
        var result = importScript("""
                repositories {
                    mavenCentral()
                    maven { url = uri("https://repo.spring.io/milestone") }
                }
                """, "app");

        assertThat(result.jkBuild().repositories())
                .extracting(r -> r.url().toString())
                .containsExactly("https://repo.spring.io/milestone");
    }

    @Test
    void subprojects_block_is_tier3() {
        var result = importScript("""
                subprojects {
                    apply plugin: 'java'
                }
                """, "root");
        assertThat(result.report().hasErrors()).isTrue();
        assertThat(result.report().issues())
                .anyMatch(i -> i.message().contains("subprojects"));
    }

    @Test
    void variable_version_warning_emitted() {
        var result = importScript("""
                dependencies {
                    implementation("com.example:lib:${springVersion}")
                }
                """, "app");
        assertThat(result.report().issues())
                .anyMatch(i -> i.message().contains("Gradle variable"));
    }

    @Test
    void unrecognised_plugin_id_warns() {
        var result = importScript("""
                plugins {
                    id("org.springframework.boot") version "3.4.0"
                }
                """, "app");
        assertThat(result.report().issues())
                .anyMatch(i -> i.message().contains("org.springframework.boot")
                        && i.message().contains("not yet mapped"));
    }

    @Test
    void block_comments_and_line_comments_are_stripped() {
        var result = importScript("""
                /* multi
                   line */
                group = "com.example" // inline comment
                version = "1.0"
                // dependencies block fully commented out below
                // dependencies { implementation("ignored:ignored:1.0") }
                """, "app");
        assertThat(result.jkBuild().project().group()).isEqualTo("com.example");
        assertThat(result.jkBuild().dependencies().of(Scope.MAIN)).isEmpty();
    }

    @Test
    void catalog_library_accessor_resolves_to_coordinate() {
        var catalog = GradleVersionCatalog.fromToml(org.tomlj.Toml.parse("""
                [versions]
                junit = "5.10.0"
                [libraries]
                junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher", version.ref = "junit" }
                """));
        var result = importScript("""
                dependencies {
                    testImplementation(libs.junit.platform.launcher)
                }
                """, "app", catalog);

        var deps = result.jkBuild().dependencies();
        assertThat(deps.of(Scope.TEST))
                .extracting(Dependency::module)
                .containsExactly("org.junit.platform:junit-platform-launcher");
        assertThat(deps.of(Scope.TEST))
                .first()
                .extracting(Dependency::version)
                .isEqualTo(VersionSelector.parse("5.10.0"));
        assertThat(result.report().hasErrors()).isFalse();
    }

    @Test
    void unquoted_groovy_catalog_accessor_resolves() {
        var catalog = GradleVersionCatalog.fromToml(org.tomlj.Toml.parse("""
                [libraries]
                guava = "com.google.guava:guava:32.1.3-jre"
                """));
        var result = importScript("""
                dependencies {
                    implementation libs.guava
                }
                """, "app", catalog);
        assertThat(result.jkBuild().dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactly("com.google.guava:guava");
    }

    @Test
    void catalog_bundle_accessor_expands_to_all_members() {
        var catalog = GradleVersionCatalog.fromToml(org.tomlj.Toml.parse("""
                [versions]
                junit = "5.10.0"
                [libraries]
                junit-jupiter           = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
                junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher", version.ref = "junit" }
                [bundles]
                testing = ["junit-jupiter", "junit-platform-launcher"]
                """));
        var result = importScript("""
                dependencies {
                    testImplementation(libs.bundles.testing)
                }
                """, "app", catalog);
        assertThat(result.jkBuild().dependencies().of(Scope.TEST))
                .extracting(Dependency::module)
                .containsExactly(
                        "org.junit.jupiter:junit-jupiter",
                        "org.junit.platform:junit-platform-launcher");
    }

    @Test
    void catalog_accessor_without_catalog_reports_error() {
        var result = importScript("""
                dependencies {
                    testImplementation(libs.junit.platform.launcher)
                }
                """, "app", null);
        assertThat(result.jkBuild().dependencies().of(Scope.TEST)).isEmpty();
        assertThat(result.report().hasErrors()).isTrue();
    }

    @Test
    void unknown_catalog_accessor_reports_error() {
        var catalog = GradleVersionCatalog.fromToml(org.tomlj.Toml.parse("""
                [libraries]
                guava = "com.google.guava:guava:32.1.3-jre"
                """));
        var result = importScript("""
                dependencies {
                    implementation(libs.does.not.exist)
                }
                """, "app", catalog);
        assertThat(result.jkBuild().dependencies().of(Scope.MAIN)).isEmpty();
        assertThat(result.report().hasErrors()).isTrue();
    }

    @Test
    void project_description_is_imported() {
        var result = importScript("""
                group = "com.example"
                version = "1.0.0"
                description = "A delightful widget library"
                """, "widget");
        assertThat(result.jkBuild().project().description())
                .isEqualTo("A delightful widget library");
    }

    @Test
    void groovy_description_without_equals_is_imported() {
        var result = importScript("""
                group = 'com.example'
                description 'Groovy-style description'
                """, "widget");
        assertThat(result.jkBuild().project().description())
                .isEqualTo("Groovy-style description");
    }

    @Test
    void missing_description_stays_null() {
        var result = importScript("""
                group = "com.example"
                version = "1.0.0"
                """, "widget");
        assertThat(result.jkBuild().project().description()).isNull();
    }

    @Test
    void kotlin_plugin_version_and_application_main_class_imported() {
        var result = importScript("""
                plugins {
                    kotlin("jvm") version "2.3.21"
                    application
                }

                group = "com.gyggly"
                version = "1.0.0"

                application {
                    mainClass.set("com.gyggly.ApplicationKt")
                }
                """, "app");

        var p = result.jkBuild().project();
        assertThat(p.isKotlin()).isTrue();
        assertThat(p.kotlin()).isEqualTo(VersionSelector.parseFloating("2.3.21"));
        assertThat(p.java()).isZero();
        assertThat(p.main()).isEqualTo("com.gyggly.ApplicationKt");
    }

    @Test
    void kotlin_id_plugin_version_imported() {
        var result = importScript("""
                plugins {
                    id("org.jetbrains.kotlin.jvm") version "2.1.0"
                }
                """, "app");
        assertThat(result.jkBuild().project().kotlin())
                .isEqualTo(VersionSelector.parseFloating("2.1.0"));
    }

    @Test
    void kotlin_plugin_without_version_defaults_floating() {
        var result = importScript("""
                plugins {
                    kotlin("jvm")
                }
                """, "app");
        assertThat(result.jkBuild().project().isKotlin()).isTrue();
        assertThat(result.jkBuild().project().kotlin()).isNotNull();
    }

    @Test
    void groovy_main_class_name_imported() {
        var result = importScript("""
                plugins {
                    id 'application'
                }
                mainClassName = 'com.example.Main'
                """, "app");
        assertThat(result.jkBuild().project().main()).isEqualTo("com.example.Main");
    }

    @Test
    void no_kotlin_plugin_stays_java_project() {
        var result = importScript("""
                plugins {
                    id("java")
                }
                """, "app");
        assertThat(result.jkBuild().project().isKotlin()).isFalse();
        assertThat(result.jkBuild().project().kotlin()).isNull();
    }

    @Test
    void kotlin_dsl_jar_manifest_attributes_imported() {
        var result = importScript("""
                group = "dev.jkbuild"
                version = "1.0.0"

                tasks.jar {
                    manifest {
                        attributes(
                                "Main-Class" to "dev.jkbuild.test.runner.JkRunner",
                                "Implementation-Title" to "jk-test-runner",
                                "Implementation-Version" to project.version)
                    }
                }
                """, "jk-test-runner");

        var jk = result.jkBuild();
        // Main-Class routes to project.main, not the manifest table.
        assertThat(jk.project().main()).isEqualTo("dev.jkbuild.test.runner.JkRunner");
        assertThat(jk.manifest()).containsExactly(
                org.assertj.core.api.Assertions.entry("Implementation-Title", "jk-test-runner"),
                org.assertj.core.api.Assertions.entry("Implementation-Version", "1.0.0"));
        assertThat(jk.manifest()).doesNotContainKey("Main-Class");
    }

    @Test
    void groovy_dsl_jar_manifest_attributes_imported() {
        var result = importScript("""
                group = 'com.example'
                version = '2.0.0'

                jar {
                    manifest {
                        attributes(
                                'Implementation-Title': 'widget',
                                'Built-By': 'jk')
                    }
                }
                """, "widget");
        assertThat(result.jkBuild().manifest()).containsExactly(
                org.assertj.core.api.Assertions.entry("Implementation-Title", "widget"),
                org.assertj.core.api.Assertions.entry("Built-By", "jk"));
    }

    @Test
    void unresolvable_manifest_expression_is_dropped_with_warning() {
        var result = importScript("""
                version = "1.0.0"
                tasks.jar {
                    manifest {
                        attributes("Build-Timestamp" to someCustomFunction())
                    }
                }
                """, "app");
        assertThat(result.jkBuild().manifest()).doesNotContainKey("Build-Timestamp");
        assertThat(result.report().issues()).isNotEmpty();
    }

    private static GradleImporter.Result importScript(String text, String artifact) {
        return GradleImporter.importFromString(text, artifact);
    }

    private static GradleImporter.Result importScript(String text, String artifact, GradleVersionCatalog catalog) {
        return GradleImporter.importFromString(text, artifact, catalog);
    }
}
