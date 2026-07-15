// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import build.jumpkick.model.Dependency;
import build.jumpkick.model.JkBuild;
import build.jumpkick.model.Scope;
import build.jumpkick.model.VersionSelector;
import org.junit.jupiter.api.Test;

/**
 * Tests the surgical text editor against the v0.7 name-as-key sub-table format. Each test
 * round-trips the edited content through {@link JkBuildParser} to confirm the file is both valid
 * TOML and a well-formed {@code jk.toml}.
 */
class JkBuildEditorTest {

    private static final String BASE = """
            [project]
            group    = "com.example"
            name     = "widget"
            version  = "0.1.0"
            jdk      = 21
            java     = 21
            """;

    @Test
    void add_to_file_without_dependencies_table_creates_sub_scope() {
        String result = JkBuildEditor.addDependency(
                BASE, Scope.MAIN, "jackson-databind", "com.fasterxml.jackson.core", "jackson-databind", "=2.18.2");

        assertThat(result).contains("[dependencies]");
        assertThat(result)
                .contains("jackson-databind = { group = \"com.fasterxml.jackson.core\", version = \"=2.18.2\" }");

        JkBuild parsed = JkBuildParser.parse(result);
        assertThat(parsed.dependencies().of(Scope.MAIN)).singleElement().satisfies(d -> {
            assertThat(d.library()).isEqualTo("jackson-databind");
            assertThat(d.module()).isEqualTo("com.fasterxml.jackson.core:jackson-databind");
            assertThat(d.version()).isInstanceOf(VersionSelector.Exact.class);
        });
    }

    @Test
    void add_omits_artifact_when_it_matches_name() {
        // `acme-thing` is deliberately not in the bundled catalog — that
        // way this test exercises the structured-form artifact-omission
        // branch, not the catalog-shorthand branch tested below.
        String result = JkBuildEditor.addDependency(BASE, Scope.MAIN, "acme-thing", "com.acme", "acme-thing", "1.0.0");

        assertThat(result).contains("acme-thing = { group = \"com.acme\", version = \"1.0.0\" }");
        assertThat(result).doesNotContain(", name =");
    }

    @Test
    void add_emits_shorthand_for_catalog_known_names() {
        // picocli is in the bundled catalog → cargo-style one-liner.
        String result = JkBuildEditor.addDependency(BASE, Scope.MAIN, "picocli", "info.picocli", "picocli", "4.7.7");

        assertThat(result).contains("picocli = \"4.7.7\"");
        assertThat(result).doesNotContain("group = \"info.picocli\"");

        // Round-trips through the parser to the same coord.
        JkBuild parsed = JkBuildParser.parse(result);
        Dependency d = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(d.library()).isEqualTo("picocli");
        assertThat(d.module()).isEqualTo("info.picocli:picocli");
    }

    @Test
    void add_uses_structured_form_when_group_disagrees_with_catalog() {
        // Same name as a catalog entry but a deliberately different group:
        // the user is overriding the catalog, so the structured form is
        // emitted (the shorthand would lie about the resolved coord).
        String result = JkBuildEditor.addDependency(BASE, Scope.MAIN, "picocli", "io.fork", "picocli", "4.7.7");

        assertThat(result).contains("picocli = { group = \"io.fork\", version = \"4.7.7\" }");
    }

    @Test
    void add_emits_artifact_when_it_differs_from_name() {
        String result = JkBuildEditor.addDependency(
                BASE, Scope.MAIN, "spring-web", "org.springframework.boot", "spring-boot-starter-web", "3.4.0");

        assertThat(result)
                .contains("spring-web = { group = \"org.springframework.boot\", "
                        + "name = \"spring-boot-starter-web\", version = \"3.4.0\" }");

        JkBuild parsed = JkBuildParser.parse(result);
        Dependency d = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(d.library()).isEqualTo("spring-web");
        assertThat(d.module()).isEqualTo("org.springframework.boot:spring-boot-starter-web");
    }

    @Test
    void add_to_existing_scope_appends_under_header() {
        String start = BASE + """

                [dependencies]
                picocli = { group = "info.picocli", version = "4.7.7" }
                """;

        String result = JkBuildEditor.addDependency(start, Scope.MAIN, "tomlj", "org.tomlj", "tomlj", "1.1.1");

        assertThat(result).contains("picocli = { group = \"info.picocli\", version = \"4.7.7\" }");
        assertThat(result).contains("tomlj = \"1.1.1\"");
        // Both entries appear under [dependencies]; no second header.
        int firstHeader = result.indexOf("[dependencies]");
        int lastHeader = result.lastIndexOf("[dependencies]");
        assertThat(firstHeader).isEqualTo(lastHeader);
    }

