// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.worker;

import static org.assertj.core.api.Assertions.assertThat;

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
}
