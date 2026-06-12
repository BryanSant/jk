// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.cli.tui.CommandManager;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.GoalView;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseStatus;

import java.time.Duration;
import java.util.ArrayList;
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

    /**
     * This member's reserved slice of the calibrated {@code total} — the same
     * pre-scan {@link dev.jkbuild.run.Goal#estimatedTotalWeight()} that was summed
     * into the aggregate denominator. The member's own 0→100% progress is scaled
     * into this slice, so it can never consume more than its share and the base
     * advances by exactly this much on completion (no boundary drift). Ignored on
     * the uncalibrated path ({@code total == 0}), which falls back to live ticks.
     */
    private final long slice;

    private long lastDenominator;

    /**
     * Diagnostics gathered from {@code error} events. The in-process path also
     * carries them on the terminal {@link GoalResult}, but the Host bridge builds
     * a diagnostic-free result (errors stream as events and live in the run log),
     * so we collect them here to surface a failed Host member's errors too.
     */
    private final List<GoalResult.Diagnostic> errors = new ArrayList<>();

    public AggregateMemberListener(AggregateContext agg, String member, List<Phase> phases) {
        this(agg, member, phases, 0);
    }

    public AggregateMemberListener(AggregateContext agg, String member, List<Phase> phases, long slice) {
        this.agg = agg;
        this.cm = agg.view();
        this.member = member;
        this.phases = phases;
        this.slice = slice;
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
    public void error(String phase, String code, String message) {
        errors.add(new GoalResult.Diagnostic(phase, code, message));
    }

    @Override
    public void goalFinish(GoalResult result) {
        if (!result.success()) {
            // Prefer the result's diagnostics (in-process); fall back to the ones
            // we gathered from error events (Host, whose result carries none).
            agg.notifyErrors(result.errors().isEmpty() ? List.copyOf(errors) : result.errors());
        }
        // Calibrated: advance the base by exactly the slice we reserved in `total`,
        // so the next member starts precisely where this one's slice ended — Σ
        // slices == total, no boundary drift. Uncalibrated: fold the live final
        // denominator (the pre-fix growing behaviour).
        agg.completeMember(agg.total() > 0 ? slice : lastDenominator);
    }

    private void push(GoalView view) {
        lastDenominator = view.denominator();
        long base = agg.completedBase();
        long total = agg.total();
        if (total > 0) {
            // Calibrated: scale this member's own 0→100% into its fixed slice and
            // clamp to it. The numerator is monotonic within the member and can
            // never exceed base + slice ≤ total, so the bar neither backtracks at
            // the member boundary nor stretches the denominator past the up-front
            // estimate. The denominator stays pinned to `total` for the whole run.
            long advanced = Math.round(view.fraction() * slice);
            long num = Math.min(base + advanced, total);
            cm.progress(num, total);
        } else {
            // Uncalibrated (--use-host / native workspace): no pre-scan, so fall
            // back to live ticks against a denominator that grows as members start.
            cm.progress(base + view.numerator(), base + view.denominator());
        }
    }
}
