// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeSet;

/**
 * Resolves the keyword specs ({@code lts}, {@code stable}, {@code latest}, {@code native}) into a
 * concrete vendor-qualified {@code <vendor>-<major>} spec string, driven by whichever entries the
 * JetBrains JDK feed actually publishes for the host.
 *
 * <p>Shared by {@code jk jdk install} and {@code jk jdk ensure} so the "what does lts/latest/native
 * mean right now" answer lives in one place.
 *
 * <ul>
 *   <li>{@code lts} / {@code stable} / {@code latest} → {@code temurin-<major>}. The Temurin bias
 *       matches the "default JDK" mental model; if Temurin isn't shipped at that major the {@link
 *       JdkSelector} flexible fallback picks the catalog's default-for-major.
 *   <li>{@code native} → the latest <b>Oracle GraalVM</b> ({@code graalvm-jdk-<major>}), the
 *       native-image-capable build.
 * </ul>
 */
public final class JdkKeywords {

    private JdkKeywords() {}

    /** The {@code native} keyword — installs the latest Oracle GraalVM. */
    private static final String NATIVE = "native";

    /** Recognised keyword spec, or empty when {@code raw} is a normal version spec. */
    public static boolean isKeyword(String raw) {
        if (raw == null) return false;
        var norm = raw.trim().toLowerCase(Locale.ROOT);
        return norm.equals("lts") || norm.equals("stable") || norm.equals("latest") || norm.equals(NATIVE);
    }

    /**
     * Translate a keyword into a concrete vendor-qualified spec for the host. Returns empty when:
     *
     * <ul>
     *   <li>{@code raw} is not a keyword (the caller treats it as a normal denormalized spec),
     *   <li>the feed publishes no (non-preview) majors for this host,
     *   <li>{@code lts}/{@code stable} was asked for but no LTS major is present, or
     *   <li>{@code native} was asked for but the feed has no Oracle GraalVM for this host.
     * </ul>
     */
    public static Optional<String> resolveToMajorSpec(JdkCatalog catalog, String raw, String os, String arch) {
        var norm = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (norm.equals(NATIVE)) return latestOracleGraalVm(catalog, os, arch);

        boolean wantLts = norm.equals("lts") || norm.equals("stable");
        boolean wantLatest = norm.equals("latest");
        if (!wantLts && !wantLatest) return Optional.empty();

        var majors = new TreeSet<Integer>();
        for (JdkCatalog.Entry e : catalog.entries()) {
            if (e.preview()) continue;
            if (!e.os().equals(os) || !e.arch().equals(arch)) continue;
            majors.add(e.majorVersion());
        }
        if (majors.isEmpty()) return Optional.empty();

        int picked;
        if (wantLatest) {
            picked = majors.last();
        } else {
            OptionalInt lts = JdkLts.latestLtsIn(majors);
            if (lts.isEmpty()) return Optional.empty();
            picked = lts.getAsInt();
        }
        return Optional.of("temurin-" + picked);
    }

    /**
     * Vendor hints an <em>installed</em> JDK must satisfy for the keyword to be considered already
     * met. {@code native} requires a GraalVM; {@code lts} / {@code latest} are vendor-agnostic (any
     * vendor's current release counts), so they return an empty list.
     */
    public static List<String> satisfactionHints(String raw) {
        return raw != null && raw.trim().equalsIgnoreCase(NATIVE) ? List.of("graalvm") : List.of();
    }

    /**
     * Resolve a keyword against an already-installed set of JDK hits — no catalog or network access
     * needed. Returns the best match:
     *
     * <ul>
     *   <li>{@code lts}/{@code stable}: newest installed LTS major, Temurin preferred.
     *   <li>{@code latest}: newest installed major of any vendor, Temurin preferred.
     * </ul>
     *
     * Returns empty for the {@code native} keyword (use {@link #resolveToMajorSpec} for that) or when
     * no hits qualify.
     */
    public static Optional<JdkHit> bestInstalledMatch(String keyword, List<JdkHit> hits) {
        if (!isKeyword(keyword) || keyword.trim().equalsIgnoreCase(NATIVE)) return Optional.empty();
        boolean wantLts =
                keyword.trim().equalsIgnoreCase("lts") || keyword.trim().equalsIgnoreCase("stable");
        var candidates = new ArrayList<JdkHit>();
        for (JdkHit h : hits) {
            Integer m = leadingMajor(h.version());
            if (m == null) continue;
            if (wantLts && !JdkLts.isLtsMajor(m)) continue;
            candidates.add(h);
        }
        if (candidates.isEmpty()) return Optional.empty();
        candidates.sort(Comparator.<JdkHit, Integer>comparing(
                        h -> leadingMajor(h.version()) == null ? 0 : leadingMajor(h.version()),
                        Comparator.reverseOrder())
                .thenComparing(h -> h.vendor() == JdkVendor.TEMURIN ? 0 : 1)
                .thenComparing(h -> h.version() == null ? "" : h.version(), Comparator.reverseOrder()));
        return Optional.of(candidates.get(0));
    }

    /**
     * Parse the leading digit sequence of a JDK version string (e.g. {@code "25"} from {@code
     * "25.0.3"}).
     */
    public static Integer leadingMajor(String version) {
        if (version == null || version.isEmpty()) return null;
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) end++;
        if (end == 0) return null;
        try {
            return Integer.parseInt(version.substring(0, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The {@code suggested_sdk_name} of the highest-versioned Oracle GraalVM on the host. */
    private static Optional<String> latestOracleGraalVm(JdkCatalog catalog, String os, String arch) {
        JdkCatalog.Entry best = null;
        for (JdkCatalog.Entry e : catalog.entries()) {
            if (e.preview()) continue;
            if (!e.os().equals(os) || !e.arch().equals(arch)) continue;
            if (!isOracleGraalVm(e)) continue;
            if (best == null
                    || JdkSelector.versionKey(e.version()).compareTo(JdkSelector.versionKey(best.version())) > 0) {
                best = e;
            }
        }
        return best == null ? Optional.empty() : Optional.of(best.suggestedSdkName());
    }

    /**
     * Oracle GraalVM ({@code graalvm-jdk-*}), as opposed to the community {@code graalvm-ce-*} line.
     */
    private static boolean isOracleGraalVm(JdkCatalog.Entry e) {
        return e.vendor().equalsIgnoreCase("Oracle") && e.product().equalsIgnoreCase("GraalVM");
    }
}
