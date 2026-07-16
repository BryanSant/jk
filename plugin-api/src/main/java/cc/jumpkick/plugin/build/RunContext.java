// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import java.util.List;

/**
 * The {@link RunExtension} surface: launches the built module (a dev/hot-reload runner). Adds the
 * verbatim program arguments to the shared terminal read surface. Execution wired in Stream 6.
 */
public interface RunContext extends TerminalContext {

    /** The program arguments passed after {@code jk run}, verbatim. */
    List<String> args();
}
