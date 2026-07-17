// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Single source of truth for "which JDK majors does jk surface?"
 *
 * <p>jk targets the forward-facing Java/Kotlin developer, not 30 years of legacy. The supported set
 * is:
 *
 * <ul>
 *   <li>{@link #MIN_MAJOR} = 17 — the floor. Anything older is dropped on sight (catalog, registry,
 *       jk.toml validation, wizards).
 *   <li>Every LTS major at or above the floor — 17, 21, 25, 29, 33, … — determined dynamically via
 *       {@link JdkLts#isLtsMajor}.
 *   <li>The single most recent major in whatever set is being filtered (the JetBrains feed, the
 *       local registry, etc.). This lets users run the bleeding edge between LTS cuts (e.g. 26
 *       today) without opening the gate to every interim release.
 * </ul>
 *
 * <p>Concretely: today's filter keeps {17, 21, 25, 26}; tomorrow when the catalog adds 27, the kept
 * set becomes {17, 21, 25, 27}.
 */
public final class SupportedJdk {

    /** Lowest major jk will accept anywhere. */
    public static final int MIN_MAJOR = 17;

    private SupportedJdk() {}

    /**
     * Cheap "is this major fundamentally acceptable" check — strictly the {@link #MIN_MAJOR} floor.
     * Use this for jk.toml parsing where we don't have catalog context yet; downstream resolution
     * will reject non-LTS / non-latest values when the catalog filter strips them.
     */
    public static boolean isSupported(int major) {
        return major >= MIN_MAJOR;
    }

    /**
     * Full predicate: an LTS at or above 17, or the single latest major in the surrounding set.
     * {@code latestAvailable} is the max major the caller wants to admit as "current"; pass it 0 to
     * mean "no latest pass" (LTS-only).
     */
    public static boolean isFirstClass(int major, int latestAvailable) {
        if (major < MIN_MAJOR) return false;
        if (JdkLts.isLtsMajor(major)) return true;
        return latestAvailable > 0 && major == latestAvailable;
    }

    /**
     * Subset of {@code available} that {@link #isFirstClass} accepts, with {@code latestAvailable}
     * inferred from {@code available} itself — caller doesn't have to pre-compute the max.
     */
    public static Set<Integer> firstClassMajors(Collection<Integer> available) {
        if (available == null || available.isEmpty()) return Set.of();
        int latest = available.stream().mapToInt(Integer::intValue).max().orElse(0);
        Set<Integer> out = new TreeSet<>();
        for (int m : available) {
            if (isFirstClass(m, latest)) out.add(m);
        }
        return out;
    }

    /**
     * A GraalVM / native-image-capable catalog entry. Identified by {@code suggested_sdk_name}
     * starting with {@code graalvm} ({@code graalvm-jdk-<major>} = Oracle GraalVM,
     * {@code graalvm-ce-<major>} = GraalVM Community) — the robust signal, since the feed's
     * {@code (vendor, product)} strings for CE don't match {@link JdkVendor}'s constants.
     */
    public static boolean isNativeImageCapable(JdkCatalog.Entry e) {
        return e.suggestedSdkName() != null && e.suggestedSdkName().startsWith("graalvm");
    }

    /**
     * The language-level majors {@code jk new} should offer for one track, newest-first, derived
     * from the live catalog so a newly-published major appears automatically. {@code nativeOnly}
     * restricts to {@linkplain #isNativeImageCapable native-image-capable} (GraalVM) distributions,
     * so a native project can't be pinned to a Java release no GraalVM ships yet.
     *
     * <p>Mirrors the wizard's established shape: every {@linkplain JdkLts#isLtsMajor LTS} at or above
     * {@link #MIN_MAJOR} that the catalog actually publishes, in descending order, then the newest
     * non-LTS major appended last (e.g. 26 while 25 is the current LTS). Returns an empty list when
     * the catalog is empty/unavailable so the caller can fall back to offline constants.
     */
    public static List<Integer> offerableMajors(JdkCatalog catalog, boolean nativeOnly, String os, String arch) {
        if (catalog == null) return List.of();
        TreeSet<Integer> avail = new TreeSet<>();
        for (JdkCatalog.Entry e : catalog.entries()) {
            if (e.os().equalsIgnoreCase(os)
                    && e.arch().equalsIgnoreCase(arch)
                    && !e.preview()
                    && e.majorVersion() >= MIN_MAJOR
                    && (!nativeOnly || isNativeImageCapable(e))) {
                avail.add(e.majorVersion());
            }
        }
        if (avail.isEmpty()) return List.of();
        int max = avail.last();
        int maxLts = JdkLts.latestLtsIn(avail).orElse(MIN_MAJOR);
        List<Integer> out = new ArrayList<>();
        for (int v = maxLts; v >= MIN_MAJOR; v--) {
            if (JdkLts.isLtsMajor(v) && avail.contains(v)) out.add(v);
        }
        if (max > maxLts) out.add(max); // the newest non-LTS release, offered last
        return out;
    }
}
