// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.protobuf;

import build.jumpkick.plugin.Plugin;
import build.jumpkick.plugin.PluginManifest;
import build.jumpkick.plugin.build.Phase;
import build.jumpkick.plugin.build.BuildPlugin;
import build.jumpkick.plugin.build.BuildPluginContext;
import build.jumpkick.plugin.build.BuildPluginHarness;
import build.jumpkick.plugin.build.In;
import build.jumpkick.plugin.build.StepSpec;
import build.jumpkick.plugin.protocol.ProtocolWriter;
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
                .after(Phase.RESOLVE)
                .before(Phase.COMPILE)
                .inputs(In.projectFiles(src), In.config())
                .outputs("gen")
                .contributesSources("gen")
                .run(ProtocStep::run));
    }
}
