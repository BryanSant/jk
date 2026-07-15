// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.plugin.build.Phase;
import build.jumpkick.run.PipelineListener;
import build.jumpkick.run.StepStatus;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens to one module's build and turns each <em>real</em> step run into a per-unit timing
 * {@link StepTimings.Sample} — the write side of the learned progress-bar ledger. Captures the
 * step's unit count at {@code stepStart} and its wall-clock at {@code stepFinish}, then derives
 * the per-unit weight via {@link EffortWeights#observedPerUnit} (which returns a negative sentinel
 * for a cache hit / skip that finished at or under its floor, so we never teach a ~0 rate). Only
 * the variable, count-driven steps are learned; flat steps keep their static weight.
 *
 * <p>Samples are appended to a shared, thread-safe sink (many modules build concurrently); the
 * caller folds them into the on-disk ledger once at build end via {@link StepTimings#record}.
 */
public final class StepTimingsRecorder implements PipelineListener {

    private final String moduleKey;
    private final List<StepTimings.Sample> sink;
    private final Map<String, Integer> ticksByStep = new ConcurrentHashMap<>();

    public StepTimingsRecorder(String moduleKey, List<StepTimings.Sample> sink) {
        this.moduleKey = moduleKey;
        this.sink = sink;
    }

    @Override
    public void stepStart(String step, Phase phase, int ticks) {
        ticksByStep.put(step, ticks);
    }

    @Override
    public void stepFinish(String step, Phase phase, StepStatus status, Duration duration) {
        if (status != StepStatus.SUCCESS || !learnable(step)) return;
        int count = ticksByStep.getOrDefault(step, 0);
        double perUnit = EffortWeights.observedPerUnit(step, duration.toMillis(), count);
        if (perUnit > 0) {
            sink.add(new StepTimings.Sample(moduleKey, step, perUnit));
        }
    }

    /** The variable, count-scaled steps whose duration is worth learning. */
    private static boolean learnable(String step) {
        return switch (step) {
            case "compile-java", "compile-kotlin", "compile-test", "run-tests" -> true;
            default -> false;
        };
    }
}
