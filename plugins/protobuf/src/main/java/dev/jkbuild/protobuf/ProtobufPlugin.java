// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.protobuf;

import dev.jkbuild.plugin.Plugin;
import dev.jkbuild.plugin.PluginManifest;
import dev.jkbuild.plugin.build.Anchor;
import dev.jkbuild.plugin.build.BuildPlugin;
import dev.jkbuild.plugin.build.BuildPluginContext;
import dev.jkbuild.plugin.build.BuildPluginHarness;
import dev.jkbuild.plugin.build.In;
import dev.jkbuild.plugin.build.StepSpec;
import dev.jkbuild.plugin.protocol.ProtocolWriter;
import java.util.List;

/**
 * The protobuf build plugin's code layer: one registration — a {@code protoc} step (before
 * COMPILE) that runs the provisioned protoc binary over the module's proto sources and
 * contributes the generated Java to the compiler's source set. The engine fingerprints the
 * declared inputs (the proto dir, config, the fetched protoc binary) and skips the body on a
 * cache hit — no plugin-side staleness logic.
 */
public final class ProtobufPlugin implements Plugin, BuildPlugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-protobuf", "##JKPB:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void register(BuildPluginContext ctx) {
        String src = ctx.config().stringOpt("src").orElse("proto");
        ctx.step(StepSpec.named("protoc")
                .after(Anchor.RESOLVE)
                .before(Anchor.COMPILE)
                .inputs(In.projectFiles(src), In.config())
                .outputs("gen")
                .contributesSources("gen")
                .run(ProtocStep::run));
    }
}
