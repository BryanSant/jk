// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.compile;

import build.jumpkick.jdk.HostPlatform;
import build.jumpkick.plugin.protocol.Ndjson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Drives the {@code jk-java-compiler} worker: runs javac in-process (under the project's JDK) with
 * annotation processors wrapped for provenance capture, and returns the diagnostics plus the
 * generated-file → originating-source mapping the incremental compiler needs for
 * annotation-processor incrementality.
 *
 * <p>Launched as {@code <javaHome>/bin/java -cp <workerJar>
 * build.jumpkick.java.compiler.JavaCompilerPlugin @<spec>}; the worker streams {@value #PREFIX} NDJSON
 * back on stdout. Mirrors {@link KotlincDriver}.
 */
public final class WorkerJavac {

    private static final String PREFIX = "##JKJC:";

    private WorkerJavac() {}

    /**
     * @param generated generated source file → the input source file(s) it originated from
     */
    public record Result(boolean success, List<CompileResult.Diagnostic> diagnostics, Map<Path, Set<Path>> generated) {
        public Result {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record Request(
            Path javaHome,
            Path workerJar,
            List<Path> sources,
            List<Path> classpath,
            List<Path> processorPath,
            Path classOutput,
            Path sourceOutput,
            int release,
            List<String> extraArgs) {}

    public static Result compile(Request request) {
        try {
            return run(request);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("java worker interrupted", e);
        }
    }

    private static Result run(Request req) throws IOException, InterruptedException {
        Path spec = writeSpec(req);
        try {
            List<CompileResult.Diagnostic> diagnostics = new ArrayList<>();
            Map<Path, Set<Path>> generated = new TreeMap<>();
            String[] status = {null};

            // Fork the java-compiler worker on jk's OWN runtime — the same rule as every
            // worker (requirements.md "worker host"), and the same javac the non-AP path
            // already uses in-process (ToolProvider on the engine JDK): --release supplies
            // the project's target semantics. It's a thin, JDK-only plugin (the compile
            // classpath travels in the spec, not on the worker JVM classpath), so its own
            // jar is the whole classpath.
            boolean win = HostPlatform.isWindows();
            Path javaExe = build.jumpkick.jdk.JavaHomes.runningJavaHome()
                    .resolve("bin")
                    .resolve(win ? "java.exe" : "java");
            List<String> command = build.jumpkick.worker.PluginLoader.command(
                    javaExe,
                    req.workerJar().toString(),
                    build.jumpkick.worker.JvmOptions.batchFlags(1),
                    List.of("@" + spec.toAbsolutePath()));
            int exit = new build.jumpkick.worker.PluginClient(PREFIX)
                    .on("diag", json -> diagnostics.add(new CompileResult.Diagnostic(
                            CompileResult.Severity.fromName(Ndjson.str(json, "sev")),
                            null,
                            0,
                            0,
                            Ndjson.str(json, "msg"))))
                    .on("prov", json -> {
                        String genStr = Ndjson.str(json, "gen");
                        if (genStr == null) return;
                        Path gen = Path.of(genStr);
                        Set<Path> origins = new TreeSet<>();
                        for (String s : Ndjson.strArray(json, "src")) origins.add(Path.of(s));
                        generated.put(gen, origins);
                    })
                    .on("result", json -> status[0] = Ndjson.str(json, "status"))
                    .run(command);
            boolean success = exit == 0 && "OK".equals(status[0]);
            return new Result(success, diagnostics, generated);
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    private static Path writeSpec(Request req) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("CLASSOUTPUT " + req.classOutput().toAbsolutePath());
        lines.add("SOURCEOUTPUT " + req.sourceOutput().toAbsolutePath());
        lines.add("RELEASE " + req.release());
        for (Path s : req.sources()) lines.add("SOURCE " + s.toAbsolutePath());
        for (Path c : req.classpath()) lines.add("CLASSPATH " + c.toAbsolutePath());
        for (Path p : req.processorPath()) lines.add("PROCESSORPATH " + p.toAbsolutePath());
        for (String a : req.extraArgs()) lines.add("ARG " + a);
        Path spec = Files.createTempFile("jk-javac-", ".spec");
        Files.write(spec, lines, StandardCharsets.UTF_8);
        return spec;
    }
}
