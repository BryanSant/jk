// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@link ConsoleSpec#fmtDuration} is the one duration formatter in the CLI: bare millis below a
 * second, one-decimal seconds below a minute, then {@code m/h/d} compound forms — capping at days.
 */
class ConsoleSpecTest {

    @Test
    void sub_second_durations_render_as_bare_millis() {
        assertThat(ConsoleSpec.fmtDuration(Duration.ofMillis(0))).isEqualTo("0ms");
        assertThat(ConsoleSpec.fmtDuration(Duration.ofMillis(1))).isEqualTo("1ms");
        assertThat(ConsoleSpec.fmtDuration(Duration.ofMillis(500))).isEqualTo("500ms");
        assertThat(ConsoleSpec.fmtDuration(Duration.ofMillis(999))).isEqualTo("999ms");
    }

    @Test
    void sub_minute_durations_render_as_one_decimal_seconds() {
        assertThat(ConsoleSpec.fmtDuration(Duration.ofMillis(1000))).isEqualTo("1.0s");
        assertThat(ConsoleSpec.fmtDuration(Duration.ofMillis(1200))).isEqualTo("1.2s");
        assertThat(ConsoleSpec.fmtDuration(Duration.ofMillis(3100))).isEqualTo("3.1s");
        assertThat(ConsoleSpec.fmtDuration(Duration.ofMillis(59_900))).isEqualTo("59.9s");
    }

    @Test
    void minute_durations_render_as_minutes_and_seconds() {
        assertThat(ConsoleSpec.fmtDuration(Duration.ofSeconds(60))).isEqualTo("1m 0s");
        assertThat(ConsoleSpec.fmtDuration(Duration.ofSeconds(124))).isEqualTo("2m 4s");
        assertThat(ConsoleSpec.fmtDuration(Duration.ofSeconds(3599))).isEqualTo("59m 59s");
    }

    @Test
    void hour_durations_render_as_hours_minutes_seconds() {
        assertThat(ConsoleSpec.fmtDuration(Duration.ofSeconds(3600))).isEqualTo("1h 0m 0s");
        assertThat(ConsoleSpec.fmtDuration(Duration.ofSeconds(3662))).isEqualTo("1h 1m 2s");
        assertThat(ConsoleSpec.fmtDuration(Duration.ofSeconds(86_399))).isEqualTo("23h 59m 59s");
    }

    @Test
    void day_durations_render_as_days_hours_minutes_seconds() {
        assertThat(ConsoleSpec.fmtDuration(Duration.ofSeconds(86_400))).isEqualTo("1d 0h 0m 0s");
        // 1d 12h 13m 5s
        assertThat(ConsoleSpec.fmtDuration(
                        Duration.ofDays(1).plusHours(12).plusMinutes(13).plusSeconds(5)))
                .isEqualTo("1d 12h 13m 5s");
        assertThat(ConsoleSpec.fmtDuration(Duration.ofDays(10).plusSeconds(7))).isEqualTo("10d 0h 0m 7s");
    }

    @Test
    void boundaries_roll_over_cleanly() {
        // The last millisecond before each unit threshold still uses the lower unit's form.
        assertThat(ConsoleSpec.fmtDuration(Duration.ofMillis(999))).isEqualTo("999ms");
        assertThat(ConsoleSpec.fmtDuration(Duration.ofMillis(59_999))).isEqualTo("60.0s");
    }
}
