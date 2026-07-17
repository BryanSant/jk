// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

/**
 * A plugin capability: own the {@link Phase#PACKAGE} phase by replacing the module's main artifact.
 * The engine invokes {@link #pack} at {@code describe} time to record the packager; the declared
 * {@link PackagerSpec.Body} runs later against a {@link PackageIo}. Implemented alongside {@link
 * cc.jumpkick.plugin.Plugin} (Spring's boot-jar, the shrink plugin).
 */
@FunctionalInterface
public interface PackageExtension {

    /** Declare this plugin's main-artifact packager against {@code ctx}. */
    void pack(PackageContext ctx) throws Exception;
}
