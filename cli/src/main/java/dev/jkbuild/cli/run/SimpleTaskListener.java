// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.cli.tui.CommandManager;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.GoalView;

import java.io.PrintStream;

/**
 * Console listener for simple-task commands: drives a {@link CommandManager} in
 * simple mode — an animated spinner + verb on a TTY, then a {@code ✔}/{@code ✗}
 * result line built from the {@link ConsoleSpec} mappers. On a pipe / under
 * {@code --quiet} ({@code animate == false}) it skips the spinner but still
 * prints the result line, so non-interactive consumers keep a summary.
 *
 * <p>Diagnostics are read from the final {@link GoalResult} and printed once, at
 * {@code goalFinish}, <em>after</em> the spinner has been stopped — never
 * mid-run — so nothing interleaves with the live animation.
 */
public final class SimpleTaskListener implements GoalListener {

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
    public void goalStart(GoalView view) {
        cm = CommandManager.simple(out, spec.verb(), animate);
    }

    @Override
    public void output(String phase, String line) {
        // Above the pinned spinner when one exists; otherwise straight to stdout.
        if (cm != null) cm.writeAbove(line);
        else out.println(line);
    }

    @Override
    public void goalFinish(GoalResult result) {
        if (cm == null) cm = CommandManager.simple(out, spec.verb(), animate);
        if (result.success()) {
            cm.finishSuccess(spec.onSuccess().apply(result));
        } else {
            cm.finishFailure(spec.onFailure().apply(result));
        }
        // Diagnostics below the result line; the spinner is already stopped.
        for (GoalResult.Diagnostic d : result.errors()) {
            err.println("error[" + d.phase() + "/" + d.code() + "]: " + d.message());
        }
        for (GoalResult.Diagnostic d : result.warnings()) {
            err.println("warn[" + d.phase() + "/" + d.code() + "]: " + d.message());
        }
    }
}
