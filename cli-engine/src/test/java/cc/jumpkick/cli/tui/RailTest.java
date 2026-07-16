// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RailTest {

    @Test
    void completed_step_prompt_is_dark_gray_without_strikethrough() {
        // JLine downsamples truecolor to indexed-256 when toAnsi() runs
        // without a truecolor-capable Terminal, so we anchor on the foreground
        // SGR + absence of strikethrough rather than the exact RGB.
        var rendered =
                Rail.stepBullet(Rail.StepState.COMPLETED, "Project name:").toAnsi();
        // No SGR 9 (strikethrough) anywhere in the emit.
        assertThat(rendered).doesNotMatch("(?s).*\\[(?:\\d+;)*9(?:;\\d+)*m.*");
        // Some kind of gray/indexed foreground is present.
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
