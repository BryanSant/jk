// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.worker;

import dev.jkbuild.worker.JvmOptions.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JvmOptionsTest {

    @Test
    void default_flags_are_50_percent_zgc_string_dedup() {
        assertThat(JvmOptions.flags(Settings.NONE, 1))
                .containsExactly("-XX:MaxRAMPercentage=50", "-XX:+UseZGC", "-XX:+UseStringDeduplication");
    }

    @Test
    void heap_cap_is_divided_across_concurrent_jvms() {
        // 4 concurrent test workers → each gets a quarter of the base cap.
        assertThat(JvmOptions.flags(Settings.NONE, 4))
                .contains("-XX:MaxRAMPercentage=12.5");
    }

    @Test
    void explicit_settings_win_and_extra_args_append() {
        Settings s = new Settings(70.0, "g1", false, java.util.List.of("-XX:+AlwaysPreTouch"));
        assertThat(JvmOptions.flags(s, 1))
                .containsExactly("-XX:MaxRAMPercentage=70", "-XX:+UseG1GC", "-XX:+AlwaysPreTouch");
        // string-dedup=false → no dedup flag; gc=g1 → G1, not ZGC.
    }

    @Test
    void gc_none_emits_no_collector_and_no_dedup() {
        Settings s = new Settings(null, "none", true, java.util.List.of());
        assertThat(JvmOptions.flags(s, 1)).containsExactly("-XX:MaxRAMPercentage=50");
    }

    @Test
    void process_settings_drive_worker_flags() {
        try {
            // The CLI stashes resolved settings; worker forks read them back.
            JvmOptions.setProcessSettings(new Settings(80.0, "g1", false, java.util.List.of()));
            assertThat(JvmOptions.workerFlags(1))
                    .containsExactly("-XX:MaxRAMPercentage=80", "-XX:+UseG1GC");
            // Concurrency still divides the resolved cap.
            assertThat(JvmOptions.workerFlags(4)).contains("-XX:MaxRAMPercentage=20");
        } finally {
            JvmOptions.setProcessSettings(null);   // don't leak into other tests
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
        Settings s = JvmOptions.fromToml(toml);
        assertThat(s.maxRamPercent()).isEqualTo(33.0);
        assertThat(s.gc()).isEqualTo("g1");
        assertThat(s.stringDedup()).isFalse();
        assertThat(s.extraArgs()).containsExactly("-XX:+AlwaysPreTouch");
    }

    @Test
    void missing_jvm_table_is_empty(@TempDir Path dir) throws Exception {
        Path toml = dir.resolve("jk.toml");
        Files.writeString(toml, "[project]\ngroup=\"x\"\nname=\"y\"\nversion=\"1\"\n");
        assertThat(JvmOptions.fromToml(toml)).isEqualTo(Settings.NONE);
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
        Settings cli = new Settings(80.0, null, null, java.util.List.of());
        Settings eff = JvmOptions.resolve(cli, dir);
        assertThat(eff.maxRamPercent()).isEqualTo(80.0);
    }
}
