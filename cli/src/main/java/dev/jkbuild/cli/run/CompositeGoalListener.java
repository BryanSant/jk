// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.GoalView;
import dev.jkbuild.run.PhaseStatus;
import java.time.Duration;

/**
 * Fans every {@link GoalListener} callback out to two delegates. Needed because a daemon-hosted
 * module's {@code Goal} is a client-side, never-{@code run()} reconstruction (see {@code
 * DaemonBuildListenerAdapter}) — a listener attached via {@code goal.addListener(...)} is never
 * driven. The listener a caller <em>returns</em> from {@code onModuleStart}, by contrast, is driven
 * by both the in-process and daemon-hosted paths alike, so composing extra listeners (e.g. {@link
 * EventLogListener}) into the returned listener is the one place that works either way.
 */
public final class CompositeGoalListener implements GoalListener {

    private final GoalListener a;
    private final GoalListener b;

    private CompositeGoalListener(GoalListener a, GoalListener b) {
        this.a = a;
        this.b = b;
    }

    /** {@code second} may be {@code null} (e.g. {@link EventLogListener#open} failed) — returns {@code first} as-is. */
    public static GoalListener of(GoalListener first, GoalListener second) {
        return second == null ? first : new CompositeGoalListener(first, second);
    }

    @Override
    public void goalStart(GoalView view) {
        a.goalStart(view);
        b.goalStart(view);
    }

    @Override
    public void phaseStart(String phase, int scope) {
        a.phaseStart(phase, scope);
        b.phaseStart(phase, scope);
    }

    @Override
    public void progress(String phase, int delta, GoalView view) {
        a.progress(phase, delta, view);
        b.progress(phase, delta, view);
    }

    @Override
    public void scopeUpdate(String phase, int delta, GoalView view) {
        a.scopeUpdate(phase, delta, view);
        b.scopeUpdate(phase, delta, view);
    }

    @Override
    public void label(String phase, String label) {
        a.label(phase, label);
        b.label(phase, label);
    }

    @Override
    public void output(String phase, String line) {
        a.output(phase, line);
        b.output(phase, line);
    }

    @Override
    public void warn(String phase, String code, String message) {
        a.warn(phase, code, message);
        b.warn(phase, code, message);
    }

    @Override
    public void error(String phase, String code, String message) {
        a.error(phase, code, message);
        b.error(phase, code, message);
    }

    @Override
    public void error(String phase, String code, String message, String test, String exceptionClass) {
        a.error(phase, code, message, test, exceptionClass);
        b.error(phase, code, message, test, exceptionClass);
    }

    @Override
    public void phaseFinish(String phase, PhaseStatus status, Duration duration) {
        a.phaseFinish(phase, status, duration);
        b.phaseFinish(phase, status, duration);
    }

    @Override
    public void goalFinish(GoalResult result) {
        a.goalFinish(result);
        b.goalFinish(result);
    }
}
