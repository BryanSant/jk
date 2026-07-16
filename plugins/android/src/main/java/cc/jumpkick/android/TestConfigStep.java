// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.plugin.build.StepExec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes Robolectric's {@code com/android/tools/test_config.properties} onto the module's test
 * runtime classpath (android-plan §3.6) — the same file AGP generates when {@code
 * isIncludeAndroidResources} is on (jk's only mode for modules with resources): it points the
 * Robolectric test runner at the module's merged manifest, raw resources, and the aapt2-linked
 * binary resource package, so resource-reading tests work without an emulator.
 */
final class TestConfigStep {

    private TestConfigStep() {}

    static void run(StepExec exec) throws Exception {
        Path manifest = exec.requireStepOutput("android-manifest").resolve("merged/AndroidManifest.xml");
        Path res = exec.requireStepOutput("android-res");
        Path rawRes = res.resolve("raw-res");
        Path apk = res.resolve("packaged/resources.ap_");
        String pkg = exec.config().string("namespace");

        Path out = exec.outputDir("cp").resolve("com/android/tools/test_config.properties");
        Files.createDirectories(out.getParent());
        StringBuilder props = new StringBuilder();
        props.append("android_merged_manifest=").append(manifest.toAbsolutePath()).append('\n');
        props.append("android_merged_resources=").append(rawRes.toAbsolutePath()).append('\n');
        // Assets: the project dir's assets/ when present (merged-assets folding is packaging-time).
        Path assets = AndroidDeps.androidFile(exec.moduleDir(), "assets");
        if (Files.isDirectory(assets)) {
            props.append("android_merged_assets=").append(assets.toAbsolutePath()).append('\n');
        }
        props.append("android_resource_apk=").append(apk.toAbsolutePath()).append('\n');
        props.append("android_custom_package=").append(pkg).append('\n');
        Files.writeString(out, props.toString(), StandardCharsets.UTF_8);
        exec.label("test_config.properties for " + pkg);
    }
}
