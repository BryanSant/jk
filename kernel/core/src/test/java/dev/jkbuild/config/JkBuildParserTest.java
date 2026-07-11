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
        assertThat(parsed.mainClass()).isNull();
        assertThat(parsed.isApplication()).isFalse();
        assertThat(parsed.shadowJar()).isFalse();
        // [native] absent entirely → DISABLED (its presence is the enable switch now)
        assertThat(parsed.nativeMode()).isEqualTo(JkBuild.NativeMode.DISABLED);
        assertThat(parsed.nativeImage()).isFalse();
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
    void java_accepts_quoted_string_and_coerces() {
        JkBuild parsed = JkBuildParser.parse("""
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                java     = "25"
                """);
        assertThat(parsed.project().java()).isEqualTo(25);
    }

    @Test
    void rejects_non_numeric_java_string() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                java     = "twenty-five"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("project.java");
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
    void parses_jdk_keyword_specs() {
        // lts/stable/latest/native are accepted as-is (the same keywords --jdk/JK_JDK/
        // .jdk-version accept) — resolved downstream by JdkKeywords, not by this parser.
        for (String keyword : new String[] {"lts", "stable", "latest", "native"}) {
            JkBuild parsed = JkBuildParser.parse("""
                    [project]
                    group    = "com.example"
                    name     = "widget"
                    version  = "1.0.0"
                    jdk      = "%s"
                    """.formatted(keyword));
            assertThat(parsed.project().jdk()).isEqualTo(keyword);
        }
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
        assertThat(JkBuildParser.parse(graal("\"graalvm-25\"")).graal()).isEqualTo("graalvm-25");
        assertThat(JkBuildParser.parse(graal("25")).graal()).isEqualTo("25");
        assertThat(JkBuildParser.parse(graal("\"native\"")).graal()).isEqualTo("native");
        // [native] declared, graal key omitted → defaults to the "graalvm" spec.
        assertThat(JkBuildParser.parse(PROJECT + "\n[native]\n").graal()).isEqualTo("graalvm");
        // No [native] table at all → null.
        assertThat(JkBuildParser.parse(PROJECT).graal()).isNull();
    }

    @Test
    void rejects_graal_point_release() {
        assertThatThrownBy(() -> JkBuildParser.parse(graal("\"graalvm-25.0.3\"")))
                .hasMessageContaining("[native].graal");
    }

    private static String graal(String value) {
        return PROJECT
                + """

                [native]
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
                [dependencies]
                slf4j-api = { group = "org.slf4j", name = "slf4j-api", version = "2.0.16" }
                picocli   = { group = "info.picocli", name = "picocli", version = "^4.7.7" }

                [test-dependencies]
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
                [dependencies]
                picocli = { group = "info.picocli", version = "4.7.7" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.library()).isEqualTo("picocli");
        assertThat(dep.module()).isEqualTo("info.picocli:picocli");
    }

    @Test
    void equals_selector_pins_dep() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
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
                [dependencies]
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
                [dependencies]
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
    void main_and_test_dependencies_can_coexist_as_separate_top_level_tables() {
        // [dependencies] (MAIN) and [test-dependencies] (TEST) are independent
        // top-level sections; they may both appear in the same jk.toml.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                stray = { group = "com.example", version = "1.0" }

                [test-dependencies]
                junit = { group = "org.junit.jupiter", name = "junit-jupiter", version = "5.10.0" }
                """);
        assertThat(parsed.dependencies().of(Scope.MAIN)).hasSize(1);
        assertThat(parsed.dependencies().of(Scope.TEST)).hasSize(1);
    }

    @Test
    void inline_path_dependency_is_a_path_source() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                shared-utils = { path = "../shared-utils" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isPath()).isTrue();
        assertThat(dep.pathSource().rawPath()).isEqualTo("../shared-utils");
        assertThat(dep.module()).isEqualTo("path:shared-utils");
        assertThat(dep.pinned()).isTrue();
    }

    @Test
    void inline_path_dependency_rejects_an_explicit_coordinate() {
        // Like git, a path dep discovers its coordinate from the target — declaring
        // group/name/version here is an error (named specifically, not a multi-source complaint).
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                picocli = { path = "../picocli", group = "info.picocli" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("with `path` must not set `group`");
    }

    @Test
    void git_source_inline_on_dep_table() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                codec = { git = "https://github.com/acme/codec", tag = "v0.9.1" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(dep.gitSource().originalUrl()).isEqualTo("https://github.com/acme/codec");
        assertThat(dep.gitSource().ref()).isInstanceOf(GitRefSpec.Tag.class);
        assertThat(dep.module()).isEqualTo("git:codec");
        assertThat(dep.pinned()).isTrue();
    }

    @Test
    void git_source_must_set_exactly_one_ref() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                codec = { git = "https://github.com/acme/codec", tag = "v1", branch = "main" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("exactly one of");
    }

    @Test
    void git_source_discovers_coordinate_from_repo() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                mylib = { git = "https://github.com/acme/widgets", tag = "v1.4.0" }
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        // Discovery: a placeholder module the resolver rewrites once the repo's
        // [project] coordinate is known.
        assertThat(dep.module()).isEqualTo("git:mylib");
        assertThat(dep.gitSource().ref()).isEqualTo(new GitRefSpec.Tag("v1.4.0"));
    }

    @Test
    void git_source_rejects_group_field() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                fork = { git = "https://github.com/me/widgets", branch = "main", group = "com.acme" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("`group`");
    }

    @Test
    void git_source_rejects_name_field() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                fork = { git = "https://github.com/me/widgets", branch = "main", name = "widgets" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("`name`");
    }

    @Test
    void git_source_rejects_version_field() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                fork = { git = "https://github.com/me/widgets", tag = "v1.0", version = "1.0.0" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("`version`");
    }

    @Test
    void git_source_rejects_fetch_field() {
        // `fetch` (the branch-tip freshness window) is gone — every git dep is pinned
        // in jk.lock and only moves on an explicit `jk update --git` / `jk fetch`.
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                fork = { git = "https://github.com/me/widgets", branch = "main", fetch = "48h" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("fetch");
    }

    @Test
    void git_source_requires_tag_branch_rev_or_embedded_ref() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                bad = { git = "https://github.com/acme/widgets" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must set `tag`, `branch`, or `rev`");
    }

    @Test
    void git_source_url_embedded_branch_via_at() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                mylib = { git = "https://github.com/jin-tonic/jin@main" }
                """);
        var src = parsed.dependencies().of(Scope.MAIN).getFirst().gitSource();
        assertThat(src.originalUrl()).isEqualTo("https://github.com/jin-tonic/jin");
        assertThat(src.ref()).isEqualTo(new GitRefSpec.Branch("main"));
        assertThat(src.shallow()).isFalse();
    }

    @Test
    void git_source_url_embedded_tag_via_at_is_deep_clone() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                requests = { git = "https://github.com/psf/requests.git@v1.2.3" }
                """);
        var src = parsed.dependencies().of(Scope.MAIN).getFirst().gitSource();
        assertThat(src.ref()).isEqualTo(new GitRefSpec.Tag("v1.2.3"));
        assertThat(src.shallow()).isFalse(); // URL-embedded → always deep
    }

    @Test
    void git_source_explicit_tag_is_shallow_clone() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                mylib = { git = "https://github.com/acme/widgets", tag = "v1.4.0" }
                """);
        var src = parsed.dependencies().of(Scope.MAIN).getFirst().gitSource();
        assertThat(src.ref()).isEqualTo(new GitRefSpec.Tag("v1.4.0"));
        assertThat(src.shallow()).isTrue(); // explicit tag = → shallow
    }

    @Test
    void git_source_url_embedded_rev_via_hash() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                mylib = { git = "https://github.com/user/repo#8f3a1b2c4d5e6f" }
                """);
        var src = parsed.dependencies().of(Scope.MAIN).getFirst().gitSource();
        assertThat(src.ref()).isEqualTo(new GitRefSpec.Rev("8f3a1b2c4d5e6f"));
        assertThat(src.shallow()).isFalse();
    }

    @Test
    void git_source_url_embedded_ref_and_explicit_ref_conflict() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                bad = { git = "https://github.com/acme/widgets@main", branch = "main" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("URL-embedded ref");
    }

    @Test
    void git_source_subdir_via_bang_before_ref() {
        // url!subdir@ref form
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                auth = { git = "https://github.com/user/repo!components/auth@v1.2.3" }
                """);
        var src = parsed.dependencies().of(Scope.MAIN).getFirst().gitSource();
        assertThat(src.originalUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(src.path()).isEqualTo("components/auth");
        assertThat(src.ref()).isEqualTo(new GitRefSpec.Tag("v1.2.3"));
        assertThat(src.shallow()).isFalse();
    }

    @Test
    void git_source_subdir_via_bang_after_ref() {
        // url@ref!subdir form
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                auth = { git = "git://github.com/user/repo@v1.2.3!components/auth" }
                """);
        var src = parsed.dependencies().of(Scope.MAIN).getFirst().gitSource();
        assertThat(src.originalUrl()).isEqualTo("git://github.com/user/repo");
        assertThat(src.path()).isEqualTo("components/auth");
        assertThat(src.ref()).isEqualTo(new GitRefSpec.Tag("v1.2.3"));
        assertThat(src.shallow()).isFalse();
    }

    @Test
    void git_source_subdir_via_bang_with_sha() {
        // url#sha!subdir form
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                auth = { git = "https://github.com/user/repo#8f3a1b2c4d5e6f!components/auth" }
                """);
        var src = parsed.dependencies().of(Scope.MAIN).getFirst().gitSource();
        assertThat(src.path()).isEqualTo("components/auth");
        assertThat(src.ref()).isEqualTo(new GitRefSpec.Rev("8f3a1b2c4d5e6f"));
        assertThat(src.shallow()).isFalse();
    }

    @Test
    void git_source_url_subdir_and_explicit_path_conflict() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                auth = { git = "https://github.com/user/repo!components/auth", branch = "main", path = "components/auth" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("sub-directory");
    }

    @Test
    void multiple_sources_on_dep_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                bad = { git = "https://github.com/acme/bad", branch = "main", version = "1.0" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("more than one");
    }

    @Test
    void no_source_on_dep_parses_as_platform_managed() {
        // Contract change (spring-boot plan §3.1): group-only = version supplied by an
        // imported [platform-dependencies] BOM at resolve time. Resolve errors clearly
        // when no BOM covers the module.
        JkBuild b = JkBuildParser.parse(PROJECT + """
                [dependencies]
                widget = { group = "com.example" }
                """);
        assertThat(b.dependencies().of(dev.jkbuild.model.Scope.MAIN).get(0).isPlatformManaged())
                .isTrue();
    }

    @Test
    void missing_group_on_dep_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
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
                [dependencies]
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
    void workspace_dependencies_string_shorthand_resolves_through_catalog() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["a"]

                [workspace.dependencies]
                jackson-databind = "2.18.2"
                """, TEST_CATALOG);
        var jd = parsed.workspace().dependencies().get("jackson-databind");
        assertThat(jd.module()).isEqualTo("tools.jackson.core:jackson-databind");
        assertThat(jd.version()).isInstanceOf(VersionSelector.Caret.class);
    }

    @Test
    void workspace_dependencies_table_without_group_resolves_through_catalog() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["a"]

                [workspace.dependencies]
                picocli = { version = "4.7.7" }
                """, TEST_CATALOG);
        var pico = parsed.workspace().dependencies().get("picocli");
        assertThat(pico.module()).isEqualTo("info.picocli:picocli");
    }

    @Test
    void workspace_dependencies_unknown_shorthand_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["a"]

                [workspace.dependencies]
                some-unknown-thing = "1.0.0"
                """, TEST_CATALOG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void workspace_dependencies_string_shorthand_rejects_git_url() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["a"]

                [workspace.dependencies]
                shared = "https://github.com/acme/shared"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("version spec");
    }

    @Test
    void workspace_dependencies_rejects_path_source() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [workspace]
                modules = ["a"]

                [workspace.dependencies]
                shared-lib = { group = "com.acme", path = "../shared" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[workspace] modules");
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

                [test-dependencies]
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
                [dependencies]
                jk-core.workspace = true
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.library()).isEqualTo("jk-core");
        assertThat(dep.module()).startsWith("workspace:");
    }

    @Test
    void workspace_true_cannot_combine_with_version() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                bad = { workspace = true, version = "1.0" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("more than one");
    }

    @Test
    void workspace_true_cannot_combine_with_group() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                bad = { workspace = true, group = "com.example" }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must not set `group`");
    }

    @Test
    void workspace_false_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
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
    void shorthand_relative_path_is_a_path_source() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                my-lib = "./some/local/path"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isPath()).isTrue();
        assertThat(dep.pathSource().rawPath()).isEqualTo("./some/local/path");
        assertThat(dep.module()).isEqualTo("path:my-lib");
    }

    @Test
    void shorthand_parent_relative_path_is_a_path_source() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                shared = "../shared-utils"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isPath()).isTrue();
        assertThat(dep.pathSource().rawPath()).isEqualTo("../shared-utils");
    }

    @Test
    void shorthand_absolute_path_is_a_path_source() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                shared = "/opt/libs/shared"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isPath()).isTrue();
        assertThat(dep.pathSource().rawPath()).isEqualTo("/opt/libs/shared");
    }

    @Test
    void shorthand_https_url_defaults_to_main_branch() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                requests = "https://github.com/psf/requests"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(dep.gitSource().ref()).isEqualTo(new GitRefSpec.Branch("main"));
        assertThat(dep.gitSource().shallow()).isFalse();
        assertThat(dep.module()).isEqualTo("git:requests");
    }

    @Test
    void shorthand_git_url_with_embedded_branch() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                jin = "git://github.com/jin-tonic/jin@mybranch"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(dep.gitSource().ref()).isEqualTo(new GitRefSpec.Branch("mybranch"));
        assertThat(dep.gitSource().shallow()).isFalse();
    }

    @Test
    void shorthand_git_url_with_embedded_tag() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                requests = "https://github.com/psf/requests.git@v1.2.3"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(dep.gitSource().ref()).isEqualTo(new GitRefSpec.Tag("v1.2.3"));
        assertThat(dep.gitSource().shallow()).isFalse(); // URL-embedded → always deep
    }

    @Test
    void shorthand_git_url_with_embedded_sha() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                repo = "git://github.com/user/repo#8f3a1b2c4d5e6f"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(dep.gitSource().ref()).isEqualTo(new GitRefSpec.Rev("8f3a1b2c4d5e6f"));
    }

    @Test
    void shorthand_git_url_with_subdir() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                auth = "https://github.com/user/repo!components/auth@main"
                """);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isTrue();
        assertThat(dep.gitSource().path()).isEqualTo("components/auth");
        assertThat(dep.gitSource().ref()).isEqualTo(new GitRefSpec.Branch("main"));
    }

    @Test
    void shorthand_ambiguous_string_is_unknown_library() {
        // A string that isn't a version spec, keyword, git URL, or explicit path
        // prefix is never guessed at as a path dep — it's an unknown short name.
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                my-dep = "some-dir"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void shorthand_string_with_slash_is_unknown_library() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                auth = "components/auth"
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void shorthand_reserved_keyword_latest_is_always_catalog() {
        // "latest" is reserved — never statted, always a version lookup.
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                unknown-lib = "latest"
                """, TEST_CATALOG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void shorthand_reserved_keyword_stable_is_always_catalog() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                unknown-lib = "stable"
                """, TEST_CATALOG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void shorthand_version_starting_with_digit_is_catalog() {
        // "1.2.3" starts with a digit → treated as version spec, not a path.
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
                unknown-lib = "1.2.3"
                """, TEST_CATALOG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("unknown short name");
    }

    @Test
    void shorthand_caret_version_is_catalog() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                jackson-databind = "^2.18.0"
                """, TEST_CATALOG);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.isGit()).isFalse();
        assertThat(dep.version()).isInstanceOf(VersionSelector.Caret.class);
    }

    @Test
    void isVersionSpecOrKeyword_covers_all_reserved_words() {
        assertThat(JkBuildParser.isVersionSpecOrKeyword("latest")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("stable")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("lts")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("preview")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("^2.0.0")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("~1.0")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("=1.2.3")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword(">=1.0")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("1.2.3")).isTrue();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("some-dir")).isFalse();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("components/auth")).isFalse();
        assertThat(JkBuildParser.isVersionSpecOrKeyword("my-library")).isFalse();
    }

    @Test
    void shorthand_string_value_resolves_through_catalog() {
        // The cargo-add experience: `name = "1.0.0"` looks up the coord in
        // the bundled catalog and treats the version as caret-floating.
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
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
                [dependencies]
                picocli = { version = "4.7.7" }
                """, TEST_CATALOG);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.module()).isEqualTo("info.picocli:picocli");
    }

    @Test
    void explicit_group_overrides_catalog() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [dependencies]
                jackson-databind = { group = "io.fork", version = "2.18.2" }
                """, TEST_CATALOG);
        var dep = parsed.dependencies().of(Scope.MAIN).getFirst();
        assertThat(dep.module()).isEqualTo("io.fork:jackson-databind");
    }

    @Test
    void unknown_shorthand_string_is_rejected() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """
                [dependencies]
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
                [dependencies]
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
                [dependencies]
                also-unknown = { version = "1.0.0" }
                """, TEST_CATALOG))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must set a `group`");
    }

    @Test
    void project_libraries_table_overrides_passed_in_catalog() {
        // The project-local [libraries] table is the top of the lookup chain;
        // it shadows the catalog passed by callers (including the bundled
        // one in production).
        JkBuild parsed = JkBuildParser.parse(PROJECT + """
                [libraries]
                picocli = "io.fork:picocli"

                [dependencies]
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

                [dependencies]
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
                [dependencies]
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

    // --- application / native / m2install -----------------------------------

    @Test
    void application_absent_means_not_an_application() {
        assertThat(JkBuildParser.parse(PROJECT).isApplication()).isFalse();
        assertThat(JkBuildParser.parse(PROJECT).mainClass()).isNull();
    }

    @Test
    void application_present_with_main() {
        JkBuild parsed = JkBuildParser.parse(PROJECT + "\n[application]\nmain = \"com.example.Main\"\n");
        assertThat(parsed.isApplication()).isTrue();
        assertThat(parsed.mainClass()).isEqualTo("com.example.Main");
    }

    @Test
    void application_present_without_main_is_still_an_application() {
        // [application]'s mere presence is the signal — a main class is not required
        // (e.g. a project that only wants shadow-jar packaging).
        JkBuild parsed = JkBuildParser.parse(PROJECT + "\n[application]\nshadow-jar = true\n");
        assertThat(parsed.isApplication()).isTrue();
        assertThat(parsed.mainClass()).isNull();
        assertThat(parsed.shadowJar()).isTrue();
    }

    @Test
    void native_absent_means_disabled_present_means_supported_or_always() {
        assertThat(JkBuildParser.parse(PROJECT).nativeMode()).isEqualTo(JkBuild.NativeMode.DISABLED);
        assertThat(JkBuildParser.parse(PROJECT + "\n[native]\n").nativeMode())
                .isEqualTo(JkBuild.NativeMode.SUPPORTED);
        assertThat(JkBuildParser.parse(PROJECT + "\n[native]\nalways = true\n").nativeMode())
                .isEqualTo(JkBuild.NativeMode.ALWAYS);
    }

    @Test
    void native_config_fields_parsed() {
        JkBuild parsed = JkBuildParser.parse(PROJECT
                + """

                [native]
                main-class = "com.example.NativeMain"
                name       = "myapp"
                args       = ["-O3", "--gc=serial"]
                always     = true
                """);
        assertThat(parsed.nativeConfig()).isPresent();
        JkBuild.NativeConfig nc = parsed.nativeConfig().orElseThrow();
        assertThat(nc.mainClass()).isEqualTo("com.example.NativeMain");
        assertThat(nc.name()).isEqualTo("myapp");
        assertThat(nc.args()).containsExactly("-O3", "--gc=serial");
        assertThat(nc.always()).isTrue();
        assertThat(nc.graal()).isEqualTo("graalvm"); // defaulted — no graal key given
    }

    @Test
    void compact_key_is_inert() {
        // `compact` is no longer supported; a stray one in an old jk.toml has no effect.
        JkBuild parsed = JkBuildParser.parse(PROJECT + "compact = true\n");
        assertThat(parsed.project().layout()).isEqualTo(JkBuild.Layout.AUTO);
    }

    @Test
    void sources_mode_parsed() {
        assertThat(JkBuildParser.parse(PROJECT).project().sourcesMode())
                .isEqualTo(dev.jkbuild.model.JkBuild.SourcesMode.DISABLED);
        assertThat(JkBuildParser.parse(PROJECT + "sources = true\n").project().sourcesMode())
                .isEqualTo(dev.jkbuild.model.JkBuild.SourcesMode.PUBLISH);
        assertThat(JkBuildParser.parse(PROJECT + "sources = \"always\"\n").project().sourcesMode())
                .isEqualTo(dev.jkbuild.model.JkBuild.SourcesMode.ALWAYS);
        assertThat(JkBuildParser.parse(PROJECT + "sources = false\n").project().sourcesMode())
                .isEqualTo(dev.jkbuild.model.JkBuild.SourcesMode.DISABLED);
    }

    @Test
    void m2install_defaults_false_explicit_true_opts_in() {
        assertThat(JkBuildParser.parse(PROJECT).project().m2install()).isFalse();
        assertThat(JkBuildParser.parse(PROJECT + "m2install = true\n").project().m2install())
                .isTrue();
        assertThat(JkBuildParser.parse(PROJECT + "m2install = false\n").project().m2install())
                .isFalse();
    }

    // ── splitEmbeddedUrl unit tests ──────────────────────────────────────────

    @Test
    void split_url_no_embedded() {
        var p = JkBuildParser.splitEmbeddedUrl("https://github.com/user/repo");
        assertThat(p.baseUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(p.subdir()).isNull();
        assertThat(p.refSpec()).isNull();
    }

    @Test
    void split_url_at_ref_only() {
        var p = JkBuildParser.splitEmbeddedUrl("https://github.com/user/repo@main");
        assertThat(p.baseUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(p.subdir()).isNull();
        assertThat(p.refSpec()).isEqualTo("@main");
    }

    @Test
    void split_url_hash_ref_only() {
        var p = JkBuildParser.splitEmbeddedUrl("https://github.com/user/repo#8f3a1b");
        assertThat(p.baseUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(p.subdir()).isNull();
        assertThat(p.refSpec()).isEqualTo("#8f3a1b");
    }

    @Test
    void split_url_ref_then_subdir() {
        var p = JkBuildParser.splitEmbeddedUrl("git://github.com/user/repo@v1.2.3!components/auth");
        assertThat(p.baseUrl()).isEqualTo("git://github.com/user/repo");
        assertThat(p.subdir()).isEqualTo("components/auth");
        assertThat(p.refSpec()).isEqualTo("@v1.2.3");
    }

    @Test
    void split_url_subdir_then_ref() {
        var p = JkBuildParser.splitEmbeddedUrl("https://github.com/user/repo!components/auth@v1.2.3");
        assertThat(p.baseUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(p.subdir()).isEqualTo("components/auth");
        assertThat(p.refSpec()).isEqualTo("@v1.2.3");
    }

    @Test
    void split_url_hash_then_subdir() {
        var p = JkBuildParser.splitEmbeddedUrl("https://github.com/user/repo#8f3a1b!components/auth");
        assertThat(p.baseUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(p.subdir()).isEqualTo("components/auth");
        assertThat(p.refSpec()).isEqualTo("#8f3a1b");
    }

    @Test
    void split_url_subdir_then_hash() {
        var p = JkBuildParser.splitEmbeddedUrl("https://github.com/user/repo!components/auth#8f3a1b");
        assertThat(p.baseUrl()).isEqualTo("https://github.com/user/repo");
        assertThat(p.subdir()).isEqualTo("components/auth");
        assertThat(p.refSpec()).isEqualTo("#8f3a1b");
    }

    @Test
    void split_url_git_at_host_not_confused_for_ref() {
        // "git@github.com" is userinfo, not an embedded ref
        var p = JkBuildParser.splitEmbeddedUrl("git@github.com:user/repo");
        assertThat(p.baseUrl()).isEqualTo("git@github.com:user/repo");
        assertThat(p.refSpec()).isNull();
    }

    @Test
    void dev_and_test_dev_scope_tables_parse() {
        // Dev-loop scopes (spring-boot plan §3.2): run-only / run+test, never packaged.
        JkBuild b = JkBuildParser.parse("""
                [project]
                group = "com.example"
                name = "app"
                version = "1.0"

                [dev-dependencies]
                devtools = { group = "org.springframework.boot", name = "spring-boot-devtools", version = "4.0.0" }

                [test-dev-dependencies]
                testcontainers = { group = "org.springframework.boot", name = "spring-boot-testcontainers", version = "4.0.0" }
                """);
        assertThat(b.dependencies().of(dev.jkbuild.model.Scope.DEV)).hasSize(1);
        assertThat(b.dependencies().of(dev.jkbuild.model.Scope.DEV).get(0).module())
                .isEqualTo("org.springframework.boot:spring-boot-devtools");
        assertThat(b.dependencies().of(dev.jkbuild.model.Scope.TEST_DEV)).hasSize(1);
    }

    @Test
    void versionless_dep_with_group_parses_as_platform_managed() {
        JkBuild b = JkBuildParser.parse("""
                [project]
                group = "com.example"
                name = "app"
                version = "1.0"

                [platform-dependencies]
                spring-boot = { group = "org.springframework.boot", name = "spring-boot-dependencies", version = "4.0.0" }

                [dependencies]
                starter-webmvc = { group = "org.springframework.boot", name = "spring-boot-starter-webmvc" }
                """);
        var dep = b.dependencies().of(dev.jkbuild.model.Scope.MAIN).get(0);
        assertThat(dep.isPlatformManaged()).isTrue();
        assertThat(dep.module()).isEqualTo("org.springframework.boot:spring-boot-starter-webmvc");
    }

    @Test
    void versionless_dep_without_group_still_errors() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                [project]
                group = "com.example"
                name = "app"
                version = "1.0"

                [dependencies]
                mystery = { optional = false }
                """))
                .hasMessageContaining("must set exactly one of");
    }

    @Test
    void runtime_and_platform_scope_tables_parse() {
        // [platform-dependencies] (BOM imports) and [runtime-dependencies] were previously
        // importer-only; hand-written manifests must be able to declare them (Boot BOM flow).
        JkBuild b = JkBuildParser.parse("""
                [project]
                group = "com.example"
                name = "app"
                version = "1.0"

                [platform-dependencies]
                spring-boot = { group = "org.springframework.boot", name = "spring-boot-dependencies", version = "4.0.0" }

                [runtime-dependencies]
                postgres-jdbc = { group = "org.postgresql", name = "postgresql", version = "42.7.4" }
                """);
        assertThat(b.dependencies().of(dev.jkbuild.model.Scope.PLATFORM)).hasSize(1);
        assertThat(b.dependencies().of(dev.jkbuild.model.Scope.RUNTIME)).hasSize(1);
    }

    @Test
    void spring_boot_table_parses_and_auto_imports_the_bom() {
        JkBuild b = JkBuildParser.parse("""
                [project]
                group = "com.example"
                name = "shop"
                version = "1.0"

                [spring-boot]
                version = "4.0.0"

                [dependencies]
                starter-webmvc = { group = "org.springframework.boot", name = "spring-boot-starter-webmvc" }
                """);
        assertThat(b.isSpringBoot()).isTrue();
        var sb = b.springBoot().orElseThrow();
        assertThat(sb.version()).isEqualTo("4.0.0");
        assertThat(sb.buildInfo()).isFalse();
        assertThat(sb.includeTools()).isTrue();
        assertThat(sb.aotEnabled(false)).isFalse(); // unset aot follows [native] presence
        assertThat(sb.aotEnabled(true)).isTrue();
        // version = "4.0.0" alone imports the BOM — no [platform-dependencies] boilerplate.
        var platform = b.dependencies().of(dev.jkbuild.model.Scope.PLATFORM);
        assertThat(platform).hasSize(1);
        assertThat(platform.get(0).module()).isEqualTo("org.springframework.boot:spring-boot-dependencies");
        assertThat(platform.get(0).version().raw()).isEqualTo("=4.0.0");
        // ...which makes the versionless starter platform-managed.
        assertThat(b.dependencies().of(dev.jkbuild.model.Scope.MAIN).get(0).isPlatformManaged())
                .isTrue();
    }

    @Test
    void spring_boot_table_requires_a_version() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """

                [spring-boot]
                build-info = true
                """))
                .hasMessageContaining("[spring-boot].version is required");
    }

    @Test
    void spring_boot_options_parse() {
        JkBuild b = JkBuildParser.parse(PROJECT + """

                [spring-boot]
                version = "4.0.0"
                aot = true
                build-info = true
                include-tools = false
                aot-args = ["--spring.profiles.active=prod"]
                """);
        var sb = b.springBoot().orElseThrow();
        assertThat(sb.aotEnabled(false)).isTrue(); // explicit aot wins over [native] absence
        assertThat(sb.buildInfo()).isTrue();
        assertThat(sb.includeTools()).isFalse();
        assertThat(sb.aotArgs()).containsExactly("--spring.profiles.active=prod");
    }

    @Test
    void spring_boot_bom_is_not_duplicated_when_user_declares_it() {
        // A deliberate [platform-dependencies] spring-boot-dependencies entry wins —
        // the auto-import must not add a second (conflicting) BOM row.
        JkBuild b = JkBuildParser.parse(PROJECT + """

                [spring-boot]
                version = "4.0.0"

                [platform-dependencies]
                spring-boot = { group = "org.springframework.boot", name = "spring-boot-dependencies", version = "4.0.1" }
                """);
        var platform = b.dependencies().of(dev.jkbuild.model.Scope.PLATFORM);
        assertThat(platform).hasSize(1);
        assertThat(platform.get(0).version().raw()).isEqualTo("4.0.1");
    }
}
