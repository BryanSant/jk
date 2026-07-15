// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.cli.CliOutput;
import build.jumpkick.jdk.HostPlatform;
import build.jumpkick.util.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@code jk build --aot-cache} (spring-boot plan §3.5, general-purpose): package the app as an
 * extracted directory layout and train a JVM startup cache for it — the same trick jk uses for its
 * own engine, productized for user applications.
 *
 * <p>Output at {@code target/aot-cache/}:
 *
 * <pre>
 * <artifact>-<version>.jar   the app jar (Boot: the thin launcher jar jarmode-extract emits)
 * lib/*.jar                  runtime deps with their original names
 * app.aot | app.jsa          the trained cache
 * </pre>
 *
 * <p>The efficiency ladder picks the best tier the project's JDK supports: JDK 25+ uses the JEP
 * 514 AOT cache ({@code -XX:AOTCacheOutput} training → {@code -XX:AOTCache} at run); older JDKs
 * fall back to classic AppCDS ({@code -XX:ArchiveClassesAtExit} → {@code -XX:SharedArchiveFile}).
 *
 * <p>Training needs one JVM run that <em>exits</em>. Spring Boot apps exit automatically (the run
 * is started with {@code -Dspring.context.exit=onRefresh} — full context refresh, then exit, which
 * is exactly the workload worth caching). Any other app must terminate on its own; a watchdog
 * kills a runaway training run and fails with a clear message.
 */
final class AotCachePackage {

    /** Non-Boot training must finish inside this window (Boot exits at refresh on its own). */
    private static final long TRAINING_TIMEOUT_SECONDS = 180;

    private AotCachePackage() {}

    /** Package + train. Returns the process exit code contract (0 = success). */
    static int run(Path projectDir, Path cacheDir) {
        try {
            return runInner(projectDir, cacheDir);
        } catch (IOException e) {
            CliOutput.err("jk build --aot-cache: " + e.getMessage());
            return build.jumpkick.model.command.Exit.SOFTWARE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CliOutput.err("jk build --aot-cache: interrupted");
            return build.jumpkick.model.command.Exit.SOFTWARE;
        }
    }

