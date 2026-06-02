// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile.incremental;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The bundled default: recompile the whole source set every time and track no
 * per-source units. With this compiler the orchestrator collapses to today's
 * "phase-key hit → restore all, miss → compile all → store all" behavior.
 * A future incremental compiler replaces this via {@link IncrementalCompilers}.
 */
public final class FullRebuildCompiler implements IncrementalCompiler {

    @Override
    public String name() {
        return "full";
    }

    @Override
    public CompilePlan plan(PlanRequest request) {
        // Everything is recompiled; nothing is carried over. Prior sources are
        // "dropped" because a full rebuild wipes and regenerates the output dir.
        Set<Path> dropped = request.prior()
                .map(p -> p.unitsBySource().keySet())
                .orElse(Set.of());
        return new CompilePlan(List.copyOf(request.request().sources()), Set.of(), dropped);
    }

    @Override
    public UnitOutputs attribute(CompilePlan plan, Path outputDir) {
        // Untracked: the orchestrator records the flat output set with no
        // per-source grouping (back-compat with pre-incremental records).
        return new UnitOutputs(Map.of());
    }
}
