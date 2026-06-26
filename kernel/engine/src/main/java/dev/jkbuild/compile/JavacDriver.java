// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Thin facade kept for backward compatibility with existing callers. The real work happens in
 * whichever {@link JavaCompileStrategy} is resolved by {@link JavaCompileStrategies} (subprocess by
 * default; an IDE plugin can register its own via {@link java.util.ServiceLoader}).
 */
public final class JavacDriver {

    private final JavaCompileStrategy strategy;

    public JavacDriver() {
        this(JavaCompileStrategies.resolve());
    }

    public JavacDriver(JavaCompileStrategy strategy) {
        this.strategy = strategy;
    }

    public CompileResult compile(CompileRequest request) {
        try {
            return strategy.compile(request);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
