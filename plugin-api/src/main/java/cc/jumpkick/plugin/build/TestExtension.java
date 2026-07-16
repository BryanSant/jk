// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

/**
 * A plugin capability: participate in the {@link Phase#TEST} phase — wire test-time inputs a
 * framework reads off the classpath. The engine invokes {@link #test} at {@code describe} time to
 * record the plugin's pre-TEST step(s); the declared {@link StepSpec.Body} runs later with resolved
 * inputs. Implemented alongside {@link cc.jumpkick.plugin.Plugin}.
 */
@FunctionalInterface
public interface TestExtension {

    /** Declare this plugin's TEST-window contribution against {@code ctx}. */
    void test(TestContext ctx) throws Exception;
}
