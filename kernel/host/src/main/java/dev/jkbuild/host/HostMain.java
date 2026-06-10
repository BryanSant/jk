// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.host;

import dev.jkbuild.plugin.host.HostEvent;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.util.JkDirs;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Entry point for the Workspace Host — the one-shot JVM the CLI forks to own
 * the build pipeline. Reads a serialized {@link HostInvocation} from its first
 * argument (a spec-file path), runs the requested command's {@link Goal}, and
 * streams structured {@link HostEvent} lines back to the CLI on stdout.
 *
 * <p>The CLI renders those events through its standard {@link HostEvent}-driven
 * {@link ReceivingGoalListener}, so the progress bar, run-log, and JSON output
 * modes are unchanged.
 *
 * <p>Exit codes mirror the command's natural exit codes (0 = success,
 * 1 = build/runtime failure, 2 = bad args, 4 = test failures, etc.).
 *
 * <p>Stdout is reserved for the event stream; all other output (warning banners,
 * JVM startup noise) goes to stderr, which the CLI either silences or forwards
 * as passthrough lines.
 */
public final class HostMain {

    private HostMain() {}

    public static void main(String[] argv) {
        // Dedicated UTF-8 stdout for the event stream.
        PrintStream eventOut = new PrintStream(
                new FileOutputStream(FileDescriptor.out), /* autoFlush */ false, StandardCharsets.UTF_8);
        try {
            int code = run(argv, eventOut);
            eventOut.println(HostEvent.exit(code));
            eventOut.flush();
            System.exit(code);
        } catch (Throwable t) {
            // Unexpected failure: emit an error event then exit non-zero.
            eventOut.println(HostEvent.error("host", "internal",
                    t.getClass().getSimpleName() + ": " + t.getMessage()));
            eventOut.println(HostEvent.exit(1));
            eventOut.flush();
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static int run(String[] argv, PrintStream eventOut) throws Exception {
        if (argv.length < 1) {
            System.err.println("jk-host: expected spec-file path as first argument");
            return 2;
        }

        HostInvocation inv;
        try {
            inv = HostInvocation.read(java.nio.file.Path.of(argv[0]));
        } catch (Exception e) {
            System.err.println("jk-host: could not read invocation spec: " + e.getMessage());
            return 2;
        }

        // Dispatch to the command-specific builder, build the Goal, attach the
        // streaming listener, then run.
        Goal goal;
        try {
            goal = HostDispatch.buildGoal(inv);
        } catch (Exception e) {
            System.err.println("jk-host: " + e.getMessage());
            return 2;
        }

        StreamingGoalListener streamer = new StreamingGoalListener(eventOut, inv.verb());
        // Emit the ordered phase-name list before goalStart so the CLI can
        // construct a ProgressBarListener without a live Goal object.
        streamer.emitPhases(goal.phases().stream()
                .map(dev.jkbuild.run.Phase::name).collect(java.util.stream.Collectors.toList()));
        goal.addListener(streamer);

        // Note: the event-log listener is NOT attached here — the CLI receives
        // GOAL_FINISH and records it on its side (where it has access to
        // EventLogListener). The Host stays free of CLI dependencies.

        GoalResult result = goal.run();
        return exitCodeFor(result, inv);
    }

    private static int exitCodeFor(GoalResult result, HostInvocation inv) {
        if (result.success()) return 0;
        // Test-failure exit code.
        var testResult = goal(result, inv);
        if (testResult != null) return 4;
        return 1;
    }

    // Placeholder: will be elaborated once HostDispatch passes test results back.
    @SuppressWarnings("unused")
    private static Object goal(GoalResult result, HostInvocation inv) {
        return null;
    }
}
