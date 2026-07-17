// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Objects;

/**
 * Version selector per PRD §7.3. Two parse modes for the two manifest forms:
 *
 * <ul>
 *   <li><b>{@code parse}</b> — {@code group:artifact:version} ({@code :} form, pinned). Bare
 *       versions are {@link Exact}; decorations ({@code ^}, {@code ~}, ranges, {@code latest}) are
 *       still accepted on the existing grammar but the {@code :} separator itself signals "pinned"
 *       at the {@code Dependency} level.
 *   <li><b>{@code parseFloating}</b> — {@code group:artifact@version} ({@code @} form, floating,
 *       Cargo-style). Bare versions default to {@link Caret} (e.g., {@code 2.18.2} → {@code
 *       ^2.18.2}). Pin via {@code =2.18.2}.
 * </ul>
 *
 * Decoration grammar (applies to both):
 *
 * <ul>
 *   <li>{@code "^2.18.2"} → {@link Caret} ({@code >=2.18.2, <3.0.0})
 *   <li>{@code "~2.18.2"} → {@link Tilde} (patch-only, {@code >=2.18.2, <2.19.0})
 *   <li>{@code "=2.18.2"} → {@link Exact} (exact pin)
 *   <li>{@code ">=2.18, <3"} → {@link Range}
 *   <li>{@code "latest"} → {@link Latest}
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
        return parse(spec, /* bareIsCaret */ false);
    }

    /**
     * Parse with caret-default semantics. Used for the {@code @}-form ({@code
     * group:artifact@version}) so that bare {@code 2.18.2} reads as {@code ^2.18.2}. Cargo-style.
     */
    public static VersionSelector parseFloating(String spec) {
        return parse(spec, /* bareIsCaret */ true);
    }

    private static VersionSelector parse(String spec, boolean bareIsCaret) {
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
        // Leading `=` is always Exact — the LIVE pin syntax lockfiles write today and the
        // explicit pin spelling under the @-form. (Not legacy: bare versions parse as Caret
        // in manifests, so `=` is how a resolved pin round-trips exactly.)
        if (trimmed.startsWith("=")) {
            return new Exact(spec, trimmed.substring(1).trim());
        }
        return bareIsCaret ? new Caret(spec, trimmed) : new Exact(spec, trimmed);
    }
}
