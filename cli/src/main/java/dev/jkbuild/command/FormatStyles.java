// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.model.JkBuild;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the effective Java/Kotlin formatter styles {@code jk format} hands to the {@code
 * jk-formatter} worker, plus the cross-language aliases.
 *
 * <p>Style names are the worker's contract (it maps them onto Spotless steps): Java {@code
 * palantir} / {@code google} / {@code aosp}; Kotlin {@code kotlinlang} / {@code google} / {@code
 * meta}. The bare-{@code jk format} default is {@code palantir} + {@code kotlinlang} — both
 * 4-space, 120-col, for a consistent look in mixed Java/Kotlin repos.
 *
 * <p>Per-language precedence: a {@code --java-style}/{@code --kotlin-style} flag wins, then a
 * {@code --style} alias, then {@code format.java}/{@code format.kotlin} in jk.toml, then the {@code
 * format.style} alias, then the built-in default.
 */
final class FormatStyles {

    private FormatStyles() {}

    static final String DEFAULT_JAVA = "palantir";
    static final String DEFAULT_KOTLIN = "kotlinlang";

    static final List<String> JAVA_STYLES = List.of("palantir", "google", "aosp");
    static final List<String> KOTLIN_STYLES = List.of("kotlinlang", "google", "meta");

    /** Cross-language presets: alias → (java, kotlin). */
    private static final Map<String, Resolved> ALIASES = buildAliases();

    private static Map<String, Resolved> buildAliases() {
        var m = new LinkedHashMap<String, Resolved>();
        // Aliases carry style names only; boolean flags are resolved separately.
        m.put("standard", new Resolved("palantir", "kotlinlang", DEFAULT_OPTIMIZE_IMPORTS));
        return Map.copyOf(m);
    }

    static final boolean DEFAULT_OPTIMIZE_IMPORTS = true;

    /** The chosen concrete styles and OpenRewrite flags for each language. */
    record Resolved(String java, String kotlin, boolean optimizeImports) {}

    /**
     * Resolve the effective styles from (CLI flags) + (jk.toml {@code [format]}). {@code cliAlias} is
     * {@code --style}; {@code cliJava}/{@code cliKotlin} are {@code --java-style}/{@code
     * --kotlin-style}. Any of these may be {@code null}.
     *
     * @throws IllegalArgumentException on an unknown style or alias (message is user-facing)
     */
    static Resolved resolve(
            String cliJava, String cliKotlin, String cliAlias, Boolean cliOptimizeImports, JkBuild.FormatConfig cfg) {
        JkBuild.FormatConfig fmt = cfg == null ? JkBuild.FormatConfig.EMPTY : cfg;
        Resolved cliAliasPair = alias(cliAlias, "--style");
        Resolved tomlAliasPair = alias(fmt.style(), "format.style");

        String java = firstNonNull(
                cliJava,
                cliAliasPair == null ? null : cliAliasPair.java(),
                fmt.java(),
                tomlAliasPair == null ? null : tomlAliasPair.java(),
                DEFAULT_JAVA);
        String kotlin = firstNonNull(
                cliKotlin,
                cliAliasPair == null ? null : cliAliasPair.kotlin(),
                fmt.kotlin(),
                tomlAliasPair == null ? null : tomlAliasPair.kotlin(),
                DEFAULT_KOTLIN);

        validate(java, JAVA_STYLES, "java");
        validate(kotlin, KOTLIN_STYLES, "kotlin");

        // Flag precedence: CLI → env var (caller-resolved) → jk.toml → default.
        boolean optimizeImports = firstNonNullBool(cliOptimizeImports, fmt.optimizeImports(), DEFAULT_OPTIMIZE_IMPORTS);

        return new Resolved(java, kotlin, optimizeImports);
    }

    private static boolean firstNonNullBool(Boolean... vals) {
        for (Boolean v : vals) {
            if (v != null) return v;
        }
        return false; // unreachable: last arg is always a non-null default
    }

    private static Resolved alias(String name, String source) {
        if (name == null || name.isBlank()) return null;
        Resolved pair = ALIASES.get(name.trim().toLowerCase(java.util.Locale.ROOT));
        if (pair == null) {
            throw new IllegalArgumentException(source
                    + " = \""
                    + name
                    + "\" is not a known style preset ("
                    + String.join(", ", ALIASES.keySet())
                    + ")");
        }
        return pair;
    }

    private static void validate(String style, List<String> allowed, String lang) {
        if (!allowed.contains(style)) {
            throw new IllegalArgumentException("unknown "
                    + lang
                    + " format style \""
                    + style
                    + "\" — choose one of "
                    + String.join(", ", allowed));
        }
    }

    private static String firstNonNull(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return null; // unreachable: the last arg is always a non-null default
    }
}
