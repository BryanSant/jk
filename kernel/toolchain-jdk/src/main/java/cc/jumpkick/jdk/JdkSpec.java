// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import java.util.Objects;

/**
 * What the user typed to {@code jk jdk install <spec>} or {@code .jdk-version}. Matched against the
 * JetBrains feed's {@code shared_index_aliases} / {@code suggested_sdk_name}.
 *
 * <p>Examples:
 *
 * <ul>
 *   <li>{@code 21} — bare major; the selector picks whichever entry the feed marks {@code default:
 *       true} for major 21.
 *   <li>{@code 21.0.5} — exact version, default vendor.
 *   <li>{@code temurin-21} — vendor-suggested SDK name (a {@code suggested_sdk_name} value from the
 *       feed).
 *   <li>{@code temurin-21.0.5} — vendor + exact version (matches an entry's {@code
 *       shared_index_aliases}).
 * </ul>
 */
public record JdkSpec(String value) {

    public JdkSpec {
        Objects.requireNonNull(value, "value");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("spec is empty");
        }
    }

    public static JdkSpec parse(String raw) {
        return new JdkSpec(raw);
    }

    /** {@code true} when the spec is just a version number ({@code 21}, {@code 21.0.5}). */
    public boolean bareVersion() {
        if (value.isEmpty()) return false;
        char first = value.charAt(0);
        return first >= '0' && first <= '9';
    }

    /** Lower-case comparison form used when matching against feed aliases. */
    public String normalized() {
        return value.toLowerCase();
    }
}
