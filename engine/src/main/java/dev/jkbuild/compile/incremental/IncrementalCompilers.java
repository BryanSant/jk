// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile.incremental;

import java.util.ServiceLoader;

/**
 * Locates the {@link IncrementalCompiler} jk should use this process. Mirrors
 * {@link dev.jkbuild.compile.JavaCompileStrategies}:
 *
 * <ol>
 *   <li>The compiler whose {@link IncrementalCompiler#name()} matches the
 *       {@code jk.incremental-compiler} system property.</li>
 *   <li>The first non-{@code "full"} compiler discovered via
 *       {@link ServiceLoader} — a plugin opts in by shipping a
 *       {@code META-INF/services/dev.jkbuild.compile.incremental.IncrementalCompiler}.</li>
 *   <li>{@link FullRebuildCompiler} — the bundled default (whole-phase rebuild,
 *       i.e. today's behavior).</li>
 * </ol>
 */
public final class IncrementalCompilers {

    private IncrementalCompilers() {}

    public static IncrementalCompiler resolve() {
        String preferred = System.getProperty("jk.incremental-compiler");
        IncrementalCompiler fallback = null;
        for (IncrementalCompiler c : ServiceLoader.load(IncrementalCompiler.class)) {
            if (preferred != null && preferred.equals(c.name())) {
                return c;
            }
            if ("full".equals(c.name())) {
                fallback = c;
            } else if (preferred == null && fallback == null) {
                return c; // first real incremental impl wins when nothing's pinned
            }
        }
        if (preferred != null) {
            throw new IllegalStateException(
                    "no IncrementalCompiler named `" + preferred + "` on the classpath");
        }
        return fallback != null ? fallback : new FullRebuildCompiler();
    }
}
