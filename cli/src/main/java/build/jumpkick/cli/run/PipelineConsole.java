// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.cli.run;

import build.jumpkick.cli.tui.Glyphs;
import build.jumpkick.run.Pipeline;
import build.jumpkick.run.PipelineListener;
import build.jumpkick.run.PipelineResult;
import build.jumpkick.run.Step;
import build.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.List;

/**
 * Single entry point CLI commands use to run a {@link Pipeline} against the right set of console
 * listeners — picks {@link CommandManagerListener} (default TTY), {@link VerboseListener} ({@code
 * --verbose}), {@link NdjsonListener} ({@code --output json}), or {@link SilentListener} (non-TTY,
 * {@code --quiet}, or interactive pipeline); always layers an {@link EventLogListener} on top so the
 * run lands in {@code <cacheRoot>/runs/}.
 *
 * <p>Ctrl-C during a pipeline is handled by the app-level {@link build.jumpkick.cli.tui.GlobalCancel}
 * handler (installed at startup): it repaints the in-flight progress bar as canceled, prints {@code
 * ‼ Canceled by user}, and halts. There is no cooperative unwind — a hard cancel is immediate and
 * predictable.
 */
public final class PipelineConsole {

    /** Output mode requested by the user. */
    public enum Mode {
        /** Default: progress bar on a TTY, silent on pipes. */
        AUTO,
        /** Per-step lines (today's {@code --verbose}). */
        VERBOSE,
        /** Silent (today's {@code --quiet}, or interactive pipelines). */
        QUIET,
        /** NDJSON to stdout (today's {@code --output json}). */
        JSON
    }

    private PipelineConsole() {}

    /**
     * Pick listeners + run the pipeline. Ctrl-C is handled by the app-level {@link
     * build.jumpkick.cli.tui.GlobalCancel} handler. Returns the pipeline's {@link PipelineResult}; caller
     * decides what exit code to surface based on {@code result.success()}.
     */
    public static PipelineResult run(Pipeline pipeline, Mode mode, Path cacheRoot) {
        // Always log every run for post-hoc debug. Best-effort: a
        // failed log open just leaves the listener out of the chain.
        EventLogListener log = EventLogListener.open(cacheRoot, pipeline.name());
        if (log != null) pipeline.addListener(log);

        PipelineListener console = chooseConsoleListener(pipeline, mode);
        if (console != null) pipeline.addListener(console);

        return pipeline.run();
    }

    /**
     * Variant that derives the cache root from {@link JkDirs#cache}. Use when the command doesn't
     * have an explicit override.
     */
    public static PipelineResult run(Pipeline pipeline, Mode mode) {
        return run(pipeline, mode, JkDirs.cache());
    }

    /**
     * Simple-task variant: render the pipeline as a spinner + command (on a TTY) and a {@code ✓}/{@code ✗}
     * result line from {@code spec}, instead of the step-by-step progress bar. {@code --output
     * json} still emits NDJSON and {@code --verbose} still prints per-step lines; otherwise the
     * {@link SimpleTaskListener} owns the output (animating only on a TTY).
     */
    public static PipelineResult run(Pipeline pipeline, Mode mode, Path cacheRoot, ConsoleSpec spec) {
        EventLogListener log = EventLogListener.open(cacheRoot, pipeline.name());
        if (log != null) pipeline.addListener(log);

        PipelineListener console =
                switch (mode) {
                    case JSON -> new NdjsonListener(System.out);
                    case VERBOSE -> new VerboseListener(System.out, System.err);
                    // AUTO animates only on an interactive TTY; QUIET / pipes print the
                    // result line without a spinner.
                    case AUTO -> new SimpleTaskListener(System.out, System.err, spec, isInteractiveTerminal());
                    case QUIET -> new SimpleTaskListener(System.out, System.err, spec, false);
                };
        pipeline.addListener(console);
        return pipeline.run();
    }

