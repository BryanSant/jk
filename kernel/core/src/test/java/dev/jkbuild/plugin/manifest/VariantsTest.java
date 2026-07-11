// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkbuild.config.JkBuildParseException;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.PluginConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Variant-axis overlays, flavor dimensions, signing flatten + the secrets side channel. */
class VariantsTest {

    private static JkBuild parse(Path dir, String androidTail) throws Exception {
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
                """ + androidTail);
        return JkBuildParser.parse(dir.resolve("jk.toml"));
    }

    @Test
    void default_build_type_is_debug_and_injected(@TempDir Path dir) throws Exception {
        JkBuild build = parse(dir, "");
        JkBuild effective = Variants.applyDefaults(build, dir);
        PluginConfig config = effective.pluginConfig("android").orElseThrow();
        assertThat(config.string("build-type")).isEqualTo("debug");
    }

    @Test
    void release_overlay_wins_and_flattens_signing_with_secrets(@TempDir Path dir) throws Exception {
        JkBuild build = parse(dir, """
                [android.build-types.release]
                application-id-suffix = ".rel"
                signing               = "release"

                [android.signing.release]
                store-file     = "/tmp/ks.jks"
                store-password = "env:TEST_STORE_PASSWORD"
                key-alias      = "upload"
                """);
        var applied = Variants.apply(
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
        assertThat(config.values()).doesNotContainKey("build-types");
        assertThat(config.values().get("signing")).isEqualTo("release");
    }

    @Test
    void flavor_dimension_requires_a_selection_and_build_type_beats_flavor(@TempDir Path dir) throws Exception {
        JkBuild build = parse(dir, """
                [android.build-types.release]
                application-id-suffix = ".fromtype"

                [android.flavors.tier]
                free = { application-id-suffix = ".free" }
                paid = { application-id-suffix = ".paid" }
                """);
        assertThatThrownBy(() -> Variants.apply(build, dir, Variants.Selection.parse("release"), Map.of()))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("--flavor");

        var applied = Variants.apply(build, dir, Variants.Selection.parse("release|tier=free"), Map.of());
        PluginConfig config = applied.build().pluginConfig("android").orElseThrow();
        assertThat(config.string("flavor")).isEqualTo("free");
        // AGP precedence: the build type overlays after flavors.
        assertThat(config.string("application-id-suffix")).isEqualTo(".fromtype");
    }

    @Test
    void unknown_build_type_and_unset_env_fail_loudly(@TempDir Path dir) throws Exception {
        JkBuild build = parse(dir, """
                [android.build-types.release]
                signing = "release"

                [android.signing.release]
                store-file = "env:DEFINITELY_NOT_SET_ANYWHERE"
                key-alias  = "upload"
                """);
        assertThatThrownBy(() -> Variants.apply(build, dir, Variants.Selection.parse("staging"), Map.of()))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("staging");
        assertThatThrownBy(() -> Variants.apply(build, dir, Variants.Selection.parse("release"), Map.of()))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("DEFINITELY_NOT_SET_ANYWHERE");
        assertThat(Variants.envRefs(build)).contains("DEFINITELY_NOT_SET_ANYWHERE");
    }
}
