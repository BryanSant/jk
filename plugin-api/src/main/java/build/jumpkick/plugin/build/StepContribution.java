// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.build;

import build.jumpkick.plugin.PluginConfig;

/**
 * The shared contribution surface behind the step-oriented phase capabilities ({@link
 * ResolveExtension}, {@link BuildExtension}, {@link TestExtension}). It fronts a single <em>implicit
 * step</em> — named after the plugin unless {@link #named renamed} — so the common plugin "declares
 * once": set its window/inputs/outputs/contributions on the context, then hand it a body with {@link
 * #run}. Plugins that need more than one step (Android) add further named steps with {@link #step}.
 *
 * <p>This adds no new wire: the capability layer records ordinary {@link StepSpec}s against the same
 * {@link BuildPluginContext} the {@link BuildPluginHarness} already drives. Declaration still happens
 * at {@code describe} (cached, input-free); the {@link StepSpec.Body} runs later with resolved inputs.
 */
public interface StepContribution {

    /** The plugin's validated config table. */
    PluginConfig config();

    /** Read-only project facts (coords, resolved main, capability flags). */
    ProjectFacts project();

    /** Rename the implicit step (default: the plugin id). Needed when another step chains to it. */
    StepContribution named(String name);

    /** The phase this step orders itself after (default {@link Phase#COMPILE}). */
    StepContribution after(Phase phase);

    /** The phase this step orders itself before (default {@link Phase#PACKAGE}). */
    StepContribution before(Phase phase);

    /** Declare the implicit step's inputs (fingerprinted into its action key). */
    StepContribution inputs(In... ins);

    /** Declare output dirs (relative to the step's scratch root). */
    StepContribution outputs(String... dirs);

    /** Fold a declared output dir into the compiler's source set (before-COMPILE source generation). */
    StepContribution contributesSources(String relDir);

    /** Fold a declared output dir into packaging + the native-image classpath as classes. */
    StepContribution contributesClasses(String relDir);

    /** Fold a declared output dir into packaging + the native-image classpath as resources. */
    StepContribution contributesResources(String relDir);

    /** Append a declared output dir to the module's test runtime classpath. */
    StepContribution contributesTestClasspath(String relDir);

    /** Declare a declared output dir as the module's classes-dir replacement (at most one per build). */
    StepContribution transformsClasses(String relDir);

    /** Provide the implicit step's body and register it. Calling this twice is an error. */
    void run(StepSpec.Body body);

    /** Escape hatch: register an additional, fully-specified named step. */
    void step(StepSpec spec);
}
