# TUI Style Compliance Refactor Plan

This plan drives the codebase toward full compliance with `docs/tui-style.md`. The audit findings
that motivate each item are cross-referenced by tag (HC = Hardcoded Colors, CS = Code Structure,
SG = Style Guide, US = Uncovered Scenarios).

---

## Phase 1 — Quick Wins

Mechanical, low-risk changes. Each item is independently applicable with no cross-item dependencies.
Estimated total: **0.5–1 day**.

---

### 1.1 Add named constants for inline palette literals in `JkDarkTheme`

**Files:** `cli/src/main/java/dev/jkbuild/cli/theme/JkDarkTheme.java`

**Changes:**

1. Add `private static final Rgb CHIP_FG = Rgb.hex(0xFFFFFF)` alongside the existing `CHIP_TEXT`
   constant (line ~270). Replace the five anonymous `Rgb.hex(0xFFFFFF)` occurrences at lines 251,
   261, 274, 279, and 284 (`planBadge`, `indigoBadge`, `goalChip`, `goalSuccessChip`,
   `goalFailureChip`) with `CHIP_FG`.

2. Add `public static final Rgb SHELL_ORANGE = Rgb.hex(0xFF9800)` in the palette section. Replace
   the inline `Rgb.hex(0xFF9800)` in `shell()` at line 346 with `SHELL_ORANGE`.

3. Add `private static final Rgb FAILURE_GRADIENT_START = Rgb.hex(0x7F1D1D)` and
   `private static final Rgb FAILURE_GRADIENT_END = Rgb.hex(0xEF4444)`. Replace the two anonymous
   literals in `FAILURE_GRADIENT` at line 94 with these constants.

**Estimated lines changed:** ~15 (3 constant additions + 8 reference replacements)

**Risk:** Low — pure renaming, no behavioral change.

**Dependencies:** None.

---

### 1.2 Replace raw ANSI escape and inline `Rgb.hex` in `Wizard.headerLine`

**File:** `cli/src/main/java/dev/jkbuild/cli/tui/Wizard.java`

**Lines:** 388–395

**Changes:**

Replace the block:
```java
dev.jkbuild.cli.theme.Rgb dg = dev.jkbuild.cli.theme.Rgb.hex(0x90A4AE);
String cap1 = Theme.colorize(Glyphs.SEGMENT_END_NERD,
        t.withBackground(t.bright(t.planBadgeColor()), dg));
String bgBlack = "\033[48;2;" + dg.r() + ";" + dg.g() + ";" + dg.b()
        + ";38;2;0;0;0m";
String cap2 = Theme.colorize(Glyphs.SEGMENT_END_NERD, t.gray());
return chipStr + cap1 + bgBlack + " " + subtitle + bgBlack + " " + Ansi.RESET + cap2;
```

With:
```java
Rgb dg = JkDarkTheme.GRAY; // or t.grayColor() if a Rgb accessor is added to Theme
String cap1 = Theme.colorize(Glyphs.SEGMENT_END_NERD,
        t.withBackground(t.bright(t.planBadgeColor()), dg));
String cap2 = Theme.colorize(Glyphs.SEGMENT_END_NERD, t.gray());
String bandContents = Theme.colorize(" " + subtitle + " ", t.scopeBadge());
return chipStr + cap1 + bandContents + Ansi.RESET + cap2;
```

This requires that `scopeBadge()` produces both the gray background and the black foreground (it
already does per `JkDarkTheme.scopeBadge()`). The caller must ensure `subtitle` does not internally
reset to default mid-string in a way that breaks the band background — if it does, the colorize wrap
still handles the outer bounds correctly.

If the subtitle can contain resets, add a `Theme.grayColor()` method returning `Rgb` on the `Theme`
interface, implemented as `return GRAY` in `JkDarkTheme`, and use:
```java
String bandPrefix = "\033[48;2;" + dg.r() + ";" + dg.g() + ";" + dg.b()
        + ";38;2;" + CHIP_TEXT.r() + ";" + CHIP_TEXT.g() + ";" + CHIP_TEXT.b() + "m";
```
...where both `dg` and `CHIP_TEXT` are named constants, eliminating all literal hex values.

**Estimated lines changed:** ~10

**Risk:** Low. The visual output is identical; only the color values are sourced from constants.

**Dependencies:** 1.1 must be applied first so `CHIP_TEXT` is a named constant; or apply
simultaneously.

---

### 1.3 Redefine `Glyphs.CROSS` to `✘` (U+2718) and add `Glyphs.BANG` and `Glyphs.X`

**File:** `cli/src/main/java/dev/jkbuild/cli/tui/Glyphs.java`

**Changes:**

1. Change `CROSS = "‼"` to `CROSS = "✘"` with updated Javadoc: "Error marker — U+2718 heavy ballot
   X. Paint with `Theme.error()`."

2. Add `public static final String BANG = "‼"` with Javadoc: "Warning marker — U+203C double
   exclamation. Paint with `Theme.warning()`."

3. Optionally add `public static final String X = "✗"` (U+2717, the lighter ballot X used by
   `VerboseListener` and `ProgressBarListener`) with Javadoc noting this is a secondary, lighter
   cross — use `CROSS` (U+2718) as the canonical error glyph.

**Estimated lines changed:** ~8 (Glyphs.java only)

