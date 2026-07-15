// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.build;

/**
 * A plugin capability: own the terminal {@link Phase#RUN} goal — launch the built module. Terminal
 * goals consume the finished module rather than contributing a step; their execution is wired in the
 * extension-remodel plan's Stream 6. Implemented alongside {@link build.jumpkick.plugin.Plugin}.
 */
@FunctionalInterface
public interface RunExtension {

    /** Launch the built module against {@code ctx}; return the process exit code. */
    int run(RunContext ctx) throws Exception;
}
