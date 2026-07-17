// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.compat;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.gradle.GradleImporter;
import org.junit.jupiter.api.Test;

/**
 * The P4 import acceptance: a Boot Gradle build maps to [spring-boot] purely via the plugin's
 * [[import.gradle-plugin]] manifest rules, and the renderer emits the table from its schema —
 * no Boot-specific code anywhere on the path.
 */
class BootImportRoundTripTest {

    @Test
    void boot_plugin_version_maps_to_the_spring_boot_table() {
        var result = GradleImporter.importFromString("""
                plugins {
                    id("java")
                    id("org.springframework.boot") version "4.0.2"
                    id("io.spring.dependency-management") version "1.1.7"
                }

                group = "com.acme"
                version = "1.0.0"

                dependencies {
                    implementation("org.springframework.boot:spring-boot-starter-web")
                }
                """, "demo");

        var config = result.jkBuild().pluginConfig("spring-boot").orElseThrow();
        assertThat(config.string("version")).isEqualTo("4.0.2");

        String rendered = JkBuildRenderer.render(result.jkBuild());
        assertThat(rendered).contains("[spring-boot]");
        assertThat(rendered).contains("version = \"4.0.2\"");
        // schema defaults are omitted — the minimal round trip the old renderer produced
        assertThat(rendered).doesNotContain("include-tools");
        assertThat(rendered).doesNotContain("build-info");
        // dependency-management is recognized (no warning) and produces no table of its own
        assertThat(result.report().issues()).noneMatch(i -> i.message().contains("io.spring.dependency-management"));
    }

    @Test
    void boot_plugin_without_version_warns_with_the_manifest_text() {
        var result = GradleImporter.importFromString("""
                plugins {
                    id("java")
                    id("org.springframework.boot")
                }
                """, "demo");
        assertThat(result.jkBuild().pluginConfig("spring-boot")).isEmpty();
        assertThat(result.report().issues()).anyMatch(i -> i.message().contains("applied without an inline version"));
    }
}
