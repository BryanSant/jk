// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.gradle;

import dev.buildjk.model.Dependency;
import dev.buildjk.model.Scope;
import dev.buildjk.model.VersionSelector;
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

        var p = result.buildJk().project();
        assertThat(p.group()).isEqualTo("com.example");
        assertThat(p.artifact()).isEqualTo("widget");
        assertThat(p.version()).isEqualTo("1.2.3");
        assertThat(p.jdk()).isEqualTo("21");

        var deps = result.buildJk().dependencies();
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

        assertThat(result.buildJk().project().group()).isEqualTo("com.example");
        assertThat(result.buildJk().dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactly("com.google.guava:guava");
        assertThat(result.buildJk().dependencies().of(Scope.TEST))
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

        assertThat(result.buildJk().dependencies().of(Scope.PLATFORM))
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
        assertThat(result.buildJk().dependencies().of(Scope.MAIN)).isEmpty();
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

        assertThat(result.buildJk().dependencies().of(Scope.MAIN)).isEmpty();
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
        assertThat(result.buildJk().project().jdk()).isEqualTo("17");
    }

    @Test
    void kotlin_jvm_toolchain_form_recognised() {
        var result = importScript("""
                kotlin {
                    jvmToolchain(21)
                }
                """, "app");
        assertThat(result.buildJk().project().jdk()).isEqualTo("21");
    }

    @Test
    void custom_repository_is_imported_skipping_central() {
        var result = importScript("""
                repositories {
                    mavenCentral()
                    maven { url = uri("https://repo.spring.io/milestone") }
                }
                """, "app");

        assertThat(result.buildJk().repositories())
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
        assertThat(result.buildJk().project().group()).isEqualTo("com.example");
        assertThat(result.buildJk().dependencies().of(Scope.MAIN)).isEmpty();
    }

    private static GradleImporter.Result importScript(String text, String artifact) {
        return GradleImporter.importFromString(text, artifact);
    }
}
