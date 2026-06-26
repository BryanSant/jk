// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.PhaseStatus;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens to one module's build and turns each <em>real</em> phase run into a per-unit timing
 * {@link PhaseTimings.Sample} — the write side of the learned progress-bar ledger. Captures the
 * phase's unit count at {@code phaseStart} and its wall-clock at {@code phaseFinish}, then derives
 * the per-unit weight via {@link EffortWeights#observedPerUnit} (which returns a negative sentinel
 * for a cache hit / skip that finished at or under its floor, so we never teach a ~0 rate). Only
 * the variable, count-driven phases are learned; flat phases keep their static weight.
 *
 * <p>Samples are appended to a shared, thread-safe sink (many modules build concurrently); the
 * caller folds them into the on-disk ledger once at build end via {@link PhaseTimings#record}.
 */
public final class PhaseTimingsRecorder implements GoalListener {

    private final String moduleKey;
    private final List<PhaseTimings.Sample> sink;
    private final Map<String, Integer> scopeByPhase = new ConcurrentHashMap<>();

    public PhaseTimingsRecorder(String moduleKey, List<PhaseTimings.Sample> sink) {
        this.moduleKey = moduleKey;
        this.sink = sink;
    }

    @Override
    public void phaseStart(String phase, int scope) {
        scopeByPhase.put(phase, scope);
    }

    @Override
    public void phaseFinish(String phase, PhaseStatus status, Duration duration) {
        if (status != PhaseStatus.SUCCESS || !learnable(phase)) return;
        int count = scopeByPhase.getOrDefault(phase, 0);
        double perUnit = EffortWeights.observedPerUnit(phase, duration.toMillis(), count);
        if (perUnit > 0) {
            sink.add(new PhaseTimings.Sample(moduleKey, phase, perUnit));
        }
    }

    /** The variable, count-scaled phases whose duration is worth learning. */
    private static boolean learnable(String phase) {
        return switch (phase) {
            case "compile-java", "compile-kotlin", "compile-test", "run-tests" -> true;
            default -> false;
        };
    }
}
