// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.compile;

import java.io.IOException;

/**
 * Pluggable Kotlin compile driver. Mirrors {@link JavaCompileStrategy};
 * the default {@link SubprocessKotlincStrategy} execs
 * {@code <kotlin-home>/bin/kotlinc}. IDE plugins or daemon clients
 * register their own via {@link java.util.ServiceLoader}.
 */
public interface KotlinCompileStrategy {

    /** Strategy identifier. */
    String name();

    /** Compile the request. */
    KotlincResult compile(KotlincRequest request) throws IOException;
}
