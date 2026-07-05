// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.worker;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.config.SessionContext;
import dev.jkbuild.config.WorkerTuning;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JvmOptionsTest {

    /** Install worker tuning onto the current session (mirrors what the CLI composition root does). */
    private static void installTuning(WorkerTuning tuning) {
        SessionContext.install(SessionContext.current().withJvm(tuning));
    }

    @Test
    void default_flags_are_50_percent_zgc_string_dedup() {
        assertThat(JvmOptions.flags(WorkerTuning.NONE, 1))
                .containsExactly("-XX:MaxRAMPercentage=50", "-XX:+UseZGC", "-XX:+UseStringDeduplication");
    }

    @Test
    void heap_cap_is_divided_across_concurrent_jvms() {
        // 4 concurrent test workers → each gets a quarter of the base cap.
        assertThat(JvmOptions.flags(WorkerTuning.NONE, 4)).contains("-XX:MaxRAMPercentage=12.5");
    }

    @Test
    void explicit_settings_win_and_extra_args_append() {
        WorkerTuning s = new WorkerTuning(70.0, "g1", false, List.of("-XX:+AlwaysPreTouch"));
        assertThat(JvmOptions.flags(s, 1))
                .containsExactly("-XX:MaxRAMPercentage=70", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch");
        // string-dedup=false → no dedup flag; gc=g1 → G1, not ZGC.
    }

    @Test
    void gc_none_emits_no_collector_and_no_dedup() {
        WorkerTuning s = new WorkerTuning(null, "none", true, List.of());
        assertThat(JvmOptions.flags(s, 1)).containsExactly("-XX:MaxRAMPercentage=50");
    }

    @Test
    void session_tuning_drives_worker_flags() {
        try {
            // The CLI carries resolved tuning on the session; worker forks read it back.
            installTuning(new WorkerTuning(80.0, "g1", false, List.of()));
            assertThat(JvmOptions.workerFlags(1)).containsExactly("-XX:MaxRAMPercentage=80", "-XX:+UseG1GC");
            // Concurrency still divides the resolved cap.
            assertThat(JvmOptions.workerFlags(4)).contains("-XX:MaxRAMPercentage=20");
        } finally {
            SessionContext.reset(); // don't leak into other tests
        }
    }

    @Test
    void absolute_flags_emit_xms_softmax_xmx_and_zgc_uncommit() {
        HeapPlan.Plan plan = new HeapPlan.Plan(4, 64L << 20, 512L << 20, 800L << 20, null);
        assertThat(JvmOptions.absoluteFlags(plan, WorkerTuning.NONE))
                .containsExactly(
                        "-Xms64m",
                        "-Xmx800m",
                        "-XX:SoftMaxHeapSize=512m",
                        "-XX:+UseZGC",
                        "-XX:+ZUncommit",
                        "-XX:ZUncommitDelay=30",
                        "-XX:+UseStringDeduplication");
    }

    @Test
    void absolute_flags_skip_softmax_for_serial_gc() {
        HeapPlan.Plan plan = new HeapPlan.Plan(1, 64L << 20, 256L << 20, 256L << 20, null);
        WorkerTuning serial = new WorkerTuning(null, "none", false, List.of());
        assertThat(JvmOptions.absoluteFlags(plan, serial))
                .containsExactly("-Xms64m", "-Xmx256m"); // no SoftMaxHeapSize, no collector
    }

    @Test
    void auto_heap_disabled_when_user_pins_a_heap_flag() {
        try {
            installTuning(new WorkerTuning(null, null, null, List.of("-Xmx2g")));
            assertThat(JvmOptions.autoHeapEnabled()).isFalse();
            installTuning(new WorkerTuning(50.0, null, null, List.of()));
            assertThat(JvmOptions.autoHeapEnabled()).isFalse();
            installTuning(WorkerTuning.NONE);
            assertThat(JvmOptions.autoHeapEnabled()).isTrue();
        } finally {
            SessionContext.reset();
        }
    }

    @Test
    void reads_jvm_table_from_toml(@TempDir Path dir) throws Exception {
        Path toml = dir.resolve("jk.toml");
        Files.writeString(toml, """
                [project]
                group = "x"
                name = "y"
                version = "1"

                [jvm]
                max-ram-percent = 33
                gc = "g1"
                string-dedup = false
                args = ["-XX:+AlwaysPreTouch"]
                """);
        WorkerTuning s = JvmOptions.fromToml(toml);
        assertThat(s.maxRamPercent()).isEqualTo(33.0);
        assertThat(s.gc()).isEqualTo("g1");
        assertThat(s.stringDedup()).isFalse();
        assertThat(s.extraArgs()).containsExactly("-XX:+AlwaysPreTouch");
    }

    @Test
    void missing_jvm_table_is_empty(@TempDir Path dir) throws Exception {
        Path toml = dir.resolve("jk.toml");
        Files.writeString(toml, "[project]\ngroup=\"x\"\nname=\"y\"\nversion=\"1\"\n");
        assertThat(JvmOptions.fromToml(toml)).isEqualTo(WorkerTuning.NONE);
    }

    @Test
    void cli_overrides_toml(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group="x"
                name="y"
                version="1"
                [jvm]
                max-ram-percent = 30
                """);
        // CLI maxRam is non-null → wins over the toml layer regardless of env.
        WorkerTuning cli = new WorkerTuning(80.0, null, null, List.of());
        WorkerTuning eff = JvmOptions.resolve(cli, dir);
        assertThat(eff.maxRamPercent()).isEqualTo(80.0);
    }
}
