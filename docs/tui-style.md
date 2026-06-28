# jk TUI Style Guide

This document is the canonical reference for all terminal output produced by `jk`. Every component,
color, glyph, and layout decision is defined here. Implementation must match this guide; this guide
must be updated when the implementation changes intentionally.

---

## 1. Output Modes

`jk` supports three output modes. The mode is resolved once at startup and does not change during a
run.

### 1.1 Nerd Font Mode *(default)*

The preferred interactive mode. Requires a terminal with a [Nerd Font](https://www.nerdfonts.com/)
installed and configured.

- Full Unicode including **Private Use Area (PUA) glyphs** (Powerline, pill caps, etc.)
- Full ANSI color and cursor-control sequences
- Animated spinners and in-place redraws

### 1.2 Non-Nerd Font Mode

For terminals without Nerd Font support. Detected automatically or forced with `--no-nerdfont`.

- Unicode characters from standard, widely-available ranges only (no PUA glyphs)
- Full ANSI color and cursor-control sequences
- Animated spinners and in-place redraws
- Powerline caps replaced by plain spaces

### 1.3 No-ANSI Mode

For pipes, CI environments, or terminals that do not support ANSI sequences. Forced by `-vv` (very
verbose), `--no-ansi`, or detection of a non-TTY stdout.

- **ASCII characters only** — no Unicode, no box-drawing, no glyphs
- No ANSI escape codes (no color, no cursor movement, no in-place redraw)
- Spinners do not animate; each state transition produces a new line
- `-vv` overrides all other mode flags and forces No-ANSI + very-verbose output

### 1.4 Color Sub-Modes

Modes 1.1 and 1.2 each support two color sub-modes:

| Sub-mode | Trigger | Effect |
|---|---|---|
| **Color** *(default)* | — | 24-bit truecolor applied per theme role |
| **No-Color** | `--color never`, `NO_COLOR` env var | ANSI color codes stripped; text attributes (bold, italic) preserved |

No-ANSI mode (1.3) is always no-color.

### 1.5 Verbose Modes

| Flag | Available In | Effect |
|---|---|---|
| *(none)* | all modes | Normal output |
| `-v` | all modes | Additional informational lines |
| `-vv` | No-ANSI only | Forces No-ANSI + every intermediate state on its own line |

---

## 2. Theme Colors

All colors are 24-bit truecolor from the **Jk Dark** palette. Every color has a named semantic role
used consistently throughout the application. Generic names like "gray" or "bright-black" are never
used in code — only the role name.

### 2.1 Status & State

Colors that communicate the outcome or current state of an operation.

| Role | Theme Method | Hex | Usage |
|---|---|---|---|
| `chip-working` / `chip-info` | `goalChip()` bg | `#0F4786` | GoalWedge chip bg — active/in-progress and informational states |
| `chip-success` | `goalSuccessChip()` bg | `#357B38` | GoalWedge chip bg — successful completion |
| `chip-error` | `goalFailureChip()` bg | `#E91E63` | GoalWedge chip bg — error/failure |
| `chip-success-cap` | `goalChipColor()` | `#357B38` | Powerline cap fg after a success chip |
| `chip-error-cap` | `goalFailColor()` | `#E91E63` | Powerline cap fg after an error chip |
| `error-text` | `error()` | `#E91E63` | Inline error text foreground (no bg) |
| `success-text` | `success()` | `#4CAF50` + bold | Inline success text foreground (no bg) |
| `warning-text` | `warning()` | `#FFC107` | Warning text foreground — also the countdown/counter color |
| `jdk-status-default` | `brightYellow()` | `#FFD54F` | JDK "default" status label |

### 2.2 Dependency Coordinates

Colors for the three segments of Maven/Cargo package coordinates.

| Role | Theme Method | Hex | Usage |
|---|---|---|---|
| `coord-group` | `coordGroup()` | `#00BCD4` | The `group` segment (`org.apache.commons`) |
| `coord-name` | `coordName()` | `#18FFFF` | The `artifact` / `name` segment (`commons-io`) |
| `coord-version` | `coordVersion()` | `#C1FBFC` | The `version` segment (`1.2.3`) |

Neutral separators (`:`, `@`, `^`, `=`) use the default terminal foreground.

### 2.3 Structural & Rail

Colors for tree rails, separators, and structural glyphs.

| Role | Theme Method | Hex | Usage |
|---|---|---|---|
| `rail` | `darkGray()` | `#546E7A` | Tree connectors (`│ ├─ ╰─`), wizard rails, PubGrub `│` |
| `dim-text` | `dim()` | *(faint attr)* | De-emphasized text; no color change, just faint |
| `separator` | `darkGray()` | `#546E7A` | Inline separators (e.g. the `·` between percent and counter) |

### 2.4 Badges & Bands

Colors for Badge components, wizard subtitle bands, and table highlights.

| Role | Theme Method | Hex | Usage |
|---|---|---|---|
| `badge-background` | `gray()` (bg) | `#90A4AE` | Badge pill background; wizard subtitle band background |
| `badge-cap` | `gray()` (fg) | `#90A4AE` | Powerline cap fg adjacent to a badge |
| `badge-text` | `CHIP_TEXT` | `#000000` | Text inside any gray badge or subtitle band |
| `table-selected-row` | `darkBlackColor()` | *≈`#334050`* | Active/selected row background in tables |
| `wizard-title-badge` | `indigoBadge()` bg | `#3F51B5` | Wizard legacy title badge (indigo) |
| `plan-badge` | `planBadge()` bg | `#0F4786` | `jk explain` / `jk tree` header chip |
| `plan-badge-cap` | `planBadgeColor()` | `#0F4786` | Cap fg for the plan-badge chip |

### 2.5 Text Roles

Colors for various categories of text content.

| Role | Theme Method | Hex | Usage |
|---|---|---|---|
| `body-text` | `settled()` / `plainWhite()` | `#CFD8DC` | Default body text foreground |
| `focused-text` | `focused()` | `#ECEFF1` + bold | Active input buffer, focused labels in wizard |
| `bright-text` | `brightWhite()` | `#ECEFF1` | Emphasis without bold (e.g. chip inner text) |
| `muted-text` | `normalGray()` | `#C5CAE9` | De-emphasized body text adjacent to bright labels |
| `path` | `path()` | `#969DD4` | File system paths |
| `shell` | *(inline)* | `#FF9800` | Shell commands and command-lines (e.g. `jk run` output) |
| `link` | `cyan()` | `#00BCD4` | JDK identifiers, URLs, and similar reference text |

### 2.6 Wizard Step Colors

Colors specific to the Wizard component step rail.

| Role | Theme Method | Hex | Usage |
|---|---|---|---|
| `wizard-active-step` | `activeStep()` | `#18FFFF` | Active step bullet (■) and focused radio/checkbox items |
| `wizard-completed-step` | `completedStep()` | `#4CAF50` | Completed step bullet (□) fill / checkbox checked |
| `wizard-settled-prompt` | `completedPrompt()` | `#90A4AE` | Prompt label text after a step is answered |
| `wizard-answer-arrow` | `brightGreen()` | `#69F0AE` | The ➜ prefix on settled answers |

### 2.7 Help Output

Colors used in `--help` screens and usage output.

| Role | Theme Method | Hex | Usage |
|---|---|---|---|
| `help-section` | `sectionHeading()` | `#4CAF50` + bold | Section headings (e.g. `Options:`) |
| `help-command` | `commandName()` | `#00BCD4` + bold | Command names in listings |
| `help-param` | `paramLabel()` | `#00BCD4` | Parameter / option labels |
| `help-highlight` | `highlight()` | `#FFC107` | Inline highlights in descriptions |

### 2.8 Syntax Highlighting

Colors for compiler diagnostic source snippets, matching the GitHub dark default theme.

| Role | Theme Method | Hex | Usage |
|---|---|---|---|
| `syn-keyword` | `synKeyword()` | `#FF7B72` | Language keywords (`public`, `class`, `fun`) |
| `syn-type` | `synType()` | `#FFA657` | Type / class names |
| `syn-function` | `synFunction()` | `#D2A8FF` | Function / method names; also annotations |
| `syn-constant` | `synConstant()` | `#79C0FF` | Named constants; also numeric literals |
| `syn-string` | `synString()` | `#A5D6FF` | String / char / text-block literals |
| `syn-comment` | `synComment()` | `#8B949E` | Line and block comments; namespace qualifiers; punctuation |

### 2.9 Gradients

Gradients are computed at runtime from start → end color pairs.

| Role | Start | End | Usage |
|---|---|---|---|
| `gradient-title` | `#536DFE` (bright-blue) | `#FF4081` (accent pink) | Wizard legacy title text gradient |
| `gradient-progress` | `#3F51B5` (indigo) | `#E040FB` (bright-magenta) | ProgressBar fill gradient |
| `gradient-spinner` | `#3F51B5` (indigo) | `#FF4081` (accent pink) | Spinner frame animation gradient |
| `gradient-failure` | `#7F1D1D` (dark red) | `#EF4444` (bright red) | Failed ProgressBar repaint gradient |

### 2.10 Foreground on Colored Backgrounds

When a background color is set (e.g. on a chip, badge, or band):

- **Background = theme color → foreground = bright-white** (`#FFFFFF`)
- **Background unset → foreground = the theme color** (text inherits the semantic color)

The `badge-text` role (`#000000`) is the exception — always pure black, regardless of the above rule, because badge backgrounds are light enough for black text.

---

## 3. Status Semantics

Every piece of status output uses one of five semantic states. Each state has a canonical icon,
color, and text style.

| State | Icon | Nerd Font Icon | Color | Background |
|---|---|---|---|---|
| **Working** | animated spinner | ✸ (animates) | white on `#0F4786` (dark royal blue) | set |
| **Info** | ● (black circle) | ● | white on `#0F4786` | set |
| **Success** | ✓ (check mark) | ✓ | white on `#357B38` (dark green) | set |
| **Error** | ✘ (U+2718 heavy ballot X) | ✘ | white on `#E91E63` (red) | set |
| **Warning** | ‼ (double exclamation) | ‼ | `#FFC107` amber (foreground only) | unset |

> **Note:** ✘ (U+2718) is the error icon everywhere. ‼ is reserved for warnings only.
> The no-ANSI fallback for all icons is `*` (asterisk for working/info), `+` (success), `!` (error/warning).

---

## 4. GoalWedge Component

The GoalWedge is the primary output element for every `jk` command. It names the goal in progress
and communicates its status through icon + color + label.

### Structure

```
{icon-slot}{label}{cap}
```

| Part | Description |
|---|---|
| **icon** | A status icon (see §3) or an animated spinner. Rendered inside the colored chip. |
| **label** | The goal or command name (e.g. `Build`, `Lock`, `New Project`). |
| **cap** | A powerline separator that terminates the chip. |

### 4.1 Nerd Font GoalWedge

```
 ✸ Build ▶
```

- Chip background: theme color for the current state
- Chip foreground: bright-white
- Cap glyph: **U+E0B0** (solid right-pointing triangle)
- Cap foreground: chip background color (so the cap blends visually)
- Cap background: unset — **except** when immediately left-adjacent to a ProgressBar, in which
  case the cap background matches the leftmost color of the bar (creating a seamless taper)

### 4.2 Non-Nerd Font GoalWedge

```
 ✸ Build  
```

- Same chip styling as Nerd Font
- Cap replaced by two plain spaces (no glyph)

### 4.3 No-Color GoalWedge

```
 ✸ Build: 
```

- No background or foreground color applied
- Cap replaced by `:` followed by a space — the colon provides the visual separator that background
  color would normally supply

### 4.4 No-ANSI GoalWedge

```
 * Build: 
```

- ASCII only: icon becomes `*` (or `+`, `!` per state — see §3)
- No color, no Unicode glyphs
- Cap is `: ` as in no-color mode

---

## 5. ProgressBar Component

### 5.1 ANSI Mode (Nerd Font and Non-Nerd Font)

- **40 slots** by default (configurable)
- Each slot is filled using Unicode block characters (█, ▏▎▍▌▋▊▉)
- Every cell (filled, fractional, empty) is **underlined**
- Color: gradient from left (indigo) to right (bright-magenta); no-color mode shows no gradient
- Trailing numeric percentage updates in place (e.g. `7%`, `98%`)

```
██▊                                      7%
```

- On 100%: the entire bar line (and any co-located components) **disappears**

### 5.2 No-ANSI Mode

Progress is communicated as plain lines at fixed thresholds only:

```
  ...Progress: 10%...
  ...Progress: 25%...
  ...Progress: 50%...
  ...Progress: 75%...
  ...Progress: 90%...
  ...Finished: 100%
```

---

## 6. Counter Component

Displays elapsed or remaining time, updated every second.

| Direction | Format | Example |
|---|---|---|
| Count-down | plain | `1m 25s` |
| Count-up | prefixed with `+` | `+14s` |

- Human-readable format: `1d 12h 3m 14s` (leading zero-value units omitted)
- Color enabled: **yellow** (`#FFC107`)
- Color disabled: plain text, no color
- **No-ANSI: counter is omitted entirely** (cannot update in place)

---

## 7. Primary Pipeline Layout

For build-pipeline commands (`jk build`, `jk test`, `jk compile`, etc.) the standard header line is:

```
{GoalWedge with spinner}{ProgressBar} {Counter}
```

Example (Nerd Font, color):

```
 ✸ Build ██▊                                      7% · +14s
```

The `·` separator between the bar percentage and the counter is dim/dark-gray.

---

## 8. ParallelTracker Component

Keeps the user updated on work distributed across many threads or processes.

### 8.1 Unit of Work (UoW)

Each item has: `name`, `phase`, `action`, `duration`, `status` (`working` | `complete`).

**Working UoW** (bottom item uses `╰─`, all others use `├─`):

```
 ╰─ dev.jkbuild:engine › Testing › relocking_detects_a_force_moved_tag(Path)
```

**Completed UoW**:

```
    ✓ [15 of 17] dev.jkbuild:compat-bridge took 34ms
```

### 8.2 Full ParallelTracker View

```
 ╰─ dev.jkbuild:engine › Testing › relocking_detects_a_force_moved_tag(Path)
    ✓ [15 of 17] dev.jkbuild:compat-bridge took 34ms
    ✓ [14 of 17] dev.jkbuild:toolchain took 28ms
    ✓ [13 of 17] dev.jkbuild:publisher took 86ms
    ✓ [12 of 17] dev.jkbuild:image-builder took 76ms
    ✓ [11 of 17] dev.jkbuild:git-client took 69ms
      … plus 10 more …
```

- Maximum **5** completed items shown; overflow collapses to `… plus N more …`
- When **all** UoW items are complete the entire component disappears

### 8.3 No-ANSI Mode

Each phase transition is a new line. Actions are not printed unless `-vv`:

```
[01 of 17] dev.jkbuild:toolchain > Compiling ...
[01 of 17] dev.jkbuild:toolchain > Testing ...
[01 of 17] dev.jkbuild:toolchain - Complete - took 76ms
```

---

## 9. Badge Component

A Badge is a labeled chip used for scope sections, index numbers, and similar short labels.

### Structure

```
{left-cap}{text}{right-cap}
```

| Part | Nerd Font glyph | Non-Nerd Font fallback |
|---|---|---|
| Left cap | U+E0B6 (left half-circle) | ` ` (one space) |
| Right cap | U+E0B4 (right half-circle) | ` ` (one space) |

- **Background**: `#90A4AE` (gray)
- **Foreground**: `#000000` (pure black)
- Cap foreground: `#90A4AE` (matches background, creating the pill effect)

Example (Nerd Font):  `main`  — visually a rounded gray pill with black text.

---

## 10. Dependency Coordinates

Maven / Cargo-style coordinates are always colored when color is enabled. The neutral separators
(`:`, `@`, `=`, `^`) use the default terminal foreground.

| Segment | Theme role | Color |
|---|---|---|
| group | `coordGroup()` | `#00BCD4` cyan |
| name / artifact | `coordName()` | `#18FFFF` bright-cyan |
| version | `coordVersion()` | `#C1FBFC` light cyan |

**Examples:**

```
org.apache.commons:commons-io:1.2.3       ← standard GAV
org.apache.commons:commons-io@=1.2.3      ← Cargo-style (same coloring)
dev.jkbuild:engine                         ← version absent, still colored
```

No-color and no-ANSI: plain text, no coloring.

---

## 11. File Paths

- Paths within the current project or workspace are displayed as **relative paths**
- All paths use the **`path()`** theme color (`#969DD4` periwinkle) when color is enabled
- Home directory is collapsed to `~` where applicable

---

## 12. Shell Commands

Command lines passed to subprocesses (e.g. `java -cp … -jar target/app.jar`) are styled with the
**`shell`** color (`#FF9800` orange) when color is enabled. No-color and no-ANSI render as plain text.

---

## 13. Plugin / Worker Output

Output collected from `jk` plugins or worker processes is always printed **above** the current
`jk`-controlled output region. It never interleaves with GoalWedge, ProgressBar, or
ParallelTracker lines.

---

## 14. Status Color Reference

Quick reference using role names from §2.1. See §2.10 for the foreground rule.

| State | Role (bg unset — fg only) | Role (bg set — chip) |
|---|---|---|
| Working / Info | `chip-working` as foreground | `chip-working` bg + white fg |
| Success | `chip-success` as foreground | `chip-success` bg + white fg |
| Error | `error-text` as foreground | `chip-error` bg + white fg |
| Warning | `warning-text` as foreground | — (always foreground-only) |

---

## 15. Tables

Used by commands such as `jk jdk list` and `jk cache info`.

### 15.1 Nerd Font / Non-Nerd Font (ANSI)

```
╭──────────────────────────────────────────────────────────────────────╮
│                  Installed Java Development Kits                     │
├─────────┬───────────────────┬─────────────────┬───────────┬──────────┤
│ Version │ Vendor            │ Spec            │ Status    │ Source   │
├─────────┼───────────────────┼─────────────────┼───────────┼──────────┤
│   26    │ Eclipse Temurin   │ temurin-26.0.1  │ installed │ jk       │
├─────────┼───────────────────┼─────────────────┼───────────┼──────────┤
│  25     │ Eclipse Temurin   │ temurin-25.0.3  │ active    │ intellij │
│         │ Microsoft OpenJDK │ ms-25.0.3       │ installed │ jdks     │
╰─────────┴───────────────────┴─────────────────┴───────────┴──────────╯
```

| Region | Style |
|---|---|
| **Title row** | Background: `#0F4786` (dark royal blue); Foreground: white; caps: U+E0B6 / U+E0B4 (Nerd Font) |
| **Header row** | Bold + bright-white foreground |
| **Selected / active row** | Background: `darkBlackColor()` (very dark gray band) |
| **Normal rows** | Default terminal colors |
| **Rails / borders** | Dark-gray (`#546E7A`) |

Non-Nerd Font: title row omits U+E0B6 / U+E0B4 pill caps; otherwise identical.

### 15.2 No-ANSI

```
+----------------------------------------------------------------------+
|                   Installed Java Development Kits                    |
+----------------------------------------------------------------------+
| Version | Vendor            | Spec            | Status    | Source   |
+---------+-------------------+-----------------+-----------+----------+
|   26    | Eclipse Temurin   | temurin-26.0.1  | installed | jk       |
+---------+-------------------+-----------------+-----------+----------+
```

- `+` corners, `-` horizontal lines, `|` vertical lines
- No color, no bold, no Unicode box-drawing characters

---

## 16. Trees

Used by `jk tree`, `jk explain`, and similar commands.

### 16.1 ANSI Mode

```
 ≡ Dependencies Tree ▶
 ● io.github.bryansant:jktest:0.1.0
 │
 ├─main
 │  ├─ foo:bar (missing)
 │  ╰─ org.jspecify:jspecify:1.0.0
 ├─provided
 │  ╰─ org.projectlombok:lombok:1.18.46
 ╰─processor
    ╰─ org.projectlombok:lombok:1.18.46
```

| Element | Style |
|---|---|
| Header (`≡ Dependencies Tree`) | GoalWedge using the Info/working chip color |
| Root bullet (`●`) | Dark-gray (`#546E7A`) |
| Root coordinate | Bold + coord colors (group/name/version) |
| Connecting lines (`│ ├─ ╰─`) | Dark-gray (`#546E7A`) |
| Scope/section label | **Badge component** (gray `#90A4AE` bg, black text, pill caps in Nerd Font) |
| Dependency coordinates | Coord colors (see §10) |

Non-Nerd Font: Badge pill caps (U+E0B6 / U+E0B4) replaced with plain spaces.

### 16.2 No-ANSI

```
 - Dependencies Tree:
 * io.github.bryansant:jktest:0.1.0
 |
 +-[main]
 |  +- foo:bar (missing)
 |  `- org.jspecify:jspecify:1.0.0
 +-[provided]
 |  `- org.projectlombok:lombok:1.18.46
 `-[processor]
    `- org.projectlombok:lombok:1.18.46
```

- Header: `- Name:` (dash + label + colon)
- Root: `*` prefix
- Branches: `+-` for non-terminal, `` `- `` for terminal
- Badges: `[label]` with square brackets

---

## 17. PubGrub Conflict Output

Dependency resolution conflicts use a quoted-block style.

### 17.1 ANSI Mode

```
‼ Cannot resolve dependencies.

 │ No versions of foo:bar match [1.0.0,2.0.0)
 │ The project depends on foo:bar [1.0.0,2.0.0)
 │ Therefore, the project's requirements cannot be resolved

These constraints are unsatisfiable together.
```

| Element | Style |
|---|---|
| `‼` header | Error red (`#E91E63`) |
| `│` rail | Bright-black (`#546E7A`) |
| Coord segments | Coord colors (§10) |
| Version constraints | `coordVersion()` color |

### 17.2 No-ANSI

```
! Cannot resolve dependencies.

  No versions of foo:bar match [1.0.0,2.0.0)
  The project depends on foo:bar [1.0.0,2.0.0)
  Therefore, the project's requirements cannot be resolved

These constraints are unsatisfiable together.
```

- `!` replaces `‼`
- Leading `│` rail replaced by two plain spaces
- No color

---

## 18. Wizard Component

`jk new` is the reference implementation for all wizards. All other wizards must use the same
header, step, and rail styling.

### 18.1 Header Line

```
 ≡ New Project ▶  Create a new project  ▶
```

| Part | Detail |
|---|---|
| Icon | `≡` (U+2261 identical-to / "burger menu") |
| Label | Goal verb (e.g. `New Project`, `New Module`, `Init`) |
| First cap | U+E0B0; foreground = chip color, background = `#90A4AE` (gray band) |
| Subtitle text | On `#90A4AE` background; foreground = black (`#000000`); re-applied after any ANSI reset |
| Closing cap | U+E0B0; foreground = `#90A4AE` (matches band), background unset |

Non-Nerd Font: first and closing caps are replaced by plain spaces; subtitle band is still colored.

No-Color: subtitle text has no background; chip has no color; plain `: ` separator.

No-ANSI: `* New Project: Create a new project` — all on one line, ASCII only.

### 18.2 Step Rail

```
 │
 ■  Module name:                   ← active step bullet (bright-cyan when active)
 │  ➜ untitled                     ← answer in italic, prefixed by ➜ in green
 │
 □  Project group:                 ← settled step bullet
 │  ➜ io.github.bryansant          ← settled answer (gray prompt label, green ➜)
 │
 ╰── Done
```

| Element | Style |
|---|---|
| Active bullet `■` | Bright-cyan (`#18FFFF`) via `activeStep()` |
| Settled bullet `□` | Dark-gray (`#546E7A`) |
| Active prompt text | Bold bright-white |
| Settled prompt text | Gray (`#90A4AE`) via `completedPrompt()` |
| Answer `➜` | Bright-green |
| Answer text | Italic, settled foreground |
| Rail lines (`│ ╰──`) | Dark-gray |
| Closer (`╰── Done`) | Success green on completion, error red on cancellation |

### 18.3 Cancellation

When the user presses Ctrl-C:

```
 ╰── ✘ Wizard canceled
```

- `✘` in error red, text in default foreground

### 18.4 No-ANSI Wizard

Wizards are not available in no-ANSI mode. Callers must provide a `--name`, `--group`, etc. flags
to supply required inputs non-interactively, or the command exits with a usage error.

---

## 19. Applying This Guide

When adding new output:

1. Identify whether it is pipeline output (use GoalWedge + optionally ProgressBar + Counter) or
   interactive (use Wizard components).
2. Choose the correct status semantic (§3) and apply the corresponding icon and colors.
3. Implement all four rendering paths: Nerd Font, Non-Nerd Font, No-Color, No-ANSI.
4. Dependency coordinates must always use §10 coloring when color is enabled.
5. Plugin/worker output must be routed above the controlled output region (§13).
6. Update this document when any of the above decisions change.
