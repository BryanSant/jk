// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.theme;

import cc.jumpkick.model.Coordinate;
import org.jline.utils.AttributedStyle;

/**
 * Canonical terminal rendering of Maven coordinates. Every command that prints a {@code
 * group:artifact[:version]} coordinate routes through here so the colors stay identical across the
 * CLI:
 *
 * <pre>
 *   [blue]group[/]:[cyan]artifact[/]:[bright-blue]version[/]
 * </pre>
 *
 * and artifact short-names (dependency libraries) render as {@code [bright-cyan]}.
 *
 * <p>The four roles map onto the active {@link Theme} palette:
 *
 * <ul>
 *   <li><b>group</b> — {@link Theme#primary()} (the Jk Dark indigo / normal blue)
 *   <li><b>artifact</b> — {@link Theme#cyan()} (normal cyan)
 *   <li><b>version</b> — {@link Theme#blue()} (bright blue)
 *   <li><b>short-name</b> — {@link Theme#brightCyan()} (bright cyan)
 * </ul>
 *
 * <p>{@link Theme#colorize} honours {@code --color} / {@code NO_COLOR} / dumb terminals, so the
 * escapes drop out cleanly and the plain text stays the canonical {@code group:artifact:version}
 * that tooling and tests expect.
 */
public final class Coords {

    private Coords() {}

    /** group segment — the theme's coordinate-group color. */
    public static AttributedStyle groupStyle() {
        return Theme.active().coordGroup();
    }

    /** artifact/name segment — the theme's coordinate-name color. */
    public static AttributedStyle artifactStyle() {
        return Theme.active().coordName();
    }

    /** version segment — the theme's coordinate-version color. */
    public static AttributedStyle versionStyle() {
        return Theme.active().coordVersion();
    }

    /** artifact short-name (dependency library) — bright-cyan. */
    public static AttributedStyle shortNameStyle() {
        return Theme.active().brightCyan();
    }

    /** {@code [blue]group[/]:[cyan]artifact[/]:[bright-blue]version[/]}. */
    public static String gav(String group, String artifact, String version) {
        return ga(group, artifact) + ":" + Theme.colorize(version, versionStyle());
    }

    /** {@code [blue]group[/]:[cyan]artifact[/]} (no version). */
    public static String ga(String group, String artifact) {
        return Theme.colorize(group, groupStyle()) + ":" + Theme.colorize(artifact, artifactStyle());
    }

    /** Color a {@link Coordinate} as {@code group:artifact:version}. */
    public static String gav(Coordinate coord) {
        return gav(coord.group(), coord.artifact(), coord.version());
    }

    /** Color the version on its own — cyan. */
    public static String version(String version) {
        return Theme.colorize(version, versionStyle());
    }

    /** An artifact short-name / library on its own — bright-cyan. */
    public static String shortName(String name) {
        return Theme.colorize(name, shortNameStyle());
    }

    /**
     * Color a module key with an optional version. The key is the usual {@code group:artifact} form;
     * a key with no {@code ':'} is treated as an artifact short-name and rendered in bright-cyan. A
     * {@code null}/blank version omits the version segment.
     */
    public static String module(String moduleKey, String version) {
        int colon = moduleKey.indexOf(':');
        String colored =
                colon < 0 ? shortName(moduleKey) : ga(moduleKey.substring(0, colon), moduleKey.substring(colon + 1));
        if (version != null && !version.isEmpty()) {
            colored += ":" + Theme.colorize(version, versionStyle());
        }
        return colored;
    }

    /** Color a {@code group:artifact} module key with no version. */
    public static String module(String moduleKey) {
        return module(moduleKey, null);
    }
}