    /**
     * Translate {@code GlobalOptions} flags into a {@link Mode}. Lives here so every command picks
     * the same precedence: {@code --output json} &gt; {@code --quiet} &gt; {@code --no-progress} &gt;
     * {@code --verbose} &gt; default.
     *
     * <p>{@code --output json} wins over the visualization flags because it's an explicit "I want
     * machine-readable output" — the user's other preferences don't override that.
     */
    public static Mode modeFor(build.jumpkick.cli.GlobalOptions opts) {
        if (opts == null) return Mode.AUTO;
        if (opts.outputIsJson()) return Mode.JSON;
        if (opts.quiet) return Mode.QUIET;
        if (opts.noProgress) return Mode.QUIET;
        if (opts.verbose) return Mode.VERBOSE;
        return Mode.AUTO;
    }

    /**
     * Pipeline-oriented variant: render the pipeline with the new {@link CommandManagerListener} (spinner
     * header + aggregate bar + dynamic step list) attributed to {@code module} (the project's {@code
     * group:artifact}), then a {@code ✓}/{@code ✗} result line from {@code spec}. {@code --output
     * json} still emits NDJSON and {@code --verbose} still prints per-step lines.
     */
    public static PipelineResult runPipeline(Pipeline pipeline, Mode mode, Path cacheRoot, ConsoleSpec spec, String module) {
        EventLogListener log = EventLogListener.open(cacheRoot, pipeline.name());
        if (log != null) pipeline.addListener(log);

        pipeline.addListener(chooseConsoleListener(pipeline.steps(), mode, spec, module));
        return pipeline.run();
    }

    /**
     * The listener {@link #runPipeline} picks per {@code mode} — split out so a caller that doesn't have
     * a real {@code Pipeline} yet (a engine-hosted test run reconstructing the step list from wire
     * events; see {@code EngineBuildListenerAdapter}) can choose the same listener from just {@code
     * steps} once it knows them, instead of duplicating this switch.
     */
    public static PipelineListener chooseConsoleListener(List<Step> steps, Mode mode, ConsoleSpec spec, String module) {
        return switch (mode) {
            case JSON -> new NdjsonListener(System.out);
            case VERBOSE -> new VerboseListener(System.out, System.err);
            case AUTO -> new CommandManagerListener(System.out, spec, module, steps, isInteractiveTerminal());
            case QUIET -> new CommandManagerListener(System.out, spec, module, steps, false);
        };
    }

    /**
     * Run {@code pipeline} with no console output (only the event log), returning its result. For builds
     * whose progress must NOT render to the terminal — e.g. composite dependency units built
     * concurrently, where N live progress bars can't share one terminal region; the caller prints a
     * compact summary line per unit instead.
     */
    public static PipelineResult runPipelineSilently(Pipeline pipeline, Path cacheRoot) {
        EventLogListener log = EventLogListener.open(cacheRoot, pipeline.name());
        if (log != null) pipeline.addListener(log);
        pipeline.addListener(new SilentListener(System.out, System.err));
        return pipeline.run();
    }

    /** A buffered run's result paired with its captured output/diagnostic lines. */
    public record Buffered(PipelineResult result, List<String> output) {}

    /**
     * Run {@code pipeline} capturing its output + warnings + errors into a buffer instead of rendering
     * live — for concurrently-built units, where the caller flushes each unit's buffer as one
     * contiguous block on completion (no interleaving across parallel builds). Only the event log
     * renders eagerly.
     */
    public static Buffered runPipelineBuffered(Pipeline pipeline, Path cacheRoot) {
        EventLogListener log = EventLogListener.open(cacheRoot, pipeline.name());
        if (log != null) pipeline.addListener(log);
        List<String> lines = new java.util.ArrayList<>();
        pipeline.addListener(new PipelineListener() {
            @Override
            public synchronized void output(String step, String line) {
                lines.add(line);
            }

            @Override
            public synchronized void warn(String step, String code, String message) {
                lines.add("  " + Glyphs.BANG + " " + step + ": " + message);
            }

            @Override
            public synchronized void error(String step, String code, String message) {
                lines.add("  " + Glyphs.CROSS + " " + step + ": " + message);
            }
        });
        PipelineResult r = pipeline.run();
        synchronized (lines) { // visibility barrier after the pipeline's threads finish
            return new Buffered(r, new java.util.ArrayList<>(lines));
        }
    }

