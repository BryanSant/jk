// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.plugin.build.Phase;
import cc.jumpkick.run.PipelineListener;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.PipelineView;
import cc.jumpkick.run.StepStatus;
import java.io.PrintStream;
import java.time.Duration;

/**
 * Emit one JSON object per event to stdout. Triggered by {@code --output json}; consumed by CI
 * scripts, log aggregators, and other tooling. Wire format is defined by {@link JsonlShape}.
 */
public final class JsonlListener implements PipelineListener {

    private final PrintStream out;

    public JsonlListener(PrintStream out) {
        this.out = out;
    }

    @Override
    public void pipelineStart(PipelineView v) {
        emit(JsonlShape.pipelineStart(v));
    }

    @Override
    public void stepStart(String step, Phase phase, int ticks) {
        emit(JsonlShape.stepStart(step, phase == null ? "" : phase.wireName(), ticks));
    }

    @Override
    public void progress(String step, int delta, PipelineView v) {
        emit(JsonlShape.progress(step, delta, v));
    }

    @Override
    public void tickUpdate(String step, int delta, PipelineView v) {
        emit(JsonlShape.tickUpdate(step, delta, v));
    }

    @Override
    public void label(String step, String label) {
        emit(JsonlShape.label(step, label));
    }

    @Override
    public void output(String step, String line) {
        emit(JsonlShape.output(step, line));
    }

    @Override
    public void warn(String step, String code, String msg) {
        emit(JsonlShape.warn(step, code, msg));
    }

    @Override
    public void error(String step, String code, String msg) {
        emit(JsonlShape.error(step, code, msg));
    }

    @Override
    public void error(String step, String code, String msg, String test, String exClass) {
        emit(JsonlShape.error(step, code, msg, test, exClass));
    }

    @Override
    public void stepFinish(String step, Phase phase, StepStatus s, Duration d) {
        emit(JsonlShape.stepFinish(step, phase == null ? "" : phase.wireName(), s, d));
    }

    @Override
    public void pipelineFinish(PipelineResult r) {
        emit(JsonlShape.pipelineFinish(r));
    }

    private void emit(String line) {
        synchronized (out) {
            out.println(line);
            out.flush();
        }
    }
}
