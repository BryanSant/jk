// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.android;

import dev.jkbuild.plugin.build.PackageIo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Android view of the module's runtime entries: every dependency whose artifact is an AAR
 * container (a remote androidx library or a workspace {@code [android] library} sibling), in
 * classpath order — the deterministic merge order for resources and manifests (app last, so the
 * app wins; matching AGP's precedence).
 */
final class AndroidDeps {

    private AndroidDeps() {}

    /** One AAR dependency: its exploded container and the pieces the steps consume. */
    record Aar(String fileName, Path container) {

        Path res() {
            return container.resolve("res");
        }

        Path manifest() {
            return container.resolve("AndroidManifest.xml");
        }

        Path rTxt() {
            return container.resolve("R.txt");
        }

        boolean hasRes() throws IOException {
            if (!Files.isDirectory(res())) return false;
            try (var listing = Files.list(res())) {
                return listing.findFirst().isPresent();
            }
        }

        /** The AAR's package/namespace, parsed from its manifest's {@code package} attribute. */
        String namespace() throws IOException {
            if (!Files.isRegularFile(manifest())) return null;
            String xml = Files.readString(manifest());
            Matcher m = Pattern.compile("package\\s*=\\s*\"([^\"]+)\"").matcher(xml);
            return m.find() ? m.group(1) : null;
        }
    }

    /** The AAR containers among {@code entries}, in entry (classpath) order. */
    static List<Aar> aars(List<PackageIo.RuntimeEntry> entries) {
        List<Aar> out = new ArrayList<>();
        for (PackageIo.RuntimeEntry e : entries) {
            if (e.container() != null) out.add(new Aar(e.fileName(), e.container()));
        }
        return out;
    }

    /**
     * The merged {@code assets/} view: AAR dependencies in classpath order, the module's own
     * {@code assets/} written last so the app wins a path conflict (AGP's precedence). Keys are
     * asset-relative paths ({@code /}-separated).
     */
    static java.util.Map<String, Path> mergedAssets(PackageIo io) throws IOException {
        java.util.Map<String, Path> out = new java.util.LinkedHashMap<>();
        for (Aar aar : aars(io.runtimeEntries())) {
            collectTree(aar.container().resolve("assets"), out);
        }
        collectTree(androidFile(io.moduleDir(), "assets"), out);
        return out;
    }

    /**
     * The module-relative Android file/dir in either layout: {@code <module>/<rel>} (jk's simple
     * layout) or {@code <module>/src/main/<rel>} (the AGP/traditional location). Returns the
     * simple-layout path when neither exists, so error messages name the primary convention.
     */
    static Path androidFile(Path moduleDir, String rel) {
        Path simple = moduleDir.resolve(rel);
        if (Files.exists(simple)) return simple;
        Path traditional = moduleDir.resolve("src/main").resolve(rel);
        return Files.exists(traditional) ? traditional : simple;
    }

    /** AAR native libs: {@code jni/<abi>/*.so} → APK {@code lib/<abi>/*.so} keys. */
    static java.util.Map<String, Path> nativeLibs(PackageIo io) throws IOException {
        java.util.Map<String, Path> out = new java.util.LinkedHashMap<>();
        for (Aar aar : aars(io.runtimeEntries())) {
            collectTree(aar.container().resolve("jni"), out);
        }
        return out;
    }

    private static void collectTree(Path root, java.util.Map<String, Path> out) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).sorted().forEach(f -> {
                out.put(root.relativize(f).toString().replace('\\', '/'), f);
            });
        }
    }

    /** Extract the per-OS aapt2 binary from its Maven wrapper jar into {@code destDir}. */
    static Path extractAapt2(Path aapt2Jar, Path destDir) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String binaryName = windows ? "aapt2.exe" : "aapt2";
        Path out = Files.createDirectories(destDir).resolve(binaryName);
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(aapt2Jar.toFile())) {
            var entry = zip.getEntry(binaryName);
            if (entry == null) {
                throw new IOException("no " + binaryName + " inside " + aapt2Jar.getFileName()
                        + " — wrong classifier for this OS?");
            }
            try (var in = zip.getInputStream(entry)) {
                Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (!out.toFile().setExecutable(true) && !Files.isExecutable(out)) {
            throw new IOException("cannot mark " + out + " executable");
        }
        return out;
    }

    /**
     * Copy the R8 retrace artifacts ({@code mapping.txt}, {@code seeds.txt}, {@code usage.txt})
     * from the step's cached scratch to the module's stable {@code target/r8/} — the path release
     * tooling (Play upload, crash retrace) reads.
     */
    static void copyRetraceArtifacts(PackageIo io) throws IOException {
        Path mapping = io.stepOutput("android-r8").map(dir -> dir.resolve("mapping")).orElse(null);
        if (mapping == null || !Files.isDirectory(mapping)) return;
        Path targetR8 = io.artifactPath().getParent().getParent().resolve("r8");
        Files.createDirectories(targetR8);
        try (var listing = Files.list(mapping)) {
            for (Path file : (Iterable<Path>) listing.sorted()::iterator) {
                Files.copy(file, targetR8.resolve(file.getFileName().toString()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
