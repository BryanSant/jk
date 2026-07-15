// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command.ide;

import java.util.List;

/**
 * A strategy that emits project configuration for one IDE from the shared {@link IdeModel}. The
 * model is computed once by {@link IdeSupport#build} and handed to every selected generator, so a
 * generator only turns the resolved workspace/deps/JDK model into that IDE's files.
 */
public interface IdeGenerator {

    /** The IDE this generator targets. */
    IdeTarget target();

    /**
     * Emit the IDE's project files into the workspace described by {@code model}. Returns a short
     * summary of what was written (one line per notable artifact), for the command's output tree.
     */
    List<String> generate(IdeModel model) throws Exception;
}
