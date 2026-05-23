// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.config;

import dev.buildjk.model.BuildJk;
import dev.buildjk.model.GitRefSpec;
import dev.buildjk.model.Scope;
import dev.buildjk.model.VersionSelector;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuildJkParserTest {

    private static final String PROJECT = """
            [project]
            group    = "com.example"
            artifact = "widget"
            version  = "1.0.0"
            jdk      = "21"
            """;

    @Test
    void parses_minimal_project_block() {
        BuildJk parsed = BuildJkParser.parse(PROJECT);
        assertThat(parsed.project().group()).isEqualTo("com.example");
        assertThat(parsed.project().artifact()).isEqualTo("widget");
        assertThat(parsed.project().version()).isEqualTo("1.0.0");
        assertThat(parsed.project().jdk()).isEqualTo("21");
        assertThat(parsed.project().language()).isEqualTo("java");
        assertThat(parsed.project().main()).isNull();
        assertThat(parsed.project().shadow()).isFalse();
        assertThat(parsed.project().nativeImage()).isFalse();
    }

    @Test
    void missing_project_block_rejected() {
        assertThatThrownBy(() -> BuildJkParser.parse(""))
                .isInstanceOf(BuildJkParseException.class)
                .hasMessageContaining("[project]");
    }

    @Test
    void missing_required_key_rejected() {
        assertThatThrownBy(() -> BuildJkParser.parse("""
                [project]
                group    = "com.example"
                artifact = "widget"
                """))
                .isInstanceOf(BuildJkParseException.class)
                .hasMessageContaining("project.version");
    }

    @Test
    void parses_dependencies_as_g_a_v_strings() {
        BuildJk parsed = BuildJkParser.parse(PROJECT + """
                [dependencies]
                main = ["org.slf4j:slf4j-api:2.0.16", "info.picocli:picocli:^4.7.7"]
                test = ["org.junit.jupiter:junit-jupiter:>=5.10,<6"]
                """);

        var mainDeps = parsed.dependencies().of(Scope.MAIN);
        assertThat(mainDeps).hasSize(2);
        assertThat(mainDeps.get(0).module()).isEqualTo("org.slf4j:slf4j-api");
        assertThat(mainDeps.get(0).version()).isInstanceOf(VersionSelector.Exact.class);
        assertThat(mainDeps.get(1).version()).isInstanceOf(VersionSelector.Caret.class);

        var testDeps = parsed.dependencies().of(Scope.TEST);
        assertThat(testDeps).hasSize(1);
        assertThat(testDeps.get(0).version()).isInstanceOf(VersionSelector.Range.class);
    }

    @Test
    void bare_version_is_exact_by_default() {
        BuildJk parsed = BuildJkParser.parse(PROJECT + """
                [dependencies]
                main = ["com.example:lib:1.2.3"]
                """);
        var v = parsed.dependencies().of(Scope.MAIN).getFirst().version();
        assertThat(v).isInstanceOf(VersionSelector.Exact.class);
        assertThat(((VersionSelector.Exact) v).version()).isEqualTo("1.2.3");
    }

    @Test
    void source_only_dep_requires_matching_sources_entry() {
        assertThatThrownBy(() -> BuildJkParser.parse(PROJECT + """
                [dependencies]
                main = ["com.foo:bar"]
                """))
                .isInstanceOf(BuildJkParseException.class)
                .hasMessageContaining("no version and no matching [sources]");
    }

    @Test
    void parses_git_source_overlay() {
        BuildJk parsed = BuildJkParser.parse(PROJECT + """
                [dependencies]
                main = ["com.foo:bar"]

                [sources]
                "com.foo:bar" = { git = "https://github.com/foo/bar", tag = "v1.2.3" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(dep.gitSource().originalUrl()).isEqualTo("https://github.com/foo/bar");
        assertThat(dep.gitSource().ref()).isInstanceOf(GitRefSpec.Tag.class);
    }

    @Test
    void source_with_explicit_version_string_uses_source() {
        BuildJk parsed = BuildJkParser.parse(PROJECT + """
                [dependencies]
                main = ["com.foo:bar:1.0"]

                [sources]
                "com.foo:bar" = { git = "https://github.com/foo/bar", branch = "main" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(dep.gitSource().ref()).isInstanceOf(GitRefSpec.Branch.class);
    }

    @Test
    void source_must_set_exactly_one_ref() {
        assertThatThrownBy(() -> BuildJkParser.parse(PROJECT + """
                [dependencies]
                main = ["com.foo:bar"]

                [sources]
                "com.foo:bar" = { git = "https://github.com/foo/bar", tag = "v1", branch = "main" }
                """))
                .isInstanceOf(BuildJkParseException.class)
                .hasMessageContaining("exactly one of");
    }

    @Test
    void parses_repositories_string_and_table_form() {
        BuildJk parsed = BuildJkParser.parse(PROJECT + """
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
        BuildJk parsed = BuildJkParser.parse(PROJECT + """
                [workspace]
                members = ["core", "io"]
                """);
        assertThat(parsed.isWorkspaceRoot()).isTrue();
        assertThat(parsed.workspace().members()).containsExactly("core", "io");
    }

    @Test
    void parses_profiles_block() {
        BuildJk parsed = BuildJkParser.parse(PROJECT + """
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
    void parses_features_block() {
        BuildJk parsed = BuildJkParser.parse(PROJECT + """
                [features]
                default = ["postgres"]

                [features.postgres]
                deps = ["org.postgresql:postgresql:42.7.4"]
                """);
        assertThat(parsed.features().defaults()).containsExactly("postgres");
        assertThat(parsed.features().byName().get("postgres").deps())
                .containsExactly("org.postgresql:postgresql:42.7.4");
    }

    @Test
    void rejects_malformed_dep_string() {
        assertThatThrownBy(() -> BuildJkParser.parse(PROJECT + """
                [dependencies]
                main = ["bareword"]
                """))
                .isInstanceOf(BuildJkParseException.class)
                .hasMessageContaining("group:artifact");
    }
}
