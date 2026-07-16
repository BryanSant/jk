// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import cc.jumpkick.plugin.PluginConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@link BuildPluginHarness}-supplied implementation of the step-oriented capability contexts.
 * It accumulates the implicit step's declarations and materializes a single {@link StepSpec} the
 * moment {@link #run} is called, registering it against the wrapped {@link BuildPluginContext} — so
 * the capability layer produces exactly the declarations {@code register()} would have.
 */
final class DefaultStepContext implements BuildContext, ResolveContext, TestContext {

    private final BuildPluginContext ctx;
    private String name;
    private Phase after;
    private Phase before;
    private final List<In> inputs = new ArrayList<>();
    private final List<String> outputs = new ArrayList<>();
    private final List<String> contributesSources = new ArrayList<>();
    private final List<String> contributesClasses = new ArrayList<>();
    private final List<String> contributesResources = new ArrayList<>();
    private final List<String> contributesTestClasspath = new ArrayList<>();
    private String transformsClasses;
    private boolean bodyRun;

    DefaultStepContext(BuildPluginContext ctx, String defaultName, Phase after, Phase before) {
        this.ctx = ctx;
        this.name = defaultName;
        this.after = after;
        this.before = before;
    }

    @Override
    public PluginConfig config() {
        return ctx.config();
    }

    @Override
    public ProjectFacts project() {
        return ctx.project();
    }

    @Override
    public StepContribution named(String name) {
        this.name = name;
        return this;
    }

    @Override
    public StepContribution after(Phase phase) {
        this.after = phase;
        return this;
    }

    @Override
    public StepContribution before(Phase phase) {
        this.before = phase;
        return this;
    }

    @Override
    public StepContribution inputs(In... ins) {
        for (In in : ins) inputs.add(in);
        return this;
    }

    @Override
    public StepContribution outputs(String... dirs) {
        for (String d : dirs) outputs.add(d);
        return this;
    }

    @Override
    public StepContribution contributesSources(String relDir) {
        contributesSources.add(relDir);
        return this;
    }

    @Override
    public StepContribution contributesClasses(String relDir) {
        contributesClasses.add(relDir);
        return this;
    }

    @Override
    public StepContribution contributesResources(String relDir) {
        contributesResources.add(relDir);
        return this;
    }

    @Override
    public StepContribution contributesTestClasspath(String relDir) {
        contributesTestClasspath.add(relDir);
        return this;
    }

    @Override
    public StepContribution transformsClasses(String relDir) {
        this.transformsClasses = relDir;
        return this;
    }

    @Override
    public void run(StepSpec.Body body) {
        if (bodyRun) {
            throw new IllegalStateException("the implicit step's body is already set for `" + name
                    + "` — register additional steps with step(StepSpec)");
        }
        bodyRun = true;
        StepSpec spec = StepSpec.named(name).after(after).before(before);
        spec.inputs(inputs.toArray(new In[0]));
        spec.outputs(outputs.toArray(new String[0]));
        for (String d : contributesSources) spec.contributesSources(d);
        for (String d : contributesClasses) spec.contributesClasses(d);
        for (String d : contributesResources) spec.contributesResources(d);
        for (String d : contributesTestClasspath) spec.contributesTestClasspath(d);
        if (transformsClasses != null) spec.transformsClasses(transformsClasses);
        spec.run(body);
        ctx.step(spec);
    }

    @Override
    public void step(StepSpec spec) {
        ctx.step(spec);
    }
}
