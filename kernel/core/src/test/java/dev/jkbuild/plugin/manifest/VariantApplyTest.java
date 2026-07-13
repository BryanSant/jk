// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkbuild.config.JkBuildParseException;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.plugin.PluginConfig;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.Variants;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Core {@code [variants]} resolution: dimension selection, overlay precedence, extra-src /
 * dependency folds, union lock deps, signing flatten + the secrets side channel.
 */
class VariantApplyTest {

    private static JkBuild parse(Path dir, String tail) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                name    = "app"
                group   = "com.example"
                version = "1.0.0"
                java    = 17

                [android]
                namespace   = "com.example.app"
                compile-sdk = 28
                min-sdk     = 24
                """ + tail);
        return JkBuildParser.parse(dir.resolve("jk.toml"));
    }

    @Test
    void default_build_type_is_debug_and_injected(@TempDir Path dir) throws Exception {
        JkBuild build = parse(dir, "");
        JkBuild effective = VariantApply.applyDefaults(build, dir);
        PluginConfig config = effective.pluginConfig("android").orElseThrow();
        assertThat(config.string("build-type")).isEqualTo("debug");
    }

    @Test
    void release_overlay_wins_and_flattens_signing_with_secrets(@TempDir Path dir) throws Exception {
        JkBuild build = parse(dir, """
                [variants.build-type.release.android]
                application-id-suffix = ".rel"
                signing               = "release"

                [android.signing.release]
                store-file     = "/tmp/ks.jks"
                store-password = "env:TEST_STORE_PASSWORD"
                key-alias      = "upload"
                """);
        var applied = VariantApply.apply(
                build, dir, Variants.Selection.parse("release"), Map.of("TEST_STORE_PASSWORD", "s3cret"));
        PluginConfig config = applied.build().pluginConfig("android").orElseThrow();
        assertThat(config.string("build-type")).isEqualTo("release");
        assertThat(config.string("application-id-suffix")).isEqualTo(".rel");
        assertThat(config.string("signing.store-file")).isEqualTo("/tmp/ks.jks");
        assertThat(config.string("signing.key-alias")).isEqualTo("upload");
        // The secret never enters the config — it rides the side channel, resolved.
        assertThat(config.values()).doesNotContainKey("signing.store-password");
        assertThat(applied.secrets()).containsEntry("signing.store-password", "s3cret");
        // Groups never ride the flat config; the overlay's string REFERENCE legitimately does.
        assertThat(config.values().get("signing")).isEqualTo("release");
    }

    @Test
    void custom_dimension_requires_a_selection_and_build_type_overlays_last(@TempDir Path dir) throws Exception {
        JkBuild build = parse(dir, """
                [variants.build-type.release.android]
                application-id-suffix = ".fromtype"

                [variants.tier.free.android]
                application-id-suffix = ".free"
                [variants.tier.paid.android]
                application-id-suffix = ".paid"
                """);
        assertThatThrownBy(() -> VariantApply.apply(build, dir, Variants.Selection.parse("release"), Map.of()))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("--variant tier=<value>");

        var applied = VariantApply.apply(build, dir, Variants.Selection.parse("release|tier=free"), Map.of());
        PluginConfig config = applied.build().pluginConfig("android").orElseThrow();
        assertThat(config.string("variant.tier")).isEqualTo("free");
        // Precedence: the build type overlays after custom dimensions.
        assertThat(config.string("application-id-suffix")).isEqualTo(".fromtype");
        // Bare-value convenience: one custom dimension, `--variant free` → *=free.
        var bare = VariantApply.apply(build, dir, Variants.Selection.parse("*=paid"), Map.of());
        assertThat(bare.build().pluginConfig("android").orElseThrow().string("variant.tier")).isEqualTo("paid");
    }

    @Test
    void dimension_default_makes_selection_optional(@TempDir Path dir) throws Exception {
        JkBuild build = parse(dir, """
                [variants.tier]
                default = "free"
                [variants.tier.free.android]
                application-id-suffix = ".free"
                [variants.tier.paid.android]
                application-id-suffix = ".paid"
                """);
        JkBuild effective = VariantApply.applyDefaults(build, dir);
        PluginConfig config = effective.pluginConfig("android").orElseThrow();
        assertThat(config.string("variant.tier")).isEqualTo("free");
        assertThat(config.string("application-id-suffix")).isEqualTo(".free");
    }

    @Test
    void extra_src_and_dependency_overlays_fold_for_the_selected_value_only(@TempDir Path dir) throws Exception {
        JkBuild build = parse(dir, """
                [dependencies]
                shared = { group = "com.acme", name = "shared", version = "1.0.0" }

