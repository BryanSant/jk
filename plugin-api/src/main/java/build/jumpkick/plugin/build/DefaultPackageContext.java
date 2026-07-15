// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.build;

import build.jumpkick.plugin.PluginConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@link BuildPluginHarness}-supplied implementation of {@link PackageContext}: accumulates the
 * packager's inputs and registers a single {@link PackagerSpec} against the wrapped {@link
 * BuildPluginContext} the moment {@link #produce} is called.
 */
final class DefaultPackageContext implements PackageContext {

    private final BuildPluginContext ctx;
    private final List<In> inputs = new ArrayList<>();
    private boolean produced;

    DefaultPackageContext(BuildPluginContext ctx) {
        this.ctx = ctx;
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
    public PackageContext inputs(In... ins) {
        for (In in : ins) inputs.add(in);
        return this;
    }

    @Override
    public void produce(String name, PackagerSpec.Body body) {
        if (produced) {
            throw new IllegalStateException("a packager is already declared — one packager may replace the"
                    + " main artifact");
        }
        produced = true;
        ctx.packaging(PackagerSpec.replacingMainArtifact(name).inputs(inputs.toArray(new In[0])).produce(body));
    }
}
