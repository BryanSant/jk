// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.android;

import dev.jkbuild.plugin.build.StepExec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The {@code android-res} step: aapt2 compile over {@code res/}, then aapt2 link against the
 * platform jar — producing the binary resource package ({@code packaged/resources.ap_}, with the
 * compiled manifest and {@code resources.arsc} inside) and the generated {@code R.java} under
 * {@code gen/} (contributed to the compiler's source set).
 *
 * <p>aapt2 ships as a per-OS native binary wrapped in a Maven jar; the step extracts it into its
 * scratch and forks it — the engine fetched the jar (a declared {@code step-dependency}), the
 * step never learns from where.
 */
final class ResourceStep {

    private ResourceStep() {}

    static void run(StepExec exec) throws Exception {
        Path aapt2 = extractAapt2(exec);
        Path platformJar = exec.requireExtra("android-jar");
        Path res = exec.moduleDir().resolve("res");
        Path manifest = exec.moduleDir().resolve("AndroidManifest.xml");
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalStateException("an [android] project needs an AndroidManifest.xml at the module root");
        }
        String namespace = exec.config().string("namespace");
        long compileSdk = exec.config().intValue("compile-sdk", 0);
        long minSdk = exec.config().intValue("min-sdk", 0);

        Path gen = exec.outputDir("gen");
        Path packaged = exec.outputDir("packaged");
        Path work = Files.createDirectories(exec.scratch().resolve("work"));

        // AGP-9 namespace posture: the manifest may omit `package` — the [android] namespace is
        // the source of truth; inject it for aapt2, which still requires the attribute.
        Path effectiveManifest = manifestWithPackage(manifest, namespace, work);

        // aapt2 compile: res/** -> a container of .flat entries (skipped entirely when the
        // project declares no res dir — a manifest-only app is legal).
        Path flats = work.resolve("res-compiled.zip");
        boolean hasRes = Files.isDirectory(res);
        if (hasRes) {
            exec.label("aapt2 compile");
            StepExec.ToolRun.Result compile = exec.tool(aapt2)
                    .arg("compile")
                    .arg("--dir")
                    .arg(res.toAbsolutePath().toString())
                    .arg("-o")
                    .arg(flats.toAbsolutePath().toString())
                    .run();
            if (compile.exit() != 0) {
                throw new IllegalStateException("aapt2 compile failed:\n" + compile.output());
            }
        }

        // aapt2 link: manifest + compiled resources against the platform -> resources.ap_ + R.java.
        exec.label("aapt2 link");
        StepExec.ToolRun link = exec.tool(aapt2)
                .arg("link")
                .arg("-o")
                .arg(packaged.resolve("resources.ap_").toAbsolutePath().toString())
                .arg("-I")
                .arg(platformJar.toAbsolutePath().toString())
                .arg("--manifest")
                .arg(effectiveManifest.toAbsolutePath().toString())
                .arg("--java")
                .arg(gen.toAbsolutePath().toString())
                .arg("--custom-package")
                .arg(namespace)
                .arg("--min-sdk-version")
                .arg(Long.toString(minSdk))
                .arg("--target-sdk-version")
                .arg(Long.toString(compileSdk))
                .arg("--version-code")
                .arg("1")
                .arg("--version-name")
                .arg(exec.project().version())
                .arg("--auto-add-overlay");
        if (hasRes) link.arg(flats.toAbsolutePath().toString());
        StepExec.ToolRun.Result linked = link.run();
        if (linked.exit() != 0) {
            throw new IllegalStateException("aapt2 link failed:\n" + linked.output());
        }
    }

    /** Extract the per-OS aapt2 binary from its Maven wrapper jar into the step scratch. */
    private static Path extractAapt2(StepExec exec) throws IOException {
        Path jar = exec.requireExtra("aapt2");
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String binaryName = windows ? "aapt2.exe" : "aapt2";
        Path out = exec.scratch().resolve("tools").resolve(binaryName);
        Files.createDirectories(out.getParent());
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(binaryName);
            if (entry == null) {
                throw new IOException("no " + binaryName + " inside " + jar.getFileName()
                        + " — wrong classifier for this OS?");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (!out.toFile().setExecutable(true) && !Files.isExecutable(out)) {
            throw new IOException("cannot mark " + out + " executable");
        }
        return out;
    }

    /**
     * The manifest aapt2 links: the project's, with {@code package="<namespace>"} injected when
     * absent (string-level, spike-grade — the manifest-merger worker is android-plan Phase 2).
     */
    private static Path manifestWithPackage(Path manifest, String namespace, Path work) throws IOException {
        String xml = Files.readString(manifest, StandardCharsets.UTF_8);
        if (!xml.contains("package=")) {
            xml = xml.replaceFirst("<manifest", "<manifest package=\"" + namespace + "\"");
        }
        Path effective = work.resolve("AndroidManifest.xml");
        Files.writeString(effective, xml, StandardCharsets.UTF_8);
        return effective;
    }

    /** Every file under {@code dir} (sorted), for tools that want explicit file lists. */
    static List<Path> filesUnder(Path dir, String suffix) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (var walk = Files.walk(dir)) {
            walk.filter(f -> Files.isRegularFile(f) && f.toString().endsWith(suffix))
                    .sorted()
                    .forEach(out::add);
        }
        return out;
    }
}
