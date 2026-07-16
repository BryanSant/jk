// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

/**
 * A plugin capability: own the terminal {@link Phase#PUBLISH} goal — sign and publish the finished
 * module's artifacts. The plugin's worker entry builds a {@link PublishContext} (main artifact +
 * repo/signing config + secrets) and calls {@link #publish}, then reports the returned {@link
 * PublishResult}. Implemented alongside {@link cc.jumpkick.plugin.Plugin}.
 */
@FunctionalInterface
public interface PublishExtension {

    /** Sign and publish the module's artifacts described by {@code ctx}; return what was handled. */
    PublishResult publish(PublishContext ctx) throws Exception;
}
