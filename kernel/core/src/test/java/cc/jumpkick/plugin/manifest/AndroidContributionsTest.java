// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The shipped android manifest's declarative layer (android-plan §3.5): {@code compose = true}
 * contributes the embeddable Compose compiler plugin, version-locked to the Kotlin compiler via
 * {@code ${kotlin.version}} — pure manifest data, zero new SPI. Off by default.
 */
class AndroidContributionsTest {

    @Test
    void compose_flag_gates_the_compose_compiler_plugin() {
        JkBuild without = android("");
        assertThat(PluginContributions.kotlinPlugins(without, null, "2.3.0", Set.of()))
                .isEmpty();

        JkBuild with = android("compose      = true\n");
        var plugins = PluginContributions.kotlinPlugins(with, null, "2.3.0", Set.of());
        assertThat(plugins).hasSize(1);
        assertThat(plugins.getFirst().artifact()).isEqualTo("kotlin-compose-compiler-plugin-embeddable");
        assertThat(plugins.getFirst().version()).isEqualTo("2.3.0");
    }

    @Test
    void library_variant_swaps_the_packaging_descriptor() {
        var manifest = PluginTableRegistry.byTable("android").orElseThrow();
        var app =
                manifest.packaging().resolve(android("").pluginConfig("android").orElseThrow());
        assertThat(app.execMode()).isEqualTo("device");
        assertThat(app.artifactExtension()).isEqualTo("apk");
        assertThat(app.deployCommand()).isEqualTo("deploy");

        var lib = manifest.packaging()
                .resolve(
                        android("library      = true\n").pluginConfig("android").orElseThrow());
        assertThat(lib.execMode()).isEqualTo("none");
        assertThat(lib.artifactExtension()).isEqualTo("aar");
        assertThat(lib.deployCommand()).isEmpty();
    }

    private static JkBuild android(String extraKeys) {
        return JkBuildParser.parse("""
                [project]
                name    = "demo"
                group   = "com.example"
                version = "1.0.0"
                java    = 17

                [android]
                namespace    = "com.example.demo"
                compile-sdk  = 34
                min-sdk      = 24
                """ + extraKeys);
    }
}
