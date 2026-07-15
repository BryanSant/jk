// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.cli.run;

import build.jumpkick.plugin.build.Phase;
import build.jumpkick.run.PipelineListener;
import build.jumpkick.run.PipelineResult;
import build.jumpkick.run.PipelineView;
import build.jumpkick.run.StepStatus;
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
 * Always-on pipeline-event recorder. Writes the same NDJSON shape as {@link NdjsonListener} but to
 * {@code <cacheRoot>/runs/<ts>-<pipeline>.ndjson} — one file per invocation. The cache prune sweep
 * collects files older than 7 days (see {@link build.jumpkick.task.RunLogGc}).
 *
 * <p>Purpose: post-hoc debugging ({@code jk debug last}), usage analytics, CI traces, future "what
 * was slow in this build" tooling.
 *
 * <p>Writes are best-effort: an IO failure during a single event silently drops that event. A
 * failure opening the file makes the whole listener a no-op for the pipeline's lifetime.
 */
public final class EventLogListener implements PipelineListener {

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private final Path file;
    private final PrintStream stream;

    private EventLogListener(Path file, PrintStream stream) {
        this.file = file;
        this.stream = stream;
    }

    /**
     * Open a fresh log under {@code cacheRoot/runs/}. {@code pipelineName} is folded into the filename
     * for human scanning. Returns {@code null} on failure — caller can simply skip adding the
     * listener.
     */
    public static EventLogListener open(Path cacheRoot, String pipelineName) {
        try {
            Path dir = cacheRoot.resolve("runs");
            Files.createDirectories(dir);
            String safePipeline = pipelineName.replaceAll("[^A-Za-z0-9_.-]", "_");
            Path file = dir.resolve(TS_FORMAT.format(Instant.now()) + "-" + safePipeline + ".ndjson");
            PrintStream stream = new PrintStream(
                    Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    /* autoFlush= */ true,
                    StandardCharsets.UTF_8);
            return new EventLogListener(file, stream);
        } catch (IOException e) {
            return null;
        }
    }

    public Path file() {
        return file;
    }

    // The bulk of the work delegates to NdjsonListener's wire format
    // by re-using its helper privately. We keep them as separate
    // classes (rather than wrapping) so listener-ordering on
    // System.out vs the log file stays explicit.

    @Override
    public void pipelineStart(PipelineView v) {
        line(NdjsonShape.pipelineStart(v));
    }

    @Override
    public void stepStart(String step, Phase phase, int ticks) {
        line(NdjsonShape.stepStart(step, phase == null ? "" : phase.wireName(), ticks));
    }

    @Override
    public void progress(String step, int delta, PipelineView v) {
        line(NdjsonShape.progress(step, delta, v));
    }

    @Override
    public void tickUpdate(String step, int delta, PipelineView v) {
        line(NdjsonShape.tickUpdate(step, delta, v));
    }

    @Override
    public void label(String step, String label) {
        line(NdjsonShape.label(step, label));
    }

    @Override
    public void output(String step, String line) {
        line(NdjsonShape.output(step, line));
    }

    @Override
    public void warn(String step, String code, String msg) {
        line(NdjsonShape.warn(step, code, msg));
    }

    @Override
    public void error(String step, String code, String msg) {
        line(NdjsonShape.error(step, code, msg));
    }

    @Override
    public void error(String step, String code, String msg, String test, String exClass) {
        line(NdjsonShape.error(step, code, msg, test, exClass));
    }

    @Override
    public void stepFinish(String step, Phase phase, StepStatus s, Duration d) {
        line(NdjsonShape.stepFinish(step, phase == null ? "" : phase.wireName(), s, d));
    }

    @Override
    public void pipelineFinish(PipelineResult r) {
        line(NdjsonShape.pipelineFinish(r));
        try {
            stream.close();
        } catch (RuntimeException ignored) {
        }
    }

    private void line(String s) {
        try {
            stream.println(s);
        } catch (RuntimeException ignored) {
            // Best-effort logging.
        }
    }
}
