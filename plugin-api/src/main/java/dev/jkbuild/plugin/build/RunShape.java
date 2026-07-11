// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.build;

import java.util.List;

/**
 * Reserved (P7): how a plugin shapes {@code jk run}/{@code jk dev} — extra dev-classpath entries
 * (DevTools-style hot-reload agents) and the hot-reload capability flag. The static artifact
 * shape (exec mode, self-contained) is manifest data ({@code [packaging]}), not code.
 */
public record RunShape(List<String> devClasspathExtraCoordinates, boolean hotReloadCapable) {}
