// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver;

import org.apache.maven.artifact.versioning.ComparableVersion;

/**
 * Maven-canonical version comparator. Delegates to {@link ComparableVersion} from {@code
 * org.apache.maven:maven-artifact}, so jk's resolver, lockfile dedup, and range-moduleship checks
 * all agree with the JVM ecosystem's de-facto ordering.
 *
 * <p>ComparableVersion's semantics in brief:
 *
 * <ul>
 *   <li>Numeric segments compare numerically: {@code 1.10 > 1.2}.
 *   <li>Trailing zero / empty / "ga"-equivalent segments are normalized: {@code 1.0 == 1.0.0 ==
 *       1.0-ga == 1.0-final == 1.0-release}.
 *   <li>Qualifier order: {@code alpha < beta < milestone (m) < rc/cr/pre < snapshot < "" (release)
 *       < sp}. Unknown qualifiers rank above {@code sp} and tie-break lexicographically.
 *   <li>Maven snapshot timestamps ({@code 1.0-20260520.123456-7}) compare correctly because
 *       ComparableVersion tokenizes the same way Maven's publisher generates them.
 * </ul>
 *
 * <p>We use this everywhere version strings need to be ordered — see {@link
 * MavenPackageSource#versions}, {@code NaiveResolver}, and the PubGrub {@code VersionSet.Range}
 * range moduleship checks.
 */
public final class Versions {

    private Versions() {}

    /**
     * Return {@code <0}, {@code 0}, or {@code >0} per {@link Comparable}, comparing {@code a} and
     * {@code b} as Maven version strings.
     */
    public static int compare(String a, String b) {
        if (a.equals(b)) return 0;
        return new ComparableVersion(a).compareTo(new ComparableVersion(b));
    }

    /**
     * Common pre-release qualifier tokens that Maven's {@link ComparableVersion} does <em>not</em>
     * rank below the release baseline (it treats them as "unknown" qualifiers that sort
     * <em>above</em> release): {@code pre}, {@code preview}, {@code dev}, {@code ea}, {@code canary},
     * {@code nightly}. The alpha/beta/milestone/rc/cr/snapshot family is already handled by the
     * ordering check, but listing them here too is a harmless backstop. A token counts only when
     * preceded by a separator and followed by a separator, digit, or end — so {@code -jre}, {@code
     * -android}, {@code -m2e} stay stable.
     */
    private static final java.util.regex.Pattern PRE_RELEASE = java.util.regex.Pattern.compile(
            "(?i)(?:^|[-_.+])(alpha|beta|milestone|m\\d|rc|cr|pre|preview|snapshot|dev|ea|canary|nightly)"
                    + "(?:[-_.+\\d]|$)");

    /** Maven's resolved-snapshot form, e.g. {@code 1.0-20260520.123456-7} — always a pre-release. */
    private static final java.util.regex.Pattern SNAPSHOT_TIMESTAMP =
            java.util.regex.Pattern.compile("-\\d{8}\\.\\d{6}-\\d+$");

    /**
     * True when {@code version} is a stable release (not a pre-release such as an
     * alpha/beta/milestone/rc/snapshot/preview/dev/ea). Two complementary checks, so it is robust
     * across the qualifiers seen in the wild:
     *
     * <ol>
     *   <li>Maven ordering — the version must sort at-or-above its own numeric core ({@code 2.4.0}
     *       for {@code 2.4.0-RC2}) per {@link ComparableVersion}. This rigorously catches the
     *       Maven-ranked pre-release family (alpha/beta/milestone/rc/cr/snapshot + aliases) while
     *       keeping release synonyms ({@code ga}/{@code final}), service packs ({@code sp}), and
     *       benign qualifiers ({@code -jre}) stable.
     *   <li>A qualifier regex for pre-release markers Maven treats as unknown (and would otherwise
     *       rank above release): {@code pre}, {@code dev}, {@code ea}, {@code preview}, {@code
     *       canary}, {@code nightly}.
     * </ol>
     *
     * A version with no leading numeric core ({@code "RELEASE"}, {@code "latest"}) is treated as
     * unstable.
     */
    public static boolean isStable(String version) {
        String core = numericCore(version);
        if (core.isEmpty()) return false;
        if (SNAPSHOT_TIMESTAMP.matcher(version).find()) return false;
        if (new ComparableVersion(version).compareTo(new ComparableVersion(core)) < 0) return false;
        return !PRE_RELEASE.matcher(version).find();
    }

    /**
     * The leading run of digits and dots (e.g. {@code 2.4.0} of {@code 2.4.0-RC2}), no trailing dot.
     */
    private static String numericCore(String version) {
        int i = 0;
        while (i < version.length()) {
            char c = version.charAt(i);
            if (Character.isDigit(c) || c == '.') i++;
            else break;
        }
        String core = version.substring(0, i);
        while (core.endsWith(".")) core = core.substring(0, core.length() - 1);
        return core;
    }
}
