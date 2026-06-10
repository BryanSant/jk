// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeSet;

/**
 * Resolves the keyword specs ({@code lts}, {@code stable}, {@code latest},
 * {@code native}) into a concrete vendor-qualified {@code <vendor>-<major>}
 * spec string, driven by whichever entries the JetBrains JDK feed actually
 * publishes for the host.
 *
 * <p>Shared by {@code jk jdk install} and {@code jk jdk ensure} so the
 * "what does lts/latest/native mean right now" answer lives in one place.
 *
 * <ul>
 *   <li>{@code lts} / {@code stable} / {@code latest} → {@code temurin-<major>}.
 *       The Temurin bias matches the "default JDK" mental model; if Temurin
 *       isn't shipped at that major the {@link JdkSelector} flexible fallback
 *       picks the catalog's default-for-major.</li>
 *   <li>{@code native} → the latest <b>Oracle GraalVM</b> ({@code graalvm-jdk-<major>}),
 *       the native-image-capable build.</li>
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
        return norm.equals("lts") || norm.equals("stable") || norm.equals("latest")
                || norm.equals(NATIVE);
    }

    /**
     * Translate a keyword into a concrete vendor-qualified spec for the host.
     * Returns empty when:
     * <ul>
     *   <li>{@code raw} is not a keyword (the caller treats it as a normal
     *       denormalized spec),</li>
     *   <li>the feed publishes no (non-preview) majors for this host,</li>
     *   <li>{@code lts}/{@code stable} was asked for but no LTS major is present, or</li>
     *   <li>{@code native} was asked for but the feed has no Oracle GraalVM
     *       for this host.</li>
     * </ul>
     */
    public static Optional<String> resolveToMajorSpec(
            JdkCatalog catalog, String raw, String os, String arch) {
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
     * Vendor hints an <em>installed</em> JDK must satisfy for the keyword to be
     * considered already met. {@code native} requires a GraalVM; {@code lts} /
     * {@code latest} are vendor-agnostic (any vendor's current release counts),
     * so they return an empty list.
     */
    public static List<String> satisfactionHints(String raw) {
        return raw != null && raw.trim().equalsIgnoreCase(NATIVE)
                ? List.of("graalvm")
                : List.of();
    }

    /** The {@code suggested_sdk_name} of the highest-versioned Oracle GraalVM on the host. */
    private static Optional<String> latestOracleGraalVm(JdkCatalog catalog, String os, String arch) {
        JdkCatalog.Entry best = null;
        for (JdkCatalog.Entry e : catalog.entries()) {
            if (e.preview()) continue;
            if (!e.os().equals(os) || !e.arch().equals(arch)) continue;
            if (!isOracleGraalVm(e)) continue;
            if (best == null || JdkSelector.versionKey(e.version())
                    .compareTo(JdkSelector.versionKey(best.version())) > 0) {
                best = e;
            }
        }
        return best == null ? Optional.empty() : Optional.of(best.suggestedSdkName());
    }

    /** Oracle GraalVM ({@code graalvm-jdk-*}), as opposed to the community {@code graalvm-ce-*} line. */
    private static boolean isOracleGraalVm(JdkCatalog.Entry e) {
        return e.vendor().equalsIgnoreCase("Oracle") && e.product().equalsIgnoreCase("GraalVM");
    }
}
