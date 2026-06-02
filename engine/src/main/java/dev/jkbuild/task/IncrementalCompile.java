// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cache.Linking;
import dev.jkbuild.compile.CompileRequest;
import dev.jkbuild.compile.CompileResult;
import dev.jkbuild.compile.JavacDriver;
import dev.jkbuild.compile.incremental.IncrementalCompiler;
import dev.jkbuild.compile.incremental.IncrementalCompiler.CompilePlan;
import dev.jkbuild.compile.incremental.IncrementalCompiler.OutputFile;
import dev.jkbuild.compile.incremental.IncrementalCompiler.PlanRequest;
import dev.jkbuild.compile.incremental.IncrementalCompiler.PriorBuild;
import dev.jkbuild.compile.incremental.IncrementalCompiler.UnitOutputs;
import dev.jkbuild.compile.incremental.IncrementalCompilers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs a cached compile task: phase-key fast path, then (on a miss) an
 * {@link IncrementalCompiler}-planned recompile, then records the result with a
 * per-source unit grouping. Shared by {@code jk build} / {@code jk test}.
 *
 * <p>With the bundled {@link dev.jkbuild.compile.incremental.FullRebuildCompiler}
 * this collapses to today's behavior exactly: phase-key hit → restore all;
 * miss → compile every source → store. The seam is dormant until a real
 * incremental compiler is registered, at which point {@code plan()} returns a
 * subset to recompile, the rest is carried over from the CAS, and removed
 * sources' classes are dropped.
 */
public final class IncrementalCompile {

    private IncrementalCompile() {}

    /** Outcome of a {@link #run}. {@code diagnostics} carries javac's messages. */
    public record Result(boolean success, String outcome, String actionKey,
                         List<CompileResult.Diagnostic> diagnostics) {
        public Result {
            diagnostics = List.copyOf(diagnostics);
        }
        /** True when an existing record satisfied the request (no compile ran). */
        public boolean cacheHit() {
            return outcome.startsWith("cache-hit");
        }
    }

    /**
     * @param taskId  project-qualified task id (see {@code ActionKey.qualifiedTaskId})
     * @param useCache when false (e.g. {@code --no-cache}), skip the phase lookup
     *                 and any carry-over: a clean full recompile that still records.
     */
    public static Result run(String taskId, CompileRequest request, String jkVersion,
                             boolean useCache, Cas cas, ActionCache actionCache) throws IOException {
        return run(taskId, request, jkVersion, useCache, cas, actionCache,
                IncrementalCompilers.resolve(), new JavacDriver());
    }

    /** Test seam: inject the planner and the javac driver explicitly. */
    static Result run(String taskId, CompileRequest request, String jkVersion,
                      boolean useCache, Cas cas, ActionCache actionCache,
                      IncrementalCompiler compiler, JavacDriver driver) throws IOException {
        Path outputDir = request.outputDir();
        if (request.sources().isEmpty()) {
            if (outputDir != null) Files.createDirectories(outputDir);
            return new Result(true, "no-sources", "", List.of());
        }

        String key = ActionKey.forJavac(taskId, request, jkVersion);

        // 1. Phase fast-path: the whole-set key already saw these exact inputs.
        if (useCache) {
            Optional<ActionCache.ActionRecord> hit = actionCache.lookup(key);
            if (hit.isPresent()) {
                actionCache.restore(hit.get(), outputDir);
                return new Result(true, "cache-hit:" + key.substring(0, 8), key, List.of());
            }
        }

        // 2. Plan the recompile set. --no-cache forces a clean full rebuild.
        Optional<PriorBuild> prior = useCache ? loadPrior(actionCache, taskId) : Optional.empty();
        CompilePlan plan = useCache
                ? compiler.plan(new PlanRequest(request, prior, stateDir(cas, taskId)))
                : new CompilePlan(List.copyOf(request.sources()), java.util.Set.of(), java.util.Set.of());

        boolean incremental = !plan.carryOver().isEmpty() && prior.isPresent();

        // 3. Incremental pre-step: materialise carried-over classes, drop removed.
        if (incremental) {
            for (Path source : plan.carryOver()) {
                for (OutputFile of : prior.get().unitsBySource().getOrDefault(source, List.of())) {
                    Path dest = outputDir.resolve(of.relPath());
                    Files.createDirectories(dest.getParent());
                    Linking.linkOrCopy(cas.pathFor(of.sha()), dest);
                }
            }
            for (Path source : plan.dropped()) {
                for (OutputFile of : prior.get().unitsBySource().getOrDefault(source, List.of())) {
                    Files.deleteIfExists(outputDir.resolve(of.relPath()));
                }
            }
        }

        // 4. Compile (the full set, or just the recompile subset). Stream outputs
        //    into the CAS in the background; finish() snapshots the whole dir —
        //    carried-over files included — into the authoritative output map.
        CompileRequest toCompile = incremental ? withSources(request, plan.recompile()) : request;
        CasPrewriter prewriter = CasPrewriter.watching(cas, outputDir);
        CompileResult result;
        Map<String, String> outputs;
        try {
            result = driver.compile(toCompile);
        } finally {
            outputs = prewriter.finish();
        }
        if (!result.success() || result.hasErrors()) {
            return new Result(false, "errors", key, result.diagnostics());
        }

        // 5. Record: merge carried-over units with the freshly attributed ones.
        Map<String, List<String>> units = new LinkedHashMap<>();
        if (incremental) {
            for (Path source : plan.carryOver()) {
                List<String> rels = new ArrayList<>();
                for (OutputFile of : prior.get().unitsBySource().getOrDefault(source, List.of())) {
                    rels.add(of.relPath());
                }
                if (!rels.isEmpty()) units.put(source.toString(), rels);
            }
        }
        UnitOutputs attributed = compiler.attribute(plan, outputDir);
        attributed.bySource().forEach((src, rels) -> units.put(src.toString(), List.copyOf(rels)));

        actionCache.storeWithOutputs(taskId, key, ActionKey.snapshotInputs(request), outputs, units);
        return new Result(true, "compiled", key, result.diagnostics());
    }

    /** Reconstruct the previous run (record + its source→outputs grouping). */
    private static Optional<PriorBuild> loadPrior(ActionCache cache, String taskId) throws IOException {
        Optional<ActionCache.ActionRecord> last = cache.lastFor(taskId);
        if (last.isEmpty()) return Optional.empty();
        ActionCache.ActionRecord r = last.get();
        Map<Path, List<OutputFile>> units = new LinkedHashMap<>();
        r.units().forEach((source, rels) -> {
            List<OutputFile> files = new ArrayList<>();
            for (String rel : rels) {
                String sha = r.outputs().get(rel);
                if (sha != null) files.add(new OutputFile(rel, sha));
            }
            units.put(Path.of(source), files);
        });
        return Optional.of(new PriorBuild(units, r.inputs()));
    }

    private static CompileRequest withSources(CompileRequest base, List<Path> sources) {
        return CompileRequest.builder()
                .sources(sources)
                .classpath(base.classpath())
                .outputDir(base.outputDir())
                .release(base.release())
                .extraOptions(base.extraOptions())
                .javaHome(base.javaHome())
                .build();
    }

    /** Per-task scratch dir an incremental compiler may persist ABI state under. */
    private static Path stateDir(Cas cas, String taskId) {
        return cas.root().resolve("actions").resolve("incremental").resolve(taskId);
    }
}
