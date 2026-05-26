// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.GoalView;
import dev.jkbuild.run.PhaseStatus;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Always-on goal-event recorder. Writes the same NDJSON shape as
 * {@link NdjsonListener} but to {@code <cacheRoot>/runs/<ts>-<goal>.ndjson}
 * — one file per invocation. The cache prune sweep collects files
 * older than 7 days (see {@link dev.jkbuild.task.RunLogGc}).
 *
 * <p>Purpose: post-hoc debugging ({@code jk debug last}), usage
 * analytics, CI traces, future "what was slow in this build" tooling.
 *
 * <p>Writes are best-effort: an IO failure during a single event
 * silently drops that event. A failure opening the file makes the
 * whole listener a no-op for the goal's lifetime.
 */
public final class EventLogListener implements GoalListener {

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private final Path file;
    private final PrintStream stream;

    private EventLogListener(Path file, PrintStream stream) {
        this.file = file;
        this.stream = stream;
    }

    /**
     * Open a fresh log under {@code cacheRoot/runs/}. {@code goalName}
     * is folded into the filename for human scanning. Returns
     * {@code null} on failure — caller can simply skip adding the
     * listener.
     */
    public static EventLogListener open(Path cacheRoot, String goalName) {
        try {
            Path dir = cacheRoot.resolve("runs");
            Files.createDirectories(dir);
            String safeGoal = goalName.replaceAll("[^A-Za-z0-9_.-]", "_");
            Path file = dir.resolve(TS_FORMAT.format(Instant.now()) + "-" + safeGoal + ".ndjson");
            PrintStream stream = new PrintStream(
                    Files.newOutputStream(file,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE),
                    /*autoFlush=*/ true, StandardCharsets.UTF_8);
            return new EventLogListener(file, stream);
        } catch (IOException e) {
            return null;
        }
    }

    public Path file() { return file; }

    // The bulk of the work delegates to NdjsonListener's wire format
    // by re-using its helper privately. We keep them as separate
    // classes (rather than wrapping) so listener-ordering on
    // System.out vs the log file stays explicit.

    @Override public void goalStart(GoalView v)                          { line(NdjsonShape.goalStart(v)); }
    @Override public void phaseStart(String phase, int scope)            { line(NdjsonShape.phaseStart(phase, scope)); }
    @Override public void progress(String phase, int delta, GoalView v)  { line(NdjsonShape.progress(phase, delta, v)); }
    @Override public void scopeUpdate(String phase, int delta, GoalView v){ line(NdjsonShape.scopeUpdate(phase, delta, v)); }
    @Override public void label(String phase, String label)              { line(NdjsonShape.label(phase, label)); }
    @Override public void warn(String phase, String code, String msg)    { line(NdjsonShape.warn(phase, code, msg)); }
    @Override public void error(String phase, String code, String msg)   { line(NdjsonShape.error(phase, code, msg)); }
    @Override public void phaseFinish(String phase, PhaseStatus s, Duration d) { line(NdjsonShape.phaseFinish(phase, s, d)); }
    @Override public void goalFinish(GoalResult r)                       {
        line(NdjsonShape.goalFinish(r));
        try { stream.close(); } catch (RuntimeException ignored) {}
    }

    private void line(String s) {
        try {
            stream.println(s);
        } catch (RuntimeException ignored) {
            // Best-effort logging.
        }
    }
}
