// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.compile;

import java.util.ServiceLoader;

/**
 * Locates the {@link KotlinCompileStrategy} for this process. See
 * {@link JavaCompileStrategies} for the resolution rules — same shape.
 */
public final class KotlinCompileStrategies {

    private KotlinCompileStrategies() {}

    public static KotlinCompileStrategy resolve() {
        String preferred = System.getProperty("jk.kotlin-compile-strategy");
        KotlinCompileStrategy fallback = null;
        for (KotlinCompileStrategy s : ServiceLoader.load(KotlinCompileStrategy.class)) {
            if (preferred != null && preferred.equals(s.name())) {
                return s;
            }
            if ("subprocess".equals(s.name())) {
                fallback = s;
            } else if (preferred == null && fallback == null) {
                return s;
            }
        }
        if (preferred != null) {
            throw new IllegalStateException(
                    "no KotlinCompileStrategy named `" + preferred + "` on the classpath");
        }
        return fallback != null ? fallback : new SubprocessKotlincStrategy();
    }
}
