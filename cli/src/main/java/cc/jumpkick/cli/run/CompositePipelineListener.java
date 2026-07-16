// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.plugin.build.Phase;
import cc.jumpkick.run.PipelineListener;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.PipelineView;
import cc.jumpkick.run.StepStatus;
import java.time.Duration;

/**
 * Fans every {@link PipelineListener} callback out to two delegates. Needed because an engine-hosted
 * module's {@code Pipeline} is a client-side, never-{@code run()} reconstruction (see {@code
 * EngineBuildListenerAdapter}) — a listener attached via {@code pipeline.addListener(...)} is never
 * driven. The listener a caller <em>returns</em> from {@code onModuleStart}, by contrast, is driven
 * by both the in-process and engine-hosted paths alike, so composing extra listeners (e.g. {@link
 * EventLogListener}) into the returned listener is the one place that works either way.
 */
public final class CompositePipelineListener implements PipelineListener {

    private final PipelineListener a;
    private final PipelineListener b;

    private CompositePipelineListener(PipelineListener a, PipelineListener b) {
        this.a = a;
        this.b = b;
    }

    /** {@code second} may be {@code null} (e.g. {@link EventLogListener#open} failed) — returns {@code first} as-is. */
    public static PipelineListener of(PipelineListener first, PipelineListener second) {
        return second == null ? first : new CompositePipelineListener(first, second);
    }

    @Override
    public void pipelineStart(PipelineView view) {
        a.pipelineStart(view);
        b.pipelineStart(view);
    }

    @Override
    public void stepStart(String step, Phase phase, int ticks) {
        a.stepStart(step, phase, ticks);
        b.stepStart(step, phase, ticks);
    }

    @Override
    public void progress(String step, int delta, PipelineView view) {
        a.progress(step, delta, view);
        b.progress(step, delta, view);
    }

    @Override
    public void tickUpdate(String step, int delta, PipelineView view) {
        a.tickUpdate(step, delta, view);
        b.tickUpdate(step, delta, view);
    }

    @Override
    public void label(String step, String label) {
        a.label(step, label);
        b.label(step, label);
    }

    @Override
    public void output(String step, String line) {
        a.output(step, line);
        b.output(step, line);
    }

    @Override
    public void warn(String step, String code, String message) {
        a.warn(step, code, message);
        b.warn(step, code, message);
    }

    @Override
    public void error(String step, String code, String message) {
        a.error(step, code, message);
        b.error(step, code, message);
    }

    @Override
    public void error(String step, String code, String message, String test, String exceptionClass) {
        a.error(step, code, message, test, exceptionClass);
        b.error(step, code, message, test, exceptionClass);
    }

    @Override
    public void stepFinish(String step, Phase phase, StepStatus status, Duration duration) {
        a.stepFinish(step, phase, status, duration);
        b.stepFinish(step, phase, status, duration);
    }

    @Override
    public void pipelineFinish(PipelineResult result) {
        a.pipelineFinish(result);
        b.pipelineFinish(result);
    }
}
