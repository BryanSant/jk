// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.cli;

import java.io.PrintStream;

/**
 * The CLI's single user-facing output seam. Commands write results and diagnostics through here
 * rather than touching {@link System#out}/{@link System#err} directly, so there is one place that
 * owns "what the client prints" — the client-side counterpart to the engine routing everything
 * through {@code PipelineListener}/{@code StepContext}. Behaviour is a thin pass-through today
 * (stdout for results, stderr for diagnostics/errors/progress); centralising it means future
 * concerns (capture for tests, a global quiet gate, alternate transports) have a single owner.
 *
 * <p>Convention, matching the streams it wraps: {@link #out} for machine/user <em>result</em> output
 * on stdout; {@link #err} for human-facing diagnostics, errors, and progress on stderr.
 */
public final class CliOutput {

    private CliOutput() {}

    /** Print a line to stdout (result output). */
    public static void out(String line) {
        System.out.println(line);
    }

    /** Print a blank line to stdout. */
    public static void out() {
        System.out.println();
    }

    /** Print to stdout with no trailing newline. */
    public static void outRaw(String s) {
        System.out.print(s);
    }

    /** Print a line to stderr (diagnostics, errors, progress). */
    public static void err(String line) {
        System.err.println(line);
    }

    /** Print a blank line to stderr. */
    public static void err() {
        System.err.println();
    }

    /** Print to stderr with no trailing newline. */
    public static void errRaw(String s) {
        System.err.print(s);
    }

    /** The raw stdout stream, for APIs that need a {@link PrintStream} (renderers, stack traces). */
    public static PrintStream stdout() {
        return System.out;
    }

    /** The raw stderr stream, for APIs that need a {@link PrintStream}. */
    public static PrintStream stderr() {
        return System.err;
    }
}
