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
     * When non-null, this member's process output + warnings are appended here
     * instead of being written above the live region as they arrive. Parallel
     * builds set this so concurrent members' output never interleaves — the
     * caller flushes the whole block (above the region) when the member finishes.
     */
    private java.util.List<String> outBuffer;

    /** Route this member's output into {@code buffer} (parallel build); see field doc. */
    public void bufferOutputInto(java.util.List<String> buffer) {
        this.outBuffer = buffer;
    }

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
        emit(line);
    }

    @Override
    public void warn(String phase, String code, String message) {
        // Surface warnings above the shared bar as they arrive — same rendering as
        // the single-project ProgressBarListener. Compiler warnings get the rich
        // block (relative paths, color); everything else stays a one-liner.
        if (ConsoleSpec.isCompilerCode(code)) {
            emit(ConsoleSpec.compilerWarning(phase, message));
        } else {
            emit(ProgressBarListener.renderDiagnostic(
                    "⚠ Warning", dev.jkbuild.cli.theme.Theme.active().warning().bold(),
                    phase, code, message));
        }
    }

    /** Buffer (parallel) or write-above-now (serial), per {@link #bufferOutputInto}. */
    private void emit(String line) {
        if (outBuffer != null) {
            synchronized (outBuffer) { outBuffer.add(line); }
        } else {
            cm.writeAbove(line);
        }
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
        if (!result.success()) {
            agg.notifyErrors(result.errors());
        }
        // Calibrated: advance the base by exactly the slice we reserved in `total`
        // and drop this member's running contribution — Σ slices == total, no
        // boundary drift, no double-count. Uncalibrated: fold the live final
        // denominator (the pre-fix growing behaviour).
        if (agg.total() > 0) agg.completeMember(member, slice);
        else agg.completeMember(lastDenominator);
    }

    private void push(GoalView view) {
        lastDenominator = view.denominator();
        long base = agg.completedBase();
        long total = agg.total();
        if (total > 0) {
            // Calibrated: scale this member's own 0→100% into its fixed slice and
            // report it through the aggregate, which sums every running member's
            // contribution onto completedBase. Monotonic within the member and
            // clamped to total, so the bar neither backtracks at a member boundary
            // nor stretches the denominator past the up-front estimate — and
            // concurrent members add up instead of clobbering each other.
            long advanced = Math.round(view.fraction() * slice);
            agg.memberProgress(member, advanced);
        } else {
            // Uncalibrated caller (slice 0): fall back to live ticks against a
            // denominator that grows as members start.
            cm.progress(base + view.numerator(), base + view.denominator());
        }
    }
}
