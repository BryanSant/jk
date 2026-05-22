// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.model;

/**
 * Dependency scope per PRD §7.2. Maven scope names retained.
 * {@code system} is intentionally absent — rejected on import.
 */
public enum Scope {
    MAIN,
    PROVIDED,
    RUNTIME,
    TEST,
    PROCESSOR,
    PLATFORM;

    /** Lowercase canonical name as used in {@code build.jk} ({@code dependencies.main}, etc.). */
    public String canonical() {
        return name().toLowerCase();
    }
}
