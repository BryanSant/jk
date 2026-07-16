// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.plugin.build.StepExec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code android-manifest} step: Google's {@code manifest-merger} over the module's
 * {@code AndroidManifest.xml} — producing {@code merged/AndroidManifest.xml} with the
 * {@code [android]} namespace as the package and {@code <uses-sdk>} injected from
 * {@code min-sdk}/{@code compile-sdk}. Library (AAR) manifests join as {@code --libs} in
 * android-plan Step 2; Step 1 merges the app manifest alone, which already replaces the
 * spike's string-level package injection with the real tool.
 *
 * <p>manifest-merger is a plain JVM library with a real dependency graph — it arrives as a
 * {@code transitive} step-dependency: a directory of jars the step forks {@code java -cp} over.
 */
final class ManifestStep {

    private ManifestStep() {}

    static void run(StepExec exec) throws Exception {
        Path manifest = AndroidDeps.androidFile(exec.moduleDir(), "AndroidManifest.xml");
        if (!Files.isRegularFile(manifest)) {
            // Optional since AGP 7 — a manifest-less library gets a synthesized minimal one
            // (package + uses-sdk injected by the merger properties below, same as AGP).
            // An APPLICATION still needs a real manifest: it declares the launchable surface.
            if (!exec.config().bool("library", false)) {
                throw new IllegalStateException(
                        "an [android] application needs an AndroidManifest.xml at the module root or src/main/");
            }
            manifest = exec.scratch().resolve("synthesized/AndroidManifest.xml");
            Files.createDirectories(manifest.getParent());
            Files.writeString(manifest, "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"/>\n");
        }
        Path mergerLib = exec.requireExtra("manifest-merger");
        String namespace = exec.config().string("namespace");
        long compileSdk = exec.config().intValue("compile-sdk", 0);
        long minSdk = exec.config().intValue("min-sdk", 0);

        Path merged = exec.outputDir("merged").resolve("AndroidManifest.xml");

        // Dependency (AAR) manifests join the merge in classpath order — app first (--main
        // wins), exactly AGP's precedence. APP builds only: a library merges its OWN manifest
        // (AGP semantics — the full closure merges once, at the consuming app; merging dep
        // manifests into a library trips cross-library conflicts like androidx.startup's
        // per-package InitializationProvider authorities).
        boolean library = exec.config().bool("library", false);
        StringBuilder libs = new StringBuilder();
        if (!library) {
            for (AndroidDeps.Aar aar : AndroidDeps.aars(exec.runtimeEntries())) {
                if (!java.nio.file.Files.isRegularFile(aar.manifest())) continue;
                if (libs.length() > 0) libs.append(java.io.File.pathSeparatorChar);
                libs.append(aar.manifest().toAbsolutePath());
            }
        }

        exec.label("merge manifest");
        StepExec.ToolRun merger = exec.java()
                .classpath(jarsIn(mergerLib))
                .mainClass("com.android.manifmerger.Merger")
                .arg("--main")
                .arg(manifest.toAbsolutePath().toString());
        if (libs.length() > 0) {
            merger.arg("--libs").arg(libs.toString());
        }
        // A LIBRARY merge runs in the merger's LIBRARY mode (AGP semantics): placeholders like
        // ${applicationId} stay literal — androidx.startup's per-package provider authorities
        // must all become the APP's id at the one real merge, not each library's namespace, or
        // the app merge sees N conflicting values. An app merge substitutes for real (the
        // merger derives the applicationId placeholder from PACKAGE).
        if (library) {
            merger.arg("--merge-type").arg("LIBRARY");
        } else {
            // The app merge consumes tools: directives (tools:node="remove" etc.) — they must
            // not reach aapt2, which rejects e.g. a <property> left carrying only tools attrs.
            merger.arg("--remove-tools-declarations");
        }
        StepExec.ToolRun.Result result = merger.arg("--property")
                .arg("PACKAGE=" + namespace)
                .arg("--property")
                .arg("MIN_SDK_VERSION=" + minSdk)
                .arg("--property")
                .arg("TARGET_SDK_VERSION=" + compileSdk)
                .arg("--out")
                .arg(merged.toAbsolutePath().toString())
                .run();
        if (result.exit() != 0 || !Files.isRegularFile(merged)) {
            throw new IllegalStateException("manifest-merger failed:\n" + result.output());
        }
    }

    /** The jars of a {@code transitive} step-dependency's lib directory, sorted. */
    static List<Path> jarsIn(Path libDir) throws IOException {
        List<Path> jars = new ArrayList<>();
        try (var listing = Files.list(libDir)) {
            listing.filter(f -> f.toString().endsWith(".jar")).sorted().forEach(jars::add);
        }
        if (jars.isEmpty()) {
            throw new IllegalStateException("no jars in the manifest-merger tool directory: " + libDir);
        }
        return jars;
    }
}