    @Test
    void add_test_scope_alongside_main_creates_separate_sub_table() {
        String start = BASE + """

                [dependencies]
                picocli = { group = "info.picocli", version = "4.7.7" }
                """;

        String result = JkBuildEditor.addDependency(
                start, Scope.TEST, "junit-jupiter", "org.junit.jupiter", "junit-jupiter", "6.1.0");

        assertThat(result).contains("[dependencies]");
        assertThat(result).contains("[test-dependencies]");
        // The new sub-table comes after main, and the entry is under it.
        int testHeader = result.indexOf("[test-dependencies]");
        int junitLine = result.indexOf("junit-jupiter =");
        assertThat(junitLine).isGreaterThan(testHeader);

        JkBuild parsed = JkBuildParser.parse(result);
        assertThat(parsed.dependencies().of(Scope.MAIN)).hasSize(1);
        assertThat(parsed.dependencies().of(Scope.TEST)).hasSize(1);
    }

    @Test
    void add_treats_bare_dependencies_as_main_shorthand() {
        String start = BASE + """

                [dependencies]
                picocli = { group = "info.picocli", version = "4.7.7" }
                """;

        String result = JkBuildEditor.addDependency(start, Scope.MAIN, "tomlj", "org.tomlj", "tomlj", "1.1.1");

        // Should extend the flat table, not create a second [dependencies] header.
        assertThat(result.indexOf("[dependencies]")).isEqualTo(result.lastIndexOf("[dependencies]"));
        assertThat(result).contains("tomlj = \"1.1.1\"");

        JkBuild parsed = JkBuildParser.parse(result);
        assertThat(parsed.dependencies().of(Scope.MAIN)).hasSize(2);
    }

    @Test
    void add_test_dep_when_bare_dependencies_is_flat_shorthand() {
        String start = BASE + """

                [dependencies]
                picocli = { group = "info.picocli", version = "4.7.7" }
                """;

        String result = JkBuildEditor.addDependency(
                start, Scope.TEST, "junit-jupiter", "org.junit.jupiter", "junit-jupiter", "6.1.0");

        assertThat(result).contains("[test-dependencies]");
        JkBuild parsed = JkBuildParser.parse(result);
        assertThat(parsed.dependencies().of(Scope.MAIN)).hasSize(1);
        assertThat(parsed.dependencies().of(Scope.TEST)).hasSize(1);
    }

