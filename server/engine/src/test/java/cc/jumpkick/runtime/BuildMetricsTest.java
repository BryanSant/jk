// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The running build-metrics store: fold rules, tiers, outcome split, JSON round-trip, GC. */
class BuildMetricsTest {

    private static final long DAY = 86_400_000L;
    private static final long NOW = 1_700_000_000_000L; // a fixed "now" for determinism

    private static Path file(Path dir) {
        return dir.resolve("metrics.json");
    }

    private static BuildMetrics.Outcome build(
            String dir, boolean success, long millis, BuildMetrics.StepSample... steps) {
        return new BuildMetrics.Outcome("build", dir, "g:n", success, false, millis, List.of(steps));
    }

    private static void record(Path f, BuildMetrics.Outcome o, long now) {
        BuildMetrics.clearMemo();
        BuildMetrics.record(f, o, now);
        BuildMetrics.clearMemo();
    }

    @Test
    void empty_store_has_no_entries(@TempDir Path dir) {
        BuildMetrics.clearMemo();
        BuildMetrics m = BuildMetrics.load(file(dir));
        assertThat(m.isEmpty()).isTrue();
        assertThat(m.invocation("build", "/p")).isEmpty();
        assertThat(m.entries()).isEmpty();
    }

    @Test
    void one_build_updates_project_and_global_tiers(@TempDir Path dir) {
        record(file(dir), build("/p", true, 1200), NOW);
        BuildMetrics m = BuildMetrics.load(file(dir));
        var project = m.invocation("build", "/p").orElseThrow();
        var global = m.invocation("build", "").orElseThrow();
        assertThat(project.ok()).isEqualTo(new BuildMetrics.Stats(1, 1200, 1200, 1200));
        assertThat(project.coord()).isEqualTo("g:n");
        assertThat(global.ok().count()).isEqualTo(1);
        assertThat(global.coord()).isNull();
    }

    @Test
    void repeated_builds_fold_count_total_min_max(@TempDir Path dir) {
        Path f = file(dir);
        record(f, build("/p", true, 1000), NOW);
        record(f, build("/p", true, 3000), NOW + 1);
        record(f, build("/p", true, 2000), NOW + 2);
        var s = BuildMetrics.load(f).invocation("build", "/p").orElseThrow().ok();
        assertThat(s.count()).isEqualTo(3);
        assertThat(s.totalMillis()).isEqualTo(6000);
        assertThat(s.minMillis()).isEqualTo(1000);
        assertThat(s.maxMillis()).isEqualTo(3000);
        assertThat(s.avgMillis()).isEqualTo(2000);
    }

    @Test
    void failed_and_cancelled_runs_are_counted_separately(@TempDir Path dir) {
        Path f = file(dir);
        record(f, build("/p", true, 1000), NOW);
        record(f, build("/p", false, 500), NOW + 1);
        record(f, new BuildMetrics.Outcome("build", "/p", "g:n", false, true, 300, List.of()), NOW + 2);
        var e = BuildMetrics.load(f).invocation("build", "/p").orElseThrow();
        assertThat(e.ok().count()).isEqualTo(1);
        assertThat(e.failed().count()).isEqualTo(1);
        assertThat(e.failed().totalMillis()).isEqualTo(500);
        assertThat(e.cancelled().count()).isEqualTo(1);
        // The failure never leaks into the success stats the estimator reads.
        assertThat(e.ok().minMillis()).isEqualTo(1000);
    }

    @Test
    void kinds_are_tracked_independently(@TempDir Path dir) {
        Path f = file(dir);
        record(f, build("/p", true, 1000), NOW);
        record(f, new BuildMetrics.Outcome("test", "/p", "g:n", true, false, 4000, List.of()), NOW + 1);
        BuildMetrics m = BuildMetrics.load(f);
        assertThat(m.invocation("build", "/p").orElseThrow().ok().totalMillis()).isEqualTo(1000);
        assertThat(m.invocation("test", "/p").orElseThrow().ok().totalMillis()).isEqualTo(4000);
    }

    @Test
    void phase_samples_update_project_and_global_phase_tiers(@TempDir Path dir) {
        Path f = file(dir);
        record(
                f,
                build(
                        "/p",
                        true,
                        1500,
                        new BuildMetrics.StepSample("/p/a", "compile-java", "SUCCESS", 600),
                        new BuildMetrics.StepSample("/p/a", "run-tests", "FAIL", 400)),
                NOW);
        BuildMetrics m = BuildMetrics.load(f);
        assertThat(m.step("/p/a", "compile-java").orElseThrow().ok().totalMillis())
                .isEqualTo(600);
        assertThat(m.step("", "compile-java").orElseThrow().ok().totalMillis()).isEqualTo(600);
        assertThat(m.step("/p/a", "run-tests").orElseThrow().failed().count()).isEqualTo(1);
        assertThat(m.step("/p/a", "run-tests").orElseThrow().ok().count()).isZero();
    }

    @Test
    void skipped_and_non_terminal_phases_teach_nothing(@TempDir Path dir) {
        Path f = file(dir);
        record(
                f,
                build(
                        "/p",
                        true,
                        100,
                        new BuildMetrics.StepSample("/p", "package-jar", "SKIPPED", 5),
                        new BuildMetrics.StepSample("/p", "ksp", "RUNNING", 5)),
                NOW);
        BuildMetrics m = BuildMetrics.load(f);
        assertThat(m.step("/p", "package-jar")).isEmpty();
        assertThat(m.step("", "ksp")).isEmpty();
    }

