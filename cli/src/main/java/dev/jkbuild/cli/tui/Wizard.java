// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Wizard orchestrator. Sets up raw mode + a shutdown hook + a SIGINT handler,
 * then drives a frame loop over its sealed {@link WizardStep} list.
 *
 * <p>Rendering is incremental: the header line and each settled step are
 * printed exactly once. Only the active step's region (its bullet line plus
 * the interactive UI below it) is erased and redrawn on each keystroke, so
 * the screen doesn't flash.
 *
 * <p>The cursor is hidden for the wizard's lifetime, except during an input
 * step where it is shown and positioned at the end of the typed buffer. On
 * cancellation (Ctrl+C) the SGR state is reset, the cursor restored, and a
 * newline emitted so the shell prompt lands on a fresh line.
 *
 * <p>The alt-screen buffer is intentionally NOT used: the locked-in transcript
 * stays visible after exit, so users can scroll back and review their answers.
 */
public final class Wizard {

    private static final String HIDE_CURSOR = "\033[?25l";
    private static final String SHOW_CURSOR = "\033[?25h";
    private static final String RESET_SGR = "\033[0m";
    private static final String CLEAR_TO_END = "\033[0J";

    /** Display width of the rail prefix "│  " in columns. */
    private static final int RAIL_PREFIX_WIDTH = 3;

    /** Poll interval for keys; lets the loop observe async cancellation flag. */
    private static final long KEY_POLL_MS = 75L;

    private final String title;
    private final List<WizardStep> steps;
    private volatile boolean cancelled;

    Wizard(String title, List<WizardStep> steps) {
        this.title = title;
        this.steps = steps;
    }

    public static WizardBuilder builder() {
        return new WizardBuilder();
    }

    /**
     * Open a system terminal with input echo suppressed.
     *
     * <p>{@link TerminalBuilder#build()} probes the terminal with capability
     * queries (DA, DECRQM). Their responses arrive on stdin shortly after;
     * with default cooked-mode ECHO on, the OS driver echoes them to the
     * screen as raw ANSI text before {@link #run} gets a chance to enter raw
     * mode. The flash is most noticeable when the caller does parallel work
     * between build and {@code run()} (e.g. {@code jk new}'s prewarm phase).
     *
     * <p>Disabling ECHO here closes the window. Output flags are left alone,
     * so callers that {@code println} between build and {@code run()} still
     * get normal LF→CRLF translation. The original attributes are restored
     * when the terminal is closed.
     */
    public static Terminal openTerminal() throws IOException {
        var terminal = TerminalBuilder.builder().system(true).build();
        var attrs = terminal.getAttributes();
        attrs.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        terminal.setAttributes(attrs);
        return terminal;
    }

    public List<WizardStep> steps() {
        return steps;
    }

    public String title() {
        return title;
    }

    public void cancel() {
        this.cancelled = true;
    }

    public Optional<Answers> run(Terminal terminal) {
        return run(terminal, Answers.of(Map.of()));
    }

