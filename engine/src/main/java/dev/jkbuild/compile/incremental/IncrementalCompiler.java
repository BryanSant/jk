// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile.incremental;

import dev.jkbuild.compile.CompileRequest;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decides <em>what</em> to (re)compile for a cached compile task — the seam a
 * future per-file / ABI-aware incremental compiler plugs into. It does
 * <strong>not</strong> run {@code javac} (that stays with
 * {@link dev.jkbuild.compile.JavaCompileStrategy}); it only plans the
 * recompile set and attributes the produced class files back to their sources.
 *
 * <p>The bundled {@link FullRebuildCompiler} recompiles everything and tracks
 * no per-source units, which makes the orchestrator
 * ({@code dev.jkbuild.task.IncrementalCompile}) reproduce today's whole-phase
 * behavior byte-for-byte. A real incremental compiler — registered via
 * {@link java.util.ServiceLoader} and resolved by {@link IncrementalCompilers}
 * — overrides {@link #plan} to return a minimal recompile set (carrying the
 * rest over from the CAS) and {@link #attribute} to map each emitted
 * {@code .class} to its originating source. It owns the dependency/ABI graph
 * (see {@link AbiOracle}); none of that lives here.
 *
 * <p>Layering note: this package intentionally depends only on
 * {@link CompileRequest} and the JDK — never on the action cache — so the
 * action cache can depend on it without a cycle. The orchestrator adapts
 * between {@code ActionCache.ActionRecord} and {@link PriorBuild}.
 */
public interface IncrementalCompiler {

    /** Stable id; also the {@code jk.incremental-compiler} selector value. */
    String name();

    /**
     * Decide the recompile set given the current inputs and (optionally) the
     * previous run. Implementations must return a plan that, together, accounts
     * for every current source: each is either in {@code recompile} or
     * {@code carryOver}. The default recompiles all of them.
     */
    CompilePlan plan(PlanRequest request);

    /**
     * After the orchestrator has compiled {@link CompilePlan#recompile()} into
     * {@code outputDir}, map each produced {@code .class} (relative path) back
     * to its source so the result can be recorded per-unit and a removed
     * source's classes can be dropped. Returning an empty mapping means
     * "untracked" — valid only for a full rebuild.
     */
    UnitOutputs attribute(CompilePlan plan, Path outputDir);

    /** A produced output: its path relative to the output dir + its CAS sha. */
    record OutputFile(String relPath, String sha) {}

    /**
     * The previous cached run, reconstructed by the orchestrator from the
     * action record. {@code unitsBySource} is the source→outputs grouping (may
     * be empty for records written before per-unit tracking); {@code inputs} is
     * the prior input-hash snapshot for diffing.
     */
    record PriorBuild(Map<Path, List<OutputFile>> unitsBySource, Map<String, String> inputs) {}

    /** Inputs to {@link #plan}: current request, the prior run, and a scratch dir. */
    record PlanRequest(CompileRequest request, Optional<PriorBuild> prior, Path stateDir) {}

    /**
     * The plan: which sources to recompile, which to carry over from the prior
     * run untouched, and which prior sources have been removed (their classes
     * are dropped). {@code recompile ∪ carryOver} must cover every current
     * source; new sources are always in {@code recompile}.
     */
    record CompilePlan(List<Path> recompile, Set<Path> carryOver, Set<Path> dropped) {}

    /** Result of {@link #attribute}: source → the output relPaths it produced. */
    record UnitOutputs(Map<Path, List<String>> bySource) {}
}
