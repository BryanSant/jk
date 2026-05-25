// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import java.util.Collection;
import java.util.OptionalInt;

/**
 * Knows which JDK majors are LTS, without an external service call.
 *
 * <p>Oracle's LTS cadence is deterministic and trivially encoded:
 * <ul>
 *   <li>Pre-cadence: {@code 8} and {@code 11} (set explicitly).</li>
 *   <li>From {@code 17} onward: every 4th major
 *       ({@code 17, 21, 25, 29, 33, …}).</li>
 * </ul>
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

    /** LTS majors that pre-date the {@code 17 + 4k} cadence. */
    private static final int LEGACY_LTS_8 = 8;
    private static final int LEGACY_LTS_11 = 11;

    /**
     * Is {@code major} an LTS release per Oracle's published cadence?
     * Does not check whether the major has actually shipped — combine
     * with {@link #latestLtsIn} for that.
     */
    public static boolean isLtsMajor(int major) {
        if (major == LEGACY_LTS_8 || major == LEGACY_LTS_11) return true;
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
