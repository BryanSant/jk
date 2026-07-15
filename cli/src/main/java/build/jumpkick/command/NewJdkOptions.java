// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.jdk.JdkHit;
import build.jumpkick.jdk.JdkRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Enumerates every JDK installed on the host for the {@code jk init} wizard's "Select a JDK" step.
 * Sourced from {@link JdkRegistry#listHits()}, which runs the full probe chain (jk's managed dir,
 * env vars, sdkman, jbang, mise, asdf, jenv, homebrew, system paths) and deduplicates by canonical
 * home path.
 *
 * <p>Each {@link Option} carries a parsed {@code major} version so the scaffolder can decide
 * whether to emit Java 25's instance-{@code main} syntax.
 */
public final class NewJdkOptions {

    public record Option(String id, String label, Path home, int major, String source) {
        public Option {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(home, "home");
            Objects.requireNonNull(source, "source");
        }
    }

    private NewJdkOptions() {}

    /** Discover every installed JDK, ordered newest-major first. */
    public static List<Option> discover() {
        var registry = new JdkRegistry();
        return discover(registry, registry.listHits());
    }

    /** Package-private overload for tests: caller injects the data sources. */
    static List<Option> discover(JdkRegistry registry, List<JdkHit> discovered) {
        // Canonicalize every home path before keying the dedup map. Two probes
        // pointing at the same install via different symlinks otherwise show
        // up twice.
        var byCanon = new LinkedHashMap<Path, Option>();

        // jk-managed installs first — they're the "primary" source for jk
        // users so they sort to the top within their major group.
        try {
            for (var jdk : registry.list()) {
                var id = jdk.identifier();
                var home = canonicalize(jdk.home());
                int major = parseMajorFromIdentifier(id)
                        .orElseGet(() -> readReleaseMajor(jdk.home()).orElse(0));
                byCanon.putIfAbsent(home, new Option(id, labelFor(id, major), home, major, "jk"));
            }
        } catch (IOException ignored) {
            // best-effort
        }

        // Then external probes (env, sdkman, jbang, asdf, jenv, brew, system).
        for (var hit : discovered) {
            var home = canonicalize(hit.home());
            if (byCanon.containsKey(home)) continue;
            int major = parseMajor(hit.version())
                    .orElseGet(() -> readReleaseMajor(hit.home()).orElse(0));
            var id = idFor(hit, major);
            byCanon.putIfAbsent(home, new Option(id, labelFor(hit, major), home, major, hit.source()));
        }

        // Highest major first; within a major, the jk-managed install sorts
        // ahead of external sources via a "jk < anything" comparator.
        return byCanon.values().stream()
                .sorted(Comparator.comparingInt(Option::major)
                        .reversed()
                        .thenComparing(o -> "jk".equals(o.source()) ? 0 : 1)
                        .thenComparing(Option::label))
                .toList();
    }

    /**
     * Parse the JDK major-version digit from a JetBrains-feed install folder name like {@code
     * temurin-25.0.1} or {@code corretto-21}. Returns empty when the identifier has no recognizable
     * digit cluster.
     */
    static java.util.Optional<Integer> parseMajorFromIdentifier(String identifier) {
        int dash = identifier.lastIndexOf('-');
        if (dash < 0 || dash == identifier.length() - 1) return java.util.Optional.empty();
        return parseMajor(identifier.substring(dash + 1));
    }

    /**
     * Parse the leading major version out of a Java version string. Handles both the JDK 9+ form
     * ({@code 25.0.1} → 25) and the legacy JDK 8 form ({@code 1.8.0_412} → 8).
     */
    static java.util.Optional<Integer> parseMajor(String version) {
        if (version == null || version.isEmpty()) return java.util.Optional.empty();
        var head = version;
        int dot = head.indexOf('.');
        var first = dot > 0 ? head.substring(0, dot) : head;
        // Strip non-digits trailing the first segment (some sources stuff "ea"/"-ea" on).
        var clean = new StringBuilder();
        for (int i = 0; i < first.length(); i++) {
            char c = first.charAt(i);
            if (Character.isDigit(c)) clean.append(c);
            else break;
        }
        if (clean.length() == 0) return java.util.Optional.empty();
        int n = Integer.parseInt(clean.toString());
        if (n != 1) return java.util.Optional.of(n);
        // Legacy 1.x → x is the major.
        if (dot < 0) return java.util.Optional.of(1);
        var rest = version.substring(dot + 1);
        int dot2 = rest.indexOf('.');
        var second = dot2 > 0 ? rest.substring(0, dot2) : rest;
        var sb2 = new StringBuilder();
        for (int i = 0; i < second.length(); i++) {
            char c = second.charAt(i);
            if (Character.isDigit(c)) sb2.append(c);
            else break;
        }
        return sb2.length() == 0 ? java.util.Optional.of(1) : java.util.Optional.of(Integer.parseInt(sb2.toString()));
    }

    /** Fallback that reads {@code $JAVA_HOME/release} when other sources don't pin a version. */
    static java.util.Optional<Integer> readReleaseMajor(Path home) {
        var release = home.resolve("release");
        if (!java.nio.file.Files.isRegularFile(release)) return java.util.Optional.empty();
        try {
            for (var line : java.nio.file.Files.readAllLines(release)) {
                var trimmed = line.trim();
                if (!trimmed.startsWith("JAVA_VERSION=")) continue;
                var raw = trimmed.substring("JAVA_VERSION=".length()).trim();
                if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
                    raw = raw.substring(1, raw.length() - 1);
                }
                return parseMajor(raw);
            }
        } catch (IOException ignored) {
            // best-effort
        }
        return java.util.Optional.empty();
    }

    private static Path canonicalize(Path home) {
        try {
            return home.toRealPath();
        } catch (IOException ignored) {
            return home.toAbsolutePath().normalize();
        }
    }

    private static String labelFor(String identifier, int major) {
        return major > 0 ? identifier + "  (JDK " + major + ")" : identifier;
    }

    private static String labelFor(JdkHit hit, int major) {
        var vendorPart = vendorLabel(hit);
        var ver = hit.version();
        var head = (vendorPart.isEmpty() ? "" : vendorPart + " ") + (ver == null ? "?" : ver);
        var sourceTag = "  [" + hit.source() + "]";
        return major > 0 ? head + sourceTag : head + sourceTag;
    }

    private static String vendorLabel(JdkHit hit) {
        var v = hit.vendor();
        if (v == null) return "";
        var name = v.name();
        // Enum names like TEMURIN, ORACLE_GRAALVM → "Temurin", "Oracle Graalvm".
        if (name.equals("UNKNOWN")) return "";
        var parts = new ArrayList<String>();
        for (var token : name.split("_")) {
            if (token.isEmpty()) continue;
            parts.add(token.charAt(0) + token.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return String.join(" ", parts);
    }

    /**
     * Stable id for a discovered (non-jk) JDK. Used as the wizard answer key; we ultimately ignore it
     * in favor of the {@code major} when writing {@code jk.toml}, but a unique id is required by the
     * radio step so each row is selectable.
     */
    private static String idFor(JdkHit hit, int major) {
        // home path is stable and unique by construction (deduped above).
        return hit.home().toString();
    }
}
