// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkbuild.credential.RepoCredential;
import dev.jkbuild.library.LibraryCatalog;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JkBuildParserTest {

    private static final String PROJECT = """
            [project]
            group    = "com.example"
            name     = "widget"
            version  = "1.0.0"
            jdk      = 21
            java     = 21
            """;

    @Test
    void parses_minimal_project_block() {
        JkBuild parsed = JkBuildParser.parse(PROJECT);
        assertThat(parsed.project().group()).isEqualTo("com.example");
        assertThat(parsed.project().name()).isEqualTo("widget");
        assertThat(parsed.project().version()).isEqualTo("1.0.0");
        assertThat(parsed.project().jdk()).isEqualTo("21");
        assertThat(parsed.project().java()).isEqualTo(21);
        assertThat(parsed.project().isKotlin()).isFalse();
        assertThat(parsed.project().main()).isNull();
        assertThat(parsed.project().shadow()).isFalse();
        // native absent → SUPPORTED (eligible via jk native; NOT auto-built on jk build)
        assertThat(parsed.project().nativeMode()).isEqualTo(JkBuild.NativeMode.SUPPORTED);
        assertThat(parsed.project().nativeImage()).isTrue(); // isTrue: != DISABLED
        assertThat(parsed.project().description()).isNull();
    }

    @Test
    void absent_build_block_yields_empty_order_after() {
        JkBuild parsed = JkBuildParser.parse(PROJECT);
        assertThat(parsed.build()).isEqualTo(JkBuild.Build.EMPTY);
        assertThat(parsed.build().orderAfter()).isEmpty();
    }

    @Test
    void parses_build_order_after() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """

                [build]
                order-after = ["kotlin-compiler", "dev.jkbuild:git-client"]
                """);
        assertThat(parsed.build().orderAfter()).containsExactly("kotlin-compiler", "dev.jkbuild:git-client");
    }

    @Test
    void rejects_non_string_order_after_entry() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """

                [build]
                order-after = [123]
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[build].order-after");
    }

    @Test
    void parses_build_embed_sha_and_treats_sources_as_order_after() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """

                [build]
                order-after = ["plain-dep"]

                [build.embed-sha]
                "jk-kotlin-compiler" = "kotlin-compiler"
                "jk-git-client" = "git-client"
                """);
        assertThat(parsed.build().embedSha())
                .containsEntry("jk-kotlin-compiler", "kotlin-compiler")
                .containsEntry("jk-git-client", "git-client");
        // embed-sha sources are build-order prerequisites, merged with explicit order-after
        assertThat(parsed.build().allOrderAfter()).contains("plain-dep", "kotlin-compiler", "git-client");
    }

    @Test
    void build_lint_defaults_on_and_can_be_disabled() {
        assertThat(JkBuildParser.parse(PROJECT).build().lint()).isTrue(); // absent → on
        JkBuild off = JkBuildParser.parse(PROJECT + """

                [build]
                lint = false
                """);
        assertThat(off.build().lint()).isFalse();
    }

    @Test
    void parses_build_test_worker_jars_as_order_after() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """

                [build]
                test-worker-jars = ["publisher", "compat-bridge"]
                """);
        assertThat(parsed.build().testWorkerJars()).containsExactly("publisher", "compat-bridge");
        // test-worker-jars modules must build first → they're order-after prerequisites
        assertThat(parsed.build().allOrderAfter()).contains("publisher", "compat-bridge");
    }

    @Test
    void rejects_non_string_embed_sha_value() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """

                [build.embed-sha]
                "jk-x" = 123
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[build.embed-sha]");
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
                name     = "widget"
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
                name     = "widget"
                version  = "1.0.0"
                jdk      = 8
                java     = 17
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("project.jdk = 8");
    }

    @Test
    void parses_format_block() {
        JkBuild parsed = JkBuildParser.parse("""
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"

                [format]
                style  = "standard"
                java   = "palantir"
                kotlin = "kotlinlang"
                """);
        assertThat(parsed.format().style()).isEqualTo("standard");
        assertThat(parsed.format().java()).isEqualTo("palantir");
        assertThat(parsed.format().kotlin()).isEqualTo("kotlinlang");
    }

    @Test
    void format_block_absent_is_empty() {
        JkBuild parsed = JkBuildParser.parse(PROJECT);
        assertThat(parsed.format()).isEqualTo(JkBuild.FormatConfig.EMPTY);
        assertThat(parsed.format().java()).isNull();
    }

    @Test
    void parses_jdk_vendor_major_spec() {
        JkBuild parsed = JkBuildParser.parse("""
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = "temurin-25"
                """);
        assertThat(parsed.project().jdk()).isEqualTo("temurin-25");
        assertThat(parsed.project().jdkMajor()).isEqualTo(25);
        // java falls back to the jdk major when not given explicitly.
        assertThat(parsed.project().javaRelease()).isEqualTo(25);
    }

    @Test
    void parses_jdk_bare_major_string() {
        JkBuild parsed = JkBuildParser.parse("""
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = "25"
                """);
        assertThat(parsed.project().jdk()).isEqualTo("25");
        assertThat(parsed.project().jdkMajor()).isEqualTo(25);
    }

    @Test
    void parses_graal_spec_variants() {
        assertThat(JkBuildParser.parse(graal("\"graalvm-25\"")).project().graal())
                .isEqualTo("graalvm-25");
        assertThat(JkBuildParser.parse(graal("25")).project().graal()).isEqualTo("25");
        assertThat(JkBuildParser.parse(graal("\"native\"")).project().graal()).isEqualTo("native");
        // Absent → null (the common case).
        assertThat(JkBuildParser.parse("""
                [project]
                group = "com.example"
                name = "widget"
                version = "1.0.0"
                """).project().graal()).isNull();
    }

    @Test
    void rejects_graal_point_release() {
        assertThatThrownBy(() -> JkBuildParser.parse(graal("\"graalvm-25.0.3\"")))
                .hasMessageContaining("project.graal");
    }

    private static String graal(String value) {
        return """
                [project]
                group = "com.example"
                name = "widget"
                version = "1.0.0"
                graal = %s
                """.formatted(value);
    }

    @Test
    void accepts_unquoted_integer_jdk_as_bare_major() {
        // Back-compat: the old integer form coerces to a bare-major string.
        JkBuild parsed = JkBuildParser.parse("""
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = 25
                """);
        assertThat(parsed.project().jdk()).isEqualTo("25");
    }

    @Test
    void rejects_jdk_point_release() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = "25.0.3"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("point release");
    }

    @Test
    void rejects_jdk_vendor_point_release() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = "temurin-25.0.3"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("point release");
    }

    @Test
    void missing_required_key_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                [project]
                group    = "com.example"
                name     = "widget"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("project.version");
    }

    @Test
    void parses_name_as_key_dep_with_full_table() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                slf4j-api = { group = "org.slf4j", name = "slf4j-api", version = "2.0.16" }
                picocli   = { group = "info.picocli", name = "picocli", version = "^4.7.7" }

                [dependencies.test]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = ">=5.10, <6" }
                """);

        var mainDeps = parsed.dependencies().of(Scope.MAIN);
        assertThat(mainDeps).hasSize(2);

        // Bare version on the new format → Caret (Cargo-style default).
        var slf4j = mainDeps.get(0);
        assertThat(slf4j.library()).isEqualTo("slf4j-api");
        assertThat(slf4j.module()).isEqualTo("org.slf4j:slf4j-api");
        assertThat(slf4j.version()).isInstanceOf(VersionSelector.Caret.class);
        assertThat(slf4j.pinned()).isFalse();

        var picocli = mainDeps.get(1);
        assertThat(picocli.library()).isEqualTo("picocli");
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
        assertThat(dep.library()).isEqualTo("picocli");
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
                junit = { group = "org.junit.jupiter", name = "junit-jupiter", version = "5.10.0" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("mixed flat and sub-scope");
    }

    @Test
    void path_source_is_pinned() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                shared-utils = { group = "com.acme", name = "shared-utils", path = "../shared-utils" }
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
    void git_source_without_group_discovers_the_coordinate() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                mylib = { git = "https://github.com/acme/widgets", tag = "v1.4.0" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        // Discovery: a placeholder module the resolver rewrites once the repo's
        // [project] coordinate is known; no override carried.
        assertThat(dep.module()).isEqualTo("git:mylib");
        assertThat(dep.gitSource().hasOverrides()).isFalse();
        assertThat(dep.gitSource().overrideGroup()).isNull();
    }

    @Test
    void git_source_honors_coordinate_and_version_overrides() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                fork = { git = "https://github.com/me/widgets-fork", branch = "main", \
                         group = "com.acme", name = "widgets", version = "1.4.0-acme" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(dep.module()).isEqualTo("com.acme:widgets");
        var src = dep.gitSource();
        assertThat(src.overrideGroup()).isEqualTo("com.acme");
        assertThat(src.overrideArtifact()).isEqualTo("widgets");
        assertThat(src.overrideVersion()).isEqualTo("1.4.0-acme");
    }

    @Test
    void git_source_parses_fetch_freshness_policy() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                fork = { group = "com.acme", name = "widgets", \
                         git = "https://github.com/me/widgets", branch = "main", fetch = "48h" }
                """);
        assertThat(parsed.dependencies().of(Scope.MAIN).getFirst().gitSource().fetch())
                .isEqualTo("48h");
    }

    @Test
    void git_source_rejects_invalid_fetch_policy() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                fork = { group = "com.acme", name = "widgets", \
                         git = "https://github.com/me/widgets", branch = "main", fetch = "soon" }
                """)).hasMessageContaining("fetch");
    }

    @Test
    void git_source_group_override_defaults_artifact_to_dep_name() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                codec = { group = "com.acme", git = "https://github.com/acme/codec", tag = "v0.9.1" }
                """);
        var src = parsed.dependencies().of(Scope.MAIN).getFirst().gitSource();
        assertThat(src.overrideGroup()).isEqualTo("com.acme");
        assertThat(src.overrideArtifact()).isEqualTo("codec"); // defaults to the dep name
        assertThat(src.overrideVersion()).isNull(); // version still derived from the tag
    }

    @Test
    void git_source_artifact_without_group_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                bad = { git = "https://github.com/acme/widgets", tag = "v1", name = "widgets" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("`name` without `group`");
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
    void dep_string_value_is_catalog_shorthand_not_v0_6_coord_string() {
        // A string value is now treated as the version shorthand for a
        // catalog-known name. Pasting the old v0.6 coord-string form
        // ("group:artifact:version") trips the unknown-short-name error.
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                "org.foo:bar" = "1.0"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void parses_workspace_dependencies_block() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["a", "b"]

                [workspace.dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.0" }
                assertj-core  = { group = "org.assertj",       name = "assertj-core",  version = "3.27.7" }
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
                modules = ["a"]

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
                modules = []

                [workspace.dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.0" }

                [dependencies.test]
                junit-jupiter.workspace = true
                """);
        var dep = parsed.dependencies().of(Scope.TEST).getFirst();
        assertThat(dep.library()).isEqualTo("junit-jupiter");
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
        assertThat(dep.library()).isEqualTo("jk-core");
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
        assertThat(parsed.repositories()).extracting(r -> r.name()).containsExactlyInAnyOrder("central", "internal");
        // No inline credential on either repo.
        assertThat(parsed.repositories())
                .allSatisfy(r -> assertThat(r.credential()).isEmpty());
    }

    @Test
    void parses_inline_token_and_basic_credentials() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories.ghp]
                url = "https://maven.pkg.github.com/jkbuild/jk"
                token = "ghp_literaltoken"

                [repositories.nexus]
                url = "https://nexus.example/repository/maven-releases/"
                username = "deployer"
                password = "s3cr3t"
                """);

        var ghp = parsed.repositories().stream()
                .filter(r -> r.name().equals("ghp"))
                .findFirst()
                .orElseThrow();
        assertThat(ghp.credential()).contains(new RepoCredential.Bearer("ghp_literaltoken"));

        var nexus = parsed.repositories().stream()
                .filter(r -> r.name().equals("nexus"))
                .findFirst()
                .orElseThrow();
        assertThat(nexus.credential()).contains(new RepoCredential.Basic("deployer", "s3cr3t"));
    }

    @Test
    void parses_object_store_config_for_s3_repo() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories.s3-releases]
                url = "s3://acme-artifacts/releases"
                region = "us-west-2"
                endpoint = "https://minio.internal:9000"
                access-key = "AKID"
                secret-key = "SEKRIT"
                """);
        var spec = parsed.repositories().get(0);
        assertThat(spec.objectStore()).hasValueSatisfying(c -> {
            assertThat(c.region()).isEqualTo("us-west-2");
            assertThat(c.endpoint()).isEqualTo("https://minio.internal:9000");
            assertThat(c.accessKey()).isEqualTo("AKID");
            assertThat(c.secretKey()).isEqualTo("SEKRIT");
            assertThat(c.hasExplicitCredentials()).isTrue();
        });
    }

    @Test
    void object_store_config_absent_when_no_keys() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories.plain]
                url = "https://repo.example/maven"
                """);
        assertThat(parsed.repositories().get(0).objectStore()).isEmpty();
    }

    @Test
    void object_store_access_key_interpolates_env() {
        String path = System.getenv("PATH");
        org.junit.jupiter.api.Assumptions.assumeTrue(path != null && !path.isBlank());
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories.s3]
                url = "s3://bucket/maven"
                access-key = "${PATH}"
                secret-key = "literal-secret"
                """);
        assertThat(parsed.repositories().get(0).objectStore())
                .hasValueSatisfying(c -> assertThat(c.accessKey()).isEqualTo(path));
    }

    @Test
    void interpolates_env_var_in_inline_credential() {
        // PATH is reliably set in the test environment; use it as a stand-in secret.
        String path = System.getenv("PATH");
        org.junit.jupiter.api.Assumptions.assumeTrue(path != null && !path.isBlank());
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [repositories.r]
                url = "https://nexus.example/repo/"
                token = "${PATH}"
                """);
        assertThat(parsed.repositories().get(0).credential()).contains(new RepoCredential.Bearer(path));
    }

    @Test
    void unset_env_var_in_credential_is_an_error() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [repositories.r]
                url = "https://nexus.example/repo/"
                token = "${JK_DEFINITELY_UNSET_VAR_XYZ}"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("JK_DEFINITELY_UNSET_VAR_XYZ");
    }

    @Test
    void parses_workspace_block() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["core", "io"]
                """);
        assertThat(parsed.isWorkspaceRoot()).isTrue();
        assertThat(parsed.workspace().modules()).containsExactly("core", "io");
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

    // ───────────────────────────────────────────────────────────────
    //  Library-catalog shorthand
    // ───────────────────────────────────────────────────────────────

    /** Synthetic catalog used so the tests don't drift with the bundled set. */
    private static final LibraryCatalog TEST_CATALOG = LibraryCatalog.of(Map.of(
            "jackson-databind", new LibraryCatalog.Module("tools.jackson.core", "jackson-databind"),
            "picocli", new LibraryCatalog.Module("info.picocli", "picocli")));

    @Test
    void shorthand_string_value_resolves_through_catalog() {
        // The cargo-add experience: `name = "1.0.0"` looks up the coord in
        // the bundled catalog and treats the version as caret-floating.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                jackson-databind = "2.18.2"
                """, TEST_CATALOG);
        var deps = parsed.dependencies().of(Scope.MAIN);
        assertThat(deps).hasSize(1);
        assertThat(deps.getFirst().library()).isEqualTo("jackson-databind");
        assertThat(deps.getFirst().module()).isEqualTo("tools.jackson.core:jackson-databind");
        assertThat(deps.getFirst().version()).isInstanceOf(VersionSelector.Caret.class);
    }

    @Test
    void shorthand_table_without_group_resolves_through_catalog() {
        // A dep table that lists only `version` (no `group`) falls back to
        // the catalog the same way the string shorthand does.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                picocli = { version = "4.7.7" }
                """, TEST_CATALOG);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.module()).isEqualTo("info.picocli:picocli");
    }

    @Test
    void explicit_group_overrides_catalog() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                jackson-databind = { group = "io.fork", version = "2.18.2" }
                """, TEST_CATALOG);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.module()).isEqualTo("io.fork:jackson-databind");
    }

    @Test
    void unknown_shorthand_string_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                some-unknown-thing = "1.0.0"
                """, TEST_CATALOG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void unknown_shorthand_surfaces_did_you_mean_for_split_families() {
        // The parser's unknown-library message includes suggestions drawn
        // from the catalog. For the Jackson family this means typing the
        // unprefixed name surfaces both major-version flavors.
        LibraryCatalog splitFamily = LibraryCatalog.of(Map.of(
                "jackson2-databind", new LibraryCatalog.Module("com.fasterxml.jackson.core", "jackson-databind"),
                "jackson3-databind", new LibraryCatalog.Module("tools.jackson.core", "jackson-databind")));

        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                jackson-databind = "2.18.2"
                """, splitFamily))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name `jackson-databind`")
                .hasMessageContaining("Did you mean:")
                .hasMessageContaining("jackson2-databind")
                .hasMessageContaining("jackson3-databind");
    }

    @Test
    void unknown_table_without_group_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                also-unknown = { version = "1.0.0" }
                """, TEST_CATALOG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must set a `group`");
    }

    @Test
    void path_source_still_requires_explicit_group_even_for_catalog_name() {
        // Catalog shorthand is name → version; path/git sources are
        // out-of-catalog by construction. Force the user to be explicit.
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                picocli = { path = "../picocli" }
                """, TEST_CATALOG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must set a `group` explicitly");
    }

    @Test
    void project_libraries_table_overrides_passed_in_catalog() {
        // The project-local [libraries] table is the top of the lookup chain;
        // it shadows the catalog passed by callers (including the bundled
        // one in production).
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [libraries]
                picocli = "io.fork:picocli"

                [dependencies.main]
                picocli = "4.7.7"
                """, TEST_CATALOG);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.module()).isEqualTo("io.fork:picocli");
    }

    @Test
    void project_libraries_can_introduce_a_brand_new_short_name() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [libraries]
                internal-widget = "com.acme:internal-widget"

                [dependencies.main]
                internal-widget = "0.1.0"
                """, TEST_CATALOG);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.module()).isEqualTo("com.acme:internal-widget");
    }

    @Test
    void project_libraries_with_versioned_coord_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [libraries]
                bad = "com.acme:bad:1.0.0"
                """, TEST_CATALOG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("carries a version");
    }

    @Test
    void parses_optional_dependency_flag() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies.main]
                guava = { group = "com.google.guava", name = "guava", version = "33.0.0-jre", optional = true }
                jackson-databind = { group = "com.fasterxml.jackson.core", version = "2.18.2" }
                """);
        var deps = parsed.dependencies().of(dev.jkbuild.model.Scope.MAIN);
        assertThat(deps)
                .filteredOn(d -> d.library().equals("guava"))
                .singleElement()
                .satisfies(d -> assertThat(d.optional()).isTrue());
        assertThat(deps)
                .filteredOn(d -> d.library().equals("jackson-databind"))
                .singleElement()
                .satisfies(d -> assertThat(d.optional()).isFalse());
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
        assertThat(parsed.features().byName().get("postgres").deps()).containsExactly("postgres-jdbc", "hikari");
    }

    // --- application / m2install -------------------------------------------

    @Test
    void application_defaults_false_without_main() {
        assertThat(JkBuildParser.parse(PROJECT).project().isApplication()).isFalse();
    }

    @Test
    void application_defaults_true_when_main_is_set() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + "main = \"com.example.Main\"\n");
        assertThat(parsed.project().isApplication()).isTrue();
    }

    @Test
    void explicit_application_false_overrides_main_default() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + "main = \"com.example.Main\"\napplication = false\n");
        assertThat(parsed.project().isApplication()).isFalse();
    }

    @Test
    void explicit_application_true_without_main() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + "application = true\n");
        assertThat(parsed.project().isApplication()).isTrue();
    }

    @Test
    void m2install_parsed_default_false() {
        assertThat(JkBuildParser.parse(PROJECT).project().m2install()).isFalse();
        assertThat(JkBuildParser.parse(PROJECT + "m2install = true\n").project().m2install())
                .isTrue();
    }
}
