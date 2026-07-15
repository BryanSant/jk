// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.build;

/**
 * A plugin capability: own the terminal {@link Phase#PUBLISH} goal — sign and publish the finished
 * module's artifacts. Terminal-goal execution is wired in the extension-remodel plan's Stream 6.
 * Implemented alongside {@link build.jumpkick.plugin.Plugin}.
 */
@FunctionalInterface
public interface PublishExtension {

    /** Publish the module's artifacts against {@code ctx}. */
    void publish(PublishContext ctx) throws Exception;
}
