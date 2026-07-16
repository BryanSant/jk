// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Objects;

/**
 * A tool target's coordinate spec — the grammar behind {@code jk tool run|install <coord>} (PRD
 * §20.3 {@code <coord>[@ver]}, docs/tool-targets-plan.md §4.2):
 *
 * <ul>
 *   <li>{@code group:artifact:version[:classifier][@type]} → {@link Pinned} (the classic
 *       {@link Coordinate} grammar, where {@code @} selects the packaging type).
 *   <li>{@code group:artifact@selector} → {@link Floating} — one colon before the {@code @}, so
 *       the suffix is a {@linkplain VersionSelector#parseFloating floating version selector}
 *       ({@code @1.2} caret, {@code @=1.2.3} exact, {@code @latest}, ranges).
 *   <li>{@code group:artifact} → {@link Floating} with {@code latest} (stable releases only —
 *       the resolver's pick skips pre-release qualifiers).
 * </ul>
 *
 * The one-colon-before-{@code @} rule keeps the two {@code @} meanings unambiguous: a packaging
 * type needs a full {@code g:a:v} to its left, a selector needs exactly {@code g:a}.
 */
public sealed interface ToolCoordSpec {

    /** The text the user wrote, for diagnostics. */
    String raw();

    /** {@code group:artifact} module identifier. */
    String module();

    /** An exact {@code g:a:v[:classifier][@type]} — resolve and fetch as-is. */
    record Pinned(Coordinate coordinate, String raw) implements ToolCoordSpec {
        @Override
        public String module() {
            return coordinate.module();
        }
    }

    /** A {@code g:a} plus a selector the resolver must pin against the repo's version list. */
    record Floating(String group, String artifact, VersionSelector selector, String raw) implements ToolCoordSpec {
        @Override
        public String module() {
            return group + ":" + artifact;
        }
    }

    static ToolCoordSpec parse(String spec) {
        Objects.requireNonNull(spec, "spec");
        String trimmed = spec.trim();
        int at = trimmed.indexOf('@');
        String body = at >= 0 ? trimmed.substring(0, at) : trimmed;
        long colons = body.chars().filter(c -> c == ':').count();
        if (colons == 1) {
            int colon = body.indexOf(':');
            String group = body.substring(0, colon);
            String artifact = body.substring(colon + 1);
            if (group.isBlank() || artifact.isBlank()) {
                throw new IllegalArgumentException("tool coordinate must be group:artifact[…], got: " + spec);
            }
            VersionSelector selector = at >= 0
                    ? VersionSelector.parseFloating(trimmed.substring(at + 1))
                    : VersionSelector.parse("latest");
            return new Floating(group, artifact, selector, spec);
        }
        // 2+ colons (or none — Coordinate.parse renders the canonical error).
        return new Pinned(Coordinate.parse(trimmed), spec);
    }
}