**Risk:** Medium — changing `Glyphs.CROSS` changes every call site that currently renders `‼` as an
error icon. Those call sites are in `GoalWedge`, `ConsoleSpec`, `CommandManager`, and
`JdkUninstallCommand`. The visual change (from `‼` to `✘`) is intentional per §3, but must be
verified visually in a terminal before merge. **No behavioral change.**

**Dependencies:** Must be applied before or with 1.4.

---

### 1.4 Update call sites that use wrong error/warning icons

**Files:**

- `cli/.../cli/tui/GlobalCancel.java` line 42: `"‼ "` with `error()` → `Glyphs.CROSS + " "` with
  `error()` (cancel is error state)
- `cli/.../command/CacheCommand.java` line 659: `"‼"` with `t.error()` → `Glyphs.CROSS`
- `cli/.../cli/CommandDispatch.java` line 236: `"‼ Error:"` → `Glyphs.CROSS + " Error:"`
- `cli/.../cli/tui/SpinnerProgressBar.java` line 211: `"✗ Failed"` → `Glyphs.CROSS + " Failed"`
  (U+2717 → U+2718)
- `cli/.../cli/run/ProgressBarListener.java` line 157: `"⚠ Warning"` → `Glyphs.BANG + " Warning"`
- `cli/.../cli/run/ProgressBarListener.java` line 173: `"✗ Error"` → `Glyphs.CROSS + " Error"`
- `cli/.../cli/run/VerboseListener.java` line 77: `"⚠"` → `Glyphs.BANG`
- `cli/.../cli/run/VerboseListener.java` line 86: `"✗"` → `Glyphs.CROSS`
- `cli/.../cli/run/GoalConsole.java` line 160: `"  ⚠ "` → `"  " + Glyphs.BANG + " "`
- `cli/.../cli/run/GoalConsole.java` line 165: `"  ✗ "` → `"  " + Glyphs.CROSS + " "`
- `cli/.../cli/run/AggregateModuleListener.java` line 107: `"⚠ Warning"` → `Glyphs.BANG + " Warning"`
- `cli/.../command/AddCommand.java` line 539: `"⚠"` → `Glyphs.BANG`
- `cli/.../command/NewCommand.java` line 671: `"⚠"` → `Glyphs.BANG`

Also update `ConsoleSpec.java` comments at lines 53–56 and 87 which document `‼` as the error/
warning marker — replace with `✘` and `‼` respectively.

**Estimated lines changed:** ~20

**Risk:** Low for warning-icon fixes (⚠→‼). Medium for error-icon fixes (depends on 1.3 visual
verification). Apply warning fixes and error fixes as separate commits.

**Dependencies:** 1.3 must land first so `Glyphs.BANG` and the new `Glyphs.CROSS` value exist.

---

### 1.5 Replace inline icon literals in command layer with `Glyphs.*`

**Files:** All command files that use inline `"✓"`, `"✗"`, `"‼"` string literals without going
through `Glyphs`.

Specific replacements:

| File | Line(s) | Current | Replace With |
|---|---|---|---|
| `BuildCommand.java` | 512, 567 | `"  ✗ "` (in exception buf) | `"  " + Glyphs.CROSS + " "` |
| `BuildCommand.java` | 596 | `ok ? "✓" : "✗"` | `ok ? Glyphs.CHECK : Glyphs.CROSS` |
| `CompositeBuild.java` | 195 | `"✓"` | `Glyphs.CHECK` |
| `CompositeBuild.java` | 197 | `"✗"` | `Glyphs.CROSS` |
| `CleanCommand.java` | 123 | `"✓"` | `Glyphs.CHECK` |
| `ExportSupport.java` | 132 | `"✓"` | `Glyphs.CHECK` |
| `RemoveCommand.java` | 97 | `"✗"` | `Glyphs.CROSS` |
| `GraalResolver.java` | 143 | `"‼"` with `warning()` | `Glyphs.BANG` |
| `GraalResolver.java` | 189 | `"✓"` | `Glyphs.CHECK` |
| `ActivateCommand.java` | 87, 119 | `"✓"` | `Glyphs.CHECK` |
| `AddCommand.java` | 168, 373, 534 | `"✓"` | `Glyphs.CHECK` |
| `IdeaCommand.java` | 262 | `"✓"` | `Glyphs.CHECK` |
| `IdeaCommand.java` | 290 | `Glyphs.CROSS + " Note"` with `warning()` | `Glyphs.BANG + " Note"` |
| `JdkInstallCommand.java` | 434 | `"✓"` | `Glyphs.CHECK` |
| `JdkUninstallCommand.java` | 380 | `"‼"` with `warning()` | `Glyphs.BANG` |
| `JdkUpdateCommand.java` | 159, 212 | `"✓"` | `Glyphs.CHECK` |
| `JdkUpdateCommand.java` | 220 | `"✗"` | `Glyphs.CROSS` |
| `JdkUpdateCommand.java` | 317 | `"‼"` with `warning()` | `Glyphs.BANG` |
| `JdkEnsureCommand.java` | 194 | `"‼"` with `warning()` | `Glyphs.BANG` |
| `VerboseListener.java` | 60 | `"✓"` | `Glyphs.CHECK` |

**Estimated lines changed:** ~25

