// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.model;

import java.util.Objects;

/**
 * Version selector per PRD §7.3. Pinned-by-default (Maven/Gradle muscle memory);
 * caret, tilde, and range syntax are opt-in decorations.
 *
 * <ul>
 *   <li>{@code "2.18.2"}  → {@link Exact}  (exactly {@code 2.18.2})</li>
 *   <li>{@code "^2.18.2"} → {@link Caret}  ({@code >=2.18.2, <3.0.0})</li>
 *   <li>{@code "~2.18.2"} → {@link Tilde}  (patch-only, {@code >=2.18.2, <2.19.0})</li>
 *   <li>{@code ">=2.18, <3"} → {@link Range}</li>
 *   <li>{@code "latest"}  → {@link Latest}</li>
 * </ul>
 */
public sealed interface VersionSelector {

    /** The text the user wrote, preserved for round-tripping and diagnostics. */
    String raw();

    record Caret(String raw, String version) implements VersionSelector {}

    record Exact(String raw, String version) implements VersionSelector {}

    record Tilde(String raw, String version) implements VersionSelector {}

    record Range(String raw) implements VersionSelector {}

    record Latest(String raw) implements VersionSelector {}

    static VersionSelector parse(String spec) {
        Objects.requireNonNull(spec, "spec");
        String trimmed = spec.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("version selector must not be blank");
        }
        if ("latest".equalsIgnoreCase(trimmed)) {
            return new Latest(spec);
        }
        if (trimmed.startsWith("^")) {
            return new Caret(spec, trimmed.substring(1).trim());
        }
        if (trimmed.startsWith("~")) {
            return new Tilde(spec, trimmed.substring(1).trim());
        }
        if (trimmed.startsWith(">") || trimmed.startsWith("<") || trimmed.contains(",")) {
            return new Range(spec);
        }
        // Backward-compatible: leading `=` still parses to Exact, since some
        // older lockfiles and tests may carry it. Bare versions are also Exact.
        if (trimmed.startsWith("=")) {
            return new Exact(spec, trimmed.substring(1).trim());
        }
        return new Exact(spec, trimmed);
    }
}
