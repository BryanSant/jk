// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.resolver;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maven-compatible version comparator. Ports the semantics of
 * {@code org.apache.maven.artifact.versioning.ComparableVersion}:
 *
 * <ul>
 *   <li>Tokens are split at {@code .}, {@code -}, and at digit→letter
 *       transitions.</li>
 *   <li>Numeric tokens compare numerically (so {@code 1.10 > 1.2}).</li>
 *   <li>Qualifier order: {@code alpha < beta < milestone (m) < rc/cr/pre
 *       < snapshot < "" (ga/final/release) < sp}. Unknown qualifiers rank
 *       above sp and compare lexicographically among themselves.</li>
 *   <li>At a given position a numeric token outranks a non-numeric one.</li>
 *   <li>Trailing zero / empty tokens are normalized so {@code 1.0 = 1.0.0}.</li>
 * </ul>
 */
public final class Versions {

    private static final Map<String, Integer> QUALIFIER_RANK = Map.ofEntries(
            Map.entry("alpha", 0), Map.entry("a", 0),
            Map.entry("beta", 1), Map.entry("b", 1),
            Map.entry("milestone", 2), Map.entry("m", 2),
            Map.entry("rc", 3), Map.entry("cr", 3), Map.entry("pre", 3),
            Map.entry("snapshot", 4),
            Map.entry("", 5),
            Map.entry("ga", 5), Map.entry("final", 5), Map.entry("release", 5),
            Map.entry("sp", 6));

    private static final int UNKNOWN_RANK = 7;

    private Versions() {}

    public static int compare(String a, String b) {
        if (a.equals(b)) return 0;
        return compareItems(parse(a), parse(b));
    }

    // --- token model -------------------------------------------------------

    private static int compareItems(List<Object> a, List<Object> b) {
        int len = Math.max(a.size(), b.size());
        for (int i = 0; i < len; i++) {
            Object x = i < a.size() ? a.get(i) : zeroOf(b.get(i));
            Object y = i < b.size() ? b.get(i) : zeroOf(a.get(i));
            int cmp = compareSingle(x, y);
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    private static Object zeroOf(Object item) {
        return item instanceof BigInteger ? BigInteger.ZERO : "";
    }

    private static int compareSingle(Object x, Object y) {
        boolean xNumeric = x instanceof BigInteger;
        boolean yNumeric = y instanceof BigInteger;
        if (xNumeric && yNumeric) return ((BigInteger) x).compareTo((BigInteger) y);
        // Numeric > non-numeric at the same position (a real number beats a qualifier).
        if (xNumeric) return 1;
        if (yNumeric) return -1;
        return compareQualifier((String) x, (String) y);
    }

    private static int compareQualifier(String x, String y) {
        int xRank = rank(x);
        int yRank = rank(y);
        if (xRank != yRank) return Integer.compare(xRank, yRank);
        if (xRank == UNKNOWN_RANK) return x.compareTo(y);
        return 0;
    }

    private static int rank(String qualifier) {
        Integer r = QUALIFIER_RANK.get(qualifier.toLowerCase());
        return r != null ? r : UNKNOWN_RANK;
    }

    private static List<Object> parse(String version) {
        List<Object> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean buildingDigits = false;

        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c == '.' || c == '-') {
                flushToken(current, tokens);
                buildingDigits = false;
                continue;
            }
            boolean digit = Character.isDigit(c);
            if (current.length() > 0 && digit != buildingDigits) {
                flushToken(current, tokens);
            }
            current.append(c);
            buildingDigits = digit;
        }
        flushToken(current, tokens);

        // Trim trailing zero / empty tokens so 1.0 == 1.0.0 == 1-final.
        while (!tokens.isEmpty()) {
            Object last = tokens.getLast();
            if (last instanceof BigInteger b && b.signum() == 0) {
                tokens.removeLast();
            } else if (last instanceof String s && rank(s) == 5) {
                tokens.removeLast();
            } else {
                break;
            }
        }
        return tokens;
    }

    private static void flushToken(StringBuilder buf, List<Object> tokens) {
        if (buf.length() == 0) return;
        String token = buf.toString();
        buf.setLength(0);
        if (Character.isDigit(token.charAt(0))) {
            tokens.add(new BigInteger(token));
        } else {
            tokens.add(token);
        }
    }
}