    @Test
    void survives_a_json_round_trip_with_pathy_keys(@TempDir Path dir) {
        Path f = file(dir);
        String project = "/home/me/src/oss/jk kernel"; // embedded space stays intact
        record(
                f,
                new BuildMetrics.Outcome(
                        "build",
                        project,
                        "dev.jk:kernel",
                        true,
                        false,
                        750,
                        List.of(new BuildMetrics.StepSample(project, "compile-java", "SUCCESS", 500))),
                NOW);
        BuildMetrics.clearMemo(); // force a real re-read from disk
        BuildMetrics m = BuildMetrics.load(f);
        assertThat(m.invocation("build", project).orElseThrow().ok().totalMillis())
                .isEqualTo(750);
        assertThat(m.step(project, "compile-java").orElseThrow().ok().totalMillis())
                .isEqualTo(500);
    }

    @Test
    void corrupt_file_is_treated_as_empty_and_recording_recovers(@TempDir Path dir) throws Exception {
        Path f = file(dir);
        Files.writeString(f, "{not json");
        BuildMetrics.clearMemo();
        assertThat(BuildMetrics.load(f).isEmpty()).isTrue();
        record(f, build("/p", true, 100), NOW);
        assertThat(BuildMetrics.load(f).invocation("build", "/p")).isPresent();
    }

    @Test
    void entries_lists_invocation_rows_before_phase_rows_in_stable_order(@TempDir Path dir) {
        Path f = file(dir);
        record(f, build("/p", true, 100, new BuildMetrics.StepSample("/p", "resolve-deps", "SUCCESS", 20)), NOW);
        List<BuildMetrics.Entry> entries = BuildMetrics.load(f).entries();
        // build×{global,project} then sync-deps×{global,project}
        assertThat(entries).hasSize(4);
        assertThat(entries.get(0).kind()).isEqualTo("build");
        assertThat(entries.get(0).dir()).isEmpty();
        assertThat(entries.get(1).dir()).isEqualTo("/p");
        assertThat(entries.get(2).step()).isEqualTo("resolve-deps");
        assertThat(entries.get(2).dir()).isEmpty();
        assertThat(entries.get(3).dir()).isEqualTo("/p");
    }

    @Test
    void prune_evicts_rows_older_than_the_max_age(@TempDir Path dir) {
        Path f = file(dir);
        record(f, build("/old", true, 100), NOW - 800 * DAY);
        record(f, build("/new", true, 100), NOW);
        var report = BuildMetrics.prune(f, new BuildMetrics.Limits(Long.MAX_VALUE, 730 * DAY), NOW, false);
        assertThat(report.evictedByAge()).isEqualTo(1);
        BuildMetrics.clearMemo();
        BuildMetrics m = BuildMetrics.load(f);
        assertThat(m.invocation("build", "/old")).isEmpty();
        assertThat(m.invocation("build", "/new")).isPresent();
        // The global row was refreshed by the /new build, so age eviction spares it.
        assertThat(m.invocation("build", "")).isPresent();
    }

    @Test
    void prune_evicts_oldest_rows_first_when_over_the_byte_cap(@TempDir Path dir) {
        Path f = file(dir);
        for (int i = 0; i < 20; i++) record(f, build("/p" + i, true, 100), NOW + i);
        var report = BuildMetrics.prune(f, new BuildMetrics.Limits(600, Long.MAX_VALUE), NOW + 100, false);
        assertThat(report.evictedBySize()).isPositive();
        BuildMetrics.clearMemo();
        BuildMetrics m = BuildMetrics.load(f);
        // The newest project row outlives the oldest.
        assertThat(m.invocation("build", "/p0")).isEmpty();
        assertThat(m.invocation("build", "/p19")).isPresent();
    }

    @Test
    void prune_dry_run_reports_but_does_not_rewrite(@TempDir Path dir) throws Exception {
        Path f = file(dir);
        record(f, build("/old", true, 100), NOW - 800 * DAY);
        byte[] before = Files.readAllBytes(f);
        var report = BuildMetrics.prune(f, new BuildMetrics.Limits(Long.MAX_VALUE, 730 * DAY), NOW, true);
        assertThat(report.evictedByAge()).isPositive();
        assertThat(Files.readAllBytes(f)).isEqualTo(before);
    }

    @Test
    void limits_resolve_env_over_config_over_defaults(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config.toml");
        Files.writeString(cfg, "[metrics]\nmax-size-mb = 3\nmax-age-days = 10\n");
        var fromCfg = BuildMetrics.Limits.resolve(cfg, k -> null);
        assertThat(fromCfg.maxBytes()).isEqualTo(3L * 1024 * 1024);
        assertThat(fromCfg.maxAgeMillis()).isEqualTo(10 * DAY);
        var fromEnv = BuildMetrics.Limits.resolve(
                cfg, Map.of("JK_METRICS_MAX_SIZE_MB", "1", "JK_METRICS_MAX_AGE_DAYS", "2")::get);
        assertThat(fromEnv.maxBytes()).isEqualTo(1024L * 1024);
        assertThat(fromEnv.maxAgeMillis()).isEqualTo(2 * DAY);
        var defaults = BuildMetrics.Limits.resolve(dir.resolve("missing.toml"), k -> null);
        assertThat(defaults.maxBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(defaults.maxAgeMillis()).isEqualTo(730 * DAY);
    }

    @Test
    void concurrent_records_all_land(@TempDir Path dir) throws Exception {
        Path f = file(dir);
        int n = 16;
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int t = i;
            threads[i] = new Thread(() -> BuildMetrics.record(f, build("/p", true, 100 + t), NOW + t));
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        BuildMetrics.clearMemo();
        var s = BuildMetrics.load(f).invocation("build", "/p").orElseThrow().ok();
        assertThat(s.count()).isEqualTo(n);
        assertThat(s.minMillis()).isEqualTo(100);
        assertThat(s.maxMillis()).isEqualTo(100 + n - 1);
    }
}
