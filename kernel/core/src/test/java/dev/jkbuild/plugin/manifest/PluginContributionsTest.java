// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkbuild.config.JkBuildParseException;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The declarative-contribution layer (build-plugins P2), driven through the real spring-boot manifest. */
class PluginContributionsTest {

    private static JkBuild boot(String extra) {
        return JkBuildParser.parse(
                """
                [project]
                name = "demo"
                group = "com.example"
                version = "1.0.0"
                jdk = "25"

                [spring-boot]
                version = "4.0.0"
                """ + extra);
    }

    // ---- [[contribute.platform-dependency]] — the BOM acceptance ------------------------------

    @Test
    void bom_injects_with_interpolated_config_version() {
        JkBuild build = boot("");
        assertThat(build.dependencies().of(Scope.PLATFORM))
                .extracting(Dependency::module, d -> d.version().raw())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "org.springframework.boot:spring-boot-dependencies", "=4.0.0"));
    }

    @Test
    void user_declared_bom_wins_over_the_contribution() {
        JkBuild build = boot(
                """
                [platform-dependencies]
                boot-bom = { group = "org.springframework.boot", name = "spring-boot-dependencies", version = "3.9.9" }
                """);
        assertThat(build.dependencies().of(Scope.PLATFORM)).hasSize(1);
        assertThat(build.dependencies().of(Scope.PLATFORM).getFirst().version().raw()).contains("3.9.9");
    }

    // ---- [[contribute.compiler-args]] ----------------------------------------------------------

    @Test
    void boot_contributes_parameters_for_both_compilers() {
        JkBuild build = boot("");
        assertThat(PluginContributions.javacArgs(build, null, Set.of())).containsExactly("-parameters");
        assertThat(PluginContributions.kotlinArgs(build, null, Set.of())).containsExactly("-java-parameters");
    }

    @Test
    void non_boot_project_contributes_nothing() {
        JkBuild build = JkBuildParser.parse(
                """
                [project]
                name = "plain"
                group = "com.example"
                version = "1.0.0"
                jdk = "25"
                """);
        assertThat(PluginContributions.javacArgs(build, null, Set.of())).isEmpty();
        assertThat(PluginContributions.kotlinPlugins(build, null, "2.3.0", Set.of())).isEmpty();
        assertThat(build.dependencies().of(Scope.PLATFORM)).isEmpty();
    }

    // ---- [[contribute.kotlin-plugin]] — the noarg classpath-has acceptance --------------------

    @Test
    void allopen_always_contributes_and_noarg_keys_on_jpa_classpath() {
        JkBuild build = boot("");

        var withoutJpa = PluginContributions.kotlinPlugins(build, null, "2.3.0", Set.of());
        assertThat(withoutJpa)
                .extracting(PluginContributions.KotlinPluginUse::id)
                .containsExactly("org.jetbrains.kotlin.allopen");

        var withJpa = PluginContributions.kotlinPlugins(build, null, "2.3.0", Set.of("jakarta.persistence:jakarta.persistence-api"));
        assertThat(withJpa)
                .extracting(PluginContributions.KotlinPluginUse::id)
                .containsExactly("org.jetbrains.kotlin.allopen", "org.jetbrains.kotlin.noarg");

        var noarg = withJpa.get(1);
        assertThat(noarg.group()).isEqualTo("org.jetbrains.kotlin");
        assertThat(noarg.artifact()).isEqualTo("kotlin-noarg-compiler-plugin-embeddable");
        assertThat(noarg.version()).isEqualTo("2.3.0"); // ${kotlin.version} interpolated
        assertThat(noarg.options()).containsExactly("preset=jpa");
    }

    // ---- manifest-load validation --------------------------------------------------------------

    @Test
    void unknown_interpolation_variable_fails_at_manifest_load() {
        assertThatThrownBy(() -> PluginManifests.parse(
                        """
                        [plugin]
                        id = "p"
                        table = "p"

                        [schema]
                        version = { type = "string", required = true }

                        [[contribute.platform-dependency]]
                        coordinate = "a:b:${config.versoin}"
                        """,
                        "p.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("${config.versoin}")
                .hasMessageContaining("declares no `versoin`");
    }

    @Test
    void classpath_has_cannot_gate_a_platform_dependency() {
        assertThatThrownBy(() -> PluginManifests.parse(
                        """
                        [plugin]
                        id = "p"
                        table = "p"

                        [[contribute.platform-dependency]]
                        coordinate = "a:b:1"
                        when = { classpath-has = "x:y" }
                        """,
                        "p.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("classpath-has cannot gate a platform-dependency");
    }

    @Test
    void when_predicates_parse_and_reject_unknown_or_stacked() {
        String base =
                """
                [plugin]
                id = "p"
                table = "p"

                [[contribute.compiler-args]]
                javac = ["-x"]
                when = { %s }
                """;
        assertThat(PluginManifests.parse(base.formatted("native-declared = true"), "p.toml")
                        .contributions()
                        .compilerArgs()
                        .getFirst()
                        .when())
                .isInstanceOf(PluginManifest.Condition.NativeDeclared.class);
        assertThat(PluginManifests.parse(base.formatted("kotlin-project = true"), "p.toml")
                        .contributions()
                        .compilerArgs()
                        .getFirst()
                        .when())
                .isInstanceOf(PluginManifest.Condition.KotlinProject.class);
        assertThat(PluginManifests.parse(base.formatted("config = \"aot\", equals = \"true\""), "p.toml")
                        .contributions()
                        .compilerArgs()
                        .getFirst()
                        .when())
                .isEqualTo(new PluginManifest.Condition.ConfigEquals("aot", "true"));
        assertThatThrownBy(() -> PluginManifests.parse(base.formatted("frobnicates = true"), "p.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("no known predicate");
        assertThatThrownBy(() ->
                        PluginManifests.parse(base.formatted("native-declared = true, kotlin-project = true"), "p.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("more than one predicate");
    }
}