**Risk:** Low — pure constant reference substitution. Behavioral change only for the `"✗"` (U+2717)
sites where `Glyphs.CROSS` will now produce `"✘"` (U+2718) after 1.3 lands.

**Dependencies:** 1.3 and 1.4 must land first.

---

### 1.6 Fix coord separator color in `ExplainCommand`

**File:** `cli/src/main/java/dev/jkbuild/command/ExplainCommand.java`

**Line:** 330

**Change:** `t.darkGray()` on the `:` separator between coordinate path pieces must be changed to
`AttributedStyle.DEFAULT` (terminal default foreground). Per §2.2, neutral separators between
coordinate segments use the default terminal foreground.

```java
// Before:
sb.append(Theme.colorize(":", t.darkGray())).append(Theme.colorize(p.substring(1), t.coordName()));
// After:
sb.append(":").append(Theme.colorize(p.substring(1), t.coordName()));
```

**Estimated lines changed:** 1

**Risk:** Low.

**Dependencies:** None.

---

### 1.7 Fix focused radio/checkbox bullets in `Wizard` — `activeStep` not `completedStep`

**File:** `cli/src/main/java/dev/jkbuild/cli/tui/Wizard.java`

**Lines:** 743, 765, 782, 830, 832, 853, 855, 870, 872

