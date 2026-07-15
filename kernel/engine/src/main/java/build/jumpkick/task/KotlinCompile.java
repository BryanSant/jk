// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.task;

import build.jumpkick.cache.Cas;
import build.jumpkick.compile.KotlincDriver;
import build.jumpkick.compile.KotlincRequest;
import build.jumpkick.compile.KotlincResult;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;

/**
 * Action-cache front for Kotlin compilation, mirroring {@link JavaIncrementalCompile} but simpler:
 * the Kotlin worker owns its own incremental recompile (BTA tracks sources in its working dir), so
 * there's no dependency-graph dirty-set step — just a whole-set action-key fast path before forking
 * the worker.
 *
 * <ol>
 *   <li>On an exact-input hit, restore the output dir from the CAS and skip the worker (the win for
 *       clean checkouts, CI, and cross-machine cache reuse).
 *   <li>On a miss, fork the worker, then snapshot its output dir into the CAS and record it under
 *       the key.
 * </ol>
 *
 * <p>Sits behind the cheap {@code .kstamp} freshness check (which short-circuits the unchanged case
 * before we even hash sources here).
 */
public final class KotlinCompile {

    private KotlinCompile() {}

    /** Outcome of a {@link #run}. {@code output} carries the worker's diagnostics. */
    public record Result(boolean success, String outcome, String actionKey, String output) {
        /** True when an existing record satisfied the request (no compile ran). */
        public boolean cacheHit() {
            return outcome.startsWith("cache-hit");
        }
    }

    /**
     * @param useCache when false ({@code --force} / {@code jk verify}), skip the lookup AND the
     *     final store — a bypassing run neither reads nor writes the action cache; the
     *     result is still recorded.
     */
    public static Result run(
            String taskId, KotlincRequest request, String jkVersion, boolean useCache, Cas cas, ActionCache actionCache)
            throws IOException {
        String key = ActionKey.forKotlinc(taskId, request, jkVersion);

        if (useCache) {
            Optional<ActionCache.ActionRecord> hit = actionCache.lookup(key);
            if (hit.isPresent()) {
                actionCache.restore(hit.get(), request.outputDir());
                return new Result(true, "cache-hit:" + key.substring(0, 8), key, "");
            }
        }

        // Stream the worker's output dir into the CAS as it's produced, then
        // snapshot the whole dir for the record.
        Files.createDirectories(request.outputDir());
        // Incremental state is only valid alongside the outputs it produced: if the output
        // dir is (now) empty of classes while IC state survives (a cleaned target/, a fresh
        // checkout with a warm cache), BTA would compile "only what changed" into the void
        // and report success with a near-empty dir. Start the IC state over instead.
        if (request.incremental() && Files.isDirectory(request.workingDir()) && !hasClasses(request.outputDir())) {
            build.jumpkick.util.PathUtil.deleteRecursively(request.workingDir());
        }
        CasPrewriter prewriter = CasPrewriter.watching(cas, request.outputDir());
        KotlincResult kr;
        Map<String, String> outputs;
        try {
            kr = new KotlincDriver().compile(request);
        } finally {
            outputs = prewriter.finish();
        }
        if (!kr.success()) {
            return new Result(false, "errors", key, kr.output());
        }
        // Never cache a zero-output "success" for a non-empty source set: stale incremental
        // state can convince the compiler nothing changed while the output dir is empty, and
        // caching that poisons every later run under the same key.
        if (outputs.isEmpty() && !request.sources().isEmpty()) {
            return new Result(true, "compiled-no-outputs", key, kr.output());
        }
        // Bypassing runs neither read NOR write: --force must not churn entries under keys
        // the normal path already owns, and jk verify's scratch build (path-salted keys that
        // can never recur) must not leave orphan records behind.
        if (useCache) actionCache.storeWithOutputs(taskId, key, Map.of(), outputs);
        return new Result(true, "compiled", key, kr.output());
    }

    /** Any {@code .class} anywhere under {@code dir}? */
    private static boolean hasClasses(java.nio.file.Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return false;
        try (var walk = Files.walk(dir)) {
            return walk.anyMatch(f -> f.toString().endsWith(".class"));
        }
    }
}
