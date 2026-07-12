// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.android;

import dev.jkbuild.plugin.build.StepExec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The {@code android-res} step: aapt2 compile over the module's {@code res/} and every AAR
 * dependency's {@code res/} (workspace {@code [android] library} siblings included), then one
 * aapt2 link against the platform — producing the binary resource package
 * ({@code packaged/resources.ap_}), the merged symbol table ({@code packaged/R.txt}), the module's
 * generated {@code R.java} under {@code gen/} (contributed to the compiler's source set), and a
 * {@code raw-res/} copy for the AAR packager.
 *
 * <p>Precedence matches AGP: dependency resources merge into one tree first ({@link
 * ResourceMerger} — resource-granular, earlier classpath entry wins) and link as one plain
 * input; the module's own resources ride as the overlay ({@code -R} + {@code
 * --auto-add-overlay}), so the app always wins a conflict with a library.
 *
 * <p><b>Non-transitive R</b> (jk's only mode): an app module additionally regenerates each AAR
 * dependency's {@code R} class — its own namespace, only the symbols its {@code R.txt} declares,
 * with the FINAL ids this link assigned — so library bytecode referencing {@code lib.R.string.x}
 * resolves at dex time without any transitive god-R. A library module generates its own
 * {@code R.java} with {@code --non-final-ids} (ids are placeholders until an app links it) and
 * ships {@code R.txt} in its AAR; its {@code R} classes never enter {@code classes.jar}.
 */
final class ResourceStep {

    private ResourceStep() {}

    static void run(StepExec exec) throws Exception {
        Path aapt2 = extractAapt2(exec);
        Path platformJar = exec.requireExtra("android-jar");
        Path res = AndroidDeps.androidFile(exec.moduleDir(), "res");
        Path manifest = exec.requireStepOutput("android-manifest").resolve("merged/AndroidManifest.xml");
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalStateException("android-manifest produced no merged manifest at " + manifest);
        }
        String namespace = exec.config().string("namespace");
        boolean library = exec.config().bool("library", false);
        long compileSdk = exec.config().intValue("compile-sdk", 0);
        long minSdk = exec.config().intValue("min-sdk", 0);
        List<AndroidDeps.Aar> aars = AndroidDeps.aars(exec.runtimeEntries());

        Path gen = exec.outputDir("gen");
        Path packaged = exec.outputDir("packaged");
        Path rawRes = exec.outputDir("raw-res");
        Path work = Files.createDirectories(exec.scratch().resolve("work"));

        // Dependency resources merge into ONE tree first (AGP semantics — finding 9: two
        // androidx AARs can define the same value resource, and aapt2 hard-errors on value
        // conflicts across separate link inputs; the merger resolves them at resource
        // granularity, earlier classpath entry wins), then compile once. The module's own
        // resources stay the -R overlay below, so the app still beats every library.
        List<Path> depFlats = new ArrayList<>();
        Path mergedDepRes = ResourceMerger.mergeDepRes(aars, work.resolve("merged-dep-res"));
        if (mergedDepRes != null && Files.isDirectory(mergedDepRes)) {
            try (var listing = Files.list(mergedDepRes)) {
                if (listing.findFirst().isPresent()) {
                    exec.label("aapt2 compile (deps)");
                    depFlats.add(compileRes(exec, aapt2, mergedDepRes, work.resolve("deps-compiled.zip")));
                }
            }
        }
        Path ownFlats = null;
        if (Files.isDirectory(res)) {
            exec.label("aapt2 compile");
            ownFlats = compileRes(exec, aapt2, res, work.resolve("res-compiled.zip"));
            copyTree(res, rawRes);
        }

        // aapt2 link: deps as plain inputs (classpath order), the module's own resources as the
        // overlay — one binary package, one merged symbol table, the module's R.java, and the
        // generated keep rules (manifest components) R8 consumes on release.
        exec.label("aapt2 link");
        Path rTxt = packaged.resolve("R.txt");
        StepExec.ToolRun.Result linked = link(
                        exec, aapt2, platformJar, manifest, namespace, compileSdk, minSdk, library, depFlats,
                        ownFlats, packaged.resolve("resources.ap_"), gen, rTxt,
                        packaged.resolve("keep-rules.pro"), false)
                .run();
        if (linked.exit() != 0) {
            throw new IllegalStateException("aapt2 link failed:\n" + linked.output());
        }

        // Release app: a second link in proto format — the AAB's resource table (bundletool
        // consumes protobuf resources, never the binary arsc).
        boolean release = "release".equals(exec.config().stringOpt("build-type").orElse("debug"));
        if (release && !library) {
            exec.label("aapt2 link (proto)");
            StepExec.ToolRun.Result proto = link(
                            exec, aapt2, platformJar, manifest, namespace, compileSdk, minSdk, false, depFlats,
                            ownFlats, packaged.resolve("resources-proto.ap_"), null, null, null, true)
                    .run();
            if (proto.exit() != 0) {
                throw new IllegalStateException("aapt2 link --proto-format failed:\n" + proto.output());
            }
        }

        // Non-transitive R: regenerate each dependency's R class from ITS R.txt with the ids
        // this link assigned — final ids in an app link, placeholders in a library link
        // (--non-final-ids; the consuming app re-finalizes, and the AAR packager excludes all
        // R classes from classes.jar). Libraries need them too: library code referencing a
        // sibling AAR's R (ui → designsystem.R.drawable) must compile (AGP does the same).
        if (Files.isRegularFile(rTxt)) {
            Map<String, String> finalSymbols = readSymbols(rTxt);
            for (AndroidDeps.Aar aar : aars) {
                if (!Files.isRegularFile(aar.rTxt())) continue;
                String depNamespace = aar.namespace();
                if (depNamespace == null || depNamespace.equals(namespace)) continue;
                writeDepR(gen, depNamespace, readSymbols(aar.rTxt()), finalSymbols);
            }
        }
    }

    /** One aapt2 link invocation — binary (R.java + symbols + keep rules) or proto (AAB). */
    private static StepExec.ToolRun link(
            StepExec exec,
            Path aapt2,
            Path platformJar,
            Path manifest,
            String namespace,
            long compileSdk,
            long minSdk,
            boolean library,
            List<Path> depFlats,
            Path ownFlats,
            Path out,
            Path gen,
            Path rTxt,
            Path keepRules,
            boolean proto) {
        StepExec.ToolRun link = exec.tool(aapt2)
                .arg("link")
                .arg("-o")
                .arg(out.toAbsolutePath().toString())
                .arg("-I")
                .arg(platformJar.toAbsolutePath().toString())
                .arg("--manifest")
                .arg(manifest.toAbsolutePath().toString())
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
        if (gen != null) link.arg("--java").arg(gen.toAbsolutePath().toString());
        if (rTxt != null) link.arg("--output-text-symbols").arg(rTxt.toAbsolutePath().toString());
        if (keepRules != null) link.arg("--proguard").arg(keepRules.toAbsolutePath().toString());
        if (proto) link.arg("--proto-format");
        if (library) link.arg("--non-final-ids");
        for (Path dep : depFlats) link.arg(dep.toAbsolutePath().toString());
        if (ownFlats != null) {
            link.arg("-R").arg(ownFlats.toAbsolutePath().toString());
        }
        return link;
    }

    private static Path compileRes(StepExec exec, Path aapt2, Path resDir, Path out) throws Exception {
        StepExec.ToolRun.Result compile = exec.tool(aapt2)
                .arg("compile")
                .arg("--dir")
                .arg(resDir.toAbsolutePath().toString())
                .arg("-o")
                .arg(out.toAbsolutePath().toString())
                .run();
        if (compile.exit() != 0) {
            throw new IllegalStateException("aapt2 compile failed for " + resDir + ":\n" + compile.output());
        }
        return out;
    }

    /** {@code "<type> <name>" → "<java-type> <value>"} from an aapt2/AGP {@code R.txt}. */
    private static Map<String, String> readSymbols(Path rTxt) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(rTxt)) {
            // Lines: `int <type> <name> <value>` or `int[] styleable <name> { v1, v2 }`.
            String[] parts = line.strip().split("\\s+", 4);
            if (parts.length < 4) continue;
            out.put(parts[1] + " " + parts[2], parts[0] + " " + parts[3]);
        }
        return out;
    }

    /** One dependency's {@code R} class: its own symbols, the app link's final values. */
    private static void writeDepR(
            Path gen, String namespace, Map<String, String> depSymbols, Map<String, String> finalSymbols)
            throws IOException {
        Map<String, Map<String, String>> byType = new LinkedHashMap<>();
        for (var symbol : depSymbols.entrySet()) {
            String resolved = finalSymbols.getOrDefault(symbol.getKey(), symbol.getValue());
            int space = symbol.getKey().indexOf(' ');
            String type = symbol.getKey().substring(0, space);
            String name = symbol.getKey().substring(space + 1);
            byType.computeIfAbsent(type, k -> new LinkedHashMap<>()).put(name, resolved);
        }
        StringBuilder java = new StringBuilder();
        java.append("/* Generated by jk (non-transitive R) — do not edit. */\n");
        java.append("package ").append(namespace).append(";\n\n");
        java.append("public final class R {\n");
        java.append("    private R() {}\n");
        for (var type : byType.entrySet()) {
            java.append("    public static final class ").append(type.getKey()).append(" {\n");
            java.append("        private ").append(type.getKey()).append("() {}\n");
            for (var field : type.getValue().entrySet()) {
                int space = field.getValue().indexOf(' ');
                String javaType = field.getValue().substring(0, space);
                String value = field.getValue().substring(space + 1);
                java.append("        public static final ")
                        .append(javaType)
                        .append(' ')
                        .append(field.getKey())
                        .append(" = ")
                        .append(value)
                        .append(";\n");
            }
            java.append("    }\n");
        }
        java.append("}\n");
        Path file = gen.resolve(namespace.replace('.', '/')).resolve("R.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, java.toString());
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Extract the per-OS aapt2 binary from its Maven wrapper jar into the step scratch. */
    private static Path extractAapt2(StepExec exec) throws IOException {
        return AndroidDeps.extractAapt2(exec.requireExtra("aapt2"), exec.scratch().resolve("tools"));
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
