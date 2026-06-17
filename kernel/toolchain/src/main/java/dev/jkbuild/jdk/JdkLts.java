// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import java.util.Collection;
import java.util.OptionalInt;

/**
 * Knows which JDK majors are LTS, without an external service call.
 *
 * <p>jk targets the forward-facing Java/Kotlin developer; 8 and 11 are
 * intentionally <em>not</em> recognised as LTS here — they're below the
 * floor enforced by {@link SupportedJdk#MIN_MAJOR}. The LTS cadence we
 * encode is the post-Sep-2021 one: every 4th major starting at 17
 * ({@code 17, 21, 25, 29, 33, …}).
 *
 * <p>To find the <em>current</em> LTS, intersect {@link #isLtsMajor} with
 * the set of majors the JetBrains JDK feed actually publishes and take
 * the max — see {@link #latestLtsIn}. That way "current LTS" is data-
 * driven (it's whichever LTS-pattern major has shipped), and we don't
 * have to ship a code update when JDK 29 releases.
 */
public final class JdkLts {

    private JdkLts() {}

    /** The first LTS major to follow the every-4-years cadence (Sep 2021). */
    private static final int CADENCE_BASE = 17;

    /**
     * The newest LTS major that has reached GA as of this jk build — the offline
     * source of truth for "latest LTS" when the JetBrains feed isn't consulted
     * (e.g. fast build-time JDK resolution / the de-facto-default rule). Bump
     * when a new LTS ships; the catalog-driven {@link #latestLtsIn} refines it
     * whenever a feed is actually available.
     */
    public static final int OFFLINE_LATEST_LTS = 25;

    /**
     * The newest GA major as of this jk build, LTS or not — the offline source
     * of truth for "latest stable". When the newest GA <em>is</em> an LTS this
     * equals {@link #OFFLINE_LATEST_LTS}; otherwise it is the non-LTS cutting
     * edge (e.g. 26 while 25 is the current LTS). Bump when a new GA ships.
     */
    public static final int OFFLINE_LATEST_STABLE = 26;

    /**
     * Is {@code major} an LTS release per Oracle's post-2021 cadence?
     * Does not check whether the major has actually shipped — combine
     * with {@link #latestLtsIn} for that.
     */
    public static boolean isLtsMajor(int major) {
        if (major < CADENCE_BASE) return false;
        return (major - CADENCE_BASE) % 4 == 0;
    }

    /**
     * Highest LTS major in {@code majors}, or empty when none of the
     * candidates is LTS. Used to map {@code jk jdk install --lts} to a
     * concrete major from the JetBrains feed.
     */
    public static OptionalInt latestLtsIn(Collection<Integer> majors) {
        return majors.stream().mapToInt(Integer::intValue).filter(JdkLts::isLtsMajor).max();
    }
}
