// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One generated-artifact build step (build-plugins plan §3.2): declared inputs → tool run →
 * declared outputs. The author's whole mental model — the engine owns <em>when</em>
 * (incrementality: declared inputs are fingerprinted into the action key) and <em>whether it can
 * be skipped</em> (a cache hit materializes the prior outputs and never calls {@link #body()}).
 *
 * <p>Output dirs are relative to a jk-owned per-module scratch; {@code contributes*} declarations
 * fold an output dir into packaging, the native-image classpath, or the compiler's source set
 * without the plugin ever learning where those live.
 */
public final class StepSpec {

    /** The step's execution body — runs in the plugin's worker JVM, never in the engine. */
    @FunctionalInterface
    public interface Body {
        void run(StepExec exec) throws Exception;
    }

    private final String name;
    private Phase after = Phase.COMPILE;
    private Phase before = Phase.PACKAGE;
    private final List<In> inputs = new ArrayList<>();
    private final List<String> outputs = new ArrayList<>();
    private final List<String> contributesClasses = new ArrayList<>();
    private final List<String> contributesResources = new ArrayList<>();
    private final List<String> contributesSources = new ArrayList<>();
    private final List<String> contributesTestClasspath = new ArrayList<>();
    private String transformsClasses;
    private Body body;

    private StepSpec(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public static StepSpec named(String name) {
        return new StepSpec(name);
    }

    public StepSpec after(Phase anchor) {
        this.after = anchor;
        return this;
    }

    public StepSpec before(Phase anchor) {
        this.before = anchor;
        return this;
    }

    public StepSpec inputs(In... ins) {
        for (In in : ins) inputs.add(in);
        return this;
    }

    /** Declare an output dir (relative to the step's scratch root). */
    public StepSpec outputs(String... dirs) {
        for (String d : dirs) outputs.add(d);
        return this;
    }

    /** Fold {@code relDir} (a declared output) into packaging + the native-image classpath as classes. */
    public StepSpec contributesClasses(String relDir) {
        contributesClasses.add(relDir);
        return this;
    }

    /** Fold {@code relDir} into packaging + the native-image classpath as resources. */
    public StepSpec contributesResources(String relDir) {
        contributesResources.add(relDir);
        return this;
    }

    /** Feed {@code relDir} into the compiler's source set (before-COMPILE source generation). */
    public StepSpec contributesSources(String relDir) {
        contributesSources.add(relDir);
        return this;
    }

    /**
     * Append {@code relDir} (a declared output) to the module's <em>test runtime classpath</em> —
     * for test-harness wiring a framework reads off the classpath (android-plan §3.6: Robolectric's
     * {@code com/android/tools/test_config.properties} pointing at the merged manifest/resources).
     */
    public StepSpec contributesTestClasspath(String relDir) {
        contributesTestClasspath.add(relDir);
        return this;
    }

    /**
     * Declare {@code relDir} (a declared output) as the module's classes-dir <em>replacement</em>:
     * everything downstream of this step — packaging, later steps' {@link In#classes()}, the
     * native-image classpath — sees {@code relDir} instead of the compiler's output. The step must
     * run in the COMPILE→PACKAGE window, declare {@link In#classes()} (the dir it transforms), and
     * copy through every class it doesn't rewrite: the output IS the classes dir afterwards. At
     * most one step per build may transform — a second declaration is an error, not a priority
     * (bytecode weaving: Hilt's superclass rewrite, OTel agents' build-time weaving, entity
     * enhancement). Unit tests run against the untransformed classes (they execute before
     * packaging); a transform is an artifact-shaping step, not a test fixture.
     */
    public StepSpec transformsClasses(String relDir) {
        this.transformsClasses = relDir;
        return this;
    }

    public StepSpec run(Body body) {
        this.body = body;
        return this;
    }

    public String name() {
        return name;
    }

    public Phase afterPhase() {
        return after;
    }

    public Phase beforePhase() {
        return before;
    }

    public List<In> declaredInputs() {
        return List.copyOf(inputs);
    }

    public List<String> declaredOutputs() {
        return List.copyOf(outputs);
    }

    public List<String> classesContributions() {
        return List.copyOf(contributesClasses);
    }

    public List<String> resourcesContributions() {
        return List.copyOf(contributesResources);
    }

    public List<String> sourcesContributions() {
        return List.copyOf(contributesSources);
    }

    public List<String> testClasspathContributions() {
        return List.copyOf(contributesTestClasspath);
    }

    /** The classes-replacing output dir ({@link #transformsClasses}), or null. */
    public String classesTransform() {
        return transformsClasses;
    }

    public Body body() {
        return body;
    }
}
