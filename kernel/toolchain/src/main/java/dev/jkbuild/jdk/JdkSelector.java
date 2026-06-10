// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

    /**
     * Default vendor for specs that don't name one. jk's mental model is "the
     * default JDK is Eclipse Temurin" — see {@link #selectPreferred}.
     */
    private static final String PREFERRED_VENDOR = "temurin";

    /**
     * {@link #select} with jk's default-vendor bias: when {@code rawSpec} names
     * no vendor (a bare major / version like {@code 26} or {@code 25.0.3}),
     * Eclipse Temurin is preferred over the feed's {@code default:true}
     * entry-for-major. Falls back to the unbiased {@link #select} when no
     * Temurin entry satisfies the spec on this host (or when the spec already
     * names a vendor, e.g. {@code corretto-25} / {@code temurin-25}).
     *
     * <p>This is the entry point every <em>install</em> path should use so the
     * Temurin bias is consistent across {@code jk jdk install <ver>},
     * {@code jk jdk ensure <ver>}, and the build pipeline's auto-install — the
     * keyword path ({@code lts}/{@code latest}) is already Temurin-biased via
     * {@link JdkKeywords#resolveToMajorSpec}.
     */
    public static Optional<JdkCatalog.Entry> selectPreferred(
            JdkCatalog catalog, String rawSpec, String os, String arch) {
        if (rawSpec == null || rawSpec.isBlank()) return Optional.empty();
        FlexibleQuery q = parseFlexible(rawSpec);
        // Bias only when the user named no vendor AND gave a concrete major/
        // version (so plain hints like "graal" aren't forced onto Temurin).
        if (q.hints().isEmpty() && q.major().isPresent()) {
            Optional<JdkCatalog.Entry> preferred = select(
                    catalog, JdkSpec.parse(PREFERRED_VENDOR + "-" + rawSpec.trim()), os, arch);
            if (preferred.isPresent()) return preferred;
        }
        return select(catalog, JdkSpec.parse(rawSpec), os, arch);
    }

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
        if (matches.isEmpty()) {
            // Strict alias / suggested-sdk-name match failed. Fall through to
            // the flexible parser so callers don't have to know the exact feed
            // vocabulary — `25-graal`, `java-17-openjdk`, `temurin-25` all
            // resolve here.
            return selectFlexible(catalog, spec.value(), os, arch);
        }

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

    /**
     * Permissive selector: parses denormalized identifiers like {@code 25-graal},
     * {@code temurin-25}, {@code 17.0.19}, {@code java-17-openjdk},
     * {@code corretto-21} into a {@code (major, exactVersion?, hints[])} tuple
     * and picks the highest-ranked entry whose feed metadata satisfies it.
     *
     * <p>Scoring: each token is worth one point if it matches the entry's
     * vendor / product / suggestedSdkName / alias (case-insensitive); exact
     * version match adds a heavy weight; default-for-major wins ties; latest
     * version breaks remaining ties. Returns empty if nothing satisfies the
     * version constraint on the target host.
     */
    public static Optional<JdkCatalog.Entry> selectFlexible(
            JdkCatalog catalog, String raw, String os, String arch) {
        var query = parseFlexible(raw);

        List<Scored> scored = new ArrayList<>();
        for (JdkCatalog.Entry entry : catalog.entries()) {
            if (!entry.os().equals(os)) continue;
            if (!entry.arch().equals(arch)) continue;
            // Major (when present) is a hard requirement; otherwise we'd offer
            // a JDK 21 for input "graal" which the user almost certainly didn't
            // ask for. Exact-version is also hard when supplied.
            if (query.major().isPresent() && entry.majorVersion() != query.major().get()) continue;
            if (query.exactVersion().isPresent()
                    && !entry.version().startsWith(query.exactVersion().get())) continue;
            int score = scoreHints(entry, query.hints());
            // Reject entries that satisfy zero hints when the user supplied any
            // — they're meaningfully off-target.
            if (!query.hints().isEmpty() && score == 0) continue;
            scored.add(new Scored(entry, score));
        }
        if (scored.isEmpty()) return Optional.empty();

        scored.sort(Comparator
                // Higher hint score first.
                .comparingInt((Scored s) -> s.score).reversed()
                // Default-for-major preferred when there are no hint scores to
                // separate (esp. bare-major inputs like "25").
                .thenComparing(s -> s.entry.defaultForMajor() ? 0 : 1)
                // Non-preview preferred.
                .thenComparing(s -> s.entry.preview() ? 1 : 0)
                // Highest version wins remaining ties.
                .thenComparing((Scored s) -> versionKey(s.entry.version()),
                        Comparator.reverseOrder()));
        return Optional.of(scored.getFirst().entry);
    }

    /** Outcome of {@link #parseFlexible} — the tokens we extracted from raw input. */
    public record FlexibleQuery(Optional<Integer> major, Optional<String> exactVersion, List<String> hints) {}

    /**
     * Token-walker that handles the denormalized forms callers care about.
     * Splits on {@code -} / {@code _}; numeric-leading tokens become version
     * info, everything else lands in {@code hints}. {@code "java"} is dropped
     * (it's a noise word in inputs like {@code java-17-openjdk}); {@code "jdk"}
     * gets the same treatment.
     */
    public static FlexibleQuery parseFlexible(String raw) {
        if (raw == null) return new FlexibleQuery(Optional.empty(), Optional.empty(), List.of());
        var tokens = raw.toLowerCase(Locale.ROOT).split("[-_]");
        Integer major = null;
        String exact = null;
        var hints = new ArrayList<String>();
        for (var tok : tokens) {
            if (tok.isEmpty()) continue;
            if (Character.isDigit(tok.charAt(0))) {
                int dot = tok.indexOf('.');
                if (dot < 0) {
                    try {
                        int m = Integer.parseInt(tok);
                        if (major == null) major = m;
                    } catch (NumberFormatException ignored) {
                        hints.add(tok);
                    }
                } else {
                    // Dotted: take everything as exact-version prefix, derive major.
                    if (exact == null) exact = tok;
                    try {
                        int m = Integer.parseInt(tok.substring(0, dot));
                        if (major == null) major = m;
                    } catch (NumberFormatException ignored) {}
                }
            } else if (tok.equals("java") || tok.equals("jdk")) {
                // Noise words — they don't disambiguate (every entry is a JDK).
            } else {
                hints.add(tok);
            }
        }
        return new FlexibleQuery(Optional.ofNullable(major), Optional.ofNullable(exact), List.copyOf(hints));
    }

    /**
     * +1 per hint that matches the entry's vendor / product / suggestedSdkName
     * / any alias (case-insensitive substring). Substring rather than equality
     * so {@code "graal"} hits {@code "graalvm-jdk-25"}.
     */
    private static int scoreHints(JdkCatalog.Entry entry, List<String> hints) {
        if (hints.isEmpty()) return 0;
        var haystack = (entry.vendor() + " " + entry.product() + " "
                + entry.suggestedSdkName() + " " + String.join(" ", entry.aliases()))
                .toLowerCase(Locale.ROOT);
        int score = 0;
        for (var h : hints) {
            if (haystack.contains(h)) score++;
        }
        return score;
    }

    private record Scored(JdkCatalog.Entry entry, int score) {}

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
    public static String versionKey(String version) {
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