    /**
     * Run with pre-seeded answers. Each step whose {@code key()} is present
     * in {@code preset} is skipped interactively but rendered as completed,
     * so the user can see what was inferred. Useful when {@code jk init
     * my-project} has supplied the "Project name" answer up front.
     */
    public Optional<Answers> run(Terminal terminal, Answers preset) {
        var saved = terminal.enterRawMode();
        // TerminalBuilder.build() probes the terminal with capability queries
        // (DA, DECRQM, etc.); the responses arrive in stdin and get echoed to
        // the screen because echo is still on at that moment. Drain the bytes
        // so KeyReader doesn't later see them as user keystrokes, then clear
        // the current line so their visual residue doesn't precede the header.
        drainInput(terminal.reader(), 40L);
        var prevHandler = terminal.handle(Terminal.Signal.INT, sig -> cancel());
        var writer = terminal.writer();
        writer.print("\r" + CLEAR_TO_END);
        // Belt-and-suspenders for crashes that bypass the finally block.
        // Same ECHO+ICANON force-on as the finally — if we crash here, leaving
        // the user's shell in raw mode would be a worse failure mode than the
        // crash itself.
        var restoreHook = new Thread(() -> {
            writer.print(SHOW_CURSOR);
            writer.print(RESET_SGR);
            writer.flush();
            var cooked = new Attributes(saved);
            cooked.setLocalFlag(Attributes.LocalFlag.ECHO, true);
            cooked.setLocalFlag(Attributes.LocalFlag.ICANON, true);
            terminal.setAttributes(cooked);
            terminal.flush();
        });
        Runtime.getRuntime().addShutdownHook(restoreHook);
        try {
            writer.print(HIDE_CURSOR);
            writer.flush();
            return Optional.of(loop(terminal, preset));
        } catch (WizardCancelled e) {
            return Optional.empty();
        } finally {
            // Reset SGR + show cursor BEFORE restoring attributes so the wizard's
            // last frame stops bleeding styles, then restore canonical/echo mode
            // so the explicit \r\n lands on its own line and the parent shell's
            // prompt isn't stranded mid-line (which manifests as "press enter
            // to get my prompt back" after Ctrl+C).
            writer.print(RESET_SGR);
            writer.print(SHOW_CURSOR);
            writer.flush();
            terminal.handle(Terminal.Signal.INT, prevHandler);
            // Restore the pre-raw attrs, but force ECHO+ICANON back on:
            // openTerminal suppresses ECHO before build() to hide JLine's
            // probe-response flicker, so `saved` here captures "echo off".
            // Callers (jk jdk uninstall's [Y/n]) read System.in after the
            // wizard returns and need cooked mode with echo on.
            var cooked = new Attributes(saved);
            cooked.setLocalFlag(Attributes.LocalFlag.ECHO, true);
            cooked.setLocalFlag(Attributes.LocalFlag.ICANON, true);
            terminal.setAttributes(cooked);
            writer.print("\r\n");
            writer.flush();
            terminal.flush();
            tryRemoveHook(restoreHook);
            // JLine's `prevHandler` round-trip doesn't restore our app-level
            // sun.misc.Signal handler, so re-install it explicitly. Without
            // this, Ctrl-C after a wizard would fall back to the JVM default.
            GlobalCancel.install();
        }
    }

    /**
     * Render a Ctrl-C cancellation closer on top of the wizard's active rail:
     * step the cursor back up to the active {@code ╰} row, preserve the existing
     * {@code ╰──} prefix (which the active region already drew in cyan), and
     * append a separator space + red {@code <message>} right after it.
     *
     * <p>Assumes the wizard has just returned {@link Optional#empty()} and that
     * the in-loop cancel path called {@link #moveBelowCloser} — so {@link #run}'s
     * trailing {@code \r\n} places the cursor two lines below the active closer.
     */
    public static void printCancellation(Terminal terminal, String message) {
        var writer = terminal.writer();
        writer.print("\033[2F"); // up 2 lines, col 1 — lands at the active ╰
        writer.print("\033[" + RAIL_PREFIX_WIDTH + "C"); // skip past "╰──"
        writer.print("\033[0J"); // erase residue beyond
        var line = new AttributedStringBuilder()
                .append(" " + message, Theme.error()) // leading space separates from ╰──
                .toAttributedString();
        writer.print(line.toAnsi(terminal));
        writer.println();
        writer.flush();
    }

    /**
     * Reposition the cursor one row below the active closer so cancellation
     * handlers can rely on a fixed cursor position regardless of step type.
     * Input steps leave the cursor mid-region (on the input buffer line); other
     * step types leave it already below the closer.
     */
    private static void moveBelowCloser(PrintWriter writer, int regionLines, int cursorOffset) {
        int linesDown = regionLines - cursorOffset;
        if (linesDown > 0) {
            writer.print("\033[" + linesDown + "B");
        }
        writer.print("\r");
        writer.flush();
    }

