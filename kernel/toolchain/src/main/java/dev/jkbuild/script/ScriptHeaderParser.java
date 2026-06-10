// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.script;

import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.VersionSelector;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Scans a script's leading comment block for jk and JBang directives
 * (PRD §19.1 / §19.2).
 *
 * <p>Recognised forms:
 * <ul>
 *   <li>{@code //jk dep group:artifact:version} — pinned, single coord per line.</li>
 *   <li>{@code //jk dep group:artifact@version} — floating constraint
 *       (preferred); decorations like {@code @^1.2}, {@code @~1.2.3},
 *       {@code @>=1,<2}, {@code @latest} are honored.</li>
 *   <li>{@code //jk jdk N} — JDK release (e.g. {@code //jk jdk 21}).</li>
 *   <li>{@code //jk repo https://...} — add a Maven repository.</li>
 *   <li>{@code //jk feature name} — enable a feature.</li>
 *   <li>{@code //jk javac-options ...} / {@code //jk java-options ...}.</li>
 *   <li>{@code //DEPS g:a:v g2:a2@v2 ...} — JBang multi-dep line; both
 *       separators accepted.</li>
 *   <li>{@code //JAVA 21} — JBang JDK selector.</li>
 *   <li>{@code //JAVAC_OPTIONS -parameters --enable-preview}.</li>
 *   <li>{@code //JAVA_OPTIONS -Xmx512m}.</li>
 *   <li>{@code //SOURCES Other.java [More.java ...]}.</li>
 * </ul>
 *
 * <p>Parsing stops at the first non-blank, non-comment line — the
 * compilation body starts there. A {@code ///usr/bin/env jk run "$0"; exit $?}
 * shebang shim on line 1 is silently skipped.
 */
public final class ScriptHeaderParser {

    private static final Pattern JK_DIRECTIVE = Pattern.compile("//jk\\s+([a-z][a-z-]*)\\s*(.*)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ScriptHeaderParser() {}

    public static ScriptHeader parse(String script) {
        List<Dependency> deps = new ArrayList<>();
        List<URI> repos = new ArrayList<>();
        List<String> features = new ArrayList<>();
        List<String> javacOptions = new ArrayList<>();
        List<String> javaOptions = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        Integer release = null;
        String kotlinVersion = null;

        for (String rawLine : script.split("\\R", -1)) {
            String line = rawLine.strip();
            // Shebang shim: ///usr/bin/env jk run "$0"; exit $?
            if (line.startsWith("///")) continue;
            if (line.isEmpty()) continue;
            if (line.startsWith("//")) {
                ParsedDirective d = parseDirective(line);
                if (d != null) {
                    switch (d.name) {
                        case "dep" -> deps.add(parseDep(d.value));
                        case "jdk", "java" -> release = parseJdk(d.value, release);
                        case "repo" -> repos.add(URI.create(d.value.trim()));
                        case "feature" -> features.add(d.value.trim());
                        case "javac-options" -> javacOptions.addAll(splitArgs(d.value));
                        case "java-options" -> javaOptions.addAll(splitArgs(d.value));
                        case "source", "sources" -> sources.addAll(splitArgs(d.value));
                        case "deps" -> {
                            for (String coord : splitArgs(d.value)) {
                                deps.add(parseDep(coord));
                            }
                        }
                        case "javac_options" -> javacOptions.addAll(splitArgs(d.value));
                        case "java_options" -> javaOptions.addAll(splitArgs(d.value));
                        case "kotlin" -> {
                            String v = d.value.trim();
                            if (!v.isEmpty()) kotlinVersion = v;
                        }
                        default -> { /* unknown directive — ignore for forward-compat */ }
                    }
                }
                continue;
            }
            // Non-comment, non-empty: body has started.
            break;
        }

        return new ScriptHeader(deps, release, repos, features,
                javacOptions, javaOptions, sources, kotlinVersion);
    }

    private static ParsedDirective parseDirective(String line) {
        // jk-style: `//jk <name> <value>`
        var m = JK_DIRECTIVE.matcher(line);
        if (m.matches()) {
            return new ParsedDirective(m.group(1), m.group(2));
        }
        // JBang-style: `//DEPS ...`, `//JAVA ...`, ...
        if (line.startsWith("//") && line.length() > 2 && Character.isUpperCase(line.charAt(2))) {
            int spaceIdx = -1;
            for (int i = 2; i < line.length(); i++) {
                if (Character.isWhitespace(line.charAt(i))) { spaceIdx = i; break; }
            }
            String name = (spaceIdx < 0 ? line.substring(2) : line.substring(2, spaceIdx))
                    .toLowerCase();
            String value = spaceIdx < 0 ? "" : line.substring(spaceIdx + 1).trim();
            return new ParsedDirective(name, value);
        }
        return null;
    }

    private static Dependency parseDep(String coord) {
        String spec = coord.trim();
        int firstColon = spec.indexOf(':');
        if (firstColon < 0) {
            throw new IllegalArgumentException(
                    "script dependency must be `group:artifact:version` or "
                            + "`group:artifact@version`, got: " + coord);
        }
        int nextColon = spec.indexOf(':', firstColon + 1);
        int atSign = spec.indexOf('@', firstColon + 1);

        String module;
        String versionPart;
        boolean floating;

        if (nextColon < 0 && atSign < 0) {
            throw new IllegalArgumentException(
                    "script dependency must include a version, got: " + coord);
        } else if (atSign >= 0 && (nextColon < 0 || atSign < nextColon)) {
            module = spec.substring(0, atSign);
            versionPart = spec.substring(atSign + 1);
            floating = true;
        } else {
            module = spec.substring(0, nextColon);
            versionPart = spec.substring(nextColon + 1);
            floating = false;
        }

        if (versionPart.isBlank()) {
            throw new IllegalArgumentException(
                    "script dependency has empty version: " + coord);
        }

        VersionSelector selector;
        if (floating) {
            selector = VersionSelector.parseFloating(versionPart);
        } else {
            String trimmed = versionPart.trim();
            if (trimmed.startsWith("^") || trimmed.startsWith("~")
                    || trimmed.startsWith(">") || trimmed.startsWith("<")
                    || trimmed.contains(",") || "latest".equalsIgnoreCase(trimmed)) {
                throw new IllegalArgumentException(
                        coord + " — the `:` form is for pinned versions only. "
                                + "Use `" + module + "@" + versionPart + "` for a floating constraint.");
            }
            selector = VersionSelector.parse(versionPart);
        }
        return new Dependency(module, selector, !floating);
    }

    private static Integer parseJdk(String raw, Integer existing) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return existing;
        // Strip a trailing "-tem" / "-graalce" etc. vendor suffix and any
        // leading "1." vestige from Java 8 days.
        String version = trimmed;
        int dash = version.indexOf('-');
        if (dash > 0) version = version.substring(0, dash);
        if (version.startsWith("1.")) version = version.substring(2);
        // Take just the major component.
        int dot = version.indexOf('.');
        if (dot > 0) version = version.substring(0, dot);
        try {
            return Integer.parseInt(version);
        } catch (NumberFormatException e) {
            // Ignore — silently leave existing setting in place.
            return existing;
        }
    }

    private static List<String> splitArgs(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return List.of();
        return List.of(WHITESPACE.split(trimmed));
    }

    private record ParsedDirective(String name, String value) {}
}
