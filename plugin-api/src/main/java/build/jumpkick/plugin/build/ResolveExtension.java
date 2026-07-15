// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.build;

/**
 * A plugin capability: participate in the {@link Phase#RESOLVE} phase. The engine invokes {@link
 * #resolve} at {@code describe} time to record the plugin's RESOLVE-window step(s); the declared
 * {@link StepSpec.Body} runs later with resolved inputs. Implemented alongside {@link
 * build.jumpkick.plugin.Plugin}.
 */
@FunctionalInterface
public interface ResolveExtension {

    /** Declare this plugin's RESOLVE-window contribution against {@code ctx}. */
    void resolve(ResolveContext ctx) throws Exception;
}
