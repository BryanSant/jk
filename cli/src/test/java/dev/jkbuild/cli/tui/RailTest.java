// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RailTest {

    @Test
    void completed_step_prompt_is_gray_and_strikethrough() {
        // JLine downsamples truecolor to indexed-256 when toAnsi() runs
        // without a truecolor-capable Terminal, so we anchor on the
        // strikethrough SGR (semantic intent) rather than the exact RGB.
        var rendered = Rail.stepBullet(Rail.StepState.COMPLETED, "Project name:").toAnsi();
        // SGR 9 = strikethrough; it's the first numeric token in JLine's emit.
        assertThat(rendered).contains("\033[9;");
        // Some kind of gray foreground is present.
        assertThat(rendered).contains("38;5;");
        // Prompt text survives the styling.
        assertThat(rendered).contains("Project name:");
    }

    @Test
    void active_step_prompt_has_no_strikethrough() {
        var rendered = Rail.stepBullet(Rail.StepState.ACTIVE, "Pick one:").toAnsi();
        // Active prompt must not carry the strikethrough SGR.
        assertThat(rendered).doesNotMatch("(?s).*\\[(?:\\d+;)*9(?:;\\d+)*m.*");
        // Active is bold (SGR 1).
        assertThat(rendered).contains(";1m");
    }
}
