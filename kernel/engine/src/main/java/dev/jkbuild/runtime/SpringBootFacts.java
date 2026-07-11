// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.PluginConfig;
import java.util.List;

/**
 * Typed reads over the {@code [spring-boot]} plugin config (build-plugins plan P1): the schema
 * lives in the built-in {@code spring-boot.jk-plugin.toml} manifest; this helper is the one
 * place engine code spells the key names. P3 dissolves it into the spring-boot plugin worker
 * (steps/packagers read their own config).
 */
public final class SpringBootFacts {

    private SpringBootFacts() {}

    /** The declared config; caller has already gated on {@link JkBuild#isSpringBoot()}. */
    public static PluginConfig of(JkBuild project) {
        return project.pluginConfig(JkBuild.SPRING_BOOT_ID).orElseThrow();
    }

    public static String version(PluginConfig boot) {
        return boot.string("version");
    }

    /** Explicit {@code aot} wins; unset defaults to whether {@code [native]} is declared. */
    public static boolean aotEnabled(PluginConfig boot, boolean nativeDeclared) {
        return boot.bool("aot").orElse(nativeDeclared);
    }

    public static boolean buildInfo(PluginConfig boot) {
        return boot.bool("build-info", false);
    }

    public static boolean includeTools(PluginConfig boot) {
        return boot.bool("include-tools", true);
    }

    public static List<String> aotArgs(PluginConfig boot) {
        return boot.stringList("aot-args");
    }
}