                [variants.tier.free]
                extra-src = ["src/free/java"]
                [variants.tier.free.dependencies]
                ads = { group = "com.acme", name = "ads", version = "2.0.0" }
                [variants.tier.paid]
                extra-src = ["src/paid/java"]
                [variants.tier.paid.dependencies]
                billing = { group = "com.acme", name = "billing", version = "3.0.0" }
                """);
        JkBuild free = VariantApply.apply(build, dir, Variants.Selection.parse("tier=free"), Map.of()).build();
        assertThat(free.build().extraSrc()).containsExactly("src/free/java");
        assertThat(free.dependencies().of(Scope.MAIN))
                .extracting(d -> d.name())
                .containsExactly("shared", "ads");

        // The lock resolves the union: both values' deps, one lockfile for every variant.
        JkBuild union = Variants.unionDependencies(build);
        assertThat(union.dependencies().of(Scope.MAIN))
                .extracting(d -> d.name())
                .containsExactly("shared", "ads", "billing");
        // Union is idempotent (lock paths may fold more than once).
        assertThat(Variants.unionDependencies(union).dependencies().of(Scope.MAIN))
                .hasSameSizeAs(union.dependencies().of(Scope.MAIN));
    }

    @Test
    void conflicting_versions_across_values_fail_the_union_loudly(@TempDir Path dir) throws Exception {
        // The resolver settles duplicate roots by highest-wins, so a silent union would build
        // the "losing" value against the other value's version — fail at lock time instead.
        JkBuild build = parse(dir, """
                [variants.tier.free.dependencies]
                commons-lang3 = { group = "org.apache.commons", name = "commons-lang3", version = "=3.12.0" }
                [variants.tier.paid.dependencies]
                commons-lang3 = { group = "org.apache.commons", name = "commons-lang3", version = "=3.18.0" }
                """);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Variants.unionDependencies(build))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[variants.tier.free]")
                .hasMessageContaining("[variants.tier.paid]")
                .hasMessageContaining("=3.12.0")
                .hasMessageContaining("=3.18.0");
    }

    @Test
    void selection_naming_an_undeclared_dimension_is_ignored(@TempDir Path dir) throws Exception {
        // Workspace-wide selections: a module without the dimension applies only what it declares.
        JkBuild build = parse(dir, "");
        JkBuild effective = VariantApply.apply(
                        build, dir, Variants.Selection.parse("contentType=demo"), Map.of())
                .build();
        PluginConfig config = effective.pluginConfig("android").orElseThrow();
        assertThat(config.values()).doesNotContainKey("variant.contentType");
        assertThat(config.string("build-type")).isEqualTo("debug");
    }

    @Test
    void unknown_build_type_unknown_value_and_unset_env_fail_loudly(@TempDir Path dir) throws Exception {
        JkBuild build = parse(dir, """
                [variants.build-type.release.android]
                signing = "release"

                [variants.tier.free.android]
                debuggable = true

                [android.signing.release]
                store-file = "env:DEFINITELY_NOT_SET_ANYWHERE"
                key-alias  = "upload"
                """);
        assertThatThrownBy(() -> VariantApply.apply(
                        build, dir, Variants.Selection.parse("staging|tier=free"), Map.of()))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("staging");
        assertThatThrownBy(() -> VariantApply.apply(
                        build, dir, Variants.Selection.parse("tier=premium"), Map.of()))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("premium");
        assertThatThrownBy(() -> VariantApply.apply(
                        build, dir, Variants.Selection.parse("release|tier=free"), Map.of()))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("DEFINITELY_NOT_SET_ANYWHERE");
        assertThat(VariantApply.envRefs(build)).contains("DEFINITELY_NOT_SET_ANYWHERE");
    }

    @Test
    void overlay_keys_validate_against_the_plugin_schema(@TempDir Path dir) {
        assertThatThrownBy(() -> parse(dir, """
                [variants.build-type.release.android]
                not-a-real-key = true
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("not-a-real-key");
        assertThatThrownBy(() -> parse(dir, """
                [variants.tier.free]
                mystery-section = { a = 1 }
                """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("mystery-section");
    }
}
