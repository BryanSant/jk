// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.GoalView;
import dev.jkbuild.run.PhaseStatus;

import java.io.PrintStream;
import java.time.Duration;

/**
 * Emit one JSON object per event to stdout. Triggered by
 * {@code --output json}; consumed by CI scripts, log aggregators, and
 * other tooling. Wire format is defined by {@link NdjsonShape}.
 */
public final class NdjsonListener implements GoalListener {

    private final PrintStream out;

    public NdjsonListener(PrintStream out) {
        this.out = out;
    }

    @Override public void goalStart(GoalView v)                          { emit(NdjsonShape.goalStart(v)); }
    @Override public void phaseStart(String phase, int scope)            { emit(NdjsonShape.phaseStart(phase, scope)); }
    @Override public void progress(String phase, int delta, GoalView v)  { emit(NdjsonShape.progress(phase, delta, v)); }
    @Override public void scopeUpdate(String phase, int delta, GoalView v){ emit(NdjsonShape.scopeUpdate(phase, delta, v)); }
    @Override public void label(String phase, String label)              { emit(NdjsonShape.label(phase, label)); }
    @Override public void output(String phase, String line)              { emit(NdjsonShape.output(phase, line)); }
    @Override public void warn(String phase, String code, String msg)    { emit(NdjsonShape.warn(phase, code, msg)); }
    @Override public void error(String phase, String code, String msg)   { emit(NdjsonShape.error(phase, code, msg)); }
    @Override public void phaseFinish(String phase, PhaseStatus s, Duration d) { emit(NdjsonShape.phaseFinish(phase, s, d)); }
    @Override public void goalFinish(GoalResult r)                       { emit(NdjsonShape.goalFinish(r)); }

    private void emit(String line) {
        synchronized (out) {
            out.println(line);
            out.flush();
        }
    }
}
