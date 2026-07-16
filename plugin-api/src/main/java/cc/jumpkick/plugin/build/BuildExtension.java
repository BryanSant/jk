// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

/**
 * A plugin capability: participate in the {@link Phase#COMPILE} phase — before-compile source
 * generation or after-compile class production. The engine invokes {@link #build} at {@code
 * describe} time to record the plugin's step(s); the declared {@link StepSpec.Body} runs later with
 * resolved inputs. Implemented alongside {@link cc.jumpkick.plugin.Plugin} (protobuf, Spring's
 * AOT step).
 */
@FunctionalInterface
public interface BuildExtension {

    /** Declare this plugin's COMPILE-window contribution against {@code ctx}. */
    void build(BuildContext ctx) throws Exception;
}
