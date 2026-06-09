// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile;

import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.worker.WorkerProcess;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Drives Kotlin compilation by forking the {@code jk-kotlin-compiler} worker,
 * which runs the Kotlin Build Tools API in-process under the project's JDK.
 *
 * <p>The worker is launched as {@code <javaHome>/bin/java -cp <workerClasspath>
 * dev.jkbuild.kotlin.compiler.KotlinCompilerWorker @<spec>}. It streams NDJSON
 * back on stdout (each line prefixed {@value #PROTOCOL_PREFIX}); we collect the
 * diagnostics and the terminal result. Replaces the former
 * {@code <kotlin-home>/bin/kotlinc} subprocess.
 */
public final class KotlincDriver {

    /** Mirrors the worker's {@code Ndjson.PREFIX}. */
    private static final String PROTOCOL_PREFIX = "##JKKC:";
    private static final String WORKER_MAIN = "dev.jkbuild.plugin.host.PluginHostMain";

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
            List<String> cmd = List.of(
                    request.javaHome().resolve("bin").resolve("java").toString(),
                    // Silence the JDK's native-access / Unsafe warnings the compiler triggers.
                    "--enable-native-access=ALL-UNNAMED",
                    "-cp", request.workerClasspath().stream()
                            .map(Path::toString).collect(Collectors.joining(java.io.File.pathSeparator)),
                    WORKER_MAIN, "@" + spec.toAbsolutePath());

            List<String> diagnostics = new ArrayList<>();
            String[] status = {null};
            // Non-protocol lines (JDK/compiler chatter) are dropped, as before.
            int exit = WorkerProcess.run(cmd, PROTOCOL_PREFIX, json -> {
                String t = Ndjson.str(json, "t");
                if ("diag".equals(t)) {
                    diagnostics.add(Ndjson.str(json, "sev") + ": " + Ndjson.str(json, "msg"));
                } else if ("result".equals(t)) {
                    status[0] = Ndjson.str(json, "status");
                }
            }, null);
            boolean success = exit == 0 && "COMPILATION_SUCCESS".equals(status[0]);
            return new KotlincResult(success, String.join("\n", diagnostics));
        } finally {
            Files.deleteIfExists(spec);
        }
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
        for (Path src : request.sources()) {
            lines.add("SOURCE " + src.toAbsolutePath());
        }
        for (Path cp : request.classpath()) {
            lines.add("CLASSPATH " + cp.toAbsolutePath());
        }
        for (String arg : request.extraArgs()) {
            lines.add("ARG " + arg);
        }
        Path spec = Files.createTempFile("jk-kotlinc-", ".spec");
        Files.write(spec, lines, StandardCharsets.UTF_8);
        return spec;
    }

}
