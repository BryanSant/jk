// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver;

import dev.jkbuild.model.Dependency;
import java.io.IOException;
import java.util.List;

/**
 * Resolves a set of declared dependencies into a flat module → version map.
 *
 * <p>v0.1 ships with {@link NaiveResolver}; a PubGrub-backed implementation
 * with prose conflict diagnostics replaces it before v1.0. The interface
 * is the swap point.
 */
public interface Resolver {
    Resolution resolve(List<Dependency> roots) throws IOException, InterruptedException;
}
