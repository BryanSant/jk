// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.build;

/**
 * The registration surface handed to {@link BuildPlugin#register}. Registration code may branch on
 * {@link #config()} / {@link #project()} — that is exactly where conditional shape belongs (the
 * manifest's predicate set is deliberately closed; anything richer is code, per the plan's
 * anti-DSL-creep rule). Registrations are recorded, not executed: the engine drives execution
 * later, per step, with resolved inputs.
 */
public interface BuildPluginContext {

    /** The parsed, schema-validated table this plugin owns. */
    dev.jkbuild.plugin.PluginConfig config();

    /** Read-only project facts (coords, resolved main, capability flags). */
    ProjectFacts project();

    /** Register a generated-artifact step (plan §3.2). */
    void step(StepSpec spec);

    /** Register the main-artifact packager (plan §3.3) — at most one per project. */
    void packaging(PackagerSpec spec);

    /** Register a plugin command ({@code jk <name>}, plan row 11) — worker-executed. */
    void verb(VerbSpec spec);

    // ---- reserved hooks -----------------------------------------------------------------
    // run()/nativeImage() shaping is P7 (jk dev's hot-reload hooks). Scaffold and import are
    // manifest data (plan rows 9-10 — pure data, no code hook needed; see [scaffold] and
    // [[import.gradle-plugin]]). Declared now so the blueprint shows the full surface;
    // registering one today is a loud error rather than a silent no-op.

    /** Reserved (P7): run/dev classpath + hot-reload shaping. */
    default void run(RunShape shape) {
        throw new UnsupportedOperationException("RunShape hooks land in a later phase");
    }

    /** Reserved (P7): native-image classpath/args shaping beyond step contributions. */
    default void nativeImage(NativeShape shape) {
        throw new UnsupportedOperationException("NativeShape hooks land in a later phase");
    }
}
