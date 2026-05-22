// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.model;

import java.util.List;
import java.util.Objects;

/**
 * The {@code deny { ... }} policy block (PRD §23.6). First-cut shape:
 *
 * <ul>
 *   <li>{@code sources.deny} — denylist of repository host names.</li>
 *   <li>{@code licenses.deny} / {@code licenses.allow} — denylist /
 *       allowlist of SPDX license identifiers (checked when license
 *       data is available; full POM-license inspection is a v0.8
 *       follow-up).</li>
 *   <li>{@code yanked} — {@code "deny"} (default), {@code "warn"}, or
 *       {@code "allow"}.</li>
 * </ul>
 */
public record DenyPolicy(
        List<String> deniedSources,
        List<String> deniedLicenses,
        List<String> allowedLicenses,
        YankedPolicy yanked) {

    public enum YankedPolicy { DENY, WARN, ALLOW }

    public DenyPolicy {
        deniedSources = deniedSources == null ? List.of() : List.copyOf(deniedSources);
        deniedLicenses = deniedLicenses == null ? List.of() : List.copyOf(deniedLicenses);
        allowedLicenses = allowedLicenses == null ? List.of() : List.copyOf(allowedLicenses);
        yanked = Objects.requireNonNullElse(yanked, YankedPolicy.DENY);
    }

    public static DenyPolicy permissive() {
        return new DenyPolicy(List.of(), List.of(), List.of(), YankedPolicy.WARN);
    }

    public boolean isEmpty() {
        return deniedSources.isEmpty() && deniedLicenses.isEmpty() && allowedLicenses.isEmpty();
    }
}
