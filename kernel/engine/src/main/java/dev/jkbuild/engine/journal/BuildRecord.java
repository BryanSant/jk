// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.engine.journal;

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
        List<Phase> phases,
        List<Diag> diagnostics) {

    /** The current on-disk schema version. */
    public static final int SCHEMA = 1;

    public BuildRecord {
        modules = modules == null ? List.of() : List.copyOf(modules);
        phases = phases == null ? List.of() : List.copyOf(phases);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /** Aggregate test counts for the run, or {@code null} when no tests ran. */
    public record Tests(long total, long succeeded, long failed, long skipped) {}

    /**
     * One module's outcome in a workspace build (the {@code modules} list is empty for a single-goal
     * build/test, whose phases sit in the record's top-level {@code phases}). {@code phases} is this
     * module's own phase chain, so the dashboard shows a chain per module.
     */
    public record Module(String coord, String dir, boolean success, int exitCode, long millis, List<Phase> phases) {
        public Module {
            phases = phases == null ? List.of() : List.copyOf(phases);
        }
    }

    /** One phase's aggregate outcome: {@code SUCCESS} / {@code FAIL} / {@code CANCELLED} / {@code SKIPPED}. */
    public record Phase(String name, String status, long millis) {}

    /** One diagnostic: {@code severity} is {@code "error"} or {@code "warning"}. */
    public record Diag(
            String severity, String phase, String code, String message, String test, String exceptionClass) {}
}
