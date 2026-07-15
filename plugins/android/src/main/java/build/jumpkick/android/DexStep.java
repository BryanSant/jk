// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.android;

import build.jumpkick.plugin.build.StepExec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code android-dex} step: D8 (from the r8 jar) over the module's compiled classes against
 * the platform jar — {@code classes.dex} into the step's {@code dex/} output. Debug-shaped
 * (no shrinking; R8 full mode is android-plan Step 3).
 */
final class DexStep {

    private DexStep() {}

    static void run(StepExec exec) throws Exception {
        Path r8 = exec.requireExtra("r8");
        // d8 judges --lib inputs by extension; the fetched blob carries none — give it one.
        Path platformJar = jarNamed(exec, exec.requireExtra("android-jar"), "android-platform.jar");
        Path dexOut = exec.outputDir("dex");
        List<Path> classFiles = ResourceStep.filesUnder(exec.classesDir(), ".class");
        if (classFiles.isEmpty()) {
            throw new IllegalStateException("no compiled classes to dex under " + exec.classesDir());
        }
        long minSdk = exec.config().intValue("min-sdk", 0);

        // The whole production runtime dexes into the app: the module's classes plus every
        // runtime entry's code — plain jars, AAR classes.jars, workspace library siblings.
        // (The platform never does: it is --lib, the compile/desugar boundary.)
        List<Path> runtimeJars = new ArrayList<>();
        for (var entry : exec.runtimeEntries()) {
            if (entry.jar() != null) runtimeJars.add(entry.jar());
        }

        exec.label("d8 (" + classFiles.size() + " classes + " + runtimeJars.size() + " jars)");
        StepExec.ToolRun d8 = exec.java()
                .classpath(List.of(r8))
                .mainClass("com.android.tools.r8.D8")
                .arg("--lib")
                .arg(platformJar.toAbsolutePath().toString())
                .arg("--min-api")
                .arg(Long.toString(minSdk))
                .arg("--output")
                .arg(dexOut.toAbsolutePath().toString());
        for (Path f : classFiles) d8.arg(f.toAbsolutePath().toString());
        for (Path jar : runtimeJars) d8.arg(jar.toAbsolutePath().toString());
        StepExec.ToolRun.Result result = d8.run();
        if (result.exit() != 0) {
            throw new IllegalStateException("d8 failed:\n" + result.output());
        }
    }

    /** A {@code .jar}-suffixed alias of {@code source} under the step's scratch. */
    private static Path jarNamed(StepExec exec, Path source, String name) throws java.io.IOException {
        Path alias = java.nio.file.Files.createDirectories(exec.scratch().resolve("tools")).resolve(name);
        java.nio.file.Files.deleteIfExists(alias);
        try {
            java.nio.file.Files.createLink(alias, source);
        } catch (java.io.IOException | UnsupportedOperationException e) {
            java.nio.file.Files.copy(source, alias);
        }
        return alias;
    }
}
