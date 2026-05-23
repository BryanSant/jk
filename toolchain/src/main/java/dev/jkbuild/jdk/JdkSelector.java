// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Picks the best {@link JdkCatalog.Entry} for a {@link JdkSpec} on a
 * given host. Strategy:
 *
 * <ol>
 *   <li>Filter to entries whose {@link JdkCatalog.Entry#os() os} and
 *       {@link JdkCatalog.Entry#arch() arch} match the host.</li>
 *   <li>Keep entries where the spec matches the entry's
 *       {@code shared_index_aliases} or {@code suggested_sdk_name}
 *       (case-insensitive).</li>
 *   <li>For bare-version specs ({@code 21}), keep entries flagged
 *       {@code default: true} when any exist; otherwise keep all
 *       matches.</li>
 *   <li>Prefer non-preview; then highest {@code jdk_version}.</li>
 * </ol>
 */
public final class JdkSelector {

    private JdkSelector() {}

    public static Optional<JdkCatalog.Entry> select(
            JdkCatalog catalog, JdkSpec spec, String os, String arch) {
        String token = spec.normalized();
        List<JdkCatalog.Entry> matches = new ArrayList<>();
        for (JdkCatalog.Entry entry : catalog.entries()) {
            if (!entry.os().equals(os)) continue;
            if (!entry.arch().equals(arch)) continue;
            if (!matchesSpec(entry, token)) continue;
            matches.add(entry);
        }
        if (matches.isEmpty()) return Optional.empty();

        if (spec.bareVersion()) {
            List<JdkCatalog.Entry> defaults = new ArrayList<>();
            for (JdkCatalog.Entry entry : matches) {
                if (entry.defaultForMajor()) defaults.add(entry);
            }
            if (!defaults.isEmpty()) matches = defaults;
        }

        matches.sort(Comparator
                .comparing(JdkCatalog.Entry::preview)
                .thenComparing((JdkCatalog.Entry e) -> versionKey(e.version()),
                        Comparator.reverseOrder()));
        return Optional.of(matches.getFirst());
    }

    private static boolean matchesSpec(JdkCatalog.Entry entry, String token) {
        if (entry.suggestedSdkName().equalsIgnoreCase(token)) return true;
        for (String alias : entry.aliases()) {
            if (alias.equalsIgnoreCase(token)) return true;
        }
        return false;
    }

    /**
     * Sortable key for a JDK version string. Splits on {@code .}, {@code +},
     * {@code -}, pads numeric parts to a fixed width so lexicographic
     * comparison agrees with numeric ordering ({@code 21.0.9} &lt; {@code 21.0.10}).
     */
    static String versionKey(String version) {
        if (version == null) return "";
        StringBuilder sb = new StringBuilder();
        StringBuilder run = new StringBuilder();
        boolean numericRun = false;
        for (int i = 0; i <= version.length(); i++) {
            char c = i < version.length() ? version.charAt(i) : '.';
            boolean isDigit = c >= '0' && c <= '9';
            if (isDigit) {
                if (!numericRun && run.length() > 0) {
                    sb.append(run).append('|');
                    run.setLength(0);
                }
                numericRun = true;
                run.append(c);
            } else {
                if (numericRun) {
                    sb.append(pad(run.toString())).append('|');
                    run.setLength(0);
                }
                numericRun = false;
                if (i < version.length()) run.append(c);
            }
        }
        if (run.length() > 0) sb.append(run);
        return sb.toString();
    }

    private static String pad(String numeric) {
        // 10-digit pad covers anything realistic.
        int needed = 10 - numeric.length();
        if (needed <= 0) return numeric;
        StringBuilder out = new StringBuilder(10);
        for (int i = 0; i < needed; i++) out.append('0');
        out.append(numeric);
        return out.toString();
    }
}
