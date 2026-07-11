// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.manifest;

import dev.jkbuild.config.JkBuildParseException;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.PluginConfig;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code ${…}} interpolation for manifest contributions (plan §3.1): {@code ${config.<key>}}
 * plus a handful of well-known values — {@code ${kotlin.version}}, {@code ${project.group}},
 * {@code ${project.name}}, {@code ${project.version}}, {@code ${host.os}} (per-OS native-tool
 * classifiers: linux/osx/windows). Deliberately closed: an unknown variable
 * is a manifest-load error (checked once via {@link #validate}, so a typo fails when the plugin
 * is installed, not mid-build), and there is no nesting or defaulting syntax.
 */
final class Interpolation {

    private static final Pattern VAR = Pattern.compile("\\$\\{([^}]*)}");

    private Interpolation() {}

    /** Manifest-load validation: every referenced variable must exist in the closed vocabulary. */
    static void validate(String template, java.util.Set<String> schemaKeys, String where) {
        Matcher m = VAR.matcher(template);
        while (m.find()) {
            String var = m.group(1);
            if (var.startsWith("config.")) {
                String key = var.substring("config.".length());
                if (!schemaKeys.contains(key)) {
                    throw new JkBuildParseException(
                            where + " references ${" + var + "} but the [schema] declares no `" + key + "`");
                }
                continue;
            }
            switch (var) {
                case "kotlin.version", "project.group", "project.name", "project.version", "host.os" -> {}
                default ->
                    throw new JkBuildParseException(where + " references unknown variable ${" + var
                            + "} (known: config.<schema-key>, kotlin.version, project.group, project.name,"
                            + " project.version, host.os)");
            }
        }
    }

    /**
     * Resolve a validated template. {@code kotlinVersion} may be null on evaluation paths where
     * no Kotlin toolchain exists — referencing {@code ${kotlin.version}} there is an evaluation
     * error (the manifest asked for a value this project cannot supply).
     */
    /**
     * The host OS in the classifier vocabulary per-OS native tools publish under ({@code linux} /
     * {@code osx} / {@code windows} — aapt2's exact set).
     */
    static String hostOs() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) return "osx";
        if (os.contains("win")) return "windows";
        return "linux";
    }

    static String resolve(String template, PluginConfig config, JkBuild.Project project, String kotlinVersion) {
        Matcher m = VAR.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String var = m.group(1);
            String value;
            if (var.startsWith("config.")) {
                String key = var.substring("config.".length());
                Object raw = config.values().get(key);
                if (raw == null) {
                    throw new JkBuildParseException("[" + config.id() + "] contribution needs ${" + var
                            + "} but the table leaves `" + key + "` unset");
                }
                value = String.valueOf(raw);
            } else {
                value = switch (var) {
                    case "kotlin.version" -> kotlinVersion;
                    case "project.group" -> project.group();
                    case "project.name" -> project.name();
                    case "project.version" -> project.version();
                    case "host.os" -> hostOs();
                    default -> null; // unreachable: validate() ran at manifest load
                };
                if (value == null) {
                    throw new JkBuildParseException("[" + config.id() + "] contribution needs ${" + var
                            + "} but this build has no value for it");
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
