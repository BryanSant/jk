// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile.incremental;

import java.nio.file.Path;
import java.util.Set;

/**
 * The dependency/ABI engine a real {@link IncrementalCompiler} brings — the
 * thing that makes per-file incremental both <em>fast</em> and <em>sound</em>.
 * It answers: "did a type's public API change (vs. just a method body)?" and
 * "which sources depend on a changed type?" An implementation extracts a
 * stable header/ABI hash per type (turbine / {@code ijar}-style — signatures
 * only, no bytecode bodies) and maintains the reverse-dependency index, so a
 * body-only edit recompiles nothing downstream while an API change recompiles
 * exactly its consumers.
 *
 * <p><strong>Intentionally unimplemented.</strong> jk has no incremental
 * compiler yet; this interface only documents the contract so the orchestrator
 * and SPI are ready. Absent an implementation, the only <em>sound</em> planner
 * is {@link FullRebuildCompiler} (recompile everything). A future incremental
 * compiler supplies its own {@code AbiOracle} and persists state under the
 * {@link IncrementalCompiler.PlanRequest#stateDir()} handed to {@code plan()}.
 */
public interface AbiOracle {

    /** A type's ABI hash: signatures only, stable across body-only edits. */
    byte[] abiHash(Path classFile);

    /** True when {@code source}'s public API changed between two ABI hashes. */
    boolean abiChanged(byte[] previousAbiHash, byte[] currentAbiHash);

    /** Sources whose compilation depends on {@code changed}'s ABI (its consumers). */
    Set<Path> dependentsOf(Path changed);

    // TODO(incremental): no production implementation yet. The forthcoming
    // incremental compiler implements this (header extraction + reverse-dep
    // graph) and wires it into its IncrementalCompiler.plan(). Until then,
    // FullRebuildCompiler is the only registered compiler.
}
