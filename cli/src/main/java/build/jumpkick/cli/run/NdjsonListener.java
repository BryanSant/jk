// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.cli.run;

import build.jumpkick.plugin.build.Phase;
import build.jumpkick.run.PipelineListener;
import build.jumpkick.run.PipelineResult;
import build.jumpkick.run.PipelineView;
import build.jumpkick.run.StepStatus;
import java.io.PrintStream;
import java.time.Duration;

/**
 * Emit one JSON object per event to stdout. Triggered by {@code --output json}; consumed by CI
 * scripts, log aggregators, and other tooling. Wire format is defined by {@link NdjsonShape}.
 */
public final class NdjsonListener implements PipelineListener {

    private final PrintStream out;

    public NdjsonListener(PrintStream out) {
        this.out = out;
    }

    @Override
    public void pipelineStart(PipelineView v) {
        emit(NdjsonShape.pipelineStart(v));
    }

    @Override
    public void stepStart(String step, Phase phase, int ticks) {
        emit(NdjsonShape.stepStart(step, phase == null ? "" : phase.wireName(), ticks));
    }

    @Override
    public void progress(String step, int delta, PipelineView v) {
        emit(NdjsonShape.progress(step, delta, v));
    }

    @Override
    public void tickUpdate(String step, int delta, PipelineView v) {
        emit(NdjsonShape.tickUpdate(step, delta, v));
    }

    @Override
    public void label(String step, String label) {
        emit(NdjsonShape.label(step, label));
    }

    @Override
    public void output(String step, String line) {
        emit(NdjsonShape.output(step, line));
    }

    @Override
    public void warn(String step, String code, String msg) {
        emit(NdjsonShape.warn(step, code, msg));
    }

    @Override
    public void error(String step, String code, String msg) {
        emit(NdjsonShape.error(step, code, msg));
    }

    @Override
    public void error(String step, String code, String msg, String test, String exClass) {
        emit(NdjsonShape.error(step, code, msg, test, exClass));
    }

    @Override
    public void stepFinish(String step, Phase phase, StepStatus s, Duration d) {
        emit(NdjsonShape.stepFinish(step, phase == null ? "" : phase.wireName(), s, d));
    }

    @Override
    public void pipelineFinish(PipelineResult r) {
        emit(NdjsonShape.pipelineFinish(r));
    }

    private void emit(String line) {
        synchronized (out) {
            out.println(line);
            out.flush();
        }
    }
}
