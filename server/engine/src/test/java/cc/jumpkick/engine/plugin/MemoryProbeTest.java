// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** {@code /proc/meminfo} parsing and the live probe's invariants. */
class MemoryProbeTest {

    private static final String MEMINFO = String.join(
            "\n",
            "MemTotal:       16004000 kB",
            "MemFree:         1200000 kB",
            "MemAvailable:    8000000 kB",
            "Buffers:          100000 kB");

    @Test
    void parses_kilobyte_values_into_bytes() {
        assertThat(MemoryProbe.meminfoValueBytes(MEMINFO, "MemTotal")).isEqualTo(16004000L * 1024);
        assertThat(MemoryProbe.meminfoValueBytes(MEMINFO, "MemAvailable")).isEqualTo(8000000L * 1024);
    }

    @Test
    void returns_minus_one_for_absent_keys() {
        assertThat(MemoryProbe.meminfoValueBytes(MEMINFO, "Cached")).isEqualTo(-1);
        assertThat(MemoryProbe.meminfoValueBytes("", "MemTotal")).isEqualTo(-1);
    }

    @Test
    void live_probe_is_sane_and_never_throws() {
        MemoryProbe.Memory m = MemoryProbe.probe();
        assertThat(m.totalBytes()).isPositive();
        assertThat(m.availableBytes()).isPositive();
        assertThat(m.availableBytes()).isLessThanOrEqualTo(m.totalBytes());
    }

    @Test
    void macos_reports_reclaimable_memory_not_just_idle_pages() {
        Assumptions.assumeTrue(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac"));
        // Regression check for the bug this class's host_statistics64 read fixes:
        // com.sun.management's free-memory figure alone counts only truly-idle pages, not the
        // inactive/purgeable pages macOS's VM keeps stocked with reclaimable file cache — on a
        // long-lived dev machine that once reported ~150 MiB "available" out of 36 GiB, starving
        // worker-JVM heap sizing (HeapPlan) down to a ~32 MiB floor. A healthy read should surface a
        // real double-digit percentage of total as available.
        MemoryProbe.Memory m = MemoryProbe.probe();
        assertThat(m.availableBytes()).isGreaterThan(m.totalBytes() / 20);
    }
}
