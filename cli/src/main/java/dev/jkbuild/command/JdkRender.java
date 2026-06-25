// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.theme.Theme;

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
}
