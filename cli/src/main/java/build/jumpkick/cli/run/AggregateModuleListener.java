// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.cli.run;

import build.jumpkick.plugin.build.Phase;
import build.jumpkick.cli.tui.CommandManager;
import build.jumpkick.cli.tui.Glyphs;
import build.jumpkick.run.PipelineListener;
import build.jumpkick.run.PipelineResult;
import build.jumpkick.run.PipelineView;
import build.jumpkick.run.Step;
import build.jumpkick.run.StepStatus;
import java.time.Duration;
import java.util.List;

/**
 * Feeds one workspace module's pipeline/step events into the shared {@link AggregateContext}'s {@link
 * CommandManager}, tagging every step row with the module so interleaved modules stay distinct.
 * Does <em>not</em> finalize the shared view — {@code BuildCommand} settles it once after the last
 * module.
 */
public final class AggregateModuleListener implements PipelineListener {

    private final AggregateContext agg;
    private final CommandManager cm;
    private final String module;
    private final List<Step> steps;

    /**
     * This module's reserved slice of the calibrated {@code total} — the same pre-scan {@link
     * build.jumpkick.run.Pipeline#estimatedTotalWeight()} that was summed into the aggregate denominator.
     * The module's own 0→100% progress is scaled into this slice, so it can never consume more than
     * its share and the base advances by exactly this much on completion (no boundary drift). Ignored
     * on the uncalibrated path ({@code total == 0}), which falls back to live ticks.
     */
    /**
     * Mutable: starts at the module's pre-scan estimate, but tracks its pipeline denominator as steps
     * {@link build.jumpkick.run.StepContext#reweight reweight} mid-run (e.g. a compile that turns out
     * to be a cheap restore). Each change is propagated to the aggregate total via {@link
     * AggregateContext#growTotal} so the module's share of the bar reflects the work it actually
     * does.
     */
    private long slice;

    /** The pipeline denominator we've already folded into {@link #slice} / the total. */
    private long knownDenominator;

    private long lastDenominator;

    /**
     * When non-null, this module's process output + warnings are appended here instead of being
     * written above the live region as they arrive. Parallel builds set this so concurrent modules'
     * output never interleaves — the caller flushes the whole block (above the region) when the
     * module finishes.
     */
    private java.util.List<String> outBuffer;

    /** Route this module's output into {@code buffer} (parallel build); see field doc. */
    public void bufferOutputInto(java.util.List<String> buffer) {
        this.outBuffer = buffer;
    }

    public AggregateModuleListener(AggregateContext agg, String module, List<Step> steps) {
        this(agg, module, steps, 0);
    }

    public AggregateModuleListener(AggregateContext agg, String module, List<Step> steps, long slice) {
        this.agg = agg;
        this.cm = agg.view();
        this.module = module;
        this.steps = steps;
        this.slice = slice;
        this.knownDenominator = slice; // the pipeline's initial denominator == its pre-scan estimate
    }

    @Override
    public void pipelineStart(PipelineView view) {
        cm.target(module);
        for (Step p : steps) {
            String display = p.label() != null && !p.label().isEmpty() ? p.label() : p.name();
            cm.addStepLabeled(module, p.name(), display);
        }
        push(view);
    }

    @Override
    public void stepStart(String step, Phase phase, int ticks) {
        cm.stepRunning(module, step);
    }

    @Override
    public void label(String step, String label) {
        cm.stepMessage(module, step, label);
    }

    @Override
    public void output(String step, String line) {
        emit(line);
    }

    @Override
    public void warn(String step, String code, String message) {
        // Surface warnings above the shared bar as they arrive — same rendering as
        // the single-project ProgressBarListener. Compiler warnings get the rich
        // block (relative paths, color); everything else stays a one-liner.
        if (ConsoleSpec.isCompilerCode(code)) {
            emit(ConsoleSpec.compilerWarning(step, message));
        } else {
            emit(ProgressBarListener.renderDiagnostic(
                    Glyphs.BANG + " Warning", build.jumpkick.cli.theme.Theme.active().warning().bold(), step, code, message));
        }
    }

    /** Buffer (parallel) or write-above-now (serial), per {@link #bufferOutputInto}. */
    private void emit(String line) {
        if (outBuffer != null) {
            synchronized (outBuffer) {
                outBuffer.add(line);
            }
        } else {
            cm.writeAbove(line);
        }
    }

    @Override
    public void progress(String step, int delta, PipelineView view) {
        push(view);
    }

    @Override
    public void tickUpdate(String step, int delta, PipelineView view) {
        push(view);
    }

    @Override
    public void stepFinish(String step, Phase phase, StepStatus status, Duration duration) {
        cm.stepDone(module, step, status == StepStatus.SUCCESS);
    }

    @Override
    public void pipelineFinish(PipelineResult result) {
        if (!result.success()) {
            agg.notifyErrors(result.errors());
        }
        // Calibrated: advance the base by exactly the slice we reserved in `total`
        // and drop this module's running contribution — Σ slices == total, no
        // boundary drift, no double-count. Uncalibrated: fold the live final
        // denominator (the pre-fix growing behaviour).
        if (agg.total() > 0) agg.completeModule(module, slice);
        else agg.completeModule(lastDenominator);
    }

    private void push(PipelineView view) {
        lastDenominator = view.denominator();
        long base = agg.completedBase();
        long total = agg.total();
        if (total > 0) {
            // A step reweighted: fold the denominator delta into this module's
            // slice and the aggregate total, so a restore/skip shrinks (and a
            // surprise full grows) the module's share of the whole-workspace bar.
            if (view.denominator() != knownDenominator) {
                agg.growTotal(view.denominator() - knownDenominator);
                slice += view.denominator() - knownDenominator;
                knownDenominator = view.denominator();
                total = agg.total();
            }
            // Calibrated: scale this module's own 0→100% into its fixed slice and
            // report it through the aggregate, which sums every running module's
            // contribution onto completedBase. Monotonic within the module and
            // clamped to total, so the bar neither backtracks at a module boundary
            // nor stretches the denominator past the up-front estimate — and
            // concurrent modules add up instead of clobbering each other.
            long advanced = Math.round(view.fraction() * slice);
            agg.moduleProgress(module, advanced);
        } else {
            // Uncalibrated caller (slice 0): fall back to live ticks against a
            // denominator that grows as modules start.
            cm.progress(base + view.numerator(), base + view.denominator());
        }
    }
}
