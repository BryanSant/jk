// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Thin facade for the Kotlin compile pipeline. Delegates to whichever
 * {@link KotlinCompileStrategy} {@link KotlinCompileStrategies} resolves
 * — by default a subprocess against {@code <kotlin-home>/bin/kotlinc}.
 */
public final class KotlincDriver {

    private final KotlinCompileStrategy strategy;

    public KotlincDriver() {
        this(KotlinCompileStrategies.resolve());
    }

    public KotlincDriver(KotlinCompileStrategy strategy) {
        this.strategy = strategy;
    }

    public KotlincResult compile(KotlincRequest request) {
        try {
            return strategy.compile(request);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
