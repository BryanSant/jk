// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tomlj.Toml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GradleVersionCatalogTest {

    private static final String CATALOG = """
            [versions]
            junit = "5.10.0"
            guava = "32.1.3-jre"

            [libraries]
            junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher", version.ref = "junit" }
            junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version.ref = "junit" }
            guava                   = "com.google.guava:guava:31.0-jre"
            commons-lang3           = { module = "org.apache.commons:commons-lang3", version = "3.14.0" }
            no-version              = { module = "org.example:managed" }

            [bundles]
            testing = ["junit-jupiter", "junit-platform-launcher"]
            """;

    private static GradleVersionCatalog parse(String toml) {
        return GradleVersionCatalog.fromToml(Toml.parse(toml));
    }

    @Test
    void resolves_module_with_version_ref() {
        assertThat(parse(CATALOG).resolveLibrary("junit.platform.launcher"))
                .contains("org.junit.platform:junit-platform-launcher:5.10.0");
    }

    @Test
    void resolves_group_name_with_version_ref() {
        assertThat(parse(CATALOG).resolveLibrary("junit.jupiter"))
                .contains("org.junit.jupiter:junit-jupiter:5.10.0");
    }

    @Test
    void resolves_string_form_library() {
        assertThat(parse(CATALOG).resolveLibrary("guava"))
                .contains("com.google.guava:guava:31.0-jre");
    }

    @Test
    void resolves_literal_version() {
        assertThat(parse(CATALOG).resolveLibrary("commons.lang3"))
                .contains("org.apache.commons:commons-lang3:3.14.0");
    }

    @Test
    void version_less_entry_is_not_resolvable() {
        // BOM-managed entries have no version of their own; jk can't pin them.
        assertThat(parse(CATALOG).resolveLibrary("no.version")).isEmpty();
    }

    @Test
    void resolves_bundle_to_member_coordinates() {
        assertThat(parse(CATALOG).resolveBundle("testing"))
                .get()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .containsExactly(
                        "org.junit.jupiter:junit-jupiter:5.10.0",
                        "org.junit.platform:junit-platform-launcher:5.10.0");
    }

    @Test
    void locate_finds_catalog_in_project_dir(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("gradle"));
        Path catalog = dir.resolve("gradle/libs.versions.toml");
        Files.writeString(catalog, CATALOG);
        assertThat(GradleVersionCatalog.locate(dir)).contains(catalog);
    }

    @Test
    void locate_walks_up_to_parent_dir(@TempDir Path root) throws IOException {
        // root/gradle/libs.versions.toml, importing root/app/build.gradle.kts.
        Files.createDirectories(root.resolve("gradle"));
        Path catalog = root.resolve("gradle/libs.versions.toml");
        Files.writeString(catalog, CATALOG);
        Path app = root.resolve("app");
        Files.createDirectories(app);
        assertThat(GradleVersionCatalog.locate(app)).contains(catalog);
    }

    @Test
    void locate_stops_above_the_parent_dir(@TempDir Path root) throws IOException {
        // Catalog at root, but the project is root/group/app — two levels down,
        // so the search (project dir + its parent) must NOT find it.
        Files.createDirectories(root.resolve("gradle"));
        Files.writeString(root.resolve("gradle/libs.versions.toml"), CATALOG);
        Path app = root.resolve("group/app");
        Files.createDirectories(app);
        assertThat(GradleVersionCatalog.locate(app)).isEmpty();
    }
}
