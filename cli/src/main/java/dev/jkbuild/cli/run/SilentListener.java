// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import java.io.PrintStream;

/**
 * Quietest console listener: prints only the final pass/fail summary
 * line and any structured errors. Used when the user pipes output,
 * passes {@code --quiet}, or when the goal is marked
 * {@link dev.jkbuild.run.Goal#interactive interactive}.
 */
public final class SilentListener implements GoalListener {

    private final PrintStream out;
    private final PrintStream err;
    private final boolean suppressDiagnostics;

    public SilentListener(PrintStream out, PrintStream err) {
        this(out, err, false);
    }

    public SilentListener(PrintStream out, PrintStream err, boolean suppressDiagnostics) {
        this.out = out;
        this.err = err;
        this.suppressDiagnostics = suppressDiagnostics;
    }

    @Override
    public void goalFinish(GoalResult result) {
        if (suppressDiagnostics) return;
        for (GoalResult.Diagnostic d : result.errors()) {
            err.println(ConsoleSpec.renderError(d));
        }
        for (GoalResult.Diagnostic d : result.warnings()) {
            err.println(ConsoleSpec.renderWarning(d));
        }
        // The command body owns the success summary — we don't want to
        // step on the existing "Built ..." / "Created ..." lines. So
        // silent mode is genuinely silent on success.
    }
}
