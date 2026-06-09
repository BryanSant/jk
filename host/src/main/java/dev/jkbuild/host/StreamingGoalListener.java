// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.host;

import dev.jkbuild.plugin.host.HostEvent;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.GoalView;
import dev.jkbuild.run.PhaseStatus;

import java.io.PrintStream;
import java.time.Duration;

/**
 * A {@link GoalListener} that serializes every callback to stdout as a
 * {@link HostEvent}-prefixed NDJSON line, for consumption by the CLI's
 * {@link dev.jkbuild.cli.run.ReceivingGoalListener}. This is the Host's half
 * of the CLI↔Host event bridge.
 *
 * <p>Synchronized to prevent interleaved lines when multiple phase threads
 * fire callbacks concurrently — each {@code println} must be atomic.
 */
public final class StreamingGoalListener implements GoalListener {

    private final PrintStream out;
    private final String goalName;

    public StreamingGoalListener(PrintStream out, String goalName) {
        this.out = out;
        this.goalName = goalName;
    }

    @Override
    public void goalStart(GoalView view) {
        emit(HostEvent.goalStart(goalName, (int) view.denominator()));
    }

    @Override
    public void phaseStart(String phase, int scope) {
        emit(HostEvent.phaseStart(phase, scope));
    }

    @Override
    public void progress(String phase, int delta, GoalView view) {
        emit(HostEvent.progress(phase, delta, (int) view.numerator(), (int) view.denominator()));
    }

    @Override
    public void scopeUpdate(String phase, int delta, GoalView view) {
        emit(HostEvent.scopeUpdate(phase, delta));
    }

    @Override
    public void label(String phase, String label) {
        emit(HostEvent.label(phase, label));
    }

    @Override
    public void output(String phase, String line) {
        emit(HostEvent.output(phase, line));
    }

    @Override
    public void warn(String phase, String code, String message) {
        emit(HostEvent.warn(phase, code, message));
    }

    @Override
    public void error(String phase, String code, String message) {
        emit(HostEvent.error(phase, code, message));
    }

    @Override
    public void phaseFinish(String phase, PhaseStatus status, Duration duration) {
        emit(HostEvent.phaseFinish(phase, status.name(), duration.toMillis()));
    }

    @Override
    public void goalFinish(GoalResult result) {
        emit(HostEvent.goalFinish(result.success(), result.duration().toMillis()));
    }

    private synchronized void emit(String line) {
        out.println(line);
        out.flush();
    }
}
