// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.cli.run;

import build.jumpkick.run.PipelineListener;
import build.jumpkick.run.PipelineResult;
import java.io.PrintStream;

/**
 * Quietest console listener: prints only the final pass/fail summary line and any structured
 * errors. Used when the user pipes output, passes {@code --quiet}, or when the pipeline is marked
 * {@link build.jumpkick.run.Pipeline#interactive interactive}.
 */
public final class SilentListener implements PipelineListener {

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
    public void pipelineFinish(PipelineResult result) {
        if (suppressDiagnostics) return;
        for (PipelineResult.Diagnostic d : result.errors()) {
            err.println(ConsoleSpec.renderError(d));
        }
        for (PipelineResult.Diagnostic d : result.warnings()) {
            err.println(ConsoleSpec.renderWarning(d));
        }
        // The command body owns the success summary — we don't want to
        // step on the existing "Built ..." / "Created ..." lines. So
        // silent mode is genuinely silent on success.
    }
}
