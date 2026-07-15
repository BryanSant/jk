// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.compile;

import build.jumpkick.plugin.Extension;
import build.jumpkick.plugin.build.Phase;
import java.io.IOException;
import java.util.Set;

/**
 * Pluggable {@code javac} driver (PRD §17 implementation seam). The default implementation, {@link
 * SubprocessJavacExtension}, execs {@code <java-home>/bin/javac} as a subprocess; an IDE plugin or a
 * compile-daemon client could ship its own implementation via {@link java.util.ServiceLoader}.
 *
 * <p>The interface is deliberately minimal: one strategy per JVM process, thread-safe, no per-call
 * mutable state. Strategies that maintain resources (warm daemons, connection pools) own their own
 * lifetimes and keep them invisible behind {@link #compile}.
 */
public interface JavaCompileStrategy extends Extension {

    /**
     * Strategy identifier. Used as a tiebreaker when multiple strategies are on the classpath and as
     * the value for the {@code jk.java-compile-strategy} system-property opt-in.
     */
    String name();

    /** An extension's id is its strategy name. */
    @Override
    default String id() {
        return name();
    }

    /** The javac strategy participates in the {@link Phase#COMPILE} phase. */
    @Override
    default Set<Phase> phases() {
        return Set.of(Phase.COMPILE);
    }

    /** Compile the request. */
    CompileResult compile(CompileRequest request) throws IOException;
}
