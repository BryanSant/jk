// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * Derives a Maven/SemVer version string from a git ref, for git-source
 * dependencies (see docs/git-source-deps.md).
 *
 * <p><b>Why the formats look the way they do:</b> jk orders versions with
 * Maven's {@code ComparableVersion} (see {@code resolver.Versions}). Under that
 * ordering a <em>hex</em> qualifier like {@code -g3f2a9c1} is "unknown" and
 * sorts <em>above</em> the release — wrong for a pre-release commit. The
 * resolved-snapshot <em>timestamp</em> form ({@code 1.2.4-20260601.134752-1}),
 * by contrast, is recognised as a snapshot and sorts below the release and
 * chronologically. So the pseudo-version uses the timestamp form and the commit
 * SHA is recorded in {@code jk.lock} (and surfaced in logs) rather than baked
 * into the version string.
 *
 * <ul>
 *   <li><b>tag</b> → coerced SemVer ({@code v1.2.3} → {@code 1.2.3}); raw tag
 *       string as a permissive fallback when it can't be coerced.</li>
 *   <li><b>branch</b> → {@code <branch>-SNAPSHOT} (mutable; the SHA is pinned in
 *       the lockfile).</li>
 *   <li><b>untagged commit</b> → pseudo-version built from the nearest
 *       reachable tag plus the commit's timestamp and short SHA:
 *       {@code <nearest-tag>-<yyyyMMdd.HHmmss>-<shortsha>} (no tag →
 *       {@code 0.0.0-…}). Under {@code ComparableVersion}, a numeric
 *       (timestamp) qualifier sorts <em>above</em> the same-core release but
 *       <em>below</em> a higher core — so {@code 1.2.3-20260601.134752-3f2a9c1b}
 *       lands between {@code 1.2.3} and {@code 1.2.4}, sorts chronologically,
 *       and carries the SHA for traceability. (We attach to the prior tag's
 *       core rather than bumping the patch precisely because of that rule.)</li>
 * </ul>
 */
public final class GitVersion {

    /** {@code yyyyMMdd.HHmmss} in UTC — Maven's resolved-snapshot timestamp shape. */
    private static final DateTimeFormatter SNAPSHOT_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss").withZone(ZoneOffset.UTC);

    private GitVersion() {}

    /** Coerce a tag to a clean version; returns the raw tag if it isn't version-like. */
    public static String fromTag(String tag) {
        String coerced = coerce(tag);
        return coerced != null ? coerced : tag.strip();
    }

    /** {@code <branch>-SNAPSHOT}, with version-unsafe characters folded to '-'. */
    public static String forBranch(String branch) {
        return sanitize(branch) + "-SNAPSHOT";
    }

    /**
     * Pseudo-version for an untagged commit: {@code <base>-<ts>-<shortSha>},
     * where {@code base} is the coerced nearest reachable tag (or {@code 0.0.0}
     * when there's none), {@code ts} is the commit's UTC time as
     * {@code yyyyMMdd.HHmmss}, and {@code shortSha} is the abbreviated commit
     * hash. The numeric timestamp makes it sort above the base tag and below the
     * next release; the SHA tie-breaks and aids traceability.
     */
    public static String pseudo(Optional<String> nearestTag, Instant commitTime, String shortSha) {
        String base = nearestTag.map(GitVersion::coerce).filter(c -> c != null).orElse("0.0.0");
        return base + "-" + SNAPSHOT_TS.format(commitTime) + "-" + shortSha;
    }

    /**
     * Coerce a tag to {@code major.minor.patch[-prerelease][+build]}: strip a
     * leading non-digit prefix (a {@code v}, {@code release-}, {@code <name>-},
     * …), require a numeric core, pad to three components. Returns null when the
     * tag has no version-like core so the caller can fall back to the raw tag.
     */
    static String coerce(String tag) {
        if (tag == null) return null;
        String s = tag.strip();
        int firstDigit = -1;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                firstDigit = i;
                break;
            }
        }
        if (firstDigit < 0) return null;
        s = s.substring(firstDigit);

        // Split off -prerelease / +build; keep the rest verbatim.
        int dash = s.indexOf('-');
        int plus = s.indexOf('+');
        int cut = minPositive(dash, plus);
        String core = cut < 0 ? s : s.substring(0, cut);
        String suffix = cut < 0 ? "" : s.substring(cut);

        String[] parts = core.split("\\.", -1);
        StringBuilder normalizedCore = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].chars().allMatch(Character::isDigit) || parts[i].isEmpty()) {
                return null; // non-numeric core → not coercible
            }
            if (i > 0) normalizedCore.append('.');
            // strip leading zeros but keep a single 0
            normalizedCore.append(Long.parseLong(parts[i]));
        }
        // Pad to at least major.minor.patch.
        for (int n = parts.length; n < 3; n++) normalizedCore.append(".0");
        return normalizedCore + suffix;
    }

    private static int minPositive(int a, int b) {
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    private static String sanitize(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.strip().toCharArray()) {
            out.append((Character.isLetterOrDigit(c) || c == '.' || c == '-') ? c : '-');
        }
        String r = out.toString().toLowerCase(Locale.ROOT);
        return r.isEmpty() ? "branch" : r;
    }
}
