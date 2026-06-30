// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

/**
 * Dependency scope per PRD §7.2. Maven scope names retained. {@code system} is intentionally absent
 * — rejected on import.
 */
public enum Scope {
    EXPORT,
    MAIN,
    PROVIDED,
    RUNTIME,
    TEST,
    PROCESSOR,
    PLATFORM;

    /** Lowercase canonical name of this scope (e.g. {@code "main"}, {@code "test"}). */
    public String canonical() {
        return name().toLowerCase();
    }

    /**
     * The {@code jk.toml} section header for this scope's dependency table.
     * MAIN maps to {@code "dependencies"}; every other scope maps to
     * {@code "<canonical>-dependencies"} (e.g. {@code "test-dependencies"}).
     */
    public String tomlSection() {
        return switch (this) {
            case MAIN      -> "dependencies";
            case TEST      -> "test-dependencies";
            case PROVIDED  -> "provided-dependencies";
            case PROCESSOR -> "processor-dependencies";
            case EXPORT    -> "export-dependencies";
            default        -> canonical() + "-dependencies";
        };
    }
}
