// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.compile;

import java.util.ServiceLoader;

/**
 * Locates the {@link JavaCompileStrategy} jk should use this process.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>The strategy whose {@link JavaCompileStrategy#name()} matches the
 *       {@code jk.java-compile-strategy} system property — explicit
 *       caller wins, even if it duplicates the default.</li>
 *   <li>The first non-{@code "subprocess"} strategy discovered via
 *       {@link ServiceLoader} — lets a plugin opt in by providing a
 *       {@code META-INF/services/dev.buildjk.compile.JavaCompileStrategy}
 *       file.</li>
 *   <li>{@link SubprocessJavacStrategy} — the bundled default.</li>
 * </ol>
 *
 * <p>Under native-image, only strategies on the build-time classpath are
 * discoverable. A native binary effectively pins the strategy at build
 * time; runtime plugin discovery is JVM-mode only.
 */
public final class JavaCompileStrategies {

    private JavaCompileStrategies() {}

    public static JavaCompileStrategy resolve() {
        String preferred = System.getProperty("jk.java-compile-strategy");
        JavaCompileStrategy fallback = null;
        for (JavaCompileStrategy s : ServiceLoader.load(JavaCompileStrategy.class)) {
            if (preferred != null && preferred.equals(s.name())) {
                return s;
            }
            if ("subprocess".equals(s.name())) {
                fallback = s;
            } else if (preferred == null && fallback == null) {
                // First non-default impl wins when no explicit choice is made.
                return s;
            }
        }
        if (preferred != null) {
            throw new IllegalStateException(
                    "no JavaCompileStrategy named `" + preferred + "` on the classpath");
        }
        return fallback != null ? fallback : new SubprocessJavacStrategy();
    }
}
