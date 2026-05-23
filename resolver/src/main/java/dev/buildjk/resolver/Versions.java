// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.resolver;

import org.apache.maven.artifact.versioning.ComparableVersion;

/**
 * Maven-canonical version comparator. Delegates to
 * {@link ComparableVersion} from {@code org.apache.maven:maven-artifact}, so
 * jk's resolver, lockfile dedup, and range-membership checks all agree with
 * the JVM ecosystem's de-facto ordering.
 *
 * <p>ComparableVersion's semantics in brief:
 * <ul>
 *   <li>Numeric segments compare numerically: {@code 1.10 > 1.2}.</li>
 *   <li>Trailing zero / empty / "ga"-equivalent segments are normalized:
 *       {@code 1.0 == 1.0.0 == 1.0-ga == 1.0-final == 1.0-release}.</li>
 *   <li>Qualifier order: {@code alpha < beta < milestone (m) < rc/cr/pre
 *       < snapshot < "" (release) < sp}. Unknown qualifiers rank above
 *       {@code sp} and tie-break lexicographically.</li>
 *   <li>Maven snapshot timestamps ({@code 1.0-20260520.123456-7}) compare
 *       correctly because ComparableVersion tokenizes the same way Maven's
 *       publisher generates them.</li>
 * </ul>
 *
 * <p>We use this everywhere version strings need to be ordered — see
 * {@link MavenPackageSource#versions}, {@code NaiveResolver}, and the
 * PubGrub {@code VersionSet.Range} range membership checks.
 */
public final class Versions {

    private Versions() {}

    /**
     * Return {@code <0}, {@code 0}, or {@code >0} per {@link Comparable},
     * comparing {@code a} and {@code b} as Maven version strings.
     */
    public static int compare(String a, String b) {
        if (a.equals(b)) return 0;
        return new ComparableVersion(a).compareTo(new ComparableVersion(b));
    }
}