**Change:** Focused but uncommitted radio/checkbox bullets currently use `completedStep()` (#4CAF50
green). Per §2.6 and §18.2, focused items use `activeStep()` (#18FFFF bright-cyan). Only items that
are already checked/selected should use `completedStep()`.

The vertical `MultiSelectStep` renderer at lines 829–832 already handles this correctly (checked →
`completedStep`, focused-only → `activeStep`, neither → `darkGray`). The horizontal `RadioStep`
renderer at lines 743–744 and the radio-in-vertical path at lines 764–765 need the same
differentiation: replace `isFocused ? completedStep() : darkGray()` with
`isFocused ? activeStep() : darkGray()`.

For multiselect, verify that the `isChecked && isFocused` case uses `completedStep()` (the item is
already selected, so green is correct even when focused).

**Estimated lines changed:** ~8

**Risk:** Low — changes only the color of the cursor bullet in wizards, not the interaction logic.

**Dependencies:** None.

---

### 1.8 Add `✘` glyph to `Wizard.printCancellation`

**File:** `cli/src/main/java/dev/jkbuild/cli/tui/Wizard.java`

**Lines:** 196–198

**Change:** The cancellation line appends the message text in `error()` style but omits the `✘`
glyph. Per §18.3:

```
╰── ✘ Wizard canceled
```

Update `printCancellation` to prepend `Glyphs.CROSS + " "` before the message:

```java
var line = new AttributedStringBuilder()
        .append(" " + Glyphs.CROSS + " " + message, Theme.active().error())
        .toAttributedString();
```

**Estimated lines changed:** 2

**Risk:** Low.

**Dependencies:** 1.3 must land first (so `Glyphs.CROSS = "✘"`).

---

### 1.9 Fix informational lines printed to `System.err` in `SyncCommand`

**File:** `cli/src/main/java/dev/jkbuild/command/SyncCommand.java`

**Lines:** 405, 418–420

**Change:** These two `System.err.println` calls are informational (not errors):

- Line 405: "jk sync: skipping module sync — ..." — change to `System.out.println`
- Lines 418–420: "jk sync: .../jk.lock not found — run `jk lock` first" — change to `System.out.println`

Line 438 ("jk sync: ... sync failed — ...") is a genuine error and stays on stderr.

**Estimated lines changed:** 3

**Risk:** Low. Could affect scripts that redirect stderr; the move to stdout is the correct
behavior per the style guide (informational output belongs on stdout).

**Dependencies:** None.

---

### 1.10 Fix `CleanCommand.gcCache` result line — use `GoalWedge.chipLine`

**File:** `cli/src/main/java/dev/jkbuild/command/CleanCommand.java`

**Lines:** 123–137

**Change:** `gcCache()` assembles its own `"✓ Cache GC: ..."` result line using a bare
`Theme.colorize("✓", ...)` + `String.format`. The main clean result at lines 101 and 108 correctly
uses `GoalWedge.chipLine`. Replace the ad-hoc assembly with:

```java
boolean nerdfont = GlobalConfig.nerdfont();
if (report.purgedBlobs() == 0) {
    System.out.println(GoalWedge.chipLine(Glyphs.CHECK, "Cache GC", nerdfont, "nothing idle past 90 days"));
} else {
    String msg = String.format("purged %,d blob%s (%s), %,d repo link%s",
            report.purgedBlobs(), ..., report.repoLinksRemoved(), ...);
    System.out.println(GoalWedge.chipLine(Glyphs.CHECK, "Cache GC", nerdfont, msg));
}
```

**Estimated lines changed:** ~10

**Risk:** Low — `GoalWedge.chipLine` is already used successfully for the clean result line in the
same method's enclosing context.

**Dependencies:** 1.3 (so `Glyphs.CHECK` is unchanged — it is already correct).

---

### 1.11 Delete dead code `LockCommand.lockCompletionLine`

**File:** `cli/src/main/java/dev/jkbuild/command/LockCommand.java`

**Lines:** 297–308

**Change:** Remove the private `lockCompletionLine(int, int, String, String)` method. It is
defined but never called; the live TUI path uses `view.addCompletion()` separately.

**Estimated lines changed:** -12 (deletion)

**Risk:** Low — dead code removal.

**Dependencies:** None.

---

## Phase 2 — Component Compliance

Moderate effort. Items may have light dependencies on Phase 1. Estimated total: **2–4 days**.

---

### 2.1 Add `Glyphs.BANG` to `ConsoleSpec.errorLine` and `compilerWarning`

**File:** `cli/src/main/java/dev/jkbuild/cli/run/ConsoleSpec.java`

**Lines:** 59–66, 90–97

**Context:** `ConsoleSpec.errorLine` renders `Glyphs.CROSS + " Error"` (which will be `✘` after
Phase 1). `ConsoleSpec.compilerWarning` renders `Glyphs.CROSS + " Warning"` — this must become
`Glyphs.BANG + " Warning"` because compiler warnings are warnings, not errors.

**Change:**

```java
// compilerWarning — line 91:
return Theme.colorize(Glyphs.BANG + " Warning", Theme.active().warning())
```

Also fix `renderWarning`'s plain-text fallback at line 83:
```java
return "warn[" + d.phase() + "]: " + d.message();
```
to:
```java
return Theme.colorize(Glyphs.BANG + " Warning", Theme.active().warning())
        + " [" + d.phase() + "]: " + d.message();
```

**Estimated lines changed:** ~5

**Risk:** Low — the change makes warnings display with `‼` instead of `✘` (current broken state).

**Dependencies:** Phase 1 items 1.3 and 1.4.

---

### 2.2 Fix `GoalWedge.failureLine` and `canceledLine` to use `Glyphs.CROSS` semantically

**File:** `cli/src/main/java/dev/jkbuild/cli/tui/GoalWedge.java`

**Lines:** 78, 85, 98, 105

**Context:** After 1.3 changes `Glyphs.CROSS` to `"✘"`, `GoalWedge` will correctly render `✘` in
all four chip-and-non-chip paths. No code change is needed beyond Phase 1 item 1.3. This item
records the explicit verification step: confirm that the nerd-font chip at line 78, the non-nerd
failure head at line 85, and the same two paths for cancellation at lines 98 and 105 all look
correct after 1.3 lands.

Additionally update the Javadoc at lines 69 and 90 to read `✘` instead of `‼`.

**Estimated lines changed:** ~4 (Javadoc only after 1.3)

**Risk:** Low — documentation update only after Phase 1 makes the code correct.

**Dependencies:** Phase 1 item 1.3.

---

### 2.3 Fix `BuildCommand.completionLine` inline icons

**File:** `cli/src/main/java/dev/jkbuild/command/BuildCommand.java`

**Lines:** 596, 512, 567

**Change:**

- Line 596: `ok ? "✓" : "✗"` → `ok ? Glyphs.CHECK : Glyphs.CROSS` (covered by 1.5).
- Lines 512 and 567: `"  ✗ "` in the exception `buf` — these are buffered error strings with no
  ANSI styling. They should be styled: `"  " + Theme.colorize(Glyphs.CROSS, Theme.active().error()) + " "`.

Additionally, the verbose workspace separator at line 655–656:
```java
System.out.println("══ " + workspaceRoot.relativize(moduleDir) + " (" + ... + ") ══");
```
should use `darkGray()` for the `═` characters:
```java
String sep = Theme.colorize("══", Theme.active().darkGray());
String mod = workspaceRoot.relativize(moduleDir).toString();
System.out.println(sep + " " + mod + " (" + (i+1) + "/" + sorted.size() + ") " + sep);
```

**Estimated lines changed:** ~10

**Risk:** Low.

**Dependencies:** Phase 1 items 1.3, 1.5.

---

### 2.4 Extend `Diagnostics.Palette.fromRgb` to accept header and rail colors

**File:** `kernel/resolver/src/main/java/dev/jkbuild/resolver/pubgrub/Diagnostics.java`

**Lines:** 51–57, 68–71

**Context:** `Palette.DEFAULT` and `Palette.fromRgb` both hardcode the error-text (`#E91E63`,
NORMAL_RED) and rail (`#546E7A`, BRIGHT_BLACK) color as raw ANSI strings. The resolver module has
no compile dependency on the CLI theme, so these cannot be `Theme.active().error()` calls.

**Change:** Add two parameters to `fromRgb`:

```java
public static Palette fromRgb(
        int coordR, int coordG, int coordB,
        int versionR, int versionG, int versionB,
        int headerR, int headerG, int headerB,
        int railR, int railG, int railB) {
    return new Palette(
        "\033[m",
        sgr(headerR, headerG, headerB),
        sgr(railR, railG, railB),
        sgr(coordR, coordG, coordB),
        sgr(versionR, versionG, versionB)
    );
}
```

Update the CLI call site in `LockCommand` (which calls `Palette.fromRgb` with coord/version colors
from `JkDarkTheme`) to also pass `Theme.active().error()` RGB values for the header and
`Theme.active().darkGray()` RGB values for the rail. The `Palette.DEFAULT` static instance becomes:

```java
public static final Palette DEFAULT = fromRgb(
    0x00, 0xBC, 0xD4,   // coordGroup (NORMAL_CYAN)
    0x18, 0xFF, 0xFF,   // coordVersion (BRIGHT_CYAN)
    0xE9, 0x1E, 0x63,   // header (NORMAL_RED)
    0x54, 0x6E, 0x7A    // rail (BRIGHT_BLACK)
);
```

**Estimated lines changed:** ~20 (Diagnostics.java) + ~5 (LockCommand.java call site)

**Risk:** Medium — touches the resolver module and the public API of `Palette`. Update the single
call site in the CLI simultaneously.

**Dependencies:** None from Phase 1.

---

### 2.5 Add `Theme.active().focused()` for phase label in `ProgressBarListener`

**File:** `cli/src/main/java/dev/jkbuild/cli/run/ProgressBarListener.java`

**Line:** 263

**Change:** The phase label is currently styled with `AttributedStyle.DEFAULT.bold()` — an ad-hoc
style assembled outside the Theme. The closest matching Theme role is `focused()` (bold
bright-white). If a plain-bold (no color) style is genuinely needed, add a `boldPlain()` method to
the `Theme` interface returning `AttributedStyle.DEFAULT.bold()`.

In the short term, changing to `Theme.active().focused()` is the most compliant approach:

```java
out.print(Theme.colorize(phaseDisplay, Theme.active().focused()));
```

**Estimated lines changed:** 1

**Risk:** Low — slight brightening of the phase label text.

**Dependencies:** None.

---

### 2.6 Add `shell` semantic role to `JkDarkTheme` — use `SHELL_ORANGE` named constant

**File:** `cli/src/main/java/dev/jkbuild/cli/theme/JkDarkTheme.java`

**Context:** After Phase 1 item 1.1, `SHELL_ORANGE` is a named constant. The `shell()` method
already exists and is used only in `RunCommand`. This item updates `docs/tui-style.md` §2.5 to
remove the "(inline)" note from the `shell` role and document `shell()` as the Theme method.

**Changes:**

Update `docs/tui-style.md` line 132:
```
| `shell` | *(inline)* | `#FF9800` | ...
```
to:
```
| `shell` | `shell()` | `#FF9800` | ...
```

Also audit `AuthLoginCommand` (device flow block) and `ActivateCommand` (backtick-quoted shell
snippet) for shell-color opportunities (see uncovered scenarios §14) — for each inline shell command
referenced in advisory text, apply `Theme.active().shell()`.

**Estimated lines changed:** ~5 (style guide + 2–3 command files)

**Risk:** Low — additive color application, no structural change.

**Dependencies:** Phase 1 item 1.1.

---

### 2.7 Unify warning icon for `JdkUninstallCommand` — apply `Glyphs.BANG`

**File:** `cli/src/main/java/dev/jkbuild/command/JdkUninstallCommand.java`

**Lines:** 361, 367, 380

**Change (overlaps 1.5):**

- Line 361: `Glyphs.CROSS` with `error()` — after Phase 1 this becomes `✘ ` correctly.
- Line 367: `Glyphs.CHECK` with `completedStep()` — correct, no change needed.
- Line 380: `"‼"` with `warning()` → `Glyphs.BANG`.

Additionally, the non-LTS fallback default line at lines 444–454 (see uncovered scenarios §5) should
follow the "post-command advisory" pattern: `Glyphs.BANG` in `warning()` for the "(non-LTS
fallback)" notice, since this signals the user's default JDK is a non-standard choice.

**Estimated lines changed:** ~5

**Risk:** Low.

**Dependencies:** Phase 1 items 1.3, 1.4.

---

### 2.8 Apply semantic styles to `jk doctor` output

**File:** `cli/src/main/java/dev/jkbuild/command/DoctorCommand.java`

**Context:** All doctor output is plain terminal foreground. Per uncovered scenario §15.

**Changes:** Apply colors to the fixed-width verb prefix:

- `ok:` → `completedStep()` green
- `verified:` → `completedStep()` green
- `linked:` → `settled()` body-text (default)
- `pruned:` → `warning()` amber

The tool name uses `link` / `cyan()`. The symlink target path after `→` uses `path()`. The summary
line numbers use `focused()`. The `---` separator uses `darkGray()`.

**Estimated lines changed:** ~20

**Risk:** Low — additive color; no structural change.

**Dependencies:** None.

---

### 2.9 Apply semantic styles to `jk tool list` output

**File:** `cli/src/main/java/dev/jkbuild/command/ToolListCommand.java`

**Context:** Per uncovered scenario §22.

**Changes:**

- Tool name: `link` / `cyan()`
- Coordinate: `Coords.module(group, artifact)` (already handles coord-group and coord-name colors)
- `→` arrow: `darkGray()`
- Path: `path()`

**Estimated lines changed:** ~10

**Risk:** Low.

**Dependencies:** None.

---

### 2.10 Apply semantic styles to `jk auth status` output

**File:** `cli/src/main/java/dev/jkbuild/command/AuthStatusCommand.java`

**Context:** Per uncovered scenario §18.

**Changes:**

- `authenticated` status: `Glyphs.CHECK` in `completedStep()` / `success()`
- `not authenticated` status: `Glyphs.CROSS` in `error()`
- Forge name: `link` / `cyan()`
- Host: `settled()` body-text

**Estimated lines changed:** ~15

**Risk:** Low.

**Dependencies:** Phase 1 items 1.3, 1.4.

---

### 2.11 Style `CacheCommand` purge confirmation — use `Glyphs.CROSS` for destructive error

**File:** `cli/src/main/java/dev/jkbuild/command/CacheCommand.java`

**Lines:** 659, 668

**Change:** After Phase 1 item 1.4, line 659's `"‼"` with `t.error()` becomes `Glyphs.CROSS`. The
`Confirm` widget prompt at line 668 uses `bang` (now `✘`) — but §3 says `‼` is the warning icon
and §7 of uncovered scenarios says the confirm prompt uses `‼` (warning-text amber). The destructive
purge header (`‼ This permanently deletes...`) is genuinely a warning/advisory, so it should use
`Glyphs.BANG` in `warning()` for the first `‼` (the advisory header), and the confirm prompt line
should also use `Glyphs.BANG` (confirm is a warning / caution, not an error state).

```java
String bang = Theme.colorize(Glyphs.BANG, t.warning());
```

**Estimated lines changed:** 2

**Risk:** Low — restores the semantically correct icon; the current `‼` with `error()` is wrong
per §3.

**Dependencies:** Phase 1 items 1.3, 1.4.

---

### 2.12 Style `jk jdk pin` confirmation line

**File:** `cli/src/main/java/dev/jkbuild/command/JdkPinCommand.java`

**Line:** 73

**Change:** "Pinned project to temurin-25" is plain text with no icon. Apply the bare confirmation
pattern: `Glyphs.CHECK` in `success()`, pin name in `focused()`.

**Estimated lines changed:** ~3

**Risk:** Low.

**Dependencies:** None.

---

### 2.13 Style `ActivateCommand` — advisory text and shell snippet

**File:** `cli/src/main/java/dev/jkbuild/command/ActivateCommand.java`

**Lines:** 87–90, 119–122

**Context:** Per uncovered scenarios §13 and §14.

**Changes:**

- Lines 87, 119: `"✓"` → `Glyphs.CHECK` (covered by 1.5, already done).
- The "Open a new shell (or run `source ~/.zshrc`)..." hint line should use `normalGray()` for the
  surrounding text and `shell()` orange for the inline backtick-quoted command.
- The "Skipped — paste this into..." fallback line should use `Glyphs.BANG` in `warning()`.

**Estimated lines changed:** ~8

**Risk:** Low.

**Dependencies:** Phase 1 items 1.3, 1.5.

---

## Phase 3 — Structural Refactor

Larger effort requiring design decisions and cross-cutting changes. Estimated total: **1–2 weeks**.

---

### 3.1 Retire `ProgressBarListener` in favor of `CommandManagerListener`

**Files:**

- `cli/.../cli/run/ProgressBarListener.java` (437 lines — retire or reduce to a thin delegator)
- `cli/.../cli/run/GoalConsole.java` (lines 183–186 — `chooseConsoleListener` AUTO branch)
- `cli/.../cli/run/CommandManagerListener.java`
- `cli/.../cli/tui/CommandManager.java`

**Context:** `ProgressBarListener` is a parallel rendering path used for the non-`jk build`
pipeline (lock, sync, new, etc.). It produces a different layout from `CommandManager`
(`▰▱` bar vs `█` bar; `›` separator vs `·`; no counter/clock; no GoalWedge chip). This is Code
Structure finding §4.

**Proposed change:**

Route all commands that currently go through `ProgressBarListener` to `CommandManagerListener`
instead. This means `GoalConsole.chooseConsoleListener` AUTO branch always returns a
`CommandManagerListener`-backed listener. The `ProgressBarListener` class can either be deleted or
kept as a deprecated path with a comment marking it for eventual removal.

Any capabilities unique to `ProgressBarListener` (e.g. its 24-segment `▰▱` bar format) that are
intentionally preferred for certain commands should be documented in `tui-style.md` first; otherwise
default to the unified `CommandManager` path.

**Estimated lines changed:** ~100 (GoalConsole routing change + call site updates) + potential
deletion of ProgressBarListener (~437 lines)

**Risk:** High — this changes the visible spinner/bar format for `jk lock`, `jk sync`, `jk new`,
and all other commands routed through `GoalConsole.run()`. Requires visual QA across all affected
commands. Do not merge without a before/after comparison session.

**Dependencies:** All Phase 1 and Phase 2 items should land first so the `CommandManager` path is
fully compliant before it absorbs the additional commands.

---

### 3.2 Add No-ANSI rendering paths

**Files:**

- `cli/.../cli/tui/GoalWedge.java`
- `cli/.../cli/tui/CommandManager.java`
- `cli/.../cli/run/ProgressBarListener.java` (or its successor after 3.1)
- `cli/.../cli/theme/Theme.java` (new `isAnsi()` method)

**Context:** Style guide §4.4, §5.3 (No-ANSI bar), §1.3. Code Structure finding §5.

**Proposed change:**

1. Add `Theme.isAnsi()` (or `Theme.ansiEnabled()`) — returns `true` unless `--no-ansi` was passed
   or stdout is not a TTY. The current `Theme.colorEnabled()` controls colors; `isAnsi()` is the
   broader gate for cursor movement and Unicode glyphs.

2. `GoalWedge.chipLine()`, `GoalWedge.failureLine()`, `GoalWedge.canceledLine()` each gain a
   No-ANSI branch:
   - Icon: `+` (success), `!` (error/cancel)
   - Cap: `: ` (ASCII)
   - No background, no color

3. `ProgressBar` (or `CommandManager`) gains a No-ANSI branch: at thresholds 10%, 25%, 50%, 75%,
   90%, and 100%, print a new plain-text line `[N%] verb message` rather than an in-place redrawn
   bar.

**Estimated lines changed:** ~80

**Risk:** Medium — new code paths, no existing code is removed. Risk is low of regressions since the
No-ANSI path is gated. Requires testing in a pipe environment (`jk build | cat`).

**Dependencies:** None from Phase 1 or 2, but 3.1 should land first so there is one bar
implementation to add the No-ANSI path to rather than two.

---

### 3.3 Document and standardize uncovered output patterns in `tui-style.md`

**File:** `docs/tui-style.md`

**Context:** The uncovered scenarios audit (§1–§25) identified patterns with no guide entry.

**Changes** — add sections for each of the following:

| New Section | Covers |
|---|---|
| §N — JDK Download Progress | `JdkDownloadBar` format: GoalWedge-style prefix + standalone ProgressBar + dim label |
| §N — Bare Confirmation Line | `✓ <name> <verb> <path>` outside a GoalWedge chip |
| §N — Post-Command Advisory | `➜` outside wizard; hint line with inline shell command |
| §N — Confirm Prompt | `‼ Question? [Y/n]` — icon, default capitalization, no-TTY behavior |
| §N — Migration Arrow | `{old} → {new}` identifier pair with coord colors |
| §N — JDK Status Column | Full palette for active/default/native/installed/available |
| §N — Embedded Utilization Bar | ProgressBar inside a table row |
| §N — Post-Chip Summary | Unstyled summary lines below a settled chip |
| §N — Verbose Module Separator | `══ module (k/N) ══` with darkGray chars |
| §N — Destructive Confirmation Block | Multi-line warning + N-default confirm |
| §N — Device Flow Prompt | Auth code, URL, waiting line for `jk auth login` |
| §N — `[k of N]` Count Bracket | Component spec for the bracket notation |
| §N — `crossedOut` Completion | Strikethrough on completed workspace unit coords |
| §N — `errorLabel()` role | Document as bold error-text (same hue as `error()`, bold added) |

Each section should specify: Nerd Font, Non-Nerd Font, No-Color, and No-ANSI variants, plus the
Theme methods to use.

**Estimated effort:** 1–2 days of documentation writing.

**Risk:** Low — documentation only. No code changes.

**Dependencies:** All Phase 1 and Phase 2 code items should be reviewed before finalizing the guide,
so the documented patterns match the implemented patterns.

---

### 3.4 Implement guide-compliant output for key uncovered patterns

After 3.3 documents the patterns, apply them to the code:

**Item 3.4a — `jk jdk update` migration arrow and item result lines**

**File:** `cli/src/main/java/dev/jkbuild/command/JdkUpdateCommand.java`

Apply migration-arrow pattern (coord colors + `darkGray()` arrow) for `→` in update result lines.
Apply `completedStep()` for success icon, `error()` for failure icon, `warning()` for the failed
subject identifier.

Estimated lines changed: ~15. Risk: Low.

**Item 3.4b — `jk sync` post-chip summary lines**

**File:** `cli/src/main/java/dev/jkbuild/command/SyncCommand.java`

Apply `focused()` to counts, `path()` to the `jk.lock` path, `body-text` to surrounding text.
Apply `link` / `cyan()` to module directory names in cascade result lines.

Estimated lines changed: ~20. Risk: Low.

**Item 3.4c — `jk auth login` device flow prompt**

**File:** `cli/src/main/java/dev/jkbuild/command/AuthLoginCommand.java`

Apply `focused()` to the one-time code, `cyan()` to the URL, `normalGray()` to the "Waiting..."
line.

Estimated lines changed: ~10. Risk: Low.

**Item 3.4d — `jk cache info` title row color consistency**

**File:** `cli/src/main/java/dev/jkbuild/command/CacheCommand.java`

The info table title row uses `activeStep()` (#18FFFF) rather than `planBadge()` (#0F4786) per §15.
Align with the table title spec.

Estimated lines changed: ~5. Risk: Low.

**Item 3.4e — `jk jdk graal` and `jk jdk install --make-default` advisory lines**

**Files:** `JdkGraalCommand.java`, `JdkInstallCommand.java`

Apply post-command advisory pattern: `brightGreen()` arrow, `focused()` key term, `normalGray()`
body, `darkGray()` parenthesized annotation.

Estimated lines changed: ~10. Risk: Low.

**Combined risk for 3.4:** Low per item. Total estimated lines: ~60.

**Dependencies:** 3.3 must define the patterns before 3.4 implements them.

---

### 3.5 Retire or fix `VerboseListener` and `AggregateModuleListener` icon inconsistencies

**Files:**

- `cli/.../cli/run/VerboseListener.java`
- `cli/.../cli/run/AggregateModuleListener.java`

**Context:** After Phase 1 items 1.4 and 1.5, the icon literal fixes are already applied. This item
addresses the structural question: `VerboseListener` uses `𝘅` (italic small x, U+1D465) at line 61
for FAIL state — this is a font-specific glyph not in the icon set. Replace with `Glyphs.CROSS`.

`VerboseListener` uses `"·"` for CANCELLED state — this is acceptable as a dim separator-style
glyph but is not in the canonical icon set. Consider adding `Glyphs.BULLET = "·"` or simply
leaving it as a literal since it is purely decorative.

**Estimated lines changed:** ~5

**Risk:** Low.

**Dependencies:** Phase 1 items 1.3, 1.4, 1.5.

---

### 3.6 Extract `[k of N]` bracket formatting into a shared helper

**Files:**

- `cli/.../command/BuildCommand.java` — `completionLine` method
- `cli/.../command/LockCommand.java` — `lockCompletionLine` (deleted by 1.11)
- Future callers

**Context:** Code Structure finding §2. After 1.11 deletes `lockCompletionLine`, only
`BuildCommand.completionLine` uses this pattern. Extract the bracket formatting into a static helper
on `ConsoleSpec` (since it is a presentation-layer concern):

```java
public static String countBracket(int n, int total, Theme t) {
    String num = String.format("%0" + Integer.toString(total).length() + "d", n);
    return Theme.colorize("[", t.darkGray()) + num + " of " + total + Theme.colorize("]", t.darkGray());
}
```

**Estimated lines changed:** ~10 (new method + update BuildCommand call site)

**Risk:** Low.

**Dependencies:** Phase 1 item 1.11.

---

## Summary

### Phase 1 — Quick Wins (< 1 day)

| Item | Files | Lines | Risk |
|---|---|---|---|
| 1.1 Named constants for inline palette literals | `JkDarkTheme.java` | ~15 | Low |
| 1.2 Replace raw ANSI escape in `Wizard.headerLine` | `Wizard.java` | ~10 | Low |
| 1.3 Redefine `Glyphs.CROSS` to `✘` | `Glyphs.java` | ~8 | Medium |
| 1.4 Update call sites with wrong error/warning icons | 12 files | ~20 | Low/Medium |
| 1.5 Replace inline icon literals with `Glyphs.*` | ~15 command files | ~25 | Low |
| 1.6 Fix coord separator color in `ExplainCommand` | `ExplainCommand.java` | 1 | Low |
| 1.7 Fix focused radio/checkbox color in `Wizard` | `Wizard.java` | ~8 | Low |
| 1.8 Add `✘` to `Wizard.printCancellation` | `Wizard.java` | 2 | Low |
| 1.9 Fix informational stderr lines in `SyncCommand` | `SyncCommand.java` | 3 | Low |
| 1.10 Use `GoalWedge.chipLine` in `CleanCommand.gcCache` | `CleanCommand.java` | ~10 | Low |
| 1.11 Delete dead `LockCommand.lockCompletionLine` | `LockCommand.java` | -12 | Low |

**Phase 1 total:** ~90 net lines changed across ~25 files.

---

### Phase 2 — Component Compliance (2–4 days)

| Item | Files | Lines | Risk |
|---|---|---|---|
| 2.1 Fix `ConsoleSpec.compilerWarning` icon | `ConsoleSpec.java` | ~5 | Low |
| 2.2 Verify/update `GoalWedge` Javadoc | `GoalWedge.java` | ~4 | Low |
| 2.3 Fix `BuildCommand` completion icons + separator | `BuildCommand.java` | ~10 | Low |
| 2.4 Extend `Diagnostics.Palette.fromRgb` | `Diagnostics.java`, `LockCommand.java` | ~25 | Medium |
| 2.5 Phase label styling in `ProgressBarListener` | `ProgressBarListener.java` | 1 | Low |
| 2.6 Update `tui-style.md` `shell` role entry | `tui-style.md` + 2–3 command files | ~5 | Low |
| 2.7 `JdkUninstallCommand` warning icons | `JdkUninstallCommand.java` | ~5 | Low |
| 2.8 Style `jk doctor` output | `DoctorCommand.java` | ~20 | Low |
| 2.9 Style `jk tool list` output | `ToolListCommand.java` | ~10 | Low |
| 2.10 Style `jk auth status` output | `AuthStatusCommand.java` | ~15 | Low |
| 2.11 Fix `CacheCommand` purge confirm icon | `CacheCommand.java` | 2 | Low |
| 2.12 Style `jk jdk pin` confirmation | `JdkPinCommand.java` | ~3 | Low |
| 2.13 Style `ActivateCommand` advisory text | `ActivateCommand.java` | ~8 | Low |

**Phase 2 total:** ~113 lines changed across ~15 files.

---

### Phase 3 — Structural Refactor (1–2 weeks)

| Item | Files | Lines | Risk |
|---|---|---|---|
| 3.1 Retire `ProgressBarListener` | `ProgressBarListener.java`, `GoalConsole.java`, etc. | ~500 | High |
| 3.2 Add No-ANSI rendering paths | `GoalWedge.java`, `CommandManager.java`, `Theme.java` | ~80 | Medium |
| 3.3 Document uncovered patterns in `tui-style.md` | `docs/tui-style.md` | ~200 lines doc | Low |
| 3.4 Implement uncovered patterns (a–e) | 5 command files | ~60 | Low |
| 3.5 Fix `VerboseListener` glyph inconsistency | `VerboseListener.java` | ~5 | Low |
| 3.6 Extract `[k of N]` bracket helper | `ConsoleSpec.java`, `BuildCommand.java` | ~10 | Low |

**Phase 3 total:** ~355 net lines changed; ~200 lines of documentation.
