// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.PluginConfig;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code ${…}} interpolation for manifest contributions (plan §3.1): {@code ${config.<key>}}
 * plus a handful of well-known values — {@code ${kotlin.version}}, {@code ${project.group}},
 * {@code ${project.name}}, {@code ${project.version}}, {@code ${host.os}} (per-OS native-tool
 * classifiers: linux/osx/windows), {@code ${host.os-arch}} (protoc-style: linux-x86_64,
 * osx-aarch_64, …). Deliberately closed: an unknown variable
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
                case "kotlin.version", "project.group", "project.name", "project.version", "host.os",
                        "host.os-arch" -> {}
                default ->
                    throw new JkBuildParseException(where + " references unknown variable ${" + var
                            + "} (known: config.<schema-key>, kotlin.version, project.group, project.name,"
                            + " project.version, host.os, host.os-arch)");
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

    /**
     * The host OS+arch in protoc's classifier vocabulary ({@code linux-x86_64} /
     * {@code osx-aarch_64} / {@code windows-x86_64} …) — the de-facto convention for native
     * binaries published as Maven artifacts (protoc, protoc-gen-grpc-java, netty-tcnative).
     */
    static String hostOsArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        String normalized = switch (arch) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch_64";
            case "ppc64le" -> "ppcle_64";
            case "s390x" -> "s390_64";
            default -> arch;
        };
        return hostOs() + "-" + normalized;
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
                    case "host.os-arch" -> hostOsArch();
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
