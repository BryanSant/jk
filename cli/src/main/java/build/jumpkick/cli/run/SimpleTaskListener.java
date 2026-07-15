// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.cli.run;

import build.jumpkick.cli.tui.CommandManager;
import build.jumpkick.run.PipelineListener;
import build.jumpkick.run.PipelineResult;
import build.jumpkick.run.PipelineView;
import java.io.PrintStream;

/**
 * Console listener for simple-task commands: drives a {@link CommandManager} in simple mode — an
 * animated spinner + command on a TTY, then a {@code ✓}/{@code ✗} result line built from the {@link
 * ConsoleSpec} mappers. On a pipe / under {@code --quiet} ({@code animate == false}) it skips the
 * spinner but still prints the result line, so non-interactive consumers keep a summary.
 *
 * <p>Diagnostics are read from the final {@link PipelineResult} and printed once, at {@code
 * pipelineFinish}, <em>after</em> the spinner has been stopped — never mid-run — so nothing interleaves
 * with the live animation.
 */
public final class SimpleTaskListener implements PipelineListener {

    private final PrintStream out;
    private final PrintStream err;
    private final ConsoleSpec spec;
    private final boolean animate;

    private CommandManager cm;

    public SimpleTaskListener(PrintStream out, PrintStream err, ConsoleSpec spec, boolean animate) {
        this.out = out;
        this.err = err;
        this.spec = spec;
        this.animate = animate;
    }

    @Override
    public void pipelineStart(PipelineView view) {
        cm = CommandManager.simple(out, spec.command(), animate);
    }

    @Override
    public void output(String step, String line) {
        // Above the pinned spinner when one exists; otherwise straight to stdout.
        String painted = StackTraceHighlight.line(line);
        if (cm != null) cm.writeAbove(painted);
        else out.println(painted);
    }

    @Override
    public void pipelineFinish(PipelineResult result) {
        if (cm == null) cm = CommandManager.simple(out, spec.command(), animate);
        String suffix = " " + ConsoleSpec.took(result.duration());
        if (result.success()) {
            cm.finishSuccess(spec.onSuccess().apply(result) + suffix);
        } else {
            cm.finishFailure(spec.onFailure().apply(result) + suffix);
        }
        // Diagnostics below the result line; the spinner is already stopped.
        for (PipelineResult.Diagnostic d : result.errors()) {
            err.println(ConsoleSpec.renderError(d));
        }
        for (PipelineResult.Diagnostic d : result.warnings()) {
            err.println(ConsoleSpec.renderWarning(d));
        }
    }
}