    private static void tryRemoveHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // shutdown in progress; nothing to do
        }
    }

    private static void drainInput(NonBlockingReader reader, long maxWaitMs) {
        try {
            var deadline = System.currentTimeMillis() + maxWaitMs;
            while (System.currentTimeMillis() < deadline) {
                var c = reader.read(5L);
                if (c == NonBlockingReader.READ_EXPIRED || c < 0) {
                    return;
                }
            }
        } catch (IOException ignored) {
            // best-effort drain
        }
    }

    private Answers loop(Terminal terminal, Answers preset) {
        var writer = terminal.writer();
        var reader = terminal.reader();
        var answers = new LinkedHashMap<String, Object>(preset.asMap());

        writer.println();
        writer.println(headerLine(terminal));
        writer.flush();

        for (var step : steps) {
            // Pre-seeded answers skip the interactive prompt but still render
            // as settled so the user can see what was inferred up front.
            if (answers.containsKey(step.key()) && preset.has(step.key())) {
                writer.println(Rail.midBlank(Rail.StepState.COMPLETED).toAnsi(terminal));
                renderSettledRegion(terminal, step, answers);
                writer.flush();
                continue;
            }
            if (!step.shouldRun().test(Answers.of(answers))) {
                continue;
            }
            writer.println(Rail.midBlank(Rail.StepState.COMPLETED).toAnsi(terminal));

            var state = new ActiveState(step, answers);
            var regionLines = renderActiveRegion(terminal, step, state);
            var cursorOffset = positionInputCursor(writer, step, state, regionLines);
            writer.flush();

            while (true) {
                if (cancelled) {
                    moveBelowCloser(writer, regionLines, cursorOffset);
                    throw new WizardCancelled();
                }
                var key = KeyReader.readOrNull(reader, KEY_POLL_MS);
                if (key == null) {
                    continue;
                }
                if (key instanceof KeyReader.Key.CtrlC) {
                    moveBelowCloser(writer, regionLines, cursorOffset);
                    throw new WizardCancelled();
                }
                var done = state.handle(key);

                writer.print(HIDE_CURSOR);
                eraseLines(writer, cursorOffset);

                if (done) {
                    state.commit(answers);
                    renderSettledRegion(terminal, step, answers);
                    writer.flush();
                    break;
                }

                regionLines = renderActiveRegion(terminal, step, state);
                cursorOffset = positionInputCursor(writer, step, state, regionLines);
                writer.flush();
            }
        }

        // Print without a trailing newline — the `finally` block writes
        // `\r\n` to land the shell prompt on a fresh line, so a println here
        // would leave a blank gap between the wizard and whatever the caller
        // emits next (e.g. a progress bar).
        writer.print(Rail.closer("", Theme.dim()).toAnsi(terminal));
        writer.flush();
        return Answers.of(Map.copyOf(answers));
    }

    private int renderActiveRegion(Terminal terminal, WizardStep step, ActiveState state) {
        var writer = terminal.writer();
        writer.println(Rail.stepBullet(Rail.StepState.ACTIVE, step.prompt()).toAnsi(terminal));
        var interactive = state.render();
        for (var line : interactive) {
            writer.println(Rail.mid(line, Rail.StepState.ACTIVE).toAnsi(terminal));
        }
        // └ hook one line below the interactive content; gets erased on commit
        // and re-emitted (with the next step's content above it) on each step.
        // Cyan matches the active rail above it.
        writer.println(Rail.closer("", Theme.dim(), Rail.StepState.ACTIVE).toAnsi(terminal));
        return 1 + interactive.size() + 1;
    }

    private void renderSettledRegion(Terminal terminal, WizardStep step, Map<String, Object> answers) {
        var writer = terminal.writer();
        writer.println(Rail.stepBullet(Rail.StepState.COMPLETED, step.prompt()).toAnsi(terminal));
        for (var line : summarize(step, answers)) {
            writer.println(Rail.mid(line, Rail.StepState.COMPLETED).toAnsi(terminal));
        }
    }

    private void eraseLines(PrintWriter writer, int rows) {
        if (rows <= 0) {
            // Still need to return to column 0 so the redraw doesn't prepend onto
            // whatever was to the left of the cursor (e.g., an input buffer).
            writer.print("\r");
            writer.print(CLEAR_TO_END);
            return;
        }
        // ESC[<n>F = cursor previous line: moves up n lines AND to column 0.
        // (ESC[<n>A only moves up, preserving column — that left the redraw
        // starting mid-line and pasting the new bullet onto the old text.)
        writer.print("\033[" + rows + "F");
        writer.print(CLEAR_TO_END);
    }

    /**
     * Positions the cursor for input steps and returns the cursor's offset
     * (in lines) from the top of the active region. The caller must pass this
     * value back to {@link #eraseLines} so the redraw moves up by the correct
     * amount instead of climbing past the region origin and eating prior
     * terminal content.
     */
    private int positionInputCursor(PrintWriter writer, WizardStep step, ActiveState state, int regionLines) {
        if (!(step instanceof WizardStep.InputStep)) {
            // Cursor sits below the region after the final println.
            return regionLines;
        }
        // After emitting (bullet + interactive) lines, the cursor sits at the start
        // of the line BELOW the region. The input buffer line is the second line of
        // the region (line index 1: bullet is line 0). Move the cursor up to that
        // line, then to the column just past the last typed character.
        var linesUp = regionLines - 1;
        if (linesUp > 0) {
            writer.print("\033[" + linesUp + "A");
        }
        var col = RAIL_PREFIX_WIDTH + state.input.length() + 1;
        writer.print("\033[" + col + "G");
        writer.print(SHOW_CURSOR);
        return 1;
    }

    /**
     * Build the header as raw ANSI: dark-gray {@code ╭── } (via the standard
     * Rail opener) followed by the gradient title with bold stamped on every
     * codepoint's SGR. Bypassing {@link AttributedString#toAnsi} for the title
     * is what keeps bold from being optimized into a single emit at the first
     * char (and then "drifting away" on terminals that render bold-as-bright).
     */
    private String headerLine(Terminal terminal) {
        var cornerWithSpace = Rail.opener("", Rail.StepState.INACTIVE).toAnsi(terminal);
        var titleAnsi = Theme.gradientHeaderAnsi(title.isEmpty() ? "Wizard" : title);
        return cornerWithSpace + titleAnsi;
    }

    private static List<AttributedString> summarize(WizardStep step, Map<String, Object> answers) {
        var answerStyle = Theme.settled().italic();
        return switch (step) {
            case WizardStep.InputStep is -> List.of(
                    answerLine(answers.getOrDefault(is.key(), "").toString(), answerStyle));
            case WizardStep.RadioStep rs -> List.of(answerLine(labelFor(rs, answers), answerStyle));
            case WizardStep.MultiSelectStep ms -> {
                @SuppressWarnings("unchecked")
                var selected = (List<String>) answers.getOrDefault(ms.key(), List.<String>of());
                if (selected.isEmpty()) {
                    yield List.of(answerLine("(none selected)", answerStyle));
                }
                // Map known choice ids to their labels; entries with no match
                // (a free-form custom value) render verbatim. Iterate the
                // stored list so selection order — including the appended
                // custom value — is preserved.
                var byId = new java.util.HashMap<String, String>();
                for (var c : ms.choices()) {
                    byId.put(c.id(), c.label());
                }
                var labels = new ArrayList<AttributedString>();
                for (var v : selected) {
                    labels.add(answerLine(byId.getOrDefault(v, v), answerStyle));
                }
                yield labels;
            }
            case WizardStep.OutputStep os -> os.render().apply(Answers.of(answers)).stream()
                    .map(s -> plain(s, Theme.dim()))
                    .toList();
        };
    }

    private static AttributedString answerLine(String text, AttributedStyle textStyle) {
        return new AttributedStringBuilder()
                .append("➜ ", Theme.brightGreen())
                .append(text, textStyle)
                .toAttributedString();
    }

    private static String labelFor(WizardStep.RadioStep step, Map<String, Object> answers) {
        var snapshot = Answers.of(answers);
        var id = answers.getOrDefault(step.key(), step.defaultChoice()).toString();
        for (var c : step.choicesFor(snapshot)) {
            if (c.id().equals(id)) {
                return c.label();
            }
        }
        return id;
    }

    private static AttributedString plain(String text, AttributedStyle style) {
        return new AttributedStringBuilder().append(text, style).toAttributedString();
    }

    /**
     * Per-step interactive state. Created at the start of each step iteration
     * and torn down once {@link #handle(KeyReader.Key)} reports completion.
     */
    private static final class ActiveState {

        private final WizardStep step;
        private final StringBuilder input;
        private int focus;
        private final LinkedHashSet<String> selected;
        private final Answers snapshot;
        private String error = "";

        ActiveState(WizardStep step, Map<String, Object> existing) {
            this.step = step;
            this.input = new StringBuilder();
            this.selected = new LinkedHashSet<>();
            // Snapshot answers as of step entry — used to resolve dynamic
            // Choice hints (Choice.hintFn) so a later step's options can
            // reflect what the user picked on an earlier step.
            this.snapshot = Answers.of(Map.copyOf(existing));
            switch (step) {
                case WizardStep.InputStep is -> {
                    var prior = existing.get(is.key());
                    if (prior != null) {
                        this.input.append(prior);
                    } else {
                        var seed = is.initialValueFor(snapshot);
                        if (seed != null && !seed.isEmpty()) {
                            this.input.append(seed);
                        }
                    }
                }
                case WizardStep.RadioStep rs -> {
                    var idx = indexOf(rs.choicesFor(snapshot), rs.defaultChoice());
                    this.focus = Math.max(0, idx);
                }
                case WizardStep.MultiSelectStep ms -> {
                    this.focus = 0;
                    this.selected.addAll(ms.defaults());
                }
                case WizardStep.OutputStep os -> {}
            }
        }

        private static int indexOf(List<Choice> choices, String id) {
            for (var i = 0; i < choices.size(); i++) {
                if (choices.get(i).id().equals(id)) {
                    return i;
                }
            }
            return -1;
        }

        /** Returns true when the step is complete and Wizard should advance. */
        boolean handle(KeyReader.Key key) {
            return switch (step) {
                case WizardStep.InputStep is -> handleInput(is, key);
                case WizardStep.RadioStep rs -> handleRadio(rs, key);
                case WizardStep.MultiSelectStep ms -> handleMulti(ms, key);
                case WizardStep.OutputStep os -> key instanceof KeyReader.Key.Enter;
            };
        }

        private boolean handleInput(WizardStep.InputStep is, KeyReader.Key key) {
            return switch (key) {
                case KeyReader.Key.Enter e -> {
                    var value = input.length() == 0 ? is.defaultValue() : input.toString();
                    var result = is.validator().apply(value);
                    if (result instanceof ValidationResult.Error err) {
                        error = err.message();
                        yield false;
                    }
                    input.setLength(0);
                    input.append(value);
                    error = "";
                    yield true;
                }
                case KeyReader.Key.Right r -> realizePlaceholder(is);
                case KeyReader.Key.Tab t -> realizePlaceholder(is);
                case KeyReader.Key.Backspace b -> {
                    if (input.length() > 0) {
                        input.deleteCharAt(input.length() - 1);
                    }
                    error = "";
                    yield false;
                }
                case KeyReader.Key.Space s -> {
                    input.append(' ');
                    error = "";
                    yield false;
                }
                case KeyReader.Key.Char(char c) -> {
                    input.append(c);
                    error = "";
                    yield false;
                }
                default -> false;
            };
        }

        private boolean realizePlaceholder(WizardStep.InputStep is) {
            // Realize the placeholder as if the user typed it. The text picks
            // up the normal "user input" styling — there's no visual distinction
            // between typed-and-accepted text.
            if (input.length() == 0 && !is.placeholder().isEmpty()) {
                input.append(is.placeholder());
                error = "";
            }
            return false;
        }

        private boolean handleRadio(WizardStep.RadioStep rs, KeyReader.Key key) {
            int choiceCount = rs.choicesFor(snapshot).size();
            boolean customEnabled = rs.hasCustomOption() && rs.orientation() == Orientation.VERTICAL;
            int size = choiceCount + (customEnabled ? 1 : 0);
            boolean onCustom = customEnabled && focus == choiceCount;
            return switch (key) {
                case KeyReader.Key.Enter e -> {
                    if (onCustom && input.length() == 0) {
                        error = "Type a value or pick an option above.";
                        yield false;
                    }
                    error = "";
                    yield true;
                }
                case KeyReader.Key.Up u -> moveFocus(-1, size, rs.orientation() == Orientation.VERTICAL);
                case KeyReader.Key.Down d -> moveFocus(1, size, rs.orientation() == Orientation.VERTICAL);
                case KeyReader.Key.Left l -> moveFocus(-1, size, rs.orientation() == Orientation.HORIZONTAL);
                case KeyReader.Key.Right r -> moveFocus(1, size, rs.orientation() == Orientation.HORIZONTAL);
                case KeyReader.Key.Backspace b when onCustom -> {
                    if (input.length() > 0) input.deleteCharAt(input.length() - 1);
                    error = "";
                    yield false;
                }
                case KeyReader.Key.Space s when onCustom -> {
                    input.append(' ');
                    error = "";
                    yield false;
                }
                case KeyReader.Key.Char(char c) when onCustom -> {
                    input.append(c);
                    error = "";
                    yield false;
                }
                default -> false;
            };
        }

        private boolean moveFocus(int delta, int size, boolean enabled) {
            if (!enabled || size == 0) {
                return false;
            }
            focus = ((focus + delta) % size + size) % size;
            return false;
        }

        private boolean handleMulti(WizardStep.MultiSelectStep ms, KeyReader.Key key) {
            int choiceCount = ms.choices().size();
            boolean customEnabled = ms.hasCustomOption() && ms.orientation() == Orientation.VERTICAL;
            int size = choiceCount + (customEnabled ? 1 : 0);
            boolean onCustom = customEnabled && focus == choiceCount;
            return switch (key) {
                case KeyReader.Key.Enter e -> true;
                // On the free-form row, Space / chars / Backspace edit the
                // buffer instead of toggling — the row is "checked" whenever
                // it holds text, so there's nothing to toggle.
                case KeyReader.Key.Backspace b when onCustom -> {
                    if (input.length() > 0) input.deleteCharAt(input.length() - 1);
                    yield false;
                }
                case KeyReader.Key.Space s -> {
                    if (onCustom) {
                        input.append(' ');
                    } else {
                        var c = ms.choices().get(focus);
                        if (!selected.add(c.id())) {
                            selected.remove(c.id());
                        }
                    }
                    yield false;
                }
                case KeyReader.Key.Char(char ch) -> {
                    if (onCustom) {
                        input.append(ch);
                    } else if (ch == 'a') {
                        if (selected.size() == choiceCount) {
                            selected.clear();
                        } else {
                            for (var c : ms.choices()) {
                                selected.add(c.id());
                            }
                        }
                    }
                    yield false;
                }
                case KeyReader.Key.Up u -> moveFocus(-1, size, ms.orientation() == Orientation.VERTICAL);
                case KeyReader.Key.Down d -> moveFocus(1, size, ms.orientation() == Orientation.VERTICAL);
                case KeyReader.Key.Left l -> moveFocus(-1, size, ms.orientation() == Orientation.HORIZONTAL);
                case KeyReader.Key.Right r -> moveFocus(1, size, ms.orientation() == Orientation.HORIZONTAL);
                default -> false;
            };
        }

        void commit(Map<String, Object> answers) {
            switch (step) {
                case WizardStep.InputStep is -> answers.put(is.key(), input.toString());
                case WizardStep.RadioStep rs -> {
                    var choices = rs.choicesFor(snapshot);
                    boolean customEnabled = rs.hasCustomOption() && rs.orientation() == Orientation.VERTICAL;
                    if (customEnabled && focus == choices.size()) {
                        answers.put(rs.key(), input.toString());
                    } else {
                        answers.put(rs.key(), choices.get(focus).id());
                    }
                }
                case WizardStep.MultiSelectStep ms -> {
                    var ordered = new ArrayList<String>();
                    for (var c : ms.choices()) {
                        if (selected.contains(c.id())) {
                            ordered.add(c.id());
                        }
                    }
                    boolean customEnabled = ms.hasCustomOption() && ms.orientation() == Orientation.VERTICAL;
                    if (customEnabled && !input.toString().isBlank()) {
                        ordered.add(input.toString());
                    }
                    answers.put(ms.key(), List.copyOf(ordered));
                }
                case WizardStep.OutputStep os -> {
                    // Output steps have no answer; they only gate progression.
                }
            }
        }

        List<AttributedString> render() {
            return switch (step) {
                case WizardStep.InputStep is -> renderInput(is);
                case WizardStep.RadioStep rs -> renderRadio(rs);
                case WizardStep.MultiSelectStep ms -> renderMulti(ms);
                case WizardStep.OutputStep os -> renderOutput(os);
            };
        }

        private List<AttributedString> renderInput(WizardStep.InputStep is) {
            var sb = new AttributedStringBuilder();
            if (input.length() == 0) {
                if (!is.placeholder().isEmpty()) {
                    sb.append(is.placeholder(), Theme.dim().italic());
                }
            } else {
                sb.append(input.toString(), Theme.focused());
            }
            var lines = new ArrayList<AttributedString>();
            lines.add(sb.toAttributedString());
            if (!error.isEmpty()) {
                lines.add(new AttributedStringBuilder()
                        .append(error, Theme.error())
                        .toAttributedString());
            }
            return lines;
        }

        private List<AttributedString> renderRadio(WizardStep.RadioStep rs) {
            var choices = rs.choicesFor(snapshot);
            var lines = new ArrayList<AttributedString>();
            if (rs.orientation() == Orientation.HORIZONTAL) {
                var sb = new AttributedStringBuilder();
                for (var i = 0; i < choices.size(); i++) {
                    var c = choices.get(i);
                    var isFocused = i == focus;
                    sb.append(isFocused ? Rail.RADIO_ON : Rail.RADIO_OFF,
                            isFocused ? Theme.completedStep() : Theme.dim());
                    sb.append(" ");
                    sb.append(c.label(), isFocused ? Theme.focused() : Theme.dim());
                    appendHint(sb, c.hintFor(snapshot));
                    if (i < choices.size() - 1) {
                        sb.append("  ");
                    }
                }
                lines.add(sb.toAttributedString());
            } else {
                for (var i = 0; i < choices.size(); i++) {
                    var c = choices.get(i);
                    var isFocused = i == focus;
                    var sb = new AttributedStringBuilder()
                            .append(isFocused ? Rail.RADIO_ON : Rail.RADIO_OFF,
                                    isFocused ? Theme.completedStep() : Theme.dim())
                            .append(" ")
                            .append(c.label(), isFocused ? Theme.focused() : Theme.dim());
                    appendHint(sb, c.hintFor(snapshot));
                    lines.add(sb.toAttributedString());
                }
                if (rs.hasCustomOption()) {
                    var isFocused = focus == choices.size();
                    var sb = new AttributedStringBuilder()
                            .append(isFocused ? Rail.RADIO_ON : Rail.RADIO_OFF,
                                    isFocused ? Theme.completedStep() : Theme.dim())
                            .append(" ");
                    appendCustomField(sb, isFocused, rs.customPlaceholder());
                    lines.add(sb.toAttributedString());
                    appendError(lines);
                }
            }
            return lines;
        }

        /**
         * Render the editable free-form field: the placeholder as dim italic
         * example text while empty (overwrite-able), or the typed text in the
         * focused/dim style once the user starts typing.
         */
        private void appendCustomField(AttributedStringBuilder sb, boolean focused, String placeholder) {
            if (input.length() == 0) {
                sb.append(placeholder, Theme.dim().italic());
            } else {
                sb.append(input.toString(), focused ? Theme.focused() : Theme.dim());
            }
        }

        private void appendError(List<AttributedString> lines) {
            if (!error.isEmpty()) {
                lines.add(new AttributedStringBuilder()
                        .append(error, Theme.error())
                        .toAttributedString());
            }
        }

        private static void appendHint(AttributedStringBuilder sb, String hint) {
            if (hint == null || hint.isEmpty()) return;
            sb.append("  ");
            sb.append(hint, Theme.darkGray());
        }

        private List<AttributedString> renderMulti(WizardStep.MultiSelectStep ms) {
            var lines = new ArrayList<AttributedString>();
            if (ms.orientation() == Orientation.VERTICAL) {
                for (var i = 0; i < ms.choices().size(); i++) {
                    var c = ms.choices().get(i);
                    var isFocused = i == focus;
                    var isChecked = selected.contains(c.id());
                    var glyph = isChecked ? Rail.CHECKBOX_ON : Rail.CHECKBOX_OFF;
                    var glyphStyle = isChecked
                            ? Theme.completedStep()
                            : (isFocused ? Theme.activeStep() : Theme.dim());
                    var labelStyle = isFocused ? Theme.focused() : Theme.dim();
                    var sb = new AttributedStringBuilder()
                            .append(glyph, glyphStyle)
                            .append(" ");
                    if (c.richLabelFn() != null) {
                        sb.append(c.richLabelFn().apply(isFocused));
                    } else {
                        sb.append(c.label(), labelStyle);
                    }
                    appendHint(sb, c.hintFor(snapshot));
                    lines.add(sb.toAttributedString());
                }
                if (ms.hasCustomOption()) {
                    var isFocused = focus == ms.choices().size();
                    var isChecked = input.length() > 0;  // checked while it holds text
                    var glyph = isChecked ? Rail.CHECKBOX_ON : Rail.CHECKBOX_OFF;
                    var glyphStyle = isChecked
                            ? Theme.completedStep()
                            : (isFocused ? Theme.activeStep() : Theme.dim());
                    var sb = new AttributedStringBuilder()
                            .append(glyph, glyphStyle)
                            .append(" ");
                    appendCustomField(sb, isFocused, ms.customPlaceholder());
                    lines.add(sb.toAttributedString());
                }
            } else {
                var sb = new AttributedStringBuilder();
                for (var i = 0; i < ms.choices().size(); i++) {
                    var c = ms.choices().get(i);
                    var isFocused = i == focus;
                    var isChecked = selected.contains(c.id());
                    var glyphStyle = isChecked
                            ? Theme.completedStep()
                            : (isFocused ? Theme.activeStep() : Theme.dim());
                    var labelStyle = isFocused ? Theme.focused() : Theme.dim();
                    sb.append(isChecked ? Rail.CHECKBOX_ON : Rail.CHECKBOX_OFF, glyphStyle);
                    sb.append(" ");
                    sb.append(c.label(), labelStyle);
                    appendHint(sb, c.hintFor(snapshot));
                    if (i < ms.choices().size() - 1) {
                        sb.append("  ");
                    }
                }
                lines.add(sb.toAttributedString());
            }
            return lines;
        }

        private List<AttributedString> renderOutput(WizardStep.OutputStep os) {
            var pieces = os.render().apply(Answers.of(Map.of()));
            var lines = new ArrayList<AttributedString>();
            for (var s : pieces) {
                lines.add(plain(s, Theme.dim()));
            }
            return lines;
        }
    }
}
