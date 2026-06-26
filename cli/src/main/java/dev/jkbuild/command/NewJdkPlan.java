// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.jdk.JdkSelector;
import dev.jkbuild.model.JkBuild;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Pure decision logic for {@code jk new}'s JDK selection — kept out of {@link NewCommand} so it can
 * be unit-tested without a terminal or a goal.
 *
 * <p>Two responsibilities:
 *
 * <ul>
 *   <li>{@link #parseExplicit} turns an explicit {@code --jdk <spec>} (after keyword resolution)
 *       into the major it targets plus the exact pin to write into {@code jk.toml}. A vendor lands
 *       in the pin <em>only</em> when the user typed one ({@code corretto-25}); a bare major
 *       ({@code 25}) stays bare. A point release is rejected.
 *   <li>{@link #shouldPrompt} / {@link #autoCandidate} decide whether the wizard must ask the user
 *       to pick a JDK, and — when it doesn't — which candidate to use. The wizard never writes a
 *       vendor, so its pin is always the bare major (see {@link NewCommand}).
 * </ul>
 */
final class NewJdkPlan {

    private NewJdkPlan() {}

    /** A resolved JDK: the feature {@code major} and the {@code pin} to write to {@code jk.toml}. */
    record Spec(int major, String pin) {}

    /**
     * Parse an explicit {@code --jdk} value (a concrete spec, not a keyword) into its {@link Spec}.
     * Accepts a bare major ({@code "25"}) or a vendor-hinted spec ({@code "corretto-25"}); the vendor
     * is preserved in the pin only in the latter case. Rejects a point release and a spec with no
     * major.
     *
     * @throws IllegalArgumentException with a user-facing message on a bad spec
     */
    static Spec parseExplicit(String raw) {
        String arg = raw.trim();
        if (JkBuild.Project.hasPointRelease(arg)) {
            throw new IllegalArgumentException("--jdk "
                    + arg
                    + " must not pin a point release — use \"<vendor>-<major>\" or \"<major>\" "
                    + "(e.g. \"temurin-25\" or \"25\"); jk keeps the patch current.");
        }
        JdkSelector.FlexibleQuery q = JdkSelector.parseFlexible(arg);
        int major = q.major().orElse(0);
        if (major == 0) {
            throw new IllegalArgumentException("--jdk "
                    + arg
                    + " must include a major version (e.g. \"temurin-25\", \"25\", or lts/stable/latest)");
        }
        if (!q.hints().isEmpty()) {
            String vendor = q.hints().get(0).toLowerCase(Locale.ROOT);
            return new Spec(major, vendor + "-" + major);
        }
        return new Spec(major, Integer.toString(major));
    }

    /** Installed candidates that can compile the chosen Java level (major ≥ floor). */
    static List<NewJdkCandidate> eligibleInstalled(List<NewJdkCandidate> candidates, int floor) {
        return candidates.stream()
                .filter(NewJdkCandidate::installed)
                .filter(c -> c.major() >= floor)
                .toList();
    }

    /**
     * Whether the wizard must prompt for a JDK. We only ask when the user hasn't pinned one another
     * way ({@code module} inherits the parent; {@code hasDefault} uses the global default)
     * <em>and</em> there's a real choice — more than one eligible installed JDK for the chosen Java
     * level.
     */
    static boolean shouldPrompt(boolean module, boolean hasDefault, List<NewJdkCandidate> candidates, int floor) {
        if (module || hasDefault) return false;
        return eligibleInstalled(candidates, floor).size() > 1;
    }

    /**
     * The candidate to use when not prompting:
     *
     * <ol>
     *   <li>a JDK matching {@code preferredMajor} (parent's / the global default's major) — installed
     *       first, then an installable row;
     *   <li>else the sole eligible installed JDK;
     *   <li>else nothing installed is eligible — fall back to {@code lts} (or the newest candidate
     *       when the chosen Java level exceeds {@code lts}), to be installed like {@code jk jdk
     *       install lts}.
     * </ol>
     */
    static Optional<NewJdkCandidate> autoCandidate(
            List<NewJdkCandidate> candidates, int floor, int preferredMajor, int lts) {
        if (preferredMajor > 0) {
            Optional<NewJdkCandidate> installedPref = candidates.stream()
                    .filter(NewJdkCandidate::installed)
                    .filter(c -> c.major() == preferredMajor)
                    .findFirst();
            if (installedPref.isPresent()) return installedPref;
            Optional<NewJdkCandidate> anyPref =
                    candidates.stream().filter(c -> c.major() == preferredMajor).findFirst();
            if (anyPref.isPresent()) return anyPref;
        }
        List<NewJdkCandidate> eligible = eligibleInstalled(candidates, floor);
        if (!eligible.isEmpty()) return Optional.of(eligible.get(0));

        int target = floor > lts ? latestMajor(candidates) : lts;
        Optional<NewJdkCandidate> atTarget =
                candidates.stream().filter(c -> c.major() == target).findFirst();
        if (atTarget.isPresent()) return atTarget;
        return candidates.stream()
                .filter(c -> c.major() >= floor)
                .max(Comparator.comparingInt(NewJdkCandidate::major))
                .or(() -> candidates.stream().findFirst());
    }

    private static int latestMajor(List<NewJdkCandidate> candidates) {
        return candidates.stream().mapToInt(NewJdkCandidate::major).max().orElse(0);
    }
}
