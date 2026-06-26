// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.cli.tui.GoalWedge;
import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.jdk.JdkVendor;
import java.nio.file.Path;

/**
 * Shared rendering helpers for {@code jk jdk} result lines.
 *
 * <p>Keeps the visual language consistent across install, uninstall, default,
 * and any other command that surfaces a JDK coordinate to the user.
 */
public final class JdkRender {

    private JdkRender() {}

    /**
     * Styled {@code [source]/identifier} coordinate:
     * brackets in bright-black, source in cyan, {@code /identifier} in the
     * path color. Used on the result lines of {@code jk jdk install},
     * {@code jk jdk uninstall}, {@code jk jdk default}, etc.
     */
    public static String coord(String source, String identifier) {
        Theme t = Theme.active();
        return Theme.colorize("[", t.darkGray())
                + Theme.colorize(source, t.cyan())
                + Theme.colorize("]", t.darkGray())
                + Theme.colorize("/" + identifier, t.path());
    }

    /**
     * Human display name for an installed JDK: {@code "Eclipse Temurin 25"},
     * {@code "JDK 21"} (unknown vendor), etc. Mirrors the logic used by
     * {@code jk jdk default} so all commands show the same label.
     */
    public static String displayName(JdkHit hit) {
        String maj = majorStr(hit.version());
        if (hit.vendor() == null || hit.vendor() == JdkVendor.UNKNOWN) {
            return maj.isEmpty() ? "JDK " + hit.version() : "JDK " + maj;
        }
        String name = hit.vendor().displayName();
        return maj.isEmpty() ? name + " " + hit.version() : name + " " + maj;
    }

    /**
     * GoalWedge chip line for a JDK availability result:
     * <ul>
     *   <li>{@code downloaded=false}: {@code  ✓ JDK ▶ {bold name} is available at {~/path}}
     *       — used when an existing install already satisfied the spec.</li>
     *   <li>{@code downloaded=true}: {@code  ✓ JDK ▶ {bold name} now is available at {~/path}}
     *       — used after a fresh download.</li>
     * </ul>
     */
    public static String available(String displayName, Path home, boolean nerdfont, boolean downloaded) {
        Theme t = Theme.active();
        String verb = downloaded ? " now is available at " : " is available at ";
        String msg = Theme.colorize(displayName, t.focused())
                + Theme.colorize(verb, t.normalGray())
                + Theme.colorize(JdkInstallCommand.tildeCollapse(home), t.path());
        return GoalWedge.chipLine(Glyphs.CHECK, "JDK", nerdfont, msg);
    }

    // ── internal helpers ────────────────────────────────────────────────────

    /** Leading digit sequence of a JDK version string (e.g. "25" from "25.0.3"). */
    private static String majorStr(String version) {
        if (version == null || version.isEmpty()) return "";
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end))) end++;
        return version.substring(0, end);
    }
}
