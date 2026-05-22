// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.resolver;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal version comparison sufficient for the naive resolver's
 * highest-wins pick. Splits on {@code .} / {@code -} / {@code _};
 * compares numeric runs numerically, strings lexicographically.
 *
 * <p>This is not a SemVer or Maven-version comparator. Full Maven-style
 * version ordering (qualifier precedence: alpha &lt; beta &lt; ... &lt; ga
 * &lt; sp) ships with PubGrub.
 */
public final class Versions {

    private static final Pattern SPLIT = Pattern.compile("[.\\-_]");
    private static final Pattern NUMERIC = Pattern.compile("\\d+");

    private Versions() {}

    public static int compare(String a, String b) {
        if (a.equals(b)) return 0;
        String[] aParts = SPLIT.split(a);
        String[] bParts = SPLIT.split(b);
        int len = Math.max(aParts.length, bParts.length);
        for (int i = 0; i < len; i++) {
            String left = i < aParts.length ? aParts[i] : "0";
            String right = i < bParts.length ? bParts[i] : "0";
            int cmp = compareSegment(left, right);
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    private static int compareSegment(String left, String right) {
        Matcher lm = NUMERIC.matcher(left);
        Matcher rm = NUMERIC.matcher(right);
        if (lm.matches() && rm.matches()) {
            return Long.compare(Long.parseLong(left), Long.parseLong(right));
        }
        if (lm.matches()) return 1;   // numeric beats non-numeric (e.g. "1" > "rc")
        if (rm.matches()) return -1;
        return left.compareTo(right);
    }
}
