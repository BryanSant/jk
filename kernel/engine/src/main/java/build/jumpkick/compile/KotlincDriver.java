// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.compile;

import build.jumpkick.plugin.protocol.Ndjson;
import build.jumpkick.worker.WorkerClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Drives Kotlin compilation by forking the {@code jk-kotlin-compiler} worker, which runs the Kotlin
 * Build Tools API in-process on jk's own runtime (the project JDK rides as -jdk-home).
 *
 * <p>The worker is launched as {@code <javaHome>/bin/java -cp <workerClasspath>
 * build.jumpkick.kotlin.compiler.KotlinCompilerPlugin @<spec>}. It streams NDJSON back on stdout (each
 * line prefixed {@value #PROTOCOL_PREFIX}); we collect the diagnostics and the terminal result.
 * Replaces the former {@code <kotlin-home>/bin/kotlinc} subprocess.
 */
public final class KotlincDriver {

    /** Mirrors the worker's {@code Ndjson.PREFIX}. */
    private static final String PROTOCOL_PREFIX = "##JKKC:";

    private static final String WORKER_MAIN = "build.jumpkick.plugin.worker.PluginWorkerMain";

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
            // The worker is jk's OWN process: it runs on jk's runtime (workers are built at
            // jk's language level — class-file 69 — and must not be hostage to the project's
            // pinned JDK; requirements.md promises a 17+ project floor). The project JDK is an
            // INPUT: writeSpec passes it as kotlinc's -jdk-home so cross-compilation still
            // resolves the pinned JDK's platform classes.
            Path hostJavaHome = build.jumpkick.jdk.JavaHomes.runningJavaHome();
            String classpath = request.workerClasspath().stream()
                    .map(Path::toString)
                    .collect(Collectors.joining(java.io.File.pathSeparator));
            List<String> rest = new ArrayList<>();
            // AOT cache for the worker JVM (WorkerAot): the Kotlin compiler IS this classpath, so
            // the cache tames its multi-second JIT warmup. Mapped when one exists for (host JDK,
            // GC, classpath); else a background trainer compiles a synthetic hello.kt so the NEXT
            // kotlin build maps it. JVM flags, so they precede -cp.
            rest.addAll(build.jumpkick.worker.WorkerAot.kotlincFlags(
                    hostJavaHome, classpath, (aotOutput, scratch) -> trainerCommand(
                            request.classpath(), classpath, hostJavaHome, aotOutput, scratch)));
            rest.addAll(List.of(
                    // Silence the JDK's native-access / Unsafe warnings the compiler triggers.
                    "--enable-native-access=ALL-UNNAMED",
                    "-cp",
                    classpath,
                    WORKER_MAIN,
                    "@" + spec.toAbsolutePath()));
            List<String> cmd = build.jumpkick.worker.JvmOptions.javaCommand(
                    hostJavaHome.resolve("bin").resolve("java").toString(), 1, rest);

            List<String> diagnostics = new ArrayList<>();
            String[] status = {null};
            // Non-protocol lines (JDK/compiler chatter) are dropped on success, but a worker
            // that DIES before speaking protocol (a broken classpath, a JVM crash) leaves its
            // whole story there — keep a bounded tail and surface it on failure, or the build
            // fails with an empty diagnostic and no way to see why.
            java.util.ArrayDeque<String> chatter = new java.util.ArrayDeque<>();
            int exit = new WorkerClient(PROTOCOL_PREFIX)
                    .on("diag", json -> diagnostics.add(Ndjson.str(json, "sev") + ": " + Ndjson.str(json, "msg")))
                    .on("result", json -> status[0] = Ndjson.str(json, "status"))
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
     * The AOT trainer's command line: the same worker spawn shape as {@link #run}, recording with
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
        List<String> lines = new ArrayList<>();
        lines.add("OUTPUT " + scratch.resolve("out").toAbsolutePath());
        lines.add("JVM_TARGET 21");
        lines.add("ARG -jdk-home");
        lines.add("ARG " + hostJavaHome.toAbsolutePath());
        lines.add("ARG -no-stdlib");
        lines.add("SOURCE " + source.toAbsolutePath());
        for (Path cp : compileClasspath) {
            lines.add("CLASSPATH " + cp.toAbsolutePath());
        }
        Path spec = scratch.resolve("train.spec");
        Files.write(spec, lines, StandardCharsets.UTF_8);
        List<String> rest = new ArrayList<>();
        rest.add("-XX:AOTCacheOutput=" + aotOutput);
        rest.addAll(List.of(
                "--enable-native-access=ALL-UNNAMED", "-cp", classpath, WORKER_MAIN, "@" + spec.toAbsolutePath()));
        return build.jumpkick.worker.JvmOptions.javaCommand(
                hostJavaHome.resolve("bin").resolve("java").toString(), 1, rest);
    }

    /** Render the request into the worker's line-oriented spec format. */
    private static Path writeSpec(KotlincRequest request) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("OUTPUT " + request.outputDir().toAbsolutePath());
        if (request.workingDir() != null) {
            lines.add("WORKDIR " + request.workingDir().toAbsolutePath());
        }
        if (request.snapshotDir() != null) {
            lines.add("SNAPSHOT_DIR " + request.snapshotDir().toAbsolutePath());
        }
        lines.add("JVM_TARGET " + request.jvmTarget());
        // Cross-compile against the project's pinned JDK: the worker HOST is jk's runtime, so
        // without -jdk-home kotlinc would resolve platform classes from jk's newer JDK and let
        // a 17-pinned project reference APIs it can't run against.
        lines.add("ARG -jdk-home");
        lines.add("ARG " + request.javaHome().toAbsolutePath());
        if (request.moduleName() != null && !request.moduleName().isBlank()) {
            lines.add("MODULE_NAME " + request.moduleName());
        }
        for (Path src : request.sources()) {
            lines.add("SOURCE " + src.toAbsolutePath());
        }
        for (Path cp : request.classpath()) {
            lines.add("CLASSPATH " + cp.toAbsolutePath());
        }
        for (String arg : request.extraArgs()) {
            lines.add("ARG " + arg);
        }
        for (KotlincRequest.Plugin plugin : request.plugins()) {
            // Tab-separated: id, jar path, then key=value options (tabs are not
            // meaningful in any of these values).
            StringBuilder line = new StringBuilder("PLUGIN ");
            line.append(plugin.id()).append('\t').append(plugin.jar().toAbsolutePath());
            for (String opt : plugin.options()) line.append('\t').append(opt);
            lines.add(line.toString());
        }
        Path spec = Files.createTempFile("jk-kotlinc-", ".spec");
        Files.write(spec, lines, StandardCharsets.UTF_8);
        return spec;
    }
}
