// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * Left-rail line builders. The rail is the box-drawing column on the left
 * edge of the wizard frame: a {@code ┌} opener, repeated {@code │} interior
 * lines, and a final {@code └} closer.
 */
public final class Rail {

    private Rail() {}

    public enum RailGlyph {
        OPEN,
        MID,
        CLOSE,
        /** Step header bullet; used for active, completed, and inactive steps (color varies by state). */
        BULLET
    }

    public enum StepState {
        COMPLETED,
        ACTIVE,
        INACTIVE
    }

    private static final String OPEN_CHAR = "╭";
    private static final String MID_CHAR = "│";
    private static final String CLOSE_CHAR = "╰";
    private static final String BULLET_CHAR = "■";

    /** Horizontal bars drawn after the corner glyph on the top/bottom of the frame. */
    private static final String CORNER_DASHES = "──";

    public static final String CHECKBOX_OFF = "◻";
    public static final String CHECKBOX_ON = "◼";
    public static final String RADIO_ON = "●";
    public static final String RADIO_OFF = "○";

    /** {@code ╭── <title>} — first line of the frame. */
    public static AttributedString opener(String title, StepState state) {
        var cornerStyle = Theme.railStyle(state, RailGlyph.OPEN);
        return new AttributedStringBuilder()
                .append(OPEN_CHAR, cornerStyle)
                .append(CORNER_DASHES, cornerStyle)
                .append(" ")
                .append(title, Theme.focused())
                .toAttributedString();
    }

    /** Same as {@link #opener(String, StepState)} but the title carries its own styling. */
    public static AttributedString opener(AttributedString styledTitle, StepState state) {
        var cornerStyle = Theme.railStyle(state, RailGlyph.OPEN);
        return new AttributedStringBuilder()
                .append(OPEN_CHAR, cornerStyle)
                .append(CORNER_DASHES, cornerStyle)
                .append(" ")
                .append(styledTitle)
                .toAttributedString();
    }

    /** {@code │  <text>} — interior line, with text styled by caller. */
    public static AttributedString mid(AttributedString text, StepState state) {
        return new AttributedStringBuilder()
                .append(MID_CHAR, Theme.railStyle(state, RailGlyph.MID))
                .append("  ")
                .append(text)
                .toAttributedString();
    }

    public static AttributedString mid(String text, StepState state, AttributedStyle textStyle) {
        return new AttributedStringBuilder()
                .append(MID_CHAR, Theme.railStyle(state, RailGlyph.MID))
                .append("  ")
                .append(text, textStyle)
                .toAttributedString();
    }

    /** Bare {@code │} (no following text). */
    public static AttributedString midBlank(StepState state) {
        return new AttributedStringBuilder()
                .append(MID_CHAR, Theme.railStyle(state, RailGlyph.MID))
                .toAttributedString();
    }

    /** {@code ╰── [text]} — final line; trailing text is omitted when empty. */
    public static AttributedString closer(String text, AttributedStyle textStyle) {
        return closer(text, textStyle, StepState.INACTIVE);
    }

    /** {@code ╰── [text]} with explicit rail state (controls the corner-glyph color). */
    public static AttributedString closer(String text, AttributedStyle textStyle, StepState state) {
        var cornerStyle = Theme.railStyle(state, RailGlyph.CLOSE);
        var sb = new AttributedStringBuilder()
                .append(CLOSE_CHAR, cornerStyle)
                .append(CORNER_DASHES, cornerStyle);
        if (text != null && !text.isEmpty()) {
            sb.append(" ").append(text, textStyle);
        }
        return sb.toAttributedString();
    }

    /** Step header bullet: {@code ■} for every state; cyan when active, otherwise dark-gray. */
    public static AttributedString stepBullet(StepState state, String prompt) {
        var promptStyle = switch (state) {
            case ACTIVE -> Theme.focused();
            case COMPLETED -> Theme.completedPrompt();
            case INACTIVE -> Theme.dim();
        };
        return new AttributedStringBuilder()
                .append(BULLET_CHAR, Theme.railStyle(state, RailGlyph.BULLET))
                .append("  ")
                .append(prompt, promptStyle)
                .toAttributedString();
    }
}
