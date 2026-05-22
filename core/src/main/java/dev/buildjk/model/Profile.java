// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.model;

import java.util.List;
import java.util.Objects;

/**
 * A build profile (PRD §14): javac options + JVM args + optional inheritance.
 * Profiles change <i>how</i> code is compiled / run, not <i>what</i> is
 * compiled. Selected via {@code --profile &lt;name&gt;}.
 *
 * <p>{@code inherits} references another profile by name; resolution
 * merges parent-then-child for list fields and lets the child override
 * scalars. The {@code ci} profile is auto-selected when standard CI env
 * vars are present.
 */
public record Profile(
        String name,
        String inherits,
        List<String> javacArgs,
        List<String> jvmArgs) {

    public Profile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(javacArgs, "javacArgs");
        Objects.requireNonNull(jvmArgs, "jvmArgs");
        javacArgs = List.copyOf(javacArgs);
        jvmArgs = List.copyOf(jvmArgs);
    }

    public static Profile of(String name) {
        return new Profile(name, null, List.of(), List.of());
    }
}