    @Test
    void add_rejects_duplicate_name_in_same_scope() {
        String start = BASE + """

                [dependencies]
                picocli = { group = "info.picocli", version = "4.7.7" }
                """;

        assertThatThrownBy(() ->
                        JkBuildEditor.addDependency(start, Scope.MAIN, "picocli", "info.picocli", "picocli", "5.0.0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already contains \"picocli\"");
    }

    @Test
    void add_allows_same_name_in_different_scope() {
        String start = BASE + """

                [provided-dependencies]
                lombok = { group = "org.projectlombok", version = "1.18.34" }
                """;

        // The same short name is fine in a different scope (e.g., lombok
        // is both `processor` and `provided`).
        String result =
                JkBuildEditor.addDependency(start, Scope.PROCESSOR, "lombok", "org.projectlombok", "lombok", "1.18.34");

        assertThat(result).contains("[provided-dependencies]");
        assertThat(result).contains("[processor-dependencies]");
    }

    @Test
    void remove_drops_the_named_entry() {
        String start = BASE + """

                [dependencies]
                picocli = { group = "info.picocli", version = "4.7.7" }
                tomlj   = { group = "org.tomlj",    version = "1.1.1" }
                """;

        String result = JkBuildEditor.removeDependency(start, Scope.MAIN, "picocli");

        assertThat(result).doesNotContain("picocli");
        assertThat(result).contains("tomlj");

        JkBuild parsed = JkBuildParser.parse(result);
        assertThat(parsed.dependencies().of(Scope.MAIN)).hasSize(1);
        assertThat(parsed.dependencies().of(Scope.MAIN).getFirst().library()).isEqualTo("tomlj");
    }

    @Test
    void remove_last_entry_leaves_sub_table_in_place() {
        String start = BASE + """

                [dependencies]
                picocli = { group = "info.picocli", version = "4.7.7" }
                """;

        String result = JkBuildEditor.removeDependency(start, Scope.MAIN, "picocli");

        // We don't try to remove the now-empty sub-table; minimal blast radius.
        assertThat(result).contains("[dependencies]");
        assertThat(result).doesNotContain("picocli");

        // An empty sub-table is still valid TOML.
        JkBuild parsed = JkBuildParser.parse(result);
        assertThat(parsed.dependencies().of(Scope.MAIN)).isEmpty();
    }

    @Test
    void remove_drops_workspace_shorthand_entry() {
        String start = BASE + """

                [test-dependencies]
                junit-jupiter.workspace = true
                assertj-core.workspace  = true
                """;

        String result = JkBuildEditor.removeDependency(start, Scope.TEST, "junit-jupiter");

        assertThat(result).doesNotContain("junit-jupiter");
        assertThat(result).contains("assertj-core.workspace");
    }

    @Test
    void remove_when_scope_missing_throws_clear_error() {
        assertThatThrownBy(() -> JkBuildEditor.removeDependency(BASE, Scope.TEST, "anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test-dependencies not found");
    }

    @Test
    void remove_when_name_missing_throws_clear_error() {
        String start = BASE + """

                [dependencies]
                picocli = { group = "info.picocli", version = "4.7.7" }
                """;

        assertThatThrownBy(() -> JkBuildEditor.removeDependency(start, Scope.MAIN, "tomlj"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("\"tomlj\" not found");
    }

    @Test
    void add_preserves_user_comments_and_blank_lines() {
        String start = """
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "0.1.0"
                jdk      = 21
                java     = 21

                # User comment above deps.
                [dependencies]
                # Inline comment about picocli.
                picocli = { group = "info.picocli", version = "4.7.7" }

                # Trailing comment block.
                [profiles.dev]
                javac = ["-g"]
                """;

        String result = JkBuildEditor.addDependency(start, Scope.MAIN, "tomlj", "org.tomlj", "tomlj", "1.1.1");

        assertThat(result).contains("# User comment above deps.");
        assertThat(result).contains("# Inline comment about picocli.");
        assertThat(result).contains("# Trailing comment block.");
        assertThat(result).contains("[profiles.dev]");
        assertThat(result).contains("tomlj = \"1.1.1\"");
    }

    // --- workspace modules -------------------------------------------------

    private static final String WS = """
            [project]
            group    = "build.jumpkick"
            name     = "jk"
            version  = "0.1.0"

            [workspace]
            modules = ["core", "io"]
            """;

    @Test
    void add_module_appends_to_inline_array() {
        String result = JkBuildEditor.addWorkspaceModule(WS, "cli");
        assertThat(result).contains("modules = [\"core\", \"io\", \"cli\"]");
        assertThat(JkBuildParser.parse(result).workspace().modules()).containsExactly("core", "io", "cli");
    }

    @Test
    void add_module_is_idempotent() {
        String result = JkBuildEditor.addWorkspaceModule(WS, "io");
        assertThat(result).isEqualTo(WS);
    }

    @Test
    void add_module_to_empty_inline_array() {
        String start = """
                [project]
                group    = "build.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = []
                """;
        String result = JkBuildEditor.addWorkspaceModule(start, "core");
        assertThat(result).contains("modules = [\"core\"]");
        assertThat(JkBuildParser.parse(result).workspace().modules()).containsExactly("core");
    }

    @Test
    void add_module_to_multiline_array_preserves_shape_and_comments() {
        String start = """
                [project]
                group    = "build.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                # the workspace
                [workspace]
                modules = [
                    "core",
                    "io",
                ]
                """;
        String result = JkBuildEditor.addWorkspaceModule(start, "cli");
        assertThat(result).contains("# the workspace");
        assertThat(result).contains("    \"cli\",");
        assertThat(JkBuildParser.parse(result).workspace().modules()).containsExactly("core", "io", "cli");
    }

    @Test
    void add_module_to_multiline_array_without_trailing_comma() {
        String start = """
                [project]
                group    = "build.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = [
                    "core",
                    "io"
                ]
                """;
        String result = JkBuildEditor.addWorkspaceModule(start, "cli");
        assertThat(JkBuildParser.parse(result).workspace().modules()).containsExactly("core", "io", "cli");
    }

    @Test
    void add_module_when_no_modules_key_creates_one() {
        String start = """
                [project]
                group    = "build.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                """;
        String result = JkBuildEditor.addWorkspaceModule(start, "core");
        assertThat(result).contains("modules = [\"core\"]");
        assertThat(JkBuildParser.parse(result).workspace().modules()).containsExactly("core");
    }

    @Test
    void add_module_without_workspace_table_throws() {
        assertThatThrownBy(() -> JkBuildEditor.addWorkspaceModule(BASE, "core"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no [workspace] table");
    }

    @Test
    void register_module_creates_the_workspace_table_for_a_plain_project() {
        String result = JkBuildEditor.registerWorkspaceModule(BASE, "core");
        assertThat(result).contains("[workspace]");
        assertThat(result).contains("modules = [\"core\"]");
        // The original [project] block is preserved.
        assertThat(result).contains("name     = \"widget\"");
        JkBuild parsed = JkBuildParser.parse(result);
        assertThat(parsed.isWorkspaceRoot()).isTrue();
        assertThat(parsed.workspace().modules()).containsExactly("core");
    }

    @Test
    void register_module_appends_to_an_existing_workspace_table() {
        String once = JkBuildEditor.registerWorkspaceModule(BASE, "core");
        String twice = JkBuildEditor.registerWorkspaceModule(once, "cli");
        assertThat(JkBuildParser.parse(twice).workspace().modules()).containsExactly("core", "cli");
        // Idempotent — re-registering an existing module is a no-op.
        assertThat(JkBuildEditor.registerWorkspaceModule(twice, "core")).isEqualTo(twice);
    }

    @Test
    void name_validation_rejects_bad_characters() {
        assertThatThrownBy(() -> JkBuildEditor.addDependency(BASE, Scope.MAIN, "has spaces", "g", "a", "1.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dependency name must match");
        assertThatThrownBy(() -> JkBuildEditor.addDependency(BASE, Scope.MAIN, "9starts-with-digit", "g", "a", "1.0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JkBuildEditor.addDependency(BASE, Scope.MAIN, "", "g", "a", "1.0"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
