// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.android;

import build.jumpkick.plugin.Plugin;
import build.jumpkick.plugin.PluginManifest;
import build.jumpkick.plugin.build.Phase;
import build.jumpkick.plugin.build.BuildPlugin;
import build.jumpkick.plugin.build.BuildPluginContext;
import build.jumpkick.plugin.build.BuildPluginHarness;
import build.jumpkick.plugin.build.In;
import build.jumpkick.plugin.build.PackagerSpec;
import build.jumpkick.plugin.build.StepSpec;
import build.jumpkick.plugin.build.PluginCommandSpec;
import build.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.List;

/**
 * The android build plugin's code layer (build-plugins plan §4 P6 — the SPI stress test;
 * android-plan.md Step 1). Three registrations, all over the public SPI:
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
        boolean library = ctx.config().bool("library", false);
        // The effective config carries the selected build type (VariantApply injected it);
        // release defaults minify ON (AGP-9 posture) — the overlay's tri-state `minify` overrides.
        boolean release = "release".equals(ctx.config().stringOpt("build-type").orElse("debug"));
        boolean minify = ctx.config().bool("minify").orElse(release && !library);
        // Both manifest/res locations are declared inputs: jk's simple layout at the module
        // root, and the AGP/traditional src/main/ home — the steps read whichever exists.
        ctx.step(StepSpec.named("android-manifest")
                .after(Phase.RESOLVE)
                .before(Phase.COMPILE)
                .inputs(
                        In.projectFiles("AndroidManifest.xml"),
                        In.projectFiles("src/main/AndroidManifest.xml"),
                        In.runtimeEntries(),
                        In.config())
                .outputs("merged")
                .run(ManifestStep::run));
        ctx.step(StepSpec.named("android-res")
                .after(Phase.RESOLVE)
                .before(Phase.COMPILE)
                .inputs(
                        In.projectFiles("res"),
                        In.projectFiles("src/main/res"),
                        In.stepOutput("android-manifest"),
                        In.runtimeEntries(),
                        In.config())
                .outputs("gen", "packaged", "raw-res")
                .contributesSources("gen")
                .run(ResourceStep::run));
        // Robolectric wiring (android-plan §3.6): a test_config.properties dir on the module's
        // test runtime classpath, pointing at the merged manifest + linked resources.
        ctx.step(StepSpec.named("android-test-config")
                .after(Phase.RESOLVE)
                .before(Phase.TEST)
                .inputs(In.stepOutput("android-manifest"), In.stepOutput("android-res"), In.config())
                .outputs("cp")
                .contributesTestClasspath("cp")
                .run(TestConfigStep::run));
        if (ctx.config().bool("build-config", false)) {
            ctx.step(StepSpec.named("android-buildconfig")
                    .after(Phase.RESOLVE)
                    .before(Phase.COMPILE)
                    .inputs(In.config())
                    .outputs("gen")
                    .contributesSources("gen")
                    .run(BuildConfigStep::run));
        }
        if (ctx.config().bool("hilt", false) && !library) {
            // Hilt unmodified-source parity (android-plan Step 5, blocker 4): rewrite
            // @AndroidEntryPoint/@HiltAndroidApp superclasses to the KSP-generated Hilt_*
            // bases — the transform AGP's Hilt plugin runs, over the generic classes-transform
            // SPI. Dex consumes the transformed dir (its In.classes() reads the replacement).
            ctx.step(StepSpec.named("android-hilt-transform")
                    .after(Phase.COMPILE)
                    .before(Phase.PACKAGE)
                    .inputs(In.classes(), In.config())
                    .outputs("classes")
                    .transformsClasses("classes")
                    .run(HiltTransformStep::run));
        }
        if (library) {
            // A library packages an AAR (classes.jar without R classes + raw res + merged
            // manifest + R.txt) and is never dexed or deployed — consumers dex it.
            ctx.packaging(PackagerSpec.replacingMainArtifact("aar")
                    .inputs(In.classes(), In.stepOutput("android-res"), In.stepOutput("android-manifest"),
                            In.config())
                    .produce(AarPackager::produce));
        } else {
            String dexStep;
            if (minify) {
                // Release: R8 full mode, whole-program — keep rules from the plugin baseline,
                // aapt2's generated rules, AAR consumer rules, and the app's proguard-files
                // (declared inputs, so a rules edit re-shrinks).
                dexStep = "android-r8";
                List<In> r8Inputs = new java.util.ArrayList<>(List.of(
                        In.classes(), In.runtimeEntries(), In.stepOutput("android-res"), In.config()));
                for (String rel : ctx.config().stringList("proguard-files")) {
                    r8Inputs.add(In.projectFiles(rel));
                }
                ctx.step(StepSpec.named("android-r8")
                        .after(Phase.COMPILE)
                        .before(Phase.PACKAGE)
                        .inputs(r8Inputs.toArray(new In[0]))
                        .outputs("dex", "mapping")
                        .run(R8Step::run));
            } else {
                dexStep = "android-dex";
                ctx.step(StepSpec.named("android-dex")
                        .after(Phase.COMPILE)
                        .before(Phase.PACKAGE)
                        .inputs(In.classes(), In.runtimeEntries(), In.config())
                        .outputs("dex")
                        .run(DexStep::run));
            }
            if (release) {
                // The release artifact is the Play-uploadable AAB ([[packaging.variant]] picks
                // the extension); bundletool assembles from the proto-format link.
                ctx.packaging(PackagerSpec.replacingMainArtifact("aab")
                        .inputs(In.stepOutput("android-res"), In.stepOutput(dexStep), In.runtimeEntries(),
                                In.projectFiles("assets"), In.projectFiles("src/main/assets"), In.config())
                        .produce(AabPackager::produce));
            } else {
                ctx.packaging(PackagerSpec.replacingMainArtifact("apk")
                        .inputs(In.stepOutput("android-res"), In.stepOutput(dexStep), In.runtimeEntries(),
                                In.projectFiles("assets"), In.projectFiles("src/main/assets"), In.config())
                        .produce(ApkPackager::produce));
            }
            ctx.command(PluginCommandSpec.named("deploy")
                    .description("Install the built APK/AAB on a device and launch it")
                    .run(DeployCommand::run));
            ctx.command(PluginCommandSpec.named("instrument")
                    .description("Run instrumented tests on a device (am instrument)")
                    .run(InstrumentCommand::run));
        }
        ctx.command(PluginCommandSpec.named("android")
                .description("Android SDK provisioning: licenses, component status")
                .run(AndroidCommand::run));
        ctx.command(PluginCommandSpec.named("avd")
                .description("Managed virtual devices: create, list, boot headless")
                .run(AvdCommand::run));
    }

}
