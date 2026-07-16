// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.engine.journal;

import java.util.List;

/**
 * The structured outcome of one build run, frozen at request-finish and persisted as {@code
 * record.json} inside a {@link BuildJournal} entry. This is the source of truth for the dashboard's
 * backfilled activity feed and the {@code jk history} CLI; the heavier per-run artifacts
 * (test-results markdown, a {@code jk.lock} snapshot, flattened diagnostics) sit beside it in the
 * same entry directory.
 *
 * <p>Deliberately a plain value object with no engine dependencies so it round-trips cleanly through
 * {@link Json}. {@code schema} lets a future reader detect and reject/upgrade an older layout.
 */
public record BuildRecord(
        String id,
        long buildNumber,
        int schema,
        String kind,
        String dir,
        String coord,
        long startedAt,
        long finishedAt,
        long millis,
        boolean success,
        boolean cancelled,
        int exitCode,
        String jkVersion,
        Tests tests,
        List<Module> modules,
        List<Step> steps,
        List<Diag> diagnostics,
        String trigger,
        String commit) {

    /**
     * The current on-disk schema version. Bumped to 2 when {@code buildNumber} — the durable,
     * monotonic per-project run counter (assigned from {@link build.jumpkick.runtime.BuildMetrics}) —
     * was added. {@code trigger} (how the build was started: {@code "cli"}/{@code "web"}) and {@code
     * commit} (the project's git HEAD at build time) were added without a bump — pre-1.0 additive
     * fields simply read back as {@code null} on older records.
     */
    public static final int SCHEMA = 2;

    public BuildRecord {
        modules = modules == null ? List.of() : List.copyOf(modules);
        steps = steps == null ? List.of() : List.copyOf(steps);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /** This record with its per-project build number set (assigned at journal-append time). */
    public BuildRecord withBuildNumber(long buildNumber) {
        return new BuildRecord(
                id, buildNumber, schema, kind, dir, coord, startedAt, finishedAt, millis,
                success, cancelled, exitCode, jkVersion, tests, modules, steps, diagnostics, trigger, commit);
    }

    /** Aggregate test counts for the run, or {@code null} when no tests ran. */
    public record Tests(long total, long succeeded, long failed, long skipped) {}

    /**
     * One module's outcome in a workspace build (the {@code modules} list is empty for a single-pipeline
     * build/test, whose steps sit in the record's top-level {@code steps}). {@code steps} is this
     * module's own step chain, so the dashboard shows a chain per module.
     */
    public record Module(String coord, String dir, boolean success, int exitCode, long millis, List<Step> steps) {
        public Module {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    /**
     * One step's aggregate outcome: {@code status} is {@code SUCCESS} / {@code FAIL} /
     * {@code CANCELLED} / {@code SKIPPED}; {@code phase} is the coarse pipeline phase's wire-name
     * ({@code ""} when unphased) so the dashboard can fold reloaded/finished cards into the same
     * phase-chain the live cards render.
     */
    public record Step(String name, String phase, String status, long millis) {
        public Step {
            phase = phase == null ? "" : phase;
        }
    }

    /**
     * One diagnostic: {@code severity} is {@code "error"} or {@code "warning"}; {@code dir} is the
     * module the failure belongs to ({@code ""} for a single-pipeline build), so the dashboard can nest
     * the failure output under the failed module inside its "failure details" roll-up.
     */
    public record Diag(
            String severity, String dir, String step, String code, String message, String test, String exceptionClass) {}
}
