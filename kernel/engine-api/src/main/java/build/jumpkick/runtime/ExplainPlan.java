// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A front-end-facing forecast of {@code jk build}: the workspace's modules in dependency order
 * (each already front-end-safe — see {@link BuildPlan.Module}), the prereq edges and
 * peak-concurrency width the ETA model needs, and any graph-resolution errors. Deliberately free
 * of engine internals: edges are exposed as a plain {@code dir → prereq dirs} map (keyed by the
 * same {@link BuildPlan.Module#dir()} the caller iterates), so a non-CLI client can render the
 * plan and compute the estimate without engine internals.
 *
 * @param modules dependency-first modules; empty when {@link #hasErrors()}
 * @param edges module dir → the dirs that must build before it
 * @param maxReadyWidth widest wave of independent modules (drives ETA concurrency)
 * @param errors graph-resolution errors (cycle / depth-cap / missing jk.toml); non-empty ⇒ no plan
 */
public record ExplainPlan(
        List<BuildPlan.Module> modules, Map<Path, Set<Path>> edges, int maxReadyWidth, List<String> errors) {
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
