// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.shrink;

import build.jumpkick.plugin.Plugin;
import build.jumpkick.plugin.PluginManifest;
import build.jumpkick.plugin.build.BuildPluginHarness;
import build.jumpkick.plugin.build.In;
import build.jumpkick.plugin.build.PackageContext;
import build.jumpkick.plugin.build.PackageExtension;
import build.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * The shrink build plugin's code layer: one PACKAGE-phase contribution — the {@code shrunk-jar}
 * packager, which runs R8 (--classfile, full mode) over the module's classes + runtime closure and
 * packages the surviving classes as one slim executable jar. Keep-rule files are declared inputs,
 * so a rules edit re-shrinks; everything else the engine fingerprints as usual.
 */
public final class ShrinkPlugin implements Plugin, PackageExtension {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-shrink", "##JKSH:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void pack(PackageContext ctx) {
        List<In> inputs = new ArrayList<>(List.of(In.classes(), In.runtimeEntries(), In.config()));
        for (String rel : ctx.config().stringList("keep-files")) {
            inputs.add(In.projectFiles(rel));
        }
        ctx.inputs(inputs.toArray(new In[0])).produce("shrunk-jar", ShrunkJarPackager::produce);
    }
}