    private static int runInner(Path projectDir, Path cacheDir)
            throws IOException, InterruptedException {
        // Thin client: the engine computes the layout inputs (boot-ness, tier, main jar,
        // coordinate-named libs, main class); this process does the file assembly + the
        // training fork it owns.
        build.jumpkick.engine.protocol.ExecPlan plan = Boolean.getBoolean("jk.test.noEngine")
                ? build.jumpkick.cli.engine.InProcessEngine.require()
                        .execPlan(projectDir, cacheDir, "aot-cache", null, null)
                : build.jumpkick.cli.engine.EngineClient.execPlan(
                        build.jumpkick.engine.EnginePaths.current(), projectDir, cacheDir, "aot-cache", null, null);
        if (plan.error() != null) {
            CliOutput.err("jk build --aot-cache: " + plan.error());
            return build.jumpkick.model.command.Exit.SOFTWARE;
        }

        // target/aot-cache next to the built artifact (mainJar sits in target/ or target/lib/).
        Path targetDir = Path.of(plan.mainJar()).getParent();
        if ("lib".equals(String.valueOf(targetDir.getFileName()))) targetDir = targetDir.getParent();
        Path outDir = targetDir.resolve("aot-cache");
        PathUtil.deleteRecursively(outDir);
        Files.createDirectories(outDir);

        Path javaHome = Path.of(plan.javaHome());
        String java = javaHome.resolve("bin")
                .resolve(HostPlatform.isWindows() ? "java.exe" : "java")
                .toString();
        boolean aotTier = "aot".equals(plan.tier());
        String cacheFile = aotTier ? "app.aot" : "app.jsa";
        boolean springBoot = plan.boot();

        // 1. The extracted layout: thin app jar + lib/ with original names.
        String appJarName;
        if (springBoot) {
            // Boot's own tooling produces the CDS/AOT-friendly layout (thin launcher jar
            // whose manifest Class-Path points at lib/) — use it rather than re-implement.
            appJarName = extractBootLayout(java, Path.of(plan.mainJar()), outDir, projectDir);
        } else {
            appJarName = assemblePlainLayout(plan, outDir);
        }

        // 2. Training run: one full startup that exits, recorded into the cache.
        List<String> training = new ArrayList<>();
        training.add(java);
        if (aotTier) {
            training.add("-XX:AOTCacheOutput=" + cacheFile);
        } else {
            training.add("-XX:ArchiveClassesAtExit=" + cacheFile);
        }
        if (springBoot) {
            // Refresh the full context, then exit — the exact startup path worth caching.
            training.add("-Dspring.context.exit=onRefresh");
        }
        training.add("-jar");
        training.add(appJarName);

        CliOutput.err("jk: training the " + (aotTier ? "AOT cache (JEP 514)" : "AppCDS archive") + " — one "
                + (springBoot ? "context refresh" : "run of the app") + " ...");
        Process process = new ProcessBuilder(training)
                .directory(outDir.toFile())
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var in = process.inputReader()) {
                in.lines().forEach(l -> output.append(l).append('\n'));
            } catch (IOException ignored) {
            }
        });
        if (!process.waitFor(TRAINING_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            CliOutput.err("jk build --aot-cache: the training run did not exit within "
                    + TRAINING_TIMEOUT_SECONDS
                    + "s. Training needs one run that terminates — Spring Boot apps exit automatically;"
                    + " other apps must exit on their own (a server main loop can't be trained this way yet).");
            return build.jumpkick.model.command.Exit.SOFTWARE;
        }
        reader.join(5_000);
        if (process.exitValue() != 0) {
            CliOutput.err("jk build --aot-cache: training run failed (exit " + process.exitValue() + "):\n"
                    + tail(output.toString()));
            return build.jumpkick.model.command.Exit.SOFTWARE;
        }
        // JEP 514's one-step flow assembles the cache in a child JVM at exit; it is written
        // before the parent's waitFor returns. Verify the artifact exists either way.
        Path cachePath = outDir.resolve(cacheFile);
        if (!Files.isRegularFile(cachePath)) {
            CliOutput.err("jk build --aot-cache: training completed but no " + cacheFile + " was produced:\n"
                    + tail(output.toString()));
            return build.jumpkick.model.command.Exit.SOFTWARE;
        }

        String runFlag = aotTier ? "-XX:AOTCache=" + cacheFile : "-XX:SharedArchiveFile=" + cacheFile;
        CliOutput.err("jk: wrote " + build.jumpkick.cli.PathDisplay.styledRaw(outDir) + " ("
                + Files.size(cachePath) / (1024 * 1024) + " MiB cache)");
        // Name the exact training binary: the cache is keyed to the JVM build, and a
        // "same version, different vendor" java silently falls back to a cold start.
        CliOutput.err("jk: run it with:  cd " + projectDir.relativize(outDir) + " && " + java + " " + runFlag
                + " -jar " + appJarName);
        return 0;
    }

    /**
     * Run Boot's {@code -Djarmode=tools extract} against the boot jar — emits the thin launcher
     * jar + {@code lib/}. Returns the thin jar's file name inside {@code outDir}.
     */
    private static String extractBootLayout(String java, Path bootJar, Path outDir, Path projectDir)
            throws IOException, InterruptedException {
        if (!Files.isRegularFile(bootJar)) {
            throw new IOException("boot jar not found at " + bootJar + " — build before --aot-cache");
        }
        // extract refuses a non-empty destination; outDir was recreated empty, but the
        // tool also wants to create it itself — point it at outDir and allow force.
        List<String> command = List.of(
                java,
                "-Djarmode=tools",
                "-jar",
                bootJar.toAbsolutePath().toString(),
                "extract",
                "--force",
                "--destination",
                outDir.toAbsolutePath().toString());
        Process process = new ProcessBuilder(command)
                .directory(projectDir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IOException("jarmode extract failed:\n" + tail(out));
        }
        try (var stream = Files.list(outDir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .map(p -> p.getFileName().toString())
                    .findFirst()
                    .orElseThrow(() -> new IOException("jarmode extract produced no app jar in " + outDir));
        }
    }

    /**
     * Non-Boot layout: the app jar with a {@code Class-Path} pointing at {@code lib/}, deps copied
     * under their original {@code artifact-version.jar} names (both straight from the engine's
     * plan). The Class-Path manifest entry lets training and runtime both use plain {@code -jar}.
     */
    private static String assemblePlainLayout(build.jumpkick.engine.protocol.ExecPlan plan, Path outDir)
            throws IOException {
        Path mainJar = Path.of(plan.mainJar());
        Path libDir = Files.createDirectories(outDir.resolve("lib"));
        List<String> libNames = new ArrayList<>(plan.libNames());
        for (int i = 0; i < plan.libNames().size(); i++) {
            Files.copy(
                    Path.of(plan.libPaths().get(i)),
                    libDir.resolve(plan.libNames().get(i)),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        // Rewrite the app jar with a Class-Path manifest entry (relative lib/ refs). A jar's
        // Class-Path is resolved against the jar's own location, so the layout is relocatable.
        String appJarName = mainJar.getFileName().toString();
        Path appJar = outDir.resolve(appJarName);
        try (var jarIn = new java.util.jar.JarInputStream(Files.newInputStream(mainJar))) {
            java.util.jar.Manifest manifest = jarIn.getManifest();
            if (manifest == null) manifest = new java.util.jar.Manifest();
            manifest.getMainAttributes()
                    .putIfAbsent(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
            if (manifest.getMainAttributes().getValue(java.util.jar.Attributes.Name.MAIN_CLASS) == null
                    && !plan.mainClass().isEmpty()) {
                manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MAIN_CLASS, plan.mainClass());
            }
            if (!libNames.isEmpty()) {
                StringBuilder cp = new StringBuilder();
                for (String name : libNames) {
                    if (cp.length() > 0) cp.append(' ');
                    cp.append("lib/").append(name);
                }
                manifest.getMainAttributes().put(java.util.jar.Attributes.Name.CLASS_PATH, cp.toString());
            }
            try (var jarOut = new java.util.jar.JarOutputStream(Files.newOutputStream(appJar), manifest)) {
                java.util.jar.JarEntry entry;
                while ((entry = jarIn.getNextJarEntry()) != null) {
                    if (entry.getName().equals("META-INF/MANIFEST.MF")) continue;
                    jarOut.putNextEntry(new java.util.jar.JarEntry(entry.getName()));
                    jarIn.transferTo(jarOut);
                    jarOut.closeEntry();
                }
            }
        }
        return appJarName;
    }

    /** The last ~25 lines — JVM/App startup logs are long; the failure is at the bottom. */
    private static String tail(String output) {
        String[] lines = output.split("\n");
        int from = Math.max(0, lines.length - 25);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }
}