    private static PipelineListener chooseConsoleListener(Pipeline pipeline, Mode mode) {
        // Interactive pipelines (wizards) must NOT render a progress bar —
        // the wizard owns the terminal. Same for JSON output (events
        // already go to stdout via NdjsonListener) and explicit quiet.
        if (pipeline.interactive()) return new SilentListener(System.out, System.err, true);
        return chooseConsoleListener(pipeline.name(), pipeline.steps(), mode);
    }

    /**
     * The default (spec-less) listener {@link #run(Pipeline, Mode, Path)} picks per {@code mode} — split
     * out so a caller with no real {@code Pipeline} (an engine-hosted run reconstructing the step list
     * from wire events; see {@code EngineResolveAdapter}) can choose the same listener from just the
     * pipeline's name and steps, instead of duplicating this switch. Non-interactive pipelines only.
     */
    public static PipelineListener chooseConsoleListener(String pipelineName, List<Step> steps, Mode mode) {
        return switch (mode) {
            case QUIET -> new SilentListener(System.out, System.err);
            case JSON -> new NdjsonListener(System.out);
            case VERBOSE -> new VerboseListener(System.out, System.err);
            case AUTO ->
                isInteractiveTerminal()
                        ? new CommandManagerListener(System.out, pipelineName, pipelineName, steps, true)
                        : new SilentListener(System.out, System.err);
        };
    }

    /**
     * Run a workspace module's pipeline into a shared {@link AggregateContext} — its events feed the one
     * aggregate {@link build.jumpkick.cli.tui.CommandManager} (bar + step list) instead of a per-module
     * view. The shared view is settled by the caller after the last module. Always records the event
     * log.
     */
    public static PipelineResult runPipelineInto(Pipeline pipeline, Path cacheRoot, String module, AggregateContext agg) {
        return runPipelineInto(pipeline, cacheRoot, module, agg, 0);
    }

    /**
     * As {@link #runPipelineInto(Pipeline, Path, String, AggregateContext)}, but with the module's reserved
     * {@code slice} of the calibrated total (its pre-scan estimate). The slice scales the module's
     * own 0→100% into its share of the aggregate bar so the bar advances cumulatively without
     * backtracking. Pass the same estimate that was summed into {@link AggregateContext#calibrate}.
     */
    public static PipelineResult runPipelineInto(Pipeline pipeline, Path cacheRoot, String module, AggregateContext agg, long slice) {
        EventLogListener log = EventLogListener.open(cacheRoot, pipeline.name());
        if (log != null) pipeline.addListener(log);
        pipeline.addListener(new AggregateModuleListener(agg, module, pipeline.steps(), slice));
        return pipeline.run();
    }

    /**
     * As {@link #runPipelineInto(Pipeline, Path, String, AggregateContext, long)} but for a module built
     * <em>concurrently</em>: its process output is appended to {@code outBuffer} instead of being
     * written above the live region as it arrives, so parallel modules' logs never interleave. The
     * caller flushes the buffer (above the shared region) when the module completes. Step/progress
     * events still feed the shared aggregate view live (the running rows + bar).
     */
    public static PipelineResult runPipelineIntoBuffered(
            Pipeline pipeline,
            Path cacheRoot,
            String module,
            AggregateContext agg,
            long slice,
            java.util.List<String> outBuffer) {
        EventLogListener log = EventLogListener.open(cacheRoot, pipeline.name());
        if (log != null) pipeline.addListener(log);
        AggregateModuleListener lis = new AggregateModuleListener(agg, module, pipeline.steps(), slice);
        lis.bufferOutputInto(outBuffer);
        pipeline.addListener(lis);
        return pipeline.run();
    }

    /**
     * True when stdout is an interactive terminal (not a pipe, dumb, or CI) — the output axis that
     * gates live progress / animation. See {@link build.jumpkick.cli.tui.Interactivity#stdoutIsTty()};
     * deliberately stdout-only so {@code jk build | less} draws plain text.
     */
    public static boolean isInteractiveTerminal() {
        return build.jumpkick.cli.tui.Interactivity.stdoutIsTty();
    }
}
