// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@code jk engine stop}'s human uptime formatting. */
class EngineStopCommandTest {

    @Test
    void uptime_drops_leading_zero_units_but_always_shows_seconds() {
        assertThat(EngineStopCommand.uptime(0)).isEqualTo("0s");
        assertThat(EngineStopCommand.uptime(13_000)).isEqualTo("13s");
        assertThat(EngineStopCommand.uptime((37 * 60 + 13) * 1000L)).isEqualTo("37m 13s");
        assertThat(EngineStopCommand.uptime((3 * 3600 + 37 * 60 + 13) * 1000L)).isEqualTo("3h 37m 13s");
    }

    @Test
    void uptime_full_days_hours_minutes_seconds() {
        long ms = ((14L * 86_400) + (3 * 3600) + (37 * 60) + 13) * 1000L;
        assertThat(EngineStopCommand.uptime(ms)).isEqualTo("14d 3h 37m 13s");
    }

    @Test
    void uptime_keeps_zero_inner_units_once_a_larger_unit_is_present() {
        // 2h 0m 5s — the minutes component stays even though it's zero.
        assertThat(EngineStopCommand.uptime((2 * 3600 + 5) * 1000L)).isEqualTo("2h 0m 5s");
    }
}
