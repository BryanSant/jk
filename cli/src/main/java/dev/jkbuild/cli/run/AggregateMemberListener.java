// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.cli.tui.CommandManager;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.GoalView;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseStatus;

import java.time.Duration;
import java.util.List;

/**
 * Feeds one workspace member's goal/phase events into the shared
 * {@link AggregateContext}'s {@link CommandManager}, tagging every phase row
 * with the member so interleaved members stay distinct. Does <em>not</em>
 * finalize the shared view — {@code BuildCommand} settles it once after the
 * last member.
 */
public final class AggregateMemberListener implements GoalListener {

    private final AggregateContext agg;
    private final CommandManager cm;
    private final String member;
    private final List<Phase> phases;

    private long lastDenominator;

    public AggregateMemberListener(AggregateContext agg, String member, List<Phase> phases) {
        this.agg = agg;
        this.cm = agg.view();
        this.member = member;
        this.phases = phases;
    }

    @Override
    public void goalStart(GoalView view) {
        cm.target(member);
        for (Phase p : phases) {
            String display = p.label() != null && !p.label().isEmpty() ? p.label() : p.name();
            cm.addPhaseLabeled(member, p.name(), display);
        }
        push(view);
    }

    @Override
    public void phaseStart(String phase, int scope) {
        cm.phaseRunning(member, phase);
    }

    @Override
    public void label(String phase, String label) {
        cm.phaseMessage(member, phase, label);
    }

    @Override
    public void output(String phase, String line) {
        cm.writeAbove(line);
    }

    @Override
    public void progress(String phase, int delta, GoalView view) {
        push(view);
    }

    @Override
    public void scopeUpdate(String phase, int delta, GoalView view) {
        push(view);
    }

    @Override
    public void phaseFinish(String phase, PhaseStatus status, Duration duration) {
        cm.phaseDone(member, phase, status == PhaseStatus.SUCCESS);
    }

    @Override
    public void goalFinish(GoalResult result) {
        agg.completeMember(lastDenominator);
    }

    private void push(GoalView view) {
        lastDenominator = view.denominator();
        long base = agg.completedBase();
        cm.progress(base + view.numerator(), base + view.denominator());
    }
}
