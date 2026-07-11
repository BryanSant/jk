// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.android;

import dev.jkbuild.plugin.Plugin;
import dev.jkbuild.plugin.PluginManifest;
import dev.jkbuild.plugin.build.Anchor;
import dev.jkbuild.plugin.build.BuildPlugin;
import dev.jkbuild.plugin.build.BuildPluginContext;
import dev.jkbuild.plugin.build.BuildPluginHarness;
import dev.jkbuild.plugin.build.In;
import dev.jkbuild.plugin.build.PackagerSpec;
import dev.jkbuild.plugin.build.StepSpec;
import dev.jkbuild.plugin.build.VerbSpec;
import dev.jkbuild.plugin.protocol.ProtocolWriter;
import java.util.List;

/**
 * The android build plugin's code layer (build-plugins plan §4 P6 — the SPI stress test;
 * android-plan.md Phase 1). Three registrations, all over the public SPI:
 *
 * <ul>
 *   <li><b>android-manifest</b> step (before COMPILE) — Google's manifest-merger over the app
 *       manifest: package from the {@code [android]} namespace, {@code <uses-sdk>} injected.
 *   <li><b>android-res</b> step (before COMPILE) — aapt2 compile + link over {@code res/} and
 *       {@code AndroidManifest.xml}: emits the binary resource package ({@code resources.ap_})
 *       and generates {@code R.java}, contributed to the compiler's source set.
 *   <li><b>android-dex</b> step (after COMPILE) — d8 over the module's compiled classes against
 *       the platform jar: emits {@code classes.dex}.
 *   <li><b>apk</b> packager — assembles the APK from the linked resources + dex, generates a
 *       debug keystore ({@code keytool}), and signs v1+v2 with the plugin's bundled apksig.
 * </ul>
 *
 * <p>Note what is absent, exactly as in the spring-boot blueprint: action keys, cache lookups,
 * CAS paths, jk directory layout. The engine fingerprints the declared inputs ({@code res/},
 * the manifest, config, classes, the fetched tool jars) and skips these bodies on a hit.
 */
public final class AndroidPlugin implements Plugin, BuildPlugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-android", "##JKAN:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        return BuildPluginHarness.run(this, args, out);
    }

    @Override
    public void register(BuildPluginContext ctx) {
        ctx.step(StepSpec.named("android-manifest")
                .after(Anchor.RESOLVE)
                .before(Anchor.COMPILE)
                .inputs(In.projectFiles("AndroidManifest.xml"), In.config())
                .outputs("merged")
                .run(ManifestStep::run));
        ctx.step(StepSpec.named("android-res")
                .after(Anchor.RESOLVE)
                .before(Anchor.COMPILE)
                .inputs(In.projectFiles("res"), In.stepOutput("android-manifest"), In.config())
                .outputs("gen", "packaged")
                .contributesSources("gen")
                .run(ResourceStep::run));
        ctx.step(StepSpec.named("android-dex")
                .after(Anchor.COMPILE)
                .before(Anchor.PACKAGE)
                .inputs(In.classes(), In.config())
                .outputs("dex")
                .run(DexStep::run));
        ctx.packaging(PackagerSpec.replacingMainArtifact("apk")
                .inputs(In.stepOutput("android-res"), In.stepOutput("android-dex"), In.config())
                .produce(ApkPackager::produce));
        ctx.verb(VerbSpec.named("deploy")
                .description("Install the built APK on a device and launch it")
                .run(DeployVerb::run));
        ctx.verb(VerbSpec.named("android")
                .description("Android SDK provisioning: licenses, component status")
                .run(AndroidVerb::run));
    }
}
