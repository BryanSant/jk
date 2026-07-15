// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.config;

import java.util.List;

/**
 * Resolved (or partially-resolved) JVM tuning for the worker JVMs jk forks (compilers, test runners,
 * …). Carried on the request-scoped {@link Session} so the worker layer reads it from the request
 * rather than a process-global; {@code null} scalars mean "fall back to the next layer / built-in
 * default", and {@code extraArgs} are raw flags appended verbatim.
 *
 * <p>The resolution logic (env / {@code jk.toml} / flag precedence, flag rendering, heap planning)
 * lives in {@code build.jumpkick.engine.plugin.JvmOptions}; this is the plain value it produces and the {@code
 * Session} carries.
 */
public record PluginTuning(Double maxRamPercent, String gc, Boolean stringDedup, List<String> extraArgs) {

    public PluginTuning {
        extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
    }

    /** The empty layer — every field unset. */
    public static final PluginTuning NONE = new PluginTuning(null, null, null, List.of());
}
