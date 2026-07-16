// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.protobuf;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.BuildContext;
import cc.jumpkick.plugin.build.BuildExtension;
import cc.jumpkick.plugin.build.BuildPluginHarness;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.plugin.build.Phase;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.List;

/**
 * The protobuf build plugin's code layer: one COMPILE-phase contribution — a {@code protoc} step
 * (before COMPILE) that runs the provisioned protoc binary over the module's proto sources and
 * contributes the generated Java to the compiler's source set. The engine fingerprints the
 * declared inputs (the proto dir, config, the fetched protoc binary) and skips the body on a
 * cache hit — no plugin-side staleness logic.
 */
public final class ProtoCompiler implements Plugin, BuildExtension {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-protobuf", "##JKPB:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void build(BuildContext ctx) {
        String src = ctx.config().stringOpt("src").orElse("proto");
        ctx.named("protoc")
                .after(Phase.RESOLVE)
                .before(Phase.COMPILE)
                .inputs(In.projectFiles(src), In.config())
                .outputs("gen")
                .contributesSources("gen")
                .run(ProtocStep::run);
    }
}
