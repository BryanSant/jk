// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver;

import dev.jkbuild.model.VersionSelector;
import dev.jkbuild.resolver.pubgrub.VersionSet;

/**
 * Converts jk's {@link VersionSelector} (caret-by-default semantics from {@code jk.toml}) into the
 * resolver's {@link VersionSet}.
 *
 * <p>v0.1 coverage: Caret and Exact. Tilde and Range fall back to Exact pending proper
 * Maven-version parsing; Latest maps to {@link VersionSet#ALL}. The resolver simply picks the
 * highest matching available version.
 */
public final class VersionSelectors {

    private VersionSelectors() {}

    public static VersionSet toVersionSet(VersionSelector selector) {
        return switch (selector) {
            case VersionSelector.Caret c -> caretRange(c.version());
            case VersionSelector.Exact e -> VersionSet.exact(e.version());
            case VersionSelector.Tilde t -> tildeRange(t.version());
            case VersionSelector.Range r -> parseRange(r.raw());
            case VersionSelector.Latest l -> VersionSet.ALL;
        };
    }

    /**
     * Parse a range expression. Accepts both Maven-bracket syntax ({@code [1.0,2.0)}, {@code [1.0]},
     * {@code (,2.0]}) and a comma-separated comparator list ({@code ">=1.0, <2.0"}).
     */
    public static VersionSet parseRange(String spec) {
        String trimmed = spec.trim();
        if (trimmed.startsWith("[") || trimmed.startsWith("(")) {
            return parseMavenBracketRange(trimmed);
        }
        VersionSet result = VersionSet.ALL;
        for (String part : trimmed.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) continue;
            result = result.intersect(parseComparator(token));
        }
        return result;
    }

    static VersionSet parseMavenBracketRange(String spec) {
        char open = spec.charAt(0);
        char close = spec.charAt(spec.length() - 1);
        if ((open != '[' && open != '(') || (close != ']' && close != ')')) {
            throw new IllegalArgumentException("malformed Maven range: " + spec);
        }
        boolean minInclusive = open == '[';
        boolean maxInclusive = close == ']';
        String inner = spec.substring(1, spec.length() - 1);
        int comma = inner.indexOf(',');
        if (comma < 0) {
            String only = inner.trim();
            if (only.isEmpty()) return VersionSet.ALL;
            return VersionSet.exact(only);
        }
        String minStr = inner.substring(0, comma).trim();
        String maxStr = inner.substring(comma + 1).trim();
        String min = minStr.isEmpty() ? null : minStr;
        String max = maxStr.isEmpty() ? null : maxStr;
        if (min == null && max == null) return VersionSet.ALL;
        if (min == null) return VersionSet.lessThan(max, maxInclusive);
        if (max == null) return VersionSet.atLeast(min, minInclusive);
        return VersionSet.between(min, minInclusive, max, maxInclusive);
    }

    static VersionSet parseComparator(String spec) {
        if (spec.startsWith(">=")) return VersionSet.atLeast(spec.substring(2).trim(), true);
        if (spec.startsWith(">")) return VersionSet.atLeast(spec.substring(1).trim(), false);
        if (spec.startsWith("<=")) return VersionSet.lessThan(spec.substring(2).trim(), true);
        if (spec.startsWith("<")) return VersionSet.lessThan(spec.substring(1).trim(), false);
        if (spec.startsWith("=")) return VersionSet.exact(spec.substring(1).trim());
        return VersionSet.exact(spec);
    }

    /**
     * Cargo-style caret semantics: increment the leading non-zero segment, zero out everything after
     * it, exclusive upper. {@code 1.2.3 → [1.2.3, 2.0.0)}, {@code 0.2.3 → [0.2.3, 0.3.0)}, {@code
     * 0.0.3 → [0.0.3, 0.0.4)}. Non-numeric versions fall back to an exact match.
     */
    static VersionSet caretRange(String version) {
        String[] parts = version.split("\\.");
        int leading = 0;
        while (leading < parts.length && parts[leading].equals("0")) leading++;
        if (leading >= parts.length) return VersionSet.exact(version);
        try {
            int n = Integer.parseInt(parts[leading]);
            StringBuilder upper = new StringBuilder();
            for (int i = 0; i < leading; i++) upper.append("0.");
            upper.append(n + 1);
            for (int i = leading + 1; i < parts.length; i++) upper.append(".0");
            return VersionSet.between(version, true, upper.toString(), false);
        } catch (NumberFormatException e) {
            return VersionSet.exact(version);
        }
    }

    /**
     * Cargo-style tilde semantics: lock the major (and minor, if present), allow patches. {@code
     * ~1.2.3 → [1.2.3, 1.3.0)}, {@code ~1.2 → [1.2, 1.3.0)}, {@code ~1 → [1, 2.0.0)}.
     */
    static VersionSet tildeRange(String version) {
        String[] parts = version.split("\\.");
        try {
            if (parts.length == 1) {
                int n = Integer.parseInt(parts[0]);
                return VersionSet.between(version, true, String.valueOf(n + 1), false);
            }
            int minor = Integer.parseInt(parts[1]);
            StringBuilder upper = new StringBuilder(parts[0]).append('.').append(minor + 1);
            for (int i = 2; i < parts.length; i++) upper.append(".0");
            return VersionSet.between(version, true, upper.toString(), false);
        } catch (NumberFormatException e) {
            return VersionSet.exact(version);
        }
    }
}
