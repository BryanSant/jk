// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.compile;

import build.jumpkick.plugin.protocol.Ndjson;
import build.jumpkick.plugin.protocol.PluginProtocol;
import build.jumpkick.plugin.protocol.SpecWriter;
import build.jumpkick.engine.plugin.PluginClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Drives Kotlin compilation by forking the {@code jk-kotlin-compiler} plugin, which runs the Kotlin
 * Build Tools API in-process on jk's own runtime (the project JDK rides as -jdk-home).
 *
 * <p>The plugin is launched as {@code <javaHome>/bin/java -cp <workerClasspath>
 * build.jumpkick.kotlin.compiler.KotlinCompilerPlugin @<spec>}. It streams NDJSON back on stdout (each
 * line prefixed {@value #PROTOCOL_PREFIX}); we collect the diagnostics and the terminal result.
 * Replaces the former {@code <kotlin-home>/bin/kotlinc} subprocess.
 */
public final class KotlincDriver {

    /** Mirrors the plugin's {@code Ndjson.PREFIX}. */
    private static final String PROTOCOL_PREFIX = "##JKKC:";

    private static final String WORKER_MAIN = "build.jumpkick.plugin.process.PluginMain";

    public KotlincResult compile(KotlincRequest request) {
        try {
            return run(request);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("kotlin compile interrupted", e);
        }
    }

    private KotlincResult run(KotlincRequest request) throws IOException, InterruptedException {
        Path spec = writeSpec(request);
        try {
            // The plugin is jk's OWN process: it runs on jk's runtime (plugins are built at
            // jk's language level — class-file 69 — and must not be hostage to the project's
            // pinned JDK; requirements.md promises a 17+ project floor). The project JDK is an
            // INPUT: writeSpec passes it as kotlinc's -jdk-home so cross-compilation still
            // resolves the pinned JDK's platform classes.
            Path hostJavaHome = build.jumpkick.jdk.JavaHomes.runningJavaHome();
            String classpath = request.workerClasspath().stream()
                    .map(Path::toString)
                    .collect(Collectors.joining(java.io.File.pathSeparator));
            List<String> rest = new ArrayList<>();
            // AOT cache for the plugin process (PluginAot): the Kotlin compiler IS this classpath, so
            // the cache tames its multi-second JIT warmup. Mapped when one exists for (host JDK,
            // GC, classpath); else a background trainer compiles a synthetic hello.kt so the NEXT
            // kotlin build maps it. JVM flags, so they precede -cp.
            rest.addAll(build.jumpkick.engine.plugin.PluginAot.kotlincFlags(
                    hostJavaHome, classpath, (aotOutput, scratch) -> trainerCommand(
                            request.classpath(), classpath, hostJavaHome, aotOutput, scratch)));
            rest.addAll(List.of(
                    // Silence the JDK's native-access / Unsafe warnings the compiler triggers.
                    "--enable-native-access=ALL-UNNAMED",
                    "-cp",
                    classpath,
                    WORKER_MAIN,
                    "@" + spec.toAbsolutePath()));
            List<String> cmd = build.jumpkick.engine.plugin.JvmOptions.javaCommand(
                    hostJavaHome.resolve("bin").resolve("java").toString(), 1, rest);

            List<String> diagnostics = new ArrayList<>();
            String[] status = {null};
            // Non-protocol lines (JDK/compiler chatter) are dropped on success, but a plugin
            // that DIES before speaking protocol (a broken classpath, a JVM crash) leaves its
            // whole story there — keep a bounded tail and surface it on failure, or the build
            // fails with an empty diagnostic and no way to see why.
            java.util.ArrayDeque<String> chatter = new java.util.ArrayDeque<>();
            int exit = new PluginClient(PROTOCOL_PREFIX)
                    .on(PluginProtocol.DIAGNOSTIC,
                            json -> diagnostics.add(Ndjson.str(json, "sev") + ": " + Ndjson.str(json, "msg")))
                    .on(PluginProtocol.RESULT, json -> status[0] = Ndjson.str(json, "status"))
                    .passthrough(line -> {
                        if (chatter.size() >= 40) chatter.removeFirst();
                        chatter.addLast(line);
                    })
                    .run(cmd);
            boolean success = exit == 0 && "COMPILATION_SUCCESS".equals(status[0]);
            if (!success && diagnostics.isEmpty() && !chatter.isEmpty()) {
                diagnostics.add("kotlinc worker exited " + exit + " without diagnostics; last output:");
                diagnostics.addAll(chatter);
            }
            return new KotlincResult(success, String.join("\n", diagnostics));
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    /**
     * The AOT trainer's command line: the same plugin spawn shape as {@link #run}, recording with
     * {@code -XX:AOTCacheOutput} while compiling a synthetic hello.kt against the triggering
     * request's own compile classpath (CAS entries are content-named, so there is no jar to hunt
     * for by name — but every real Kotlin compile already carries the version-matched
     * kotlin-stdlib the request pairs with {@code -no-stdlib}). Full startup + compile fidelity —
     * exactly the warmup the cache exists to skip.
     */
    private static List<String> trainerCommand(
            List<Path> compileClasspath, String classpath, Path hostJavaHome, Path aotOutput, Path scratch)
            throws IOException {
        Path source = scratch.resolve("Hello.kt");
        Files.writeString(
                source,
                """
                package demo

                data class Point(val x: Int, val y: Int)

                sealed interface Shape
                data class Circle(val r: Double) : Shape
                data class Square(val s: Double) : Shape

                fun area(shape: Shape): Double = when (shape) {
                    is Circle -> Math.PI * shape.r * shape.r
                    is Square -> shape.s * shape.s
                }

                fun main() {
                    val points = (1..10).map { Point(it, it * 2) }
                    val shapes: List<Shape> = listOf(Circle(2.0), Square(3.0))
                    println(points.filter { it.x % 2 == 0 }.joinToString { "${it.x},${it.y}" })
                    println(shapes.sumOf { area(it) })
                }
                """);
        SpecWriter sw = new SpecWriter()
                .op(PluginProtocol.OP_COMPILE, null, "jk-kotlin-compiler")
                .configString("jvmTarget", "21")
                .layout(java.util.Map.of("classesDir", scratch.resolve("out")))
                .arg("-jdk-home")
                .arg(hostJavaHome.toAbsolutePath().toString())
                .arg("-no-stdlib")
                .source(source);
        for (Path cp : compileClasspath) sw.cp(cp, PluginProtocol.ROLE_COMPILE);
        Path spec = scratch.resolve("train.spec");
        Files.write(spec, sw.lines(), StandardCharsets.UTF_8);
        List<String> rest = new ArrayList<>();
        rest.add("-XX:AOTCacheOutput=" + aotOutput);
        rest.addAll(List.of(
                "--enable-native-access=ALL-UNNAMED", "-cp", classpath, WORKER_MAIN, "@" + spec.toAbsolutePath()));
        return build.jumpkick.engine.plugin.JvmOptions.javaCommand(
                hostJavaHome.resolve("bin").resolve("java").toString(), 1, rest);
    }

    /** Render the request into the unified NDJSON plugin spec. */
    private static Path writeSpec(KotlincRequest request) throws IOException {
        SpecWriter sw = new SpecWriter()
                .op(PluginProtocol.OP_COMPILE, null, "jk-kotlin-compiler")
                .configString("jvmTarget", String.valueOf(request.jvmTarget()));
        java.util.Map<String, Path> layout = new java.util.LinkedHashMap<>();
        layout.put("classesDir", request.outputDir());
        if (request.workingDir() != null) layout.put("workdir", request.workingDir());
        if (request.snapshotDir() != null) layout.put("snapshotDir", request.snapshotDir());
        sw.layout(layout);
        if (request.moduleName() != null && !request.moduleName().isBlank()) {
            sw.configString("moduleName", request.moduleName());
        }
        // Cross-compile against the project's pinned JDK: the plugin HOST is jk's runtime, so without
        // -jdk-home kotlinc would resolve platform classes from jk's newer JDK and let a 17-pinned
        // project reference APIs it can't run against.
        sw.arg("-jdk-home").arg(request.javaHome().toAbsolutePath().toString());
        for (Path src : request.sources()) sw.source(src);
        for (Path cp : request.classpath()) sw.cp(cp, PluginProtocol.ROLE_COMPILE);
        for (String arg : request.extraArgs()) sw.arg(arg);
        for (KotlincRequest.Plugin plugin : request.plugins()) {
            sw.compilerPlugin(plugin.id(), plugin.jar(), plugin.options());
        }
        Path spec = Files.createTempFile("jk-kotlinc-", ".spec");
        Files.write(spec, sw.lines(), StandardCharsets.UTF_8);
        return spec;
    }
}
