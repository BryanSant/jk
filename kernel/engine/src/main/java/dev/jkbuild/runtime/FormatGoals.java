// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.tool.ToolResolver;
import dev.jkbuild.worker.WorkerClient;
import dev.jkbuild.worker.WorkerJar;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * The shared {@code jk format} goal — collect Java/Kotlin sources, resolve the formatter
 * implementation jars through jk's own resolver into the CAS, and fork the {@code jk-formatter}
 * worker (Spotless + optional OpenRewrite) — hoisted out of the CLI so the resident engine can host
 * the verb (Wave 2 of {@code docs/architecture/slim-client.md}) while the command's test-only
 * in-process path builds the exact same goal.
 *
 * <p>Per-file results stream through the {@link FileObserver} as plain structured strings — the
 * client renders (and themes) them. The worker's exit code is a <em>result</em>, not a failure:
 * {@code --check} exits non-zero when files need formatting, so it rides the {@link #WORKER_EXIT}
 * key (and the wire's {@code goal-finish} variant) instead of failing the goal.
 */
public final class FormatGoals {

    private FormatGoals() {}

    // jk-pinned formatter impl versions (resolved via jk; the worker uses these).
    public static final String PALANTIR_VERSION = "2.80.0";
    public static final String GOOGLE_VERSION = "1.28.0";
    public static final String KTFMT_VERSION = "0.61";
    public static final int KOTLIN_MAX_WIDTH = 120; // match Palantir's 120-col

    // palantir/google-java-format reflectively use the JDK compiler internals.
    private static final List<String> JAVAC_EXPORTS = List.of(
            "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED");

    /** Receives each file's result as the worker streams it ({@code status} = {@code changed}/{@code clean}/{@code error}). */
    public interface FileObserver {
        void onFile(String path, String status, String message, int index, int total);
    }

    /** Summary counts, populated by the format phase (all present once the goal finishes successfully). */
    public static final GoalKey<Integer> CHANGED = GoalKey.of("format-changed", Integer.class);

    public static final GoalKey<Integer> CLEAN = GoalKey.of("format-clean", Integer.class);
    public static final GoalKey<Integer> ERRORS = GoalKey.of("format-errors", Integer.class);
    public static final GoalKey<Integer> TOTAL = GoalKey.of("format-total", Integer.class);
    public static final GoalKey<Integer> WORKER_EXIT = GoalKey.of("format-worker-exit", Integer.class);

    /**
     * Build the format goal for {@code projectDir}. Style names arrive already resolved (flags/env/
     * {@code [format]} block are the client's concern). Phases: {@code collect-sources} (SYNC) walks
     * the tree, {@code resolve-formatters} (IO) pulls the impl jars via {@link ToolResolver}, {@code
     * format} (IO) forks the worker and streams per-file results. A project with no sources
     * finishes successfully with {@link #TOTAL} = 0 and no worker forked.
     */
    public static Goal formatGoal(
            Path projectDir,
            Path cache,
            boolean check,
            String javaStyle,
            String kotlinStyle,
            boolean optimizeImports,
            Path rewriteConfig,
            FileObserver observer) {
        GoalKey<List> javaFilesKey = GoalKey.of("format-java-files", List.class);
        GoalKey<List> kotlinFilesKey = GoalKey.of("format-kotlin-files", List.class);
        GoalKey<List> javaJarsKey = GoalKey.of("format-java-jars", List.class);
        GoalKey<List> kotlinJarsKey = GoalKey.of("format-kotlin-jars", List.class);

        Phase collect = Phase.builder("collect-sources")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("collect sources");
                    List<Path> javaFiles = collectSources(projectDir, ".java");
                    List<Path> kotlinFiles = collectSources(projectDir, ".kt");
                    ctx.put(javaFilesKey, javaFiles);
                    ctx.put(kotlinFilesKey, kotlinFiles);
                    ctx.put(TOTAL, javaFiles.size() + kotlinFiles.size());
                    ctx.progress(1);
                })
                .build();

        Phase resolve = Phase.builder("resolve-formatters")
                .kind(PhaseKind.IO)
                .requires("collect-sources")
                .scope(1)
                .execute(ctx -> {
                    @SuppressWarnings("unchecked")
                    List<Path> javaFiles = (List<Path>) ctx.require(javaFilesKey);
                    @SuppressWarnings("unchecked")
                    List<Path> kotlinFiles = (List<Path>) ctx.require(kotlinFilesKey);
                    if (javaFiles.isEmpty() && kotlinFiles.isEmpty()) {
                        ctx.put(javaJarsKey, List.of());
                        ctx.put(kotlinJarsKey, List.of());
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("resolve formatter jars");
                    var resolver = ToolResolver.mavenCentral(new Http(), new Cas(cache));
                    try {
                        ctx.put(
                                javaJarsKey,
                                javaFiles.isEmpty()
                                        ? List.of()
                                        : resolver.resolve(javaCoord(javaStyle), "java-format", "ignored")
                                                .classpath());
                        ctx.put(
                                kotlinJarsKey,
                                kotlinFiles.isEmpty()
                                        ? List.of()
                                        : resolver.resolve(
                                                        Coordinate.of("com.facebook", "ktfmt", KTFMT_VERSION),
                                                        "ktfmt",
                                                        "ignored")
                                                .classpath());
                    } catch (RuntimeException e) {
                        ctx.error("resolve", e.getMessage());
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();

        Phase format = Phase.builder("format")
                .kind(PhaseKind.IO)
                .requires("resolve-formatters")
                .scope(0) // grown to the real file count once collected
                .execute(ctx -> {
                    @SuppressWarnings("unchecked")
                    List<Path> javaFiles = (List<Path>) ctx.require(javaFilesKey);
                    @SuppressWarnings("unchecked")
                    List<Path> kotlinFiles = (List<Path>) ctx.require(kotlinFilesKey);
                    int total = javaFiles.size() + kotlinFiles.size();
                    if (total == 0) {
                        ctx.put(CHANGED, 0);
                        ctx.put(CLEAN, 0);
                        ctx.put(ERRORS, 0);
                        ctx.put(WORKER_EXIT, 0);
                        return;
                    }
                    ctx.updateScope(total);
                    ctx.label(check ? "check formatting" : "format sources");
                    @SuppressWarnings("unchecked")
                    List<Path> javaJars = (List<Path>) ctx.require(javaJarsKey);
                    @SuppressWarnings("unchecked")
                    List<Path> kotlinJars = (List<Path>) ctx.require(kotlinJarsKey);

                    Path workerJar = WorkerJar.FORMATTER.locate(new Cas(cache));
                    Path spec = writeSpec(
                            check,
                            javaStyle,
                            kotlinStyle,
                            javaFiles,
                            javaJars,
                            kotlinFiles,
                            kotlinJars,
                            optimizeImports,
                            rewriteConfig,
                            cache);
                    try {
                        AtomicInteger changed = new AtomicInteger();
                        AtomicInteger clean = new AtomicInteger();
                        AtomicInteger errors = new AtomicInteger();
                        AtomicInteger index = new AtomicInteger();
                        int exit = new WorkerClient("##JKFMT:")
                                .on("file", json -> {
                                    String status = Ndjson.str(json, "status");
                                    if ("changed".equals(status)) changed.incrementAndGet();
                                    else if ("error".equals(status)) errors.incrementAndGet();
                                    else clean.incrementAndGet();
                                    observer.onFile(
                                            Ndjson.str(json, "path"),
                                            status,
                                            Ndjson.str(json, "msg"),
                                            index.incrementAndGet(),
                                            total);
                                    ctx.progress(1);
                                })
                                .passthrough(ctx::output)
                                .run(WorkerCommands.javaCommand(
                                        workerJar, javaFiles.isEmpty() ? List.of() : JAVAC_EXPORTS, spec));
                        ctx.put(CHANGED, changed.get());
                        ctx.put(CLEAN, clean.get());
                        ctx.put(ERRORS, errors.get());
                        ctx.put(WORKER_EXIT, exit);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("format worker interrupted", e);
                    } finally {
                        Files.deleteIfExists(spec);
                    }
                })
                .build();

        return Goal.builder("format")
                .addPhase(collect)
                .addPhase(resolve)
                .addPhase(format)
                .build();
    }

    private static Coordinate javaCoord(String style) {
        return "palantir".equals(style)
                ? Coordinate.of("com.palantir.javaformat", "palantir-java-format", PALANTIR_VERSION)
                : Coordinate.of("com.google.googlejavaformat", "google-java-format", GOOGLE_VERSION);
    }

    private static String javaVersion(String style) {
        return "palantir".equals(style) ? PALANTIR_VERSION : GOOGLE_VERSION;
    }

    private static Path writeSpec(
            boolean check,
            String javaStyle,
            String kotlinStyle,
            List<Path> javaFiles,
            List<Path> javaJars,
            List<Path> kotlinFiles,
            List<Path> kotlinJars,
            boolean optimizeImports,
            Path rewriteConfig,
            Path cacheDir)
            throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("mode\t" + (check ? "check" : "apply"));
        if (!javaFiles.isEmpty()) {
            lines.add("java\t" + javaStyle + "\t" + javaVersion(javaStyle) + "\t" + joinJars(javaJars));
        }
        if (!kotlinFiles.isEmpty()) {
            lines.add("kotlin\t"
                    + kotlinStyle
                    + "\t"
                    + KTFMT_VERSION
                    + "\t"
                    + KOTLIN_MAX_WIDTH
                    + "\t"
                    + joinJars(kotlinJars));
        }
        boolean anyRewrite = (optimizeImports || rewriteConfig != null) && !javaFiles.isEmpty();
        if (anyRewrite) {
            lines.add("rewrite-flags\toptimize-imports=" + optimizeImports);
            if (rewriteConfig != null) {
                lines.add("rewrite-config\t" + rewriteConfig.toAbsolutePath());
            }
        }
        // Pass the cache root so the worker can read/write per-file format stamps.
        if (cacheDir != null) {
            lines.add("cache-dir\t" + cacheDir.toAbsolutePath());
        }
        for (Path f : javaFiles) lines.add("f\tjava\t" + f.toAbsolutePath());
        for (Path f : kotlinFiles) lines.add("f\tkotlin\t" + f.toAbsolutePath());
        Path spec = Files.createTempFile("jk-format-", ".spec");
        Files.write(spec, lines, StandardCharsets.UTF_8);
        return spec;
    }

    private static String joinJars(List<Path> jars) {
        return jars.stream()
                .map(p -> p.toAbsolutePath().toString())
                .reduce((a, b) -> a + File.pathSeparator + b)
                .orElse("");
    }

    /** Collect project source files with the given extension, skipping build/VCS output dirs. */
    private static List<Path> collectSources(Path root, String ext) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(ext))
                    .filter(FormatGoals::notExcluded)
                    .sorted()
                    .toList();
        }
    }

    private static boolean notExcluded(Path p) {
        for (Path seg : p) {
            String s = seg.toString();
            if (s.equals("target")
                    || s.equals("build")
                    || s.equals(".jk")
                    || s.equals(".git")
                    || s.equals("node_modules")) {
                return false;
            }
        }
        return true;
    }
}
