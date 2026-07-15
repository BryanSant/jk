// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.android;

import build.jumpkick.plugin.build.StepExec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code android-r8} step (release / {@code minify = true} — android-plan §3.3): R8 full
 * mode over the whole program — the module's classes plus every runtime entry's code (plain
 * jars, AAR {@code classes.jar}s, workspace library siblings) — against the platform jar,
 * producing shrunk/optimized dex into {@code dex/} and the retrace artifacts into
 * {@code mapping/} ({@code mapping.txt}, {@code seeds.txt}, {@code usage.txt} — the packager
 * copies them to the module's stable {@code target/r8/}).
 *
 * <p>Keep rules, in order: the plugin's baseline rules (attribute keeps — R8 full mode's own
 * defaults do the heavy lifting; AGP's {@code proguard-android-optimize.txt} is mostly
 * historical), aapt2's generated rules (manifest components: activities, services — from the
 * {@code android-res} link), the AAR dependencies' consumer rules ({@code proguard.txt} — A2
 * stored them in the exploded container), and the app's own {@code proguard-files}. Missing
 * classes are hard errors (full-mode default) and surface verbatim as the build failure.
 */
final class R8Step {

    private R8Step() {}

    /** The baseline rules every release build gets — kept tiny on purpose. */
    private static final String BASELINE_RULES =
            """
            # jk android baseline (R8 full mode carries the real defaults)
            -keepattributes Signature,InnerClasses,EnclosingMethod
            -keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
            -keepattributes RuntimeVisibleTypeAnnotations,AnnotationDefault
            -keepattributes SourceFile,LineNumberTable
            -renamesourcefileattribute SourceFile
            """;

    static void run(StepExec exec) throws Exception {
        Path r8 = exec.requireExtra("r8");
        Path platformJar = jarNamed(exec, exec.requireExtra("android-jar"), "android-platform.jar");
        Path dexOut = exec.outputDir("dex");
        Path mappingOut = exec.outputDir("mapping");
        long minSdk = exec.config().intValue("min-sdk", 0);

        List<Path> classFiles = ResourceStep.filesUnder(exec.classesDir(), ".class");
        if (classFiles.isEmpty()) {
            throw new IllegalStateException("no compiled classes to shrink under " + exec.classesDir());
        }
        List<Path> runtimeJars = new ArrayList<>();
        for (var entry : exec.runtimeEntries()) {
            if (entry.jar() != null) runtimeJars.add(entry.jar());
        }

        // Keep-rule collection, baseline → aapt2 → consumer rules → the app's own files.
        List<Path> rules = new ArrayList<>();
        Path baseline = exec.scratch().resolve("baseline-rules.pro");
        Files.writeString(baseline, BASELINE_RULES);
        rules.add(baseline);
        Path aaptRules = exec.requireStepOutput("android-res").resolve("packaged/keep-rules.pro");
        if (Files.isRegularFile(aaptRules)) rules.add(aaptRules);
        for (var entry : exec.runtimeEntries()) {
            if (entry.container() == null) continue;
            Path consumer = entry.container().resolve("proguard.txt");
            if (Files.isRegularFile(consumer)) rules.add(consumer);
        }
        for (String rel : exec.config().stringList("proguard-files")) {
            Path file = exec.moduleDir().resolve(rel);
            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException("proguard-files names a missing file: " + rel);
            }
            rules.add(file);
        }
        // Retrace outputs ride a generated fragment (they are -print* directives, not flags).
        Path outputs = exec.scratch().resolve("output-rules.pro");
        Files.writeString(
                outputs,
                "-printseeds " + mappingOut.resolve("seeds.txt").toAbsolutePath() + "\n"
                        + "-printusage " + mappingOut.resolve("usage.txt").toAbsolutePath() + "\n");
        rules.add(outputs);

        exec.label("R8 (" + classFiles.size() + " classes + " + runtimeJars.size() + " jars)");
        StepExec.ToolRun run = exec.java()
                .classpath(List.of(r8))
                .mainClass("com.android.tools.r8.R8")
                .arg("--release")
                .arg("--lib")
                .arg(platformJar.toAbsolutePath().toString())
                .arg("--min-api")
                .arg(Long.toString(minSdk))
                .arg("--output")
                .arg(dexOut.toAbsolutePath().toString())
                .arg("--pg-map-output")
                .arg(mappingOut.resolve("mapping.txt").toAbsolutePath().toString());
        for (Path conf : rules) {
            run.arg("--pg-conf").arg(conf.toAbsolutePath().toString());
        }
        for (Path f : classFiles) run.arg(f.toAbsolutePath().toString());
        for (Path jar : runtimeJars) run.arg(jar.toAbsolutePath().toString());
        StepExec.ToolRun.Result result = run.run();
        if (result.exit() != 0) {
            throw new IllegalStateException("R8 failed:\n" + result.output());
        }
    }

    /** A {@code .jar}-suffixed alias of {@code source} under the step's scratch (d8/r8 judge by extension). */
    private static Path jarNamed(StepExec exec, Path source, String name) throws IOException {
        Path alias = Files.createDirectories(exec.scratch().resolve("tools")).resolve(name);
        Files.deleteIfExists(alias);
        try {
            Files.createLink(alias, source);
        } catch (IOException | UnsupportedOperationException e) {
            Files.copy(source, alias);
        }
        return alias;
    }
}
