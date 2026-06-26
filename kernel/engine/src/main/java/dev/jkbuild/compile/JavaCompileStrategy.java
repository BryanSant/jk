// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile;

import java.io.IOException;

/**
 * Pluggable {@code javac} driver (PRD §17 implementation seam). The default implementation, {@link
 * SubprocessJavacStrategy}, execs {@code <java-home>/bin/javac} as a subprocess; an IDE plugin or a
 * compile-daemon client could ship its own implementation via {@link java.util.ServiceLoader}.
 *
 * <p>The interface is deliberately minimal: one strategy per JVM process, thread-safe, no per-call
 * mutable state. Strategies that maintain resources (warm daemons, connection pools) own their own
 * lifetimes and keep them invisible behind {@link #compile}.
 */
public interface JavaCompileStrategy {

    /**
     * Strategy identifier. Used as a tiebreaker when multiple strategies are on the classpath and as
     * the value for the {@code jk.java-compile-strategy} system-property opt-in.
     */
    String name();

    /** Compile the request. */
    CompileResult compile(CompileRequest request) throws IOException;
}
