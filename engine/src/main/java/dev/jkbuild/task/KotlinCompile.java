// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compile.KotlincDriver;
import dev.jkbuild.compile.KotlincRequest;
import dev.jkbuild.compile.KotlincResult;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;

/**
 * Action-cache front for Kotlin compilation, mirroring {@link JavaIncrementalCompile}
 * but simpler: the Kotlin worker owns its own incremental recompile (BTA tracks
 * sources in its working dir), so there's no dependency-graph dirty-set step —
 * just a whole-set action-key fast path before forking the worker.
 *
 * <ol>
 *   <li>On an exact-input hit, restore the output dir from the CAS and skip the
 *       worker (the win for clean checkouts, CI, and cross-machine cache reuse).</li>
 *   <li>On a miss, fork the worker, then snapshot its output dir into the CAS and
 *       record it under the key.</li>
 * </ol>
 *
 * <p>Sits behind the cheap {@code .kstamp} freshness check (which short-circuits
 * the unchanged case before we even hash sources here).
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
     * @param useCache when false ({@code --no-cache}), skip the lookup and always
     *                 run the worker; the result is still recorded.
     */
    public static Result run(String taskId, KotlincRequest request, String jkVersion,
                             boolean useCache, Cas cas, ActionCache actionCache) throws IOException {
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
        actionCache.storeWithOutputs(taskId, key, Map.of(), outputs);
        return new Result(true, "compiled", key, kr.output());
    }
}
