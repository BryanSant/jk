// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JkBuildParserTest {

    private static final String PROJECT = """
            [project]
            group    = "com.example"
            artifact = "widget"
            version  = "1.0.0"
            jdk      = 21
            java     = 21
            """;

    @Test
    void parses_minimal_project_block() {
        JkBuild parsed = JkBuildParser.parse(PROJECT);
        assertThat(parsed.project().group()).isEqualTo("com.example");
        assertThat(parsed.project().artifact()).isEqualTo("widget");
        assertThat(parsed.project().version()).isEqualTo("1.0.0");
        assertThat(parsed.project().jdk()).isEqualTo(21);
        assertThat(parsed.project().java()).isEqualTo(21);
        assertThat(parsed.project().isKotlin()).isFalse();
        assertThat(parsed.project().main()).isNull();
        assertThat(parsed.project().shadow()).isFalse();
        assertThat(parsed.project().nativeImage()).isFalse();
        assertThat(parsed.project().description()).isNull();
    }

    @Test
    void parses_optional_description() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                description = "A widget for widgeting."
                """);
        assertThat(parsed.project().description()).isEqualTo("A widget for widgeting.");
    }

    @Test
    void blank_description_normalises_to_null() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                description = "   "
                """);
        assertThat(parsed.project().description()).isNull();
    }

    @Test
    void missing_project_block_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(""))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[project]");
    }

    @Test
    void rejects_project_java_below_17() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                [project]
                group    = "com.example"
                artifact = "widget"
                version  = "1.0.0"
                java     = 11
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("project.java = 11")
                .hasMessageContaining("JDK 17 and above");
    }

    @Test
    void rejects_project_jdk_below_17() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                [project]
                group    = "com.example"
                artifact = "widget"
                version  = "1.0.0"
                jdk      = 8
                java     = 17
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("project.jdk = 8");
    }

    @Test
    void missing_required_key_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                [project]
                group    = "com.example"
                artifact = "widget"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("project.version");
    }

    @Test
    void parses_name_as_key_dep_with_full_table() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                slf4j-api = { group = "org.slf4j", artifact = "slf4j-api", version = "2.0.16" }
                picocli   = { group = "info.picocli", artifact = "picocli", version = "^4.7.7" }

                [dependencies.test]
                junit-jupiter = { group = "org.junit.jupiter", artifact = "junit-jupiter", version = ">=5.10, <6" }
                """);

        var mainDeps = parsed.dependencies().of(Scope.MAIN);
        assertThat(mainDeps).hasSize(2);

        // Bare version on the new format → Caret (Cargo-style default).
        var slf4j = mainDeps.get(0);
        assertThat(slf4j.name()).isEqualTo("slf4j-api");
        assertThat(slf4j.module()).isEqualTo("org.slf4j:slf4j-api");
        assertThat(slf4j.version()).isInstanceOf(VersionSelector.Caret.class);
        assertThat(slf4j.pinned()).isFalse();

        var picocli = mainDeps.get(1);
        assertThat(picocli.name()).isEqualTo("picocli");
        assertThat(picocli.module()).isEqualTo("info.picocli:picocli");
        assertThat(picocli.version()).isInstanceOf(VersionSelector.Caret.class);
        assertThat(picocli.pinned()).isFalse();

        var testDeps = parsed.dependencies().of(Scope.TEST);
        assertThat(testDeps).hasSize(1);
        assertThat(testDeps.getFirst().pinned()).isFalse();
        assertThat(testDeps.getFirst().version()).isInstanceOf(VersionSelector.Range.class);
    }

    @Test
    void artifact_defaults_to_key_name() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                picocli = { group = "info.picocli", version = "4.7.7" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.name()).isEqualTo("picocli");
        assertThat(dep.module()).isEqualTo("info.picocli:picocli");
    }

    @Test
    void equals_selector_pins_dep() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                lib = { group = "com.example", version = "=1.2.3" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.version()).isInstanceOf(VersionSelector.Exact.class);
        assertThat(((VersionSelector.Exact) dep.version()).version()).isEqualTo("1.2.3");
        assertThat(dep.pinned()).isTrue();
    }

    @Test
    void bare_version_is_caret_floating() {
        // Per the v1 locked default: bare "1.2.3" reads as ^1.2.3.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                lib = { group = "com.example", version = "1.2.3" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.version()).isInstanceOf(VersionSelector.Caret.class);
        assertThat(((VersionSelector.Caret) dep.version()).version()).isEqualTo("1.2.3");
        assertThat(dep.pinned()).isFalse();
    }

    @Test
    void latest_selector_floats() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                lib = { group = "com.example", version = "latest" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.version()).isInstanceOf(VersionSelector.Latest.class);
        assertThat(dep.pinned()).isFalse();
    }

    @Test
    void default_scope_shorthand_treats_dependencies_as_main() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                slf4j-api = { group = "org.slf4j", version = "2.0.16" }
                picocli   = { group = "info.picocli", version = "4.7.7" }
                """);

        assertThat(parsed.dependencies().of(Scope.MAIN)).hasSize(2);
        assertThat(parsed.dependencies().of(Scope.TEST)).isEmpty();
    }

    @Test
    void mixed_flat_and_sub_scope_is_rejected() {
        // [dependencies] cannot mix flat deps with sub-scope tables: the
        // shape is ambiguous and the parser rejects it.
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                stray = { group = "com.example", version = "1.0" }

                [dependencies.test]
                junit = { group = "org.junit.jupiter", artifact = "junit-jupiter", version = "5.10.0" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("mixed flat and sub-scope");
    }

    @Test
    void path_source_is_pinned() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                shared-utils = { group = "com.acme", artifact = "shared-utils", path = "../shared-utils" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isPath()).isTrue();
        assertThat(dep.pathSource()).isEqualTo("../shared-utils");
        assertThat(dep.module()).isEqualTo("com.acme:shared-utils");
        assertThat(dep.pinned()).isTrue();
    }

    @Test
    void git_source_inline_on_dep_table() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                codec = { group = "com.acme", git = "https://github.com/acme/codec", tag = "v0.9.1" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(dep.gitSource().originalUrl()).isEqualTo("https://github.com/acme/codec");
        assertThat(dep.gitSource().ref()).isInstanceOf(GitRefSpec.Tag.class);
        assertThat(dep.module()).isEqualTo("com.acme:codec");
        assertThat(dep.pinned()).isTrue();
    }

    @Test
    void git_source_must_set_exactly_one_ref() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                codec = { group = "com.acme", git = "https://github.com/acme/codec", tag = "v1", branch = "main" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("exactly one of");
    }

    @Test
    void multiple_sources_on_dep_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                bad = { group = "com.example", version = "1.0", path = "../bad" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("more than one");
    }

    @Test
    void no_source_on_dep_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                bad = { group = "com.example" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must set exactly one of");
    }

    @Test
    void missing_group_on_dep_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                bad = { version = "1.0" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("group");
    }

    @Test
    void dep_value_must_be_inline_table() {
        // The v0.6 string-array form is gone; raw strings are rejected.
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                bad = "org.foo:bar:1.0"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("inline table");
    }

    @Test
    void parses_workspace_dependencies_block() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [workspace]
                members = ["a", "b"]

                [workspace.dependencies]
                junit-jupiter = { group = "org.junit.jupiter", artifact = "junit-jupiter", version = "6.1.0" }
                assertj-core  = { group = "org.assertj",       artifact = "assertj-core",  version = "3.27.7" }
                """);
        assertThat(parsed.workspace().dependencies()).hasSize(2);
        var jj = parsed.workspace().dependencies().get("junit-jupiter");
        assertThat(jj.group()).isEqualTo("org.junit.jupiter");
        assertThat(jj.artifact()).isEqualTo("junit-jupiter");
        assertThat(jj.module()).isEqualTo("org.junit.jupiter:junit-jupiter");
        assertThat(jj.version()).isInstanceOf(VersionSelector.Caret.class);
    }

    @Test
    void workspace_dependencies_artifact_defaults_to_key() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [workspace]
                members = ["a"]

                [workspace.dependencies]
                picocli = { group = "info.picocli", version = "4.7.7" }
                """);
        var pico = parsed.workspace().dependencies().get("picocli");
        assertThat(pico.artifact()).isEqualTo("picocli");
    }

    @Test
    void workspace_true_resolves_against_workspace_dependencies() {
        // A workspace = true dep with a matching [workspace.dependencies]
        // entry materializes that entry's coord directly during parse.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [workspace]
                members = []

                [workspace.dependencies]
                junit-jupiter = { group = "org.junit.jupiter", artifact = "junit-jupiter", version = "6.1.0" }

                [dependencies.test]
                junit-jupiter.workspace = true
                """);
        var dep = parsed.dependencies().of(Scope.TEST).getFirst();
        assertThat(dep.name()).isEqualTo("junit-jupiter");
        assertThat(dep.module()).isEqualTo("org.junit.jupiter:junit-jupiter");
        assertThat(dep.version()).isInstanceOf(VersionSelector.Caret.class);
    }

    @Test
    void workspace_true_without_match_emits_placeholder_for_merge() {
        // Without a [workspace.dependencies] match the parser emits a
        // placeholder that WorkspaceMerge resolves against the sibling
        // list. The single-file parser cannot fail here because it does
        // not own the sibling roster.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                jk-core.workspace = true
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.name()).isEqualTo("jk-core");
        assertThat(dep.module()).startsWith("workspace:");
    }

    @Test
    void workspace_true_cannot_combine_with_version() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                bad = { workspace = true, version = "1.0" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("more than one");
    }

    @Test
    void workspace_true_cannot_combine_with_group() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                bad = { workspace = true, group = "com.example" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must not set `group`");
    }

    @Test
    void workspace_false_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                bad = { workspace = false }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must be `true`");
    }

    @Test
    void parses_repositories_string_and_table_form() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                internal = { url = "https://nexus.example/repository/maven-releases/" }
                """);
        assertThat(parsed.repositories())
                .extracting(r -> r.name())
                .containsExactlyInAnyOrder("central", "internal");
    }

    @Test
    void parses_workspace_block() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [workspace]
                members = ["core", "io"]
                """);
        assertThat(parsed.isWorkspaceRoot()).isTrue();
        assertThat(parsed.workspace().members()).containsExactly("core", "io");
        assertThat(parsed.workspace().dependencies()).isEmpty();
    }

    @Test
    void parses_profiles_block() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [profiles.dev]
                javac = ["-g", "-parameters"]
                jvm-args = ["-Xshare:auto"]

                [profiles.ci]
                inherits = "dev"
                javac = ["-Werror"]
                """);
        assertThat(parsed.profiles().byName().get("dev").javacArgs()).contains("-g");
        assertThat(parsed.profiles().byName().get("ci").inherits()).isEqualTo("dev");
    }

    @Test
    void parses_features_block_with_dep_names() {
        // Feature `deps` are now dep names (not coord strings). Resolution
        // happens at activation time, against [dependencies.*].
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [features]
                default = ["postgres"]

                [features.postgres]
                deps = ["postgres-jdbc", "hikari"]
                """);
        assertThat(parsed.features().defaults()).containsExactly("postgres");
        assertThat(parsed.features().byName().get("postgres").deps())
                .containsExactly("postgres-jdbc", "hikari");
    }
}
