// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.plugin.PluginConfig;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;

class PluginTableRegistryTest {

    @Test
    void built_in_spring_boot_manifest_loads_and_owns_its_table() {
        var manifest = PluginTableRegistry.byTable("spring-boot").orElseThrow();
        assertThat(manifest.id()).isEqualTo("spring-boot");
        assertThat(manifest.schema()).containsKeys("version", "aot", "build-info", "include-tools", "aot-args");
        assertThat(manifest.schema().get("version").required()).isTrue();
        assertThat(manifest.schema().get("aot").defaultValue()).isNull(); // tri-state
        assertThat(PluginTableRegistry.byTable("micronaut")).isEmpty();
    }

    @Test
    void validate_applies_defaults_and_keeps_tristate_absent() {
        var manifest = PluginTableRegistry.byTable("spring-boot").orElseThrow();
        var table = Toml.parse("version = \"4.0.0\"");
        PluginConfig config = PluginTableRegistry.validate(manifest, table);
        assertThat(config.string("version")).isEqualTo("4.0.0");
        assertThat(config.bool("aot")).isEmpty();
        assertThat(config.bool("build-info", true)).isFalse(); // schema default false wins
        assertThat(config.bool("include-tools", false)).isTrue(); // schema default true wins
        assertThat(config.stringList("aot-args")).isEmpty();
    }

    @Test
    void validate_enforces_required_with_example_and_hint() {
        var manifest = PluginTableRegistry.byTable("spring-boot").orElseThrow();
        assertThatThrownBy(() -> PluginTableRegistry.validate(manifest, Toml.parse("build-info = true")))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[spring-boot].version is required")
                .hasMessageContaining("e.g. version = \"4.0.0\"")
                .hasMessageContaining("BOM, loader, and AOT tooling");
    }

    @Test
    void validate_rejects_wrongly_typed_values() {
        var manifest = PluginTableRegistry.byTable("spring-boot").orElseThrow();
        assertThatThrownBy(() ->
                        PluginTableRegistry.validate(manifest, Toml.parse("version = \"4.0.0\"\naot-args = [1, 2]")))
                .hasMessageContaining("[spring-boot].aot-args must be an array of strings");
        assertThatThrownBy(
                        () -> PluginTableRegistry.validate(manifest, Toml.parse("version = \"4.0.0\"\naot = \"yes\"")))
                .hasMessageContaining("[spring-boot].aot must be a boolean");
    }

    @Test
    void manifest_parser_rejects_bad_manifests() {
        assertThatThrownBy(() -> PluginDescriptors.parse("[schema]\nx = { type = \"string\" }", "p.toml"))
                .hasMessageContaining("missing the required [plugin] table");
        assertThatThrownBy(() -> PluginDescriptors.parse(
                        "[plugin]\nid = \"x\"\ntable = \"x\"\n[schema]\nk = { type = \"nope\" }", "p.toml"))
                .hasMessageContaining("unknown schema type");
    }

    @org.junit.jupiter.api.Test
    void shipped_manifest_declares_scaffold_and_import_rules() {
        var boot = PluginTableRegistry.manifests().stream()
                .filter(m -> m.id().equals("spring-boot"))
                .findFirst()
                .orElseThrow();

        var scaffold = boot.scaffold();
        org.assertj.core.api.Assertions.assertThat(scaffold.flag()).isEqualTo("spring");
        org.assertj.core.api.Assertions.assertThat(scaffold.appends()).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(scaffold.files())
                .anyMatch(f -> f.path().contains("Application.java") && "java".equals(f.whenLang()))
                .anyMatch(f -> f.path().contains("Application.kt") && "kotlin".equals(f.whenLang()))
                .anyMatch(f -> f.path().endsWith("application.properties") && f.keepExisting());
        // every referenced template resource resolves
        for (var a : scaffold.appends()) {
            org.assertj.core.api.Assertions.assertThat(PluginTableRegistry.resourceText(boot, a.template()))
                    .contains("[spring-boot]");
        }
        for (var f : scaffold.files()) {
            org.assertj.core.api.Assertions.assertThat(PluginTableRegistry.resourceText(boot, f.template()))
                    .isNotBlank();
        }

        org.assertj.core.api.Assertions.assertThat(boot.gradleImports())
                .anyMatch(r -> r.id().equals("org.springframework.boot")
                        && "version".equals(r.versionTo())
                        && r.missingVersionWarning() != null)
                .anyMatch(r -> r.id().equals("io.spring.dependency-management") && r.versionTo() == null);
    }
}
