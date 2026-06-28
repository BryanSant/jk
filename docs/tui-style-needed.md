# TUI Style Guide — Gaps and Exceptions

This document records output scenarios that are not covered by `docs/tui-style.md`, plus
compliance violations found during a cross-codebase audit.  It is a working document: each
section describes current behavior and the recommended treatment.  Accepted items will be merged
into `tui-style.md`; disputed items move to "Open Questions."

---

## Part A — Uncovered Scenarios

These patterns exist in the codebase today but have no corresponding rule in the style guide.

---

### A.1  JDK Download Progress (`JdkDownloadBar`)

**Commands:** `jk jdk install`, `jk jdk ensure`, `jk jdk update`

**File:** `cli/src/main/java/dev/jkbuild/cli/tui/JdkDownloadBar.java`

**Current output (Nerd Font):**

```
 ✷ JDK ▶[████████▓░░░░░░░░░░░] · Downloading Eclipse Temurin 26
```

Spinner/label slot uses the `goalChip()` background (`#0F4786`), animated, redrawn in place.
The `·` separator and trailing label are outside the chip.

**Current output (Non-Nerd Font):**

```
✷ JDK [████████▓░░░░░░░░░░░] · Downloading Eclipse Temurin 26
```

**Why not covered:** §7 defines the GoalWedge + ProgressBar + Counter layout for pipeline
commands.  `JdkDownloadBar` is an ad-hoc component: it uses the working-chip style for the
spinner/label but then attaches a standalone `ProgressBar` with no `Counter`, no time display,
and a muted label separated by a `·` — whereas in §7 the `·` separates the bar percentage from
the counter.  There is no section governing standalone download progress for discrete binary
fetches outside a Goal pipeline.

**Recommended treatment:**

- Prefix: working-chip style (`goalChip()` bg `#0F4786`, white fg) with the animated spinner and
  label `JDK`.
- Bar: standard ProgressBar from §5 (gradient fill, 40 slots, no Counter appended).
- Separator: `·` in `darkGray()` (`#546E7A`) — the `separator` role (§2.3).
- Label: `Downloading <displayName>` in `muted-text` (`normalGray()`, `#C5CAE9`).
- No-ANSI: single line `Downloading <displayName>...` on start; `Done.` on finish.
- No-Color: retain block characters and underline; no gradient.

---

### A.2  JDK Install Post-Download Result Line

**Commands:** `jk jdk install`

**File:** `cli/src/main/java/dev/jkbuild/command/JdkInstallCommand.java` (lines 433–459)

**Current output:**

```
✓ Eclipse Temurin 26 has been installed to ~/path/to/jdk
```

`✓` is `completedStep()` green; distribution name is `focused()` bold-white; "installed" is
`focused()`; path is `path()` periwinkle.  Uses `Glyphs.CHECK` directly, not a GoalWedge chip.

**Why not covered:** §4 covers GoalWedge chip lines; §3 covers success state inside chips.  This
is a free-standing summary line printed below the progress bar.  The guide defines no "bare
confirmation line" pattern for post-operation results printed outside the chip structure.

**Recommended treatment (bare confirmation line):**

```
✓ <bold-white name> <muted-text verb> <path-color path>
```

- `✓` uses `success()` (`#4CAF50` + bold) — the `success-text` role.
- Name uses `focused-text`.
- Verb uses `muted-text` (`normalGray()`).
- Path uses `path()` (`#969DD4`).
- No-Color: same structure, attributes only.
- No-ANSI: `+ <name> <verb> <path>`.

---

### A.3  Post-Command Advisory Line (`➜` outside a wizard)

**Commands:** `jk jdk install --make-default`

**File:** `cli/src/main/java/dev/jkbuild/command/JdkInstallCommand.java` (lines 303–315)

**Current output:**

```

➜ temurin-25.0.3 is now the default JDK
Add `jk hook bash` to activate JAVA_HOME on new shells.
```

`➜` is `brightGreen()` (`#69F0AE`); key term is `focused()`; second line is plain terminal
foreground.

**Why not covered:** §18.2 uses `➜` exclusively for settled wizard answers.  Here `➜` appears
outside any wizard, as a standalone post-command advisory.  The follow-up hint line has no
defined styling.

**Recommended treatment (post-command advisory):**

- `➜` in `brightGreen()` (`#69F0AE`) — same role as `wizard-answer-arrow` but used in a
  non-wizard context.
- Key term (identifier or noun being described) in `focused()`.
- Remainder of the line in `body-text` (`settled()`, `#CFD8DC`).
- Follow-up hint line (no icon): `muted-text` (`normalGray()`, `#C5CAE9`).
- No-Color: plain text.
- No-ANSI: omit `➜` prefix; print as plain text.

---

### A.4  `jk jdk graal` Confirmation Line

**Commands:** `jk jdk graal`

**File:** `cli/src/main/java/dev/jkbuild/command/JdkGraalCommand.java` (lines 85–91)

**Current output:**

```
➜ The native (default GraalVM) JDK is now set to GraalVM CE 25 (graalvm-ce-25.0.1)
```

`➜` is `brightGreen()`; "native" and "GraalVM CE 25" are `focused()`; the parenthesized
identifier `(graalvm-ce-25.0.1)` is `darkGray()`.

**Why not covered:** Same gap as A.3.  Additionally, the parenthesized secondary identifier in
`darkGray()` at the end of the line is a recurring sub-pattern with no named role.

**Recommended treatment:**

- Apply the post-command advisory pattern from A.3.
- Parenthesized secondary qualifier `(<id>)` uses `dim-annotation`: `darkGray()` (`#546E7A`),
  the `separator` / `rail` role.
- No-ANSI: plain text, drop `➜`, retain parenthesized qualifier.

---

### A.5  `jk jdk uninstall` — Non-LTS Fallback Default Line

**Commands:** `jk jdk uninstall`

**File:** `cli/src/main/java/dev/jkbuild/command/JdkUninstallCommand.java` (lines 444–454)

**Current output (no remaining JDKs):**

```
(no remaining JDKs — global default cleared)
```

in `normalGray()`.

**Current output (non-LTS fallback):**

```
➜ The default JDK is now set to temurin-25.0.1 (non-LTS fallback)
```

`➜` brightGreen; "default" focused; identifier focused; `(non-LTS fallback)` in `darkGray()`.

**Why not covered:** The `normalGray()` parenthesized notice line is an inline informational
notice that maps to none of the defined semantic roles (not warning, error, or success).  The
`(non-LTS fallback)` qualifier pattern recurs across JDK commands without a defined name.

**Recommended treatment:**

- Parenthesized-notice variant: plain body text wrapped in literal `( )` characters, no icon.
  Color: `muted-text` (`normalGray()`, `#C5CAE9`).  Named role: **soft informational notice**.
- `(<qualifier>)` trailing annotation: `darkGray()` (`#546E7A`).  Named role: **dim-annotation**
  (same as A.4).
- Both: No-Color and No-ANSI render as plain text.

---

### A.6  `jk jdk update` — Per-Item Result Lines

**Commands:** `jk jdk update`

**File:** `cli/src/main/java/dev/jkbuild/command/JdkUpdateCommand.java`

**Current output:**

```
• temurin-25.0.2 — no update available in the feed
✓ Updated temurin-25.0.2 → temurin-25.0.3
✗ Failed to update temurin-25.0.2: <message>
```

- `•` in `darkGray()`; identifier in `cyan()`; message in `normalGray()`.
- `✓` in `completedStep()` green; both identifiers in `cyan()`; `→` in plain terminal fg.
- `✗` in `error()` pink-red; failed identifier in `warning()` amber.

**Why not covered:** The `•` bullet marker in `darkGray()` and the `→` migration arrow have no
documented roles.  The per-item result pattern `{icon} verb {old} → {new}` does not appear in
the guide.  Using `warning()` amber for the subject of a failed operation (rather than error red)
is an intentional semantic choice not recorded anywhere.

**Recommended treatment:**

- `•` bullet: `separator` / `darkGray()` (`#546E7A`).
- `→` migration arrow: `separator` / `darkGray()`.  Left side (source) in `link` / `cyan()`;
  right side (destination) in `focused-text`.
- Success result: `✓` via `success-text`; identifiers in `link`.
- Failure result: `✗` via `error-text`; subject identifier in `warning-text` amber to
  distinguish the target from the reason text.  Reason message in `muted-text`.
- No-ANSI:
  - No update: `- <id>: no update available`
  - Success: `+ Updated <old> to <new>`
  - Failure: `! Failed <old>: <msg>`

---

### A.7  Inline Confirmation Prompt (`Confirm` widget)

**Commands:** `jk jdk update`, `jk cache purge`, and any command requiring interactive confirmation

**File:** `cli/src/main/java/dev/jkbuild/command/JdkUpdateCommand.java` (lines 306–318)

**Current output:**

```

The following 2 JDKs will be updated:
   temurin-25.0.2 → temurin-25.0.3
   openjdk-21.0.5 → openjdk-21.0.6
‼ Proceed? [Y/n]
```

Header line in plain terminal fg; identifiers in `cyan()` and `focused()`; `‼` in `warning()`.
`[Y/n]` default indicated by capitalisation.

**Why not covered:** §18 covers multi-step Wizards (rail pattern).  `Confirm` is a single-line
inline prompt with `‼` as the icon, a question in body-text, and `[Y/n]` as the suffix.  No
section defines this pattern.

**Recommended treatment (Confirm prompt):**

```
‼ <question text>? [Y/n]
```

- `‼` in `warning-text` amber (`#FFC107`).
- Question text in `body-text`.
- `[Y/n]` suffix: brackets in `separator` darkGray; default option capitalised and in
  `focused-text`; non-default in `muted-text`.
- No-TTY / No-ANSI: the command must accept `-y` / `--yes`; if neither is provided, print an
  error and exit non-zero.
- No-Color: same layout, attributes only.

---

### A.8  `jk cache info` — Utilization Row with Embedded ProgressBar

**Commands:** `jk cache info`

**File:** `cli/src/main/java/dev/jkbuild/command/CacheCommand.java` (lines 278–286)

**Current output (inside box-drawn table):**

```
│ Utilization  [████████▓░░░░░░░░░░░] 43% │
```

A ProgressBar is embedded inline within a table row that spans the full inner table width.

Additionally, the `CacheInfoCommand` title row uses `activeStep()` (`#18FFFF` bright-cyan) rather
than `goalChip()` (`#0F4786`), which differs from the §15 title-row specification.

**Why not covered:** §15 (Tables) specifies the title row, header row, selected row, and borders.
§5 (ProgressBar) specifies standalone bar behavior.  No rule covers embedding a ProgressBar
inside a table row.  The title-row color inconsistency is an undocumented deviation.

**Recommended treatment:**

- Embedded utilization row: full-width footer row; label `Utilization` in `body-text`; inline
  ProgressBar (§5, no Counter); percentage suffix in `focused-text`.
- Title-row color: flag the `activeStep()` usage as a **bug** — align with §15 (`goalChip()` bg
  `#0F4786`, white fg).
- No-ANSI: `Utilization: 43%` as plain text.

---

### A.9  `jk cache prune` — Structured Summary Line

**Commands:** `jk cache prune`

**File:** `cli/src/main/java/dev/jkbuild/command/CacheCommand.java` (lines 521–555)

**Current output:**

```
Pruned: records expired 1,234 (45.6 MiB), temps 0 (0 B), run-logs 5 (1.2 KiB)
```

Entirely plain terminal foreground in all modes.

**Why not covered:** This is an intentionally unstyled machine-parseable summary line.  The guide
says nothing about structured summary lines that must remain grep-friendly.

**Recommended treatment (structured summary line):**

- Format: plain `body-text` foreground; comma-separated `key N (size)` segments.
- Numbers may use `focused-text` for emphasis; zero-count segments may be omitted.
- No-ANSI / No-Color: same plain format (already compliant).
- Companion warning line (`Warning: evicted N reachable objects…`) on stderr: style with `‼` in
  `warning-text` amber and message in `body-text`.

---

### A.10  `jk cache purge` — Destructive Confirmation Block

**Commands:** `jk cache purge`

**File:** `cli/src/main/java/dev/jkbuild/command/CacheCommand.java` (lines 656–669)

**Current output:**

```

‼ This permanently deletes the ENTIRE jk cache.
  ~/.cache/jk
  1,234 files, 45.6 GiB — every cached dependency, CAS blob, and the m2 repo mirror.
  jk will re-download everything on the next build.
‼ Purge the whole cache? [y/N]
```

Header `‼` and text use `errorLabel()` — a method that appears in the codebase but is absent
from the §2 color tables.  The confirmation uses the `Confirm` widget (A.7) with default=`false`
(capital N).

**Why not covered:** The "destructive-action notice block" (multi-line warning with indented path
and stats) is a new pattern.  `errorLabel()` is used but not defined in the style guide.

**Recommended treatment (destructive confirmation block):**

- Header `‼` and first notice line: `error-text` (`error()`, `#E91E63`).
- Indented detail lines (path, stats, consequence): `body-text`.
- Path: `path()` (`#969DD4`).
- Confirmation prompt: `Confirm` widget (A.7) with default=`false`.
- `errorLabel()`: audit the implementation — if it resolves to the same value as `error()`,
  remove the alias and document that destructive confirmations use `error-text`; if it is a
  distinct style, add it to §2.8 with a precise hex value and usage note.
- No-ANSI: plain text list; require `-y` / `--yes` flag.

---

### A.11  `jk sync` — Post-Chip Summary Lines

**Commands:** `jk sync`

**File:** `cli/src/main/java/dev/jkbuild/command/SyncCommand.java` (lines 447–478)

**Current output (after the GoalWedge settles):**

```
Workspace: 5 modules
Created jk.lock (42 packages)
JDK: temurin-25.0.3 (already installed)
14 fetched, 203 up-to-date, 0 skipped
Workers: 2 present, 1 fetched, 0 missing
```

All plain terminal foreground except paths, which use `path()` via `PathDisplay.styledRaw()`.

**Why not covered:** §4 covers the GoalWedge component itself.  The guide has no "post-chip
summary" pattern for commands that emit multiple result lines beneath their chip.

**Recommended treatment (post-chip summary):**

- Each summary line in `body-text`.
- Key counts and identifiers in `focused-text` (bold bright-white).
- Paths in `path()` (`#969DD4`).
- Verb segments (`fetched`, `up-to-date`) in `muted-text`.
- No-ANSI: plain text (already effectively compliant).
- No-Color: same structure, bold attributes only.

---

### A.12  `jk sync` — Per-Module Cascade Lines

**Commands:** `jk sync` (workspace)

**File:** `cli/src/main/java/dev/jkbuild/command/SyncCommand.java` (lines 427–439)

**Current output:**

```
kernel/core: 5 fetched, 10 up-to-date, 0 skipped
```

Entirely plain terminal foreground.

**Why not covered:** Per-module sync result lines printed during cascade processing are
completely unstyled.  No rule covers these.

**Recommended treatment:**

- Module path (relative) in `path()` or `coordName()` depending on whether it is a path or a
  coordinate identifier.
- Counts in `focused-text`.
- Verb `fetched` in `success-text` green; `up-to-date` in `muted-text`; `skipped` in
  `muted-text`.
- No-ANSI: plain text (already compliant).

---

### A.13  `jk activate` — Bare Confirmation and Skipped Variant

**Commands:** `jk activate`

**File:** `cli/src/main/java/dev/jkbuild/command/ActivateCommand.java` (lines 87–90, 119–122)

**Current output (already wired):**

```
✓ jk activation is already wired up in ~/.zshrc
```

`✓` in `completedStep()` green; RC filename in `focused()`; rest in plain terminal fg.

**Current output (skipped):**

```
Skipped — paste this into your ~/.zshrc when you're ready:
  eval "$(jk activate zsh)"
```

Entirely unstyled.

**Current output (after append):**

```
✓ appended jk activation to ~/.zshrc
Open a new shell (or run `source ~/.zshrc`) to pick up the change.
```

`✓` in `completedStep()` green; filename in `focused()`; second line plain terminal fg with
backtick-quoted command unformatted.

**Why not covered:** Same "bare confirmation line" gap as A.2.  The "Skipped" variant, the inline
shell snippet in advisory prose, and the source-hint line all have no styling rules.

**Recommended treatment:**

- `✓` confirmation: bare confirmation line pattern (A.2).
- "Skipped" line: `‼` in `warning-text` amber — the user chose not to apply the change, which
  is a soft-warning state.
- Inline shell command in advisory prose (e.g. `` `source ~/.zshrc` ``): `shell` orange
  (`#FF9800`) — same role as §12, applied inline within a prose sentence.
- Source hint / follow-up advisory: `muted-text` (`normalGray()`).
- No-Color / No-ANSI: plain text; backtick quoting retained.

---

### A.14  `jk doctor` — Tool Inventory Lines

**Commands:** `jk doctor`

**File:** `cli/src/main/java/dev/jkbuild/command/DoctorCommand.java`

**Current output:**

```
ok:       mvn 3.9.9
linked:   gradle 8.10 → /home/user/.sdkman/candidates/gradle/8.10/
pruned:   kotlin 1.9.0 (link target missing: /path/to/kotlin)
verified: gradle 8.10 (sha256-tree=abc123def456…)
---
3 healthy, 1 pruned, 1 fingerprinted
```

Entirely plain terminal foreground.  Fixed-width verb prefix padded to 10 chars.

**Why not covered:** `jk doctor` output is fully unstyled.  The fixed-width verb-prefix format is
a variant of the tabular pattern not covered by §15.  `pruned` and `verified` have clear semantic
states (error and success) but carry no color.

**Recommended treatment:**

- `ok:` and `verified:` verb prefix: `success-text` green (`#4CAF50`).
- `linked:` verb prefix: `body-text`.
- `pruned:` verb prefix: `warning-text` amber (`#FFC107`), because the tool is unusable but was
  auto-remediated rather than hard-failing.
- Symlink target `→ <path>`: `→` in `separator` darkGray; path in `path()`.
- Separator line `---` and summary line: `separator` darkGray.
- No-ANSI: plain text (already compliant).

---

### A.15  `jk jdk list` — Status Column Semantic Colors

**Commands:** `jk jdk list`

**File:** `cli/src/main/java/dev/jkbuild/command/JdkListCommand.java` (lines 555–574)

**Current styles:**

| Status label | Method | Hex |
|---|---|---|
| `active` | `brightCyan().bold()` | `#18FFFF` |
| `default` | `brightYellow()` | `#FFD54F` |
| `native` | `brightGreen()` | `#69F0AE` |
| `installed` | `completedStep()` | `#4CAF50` |
| `available` | `darkGray()` | `#546E7A` |

Composite `active/default` label uses `/` as separator; its color is not documented.

**Why not covered:** §15 specifies structural table regions (title row, header, selected row,
borders) but says nothing about status-cell semantic coloring in data rows.  §2.1 lists
`jdk-status-default` (`brightYellow()`) but does not document the full palette.

**Recommended treatment:**

Expand §2.1 or add a sub-section to §15 with the full JDK status palette:

| Status | Role name | Method | Hex |
|---|---|---|---|
| `active` | `jdk-status-active` | `brightCyan().bold()` | `#18FFFF` |
| `default` | `jdk-status-default` | `brightYellow()` | `#FFD54F` |
| `native` | `jdk-status-native` | `brightGreen()` | `#69F0AE` |
| `installed` | `jdk-status-installed` | `completedStep()` | `#4CAF50` |
| `available` | `jdk-status-available` | `darkGray()` | `#546E7A` |

Composite label: statuses separated by `/` in `separator` darkGray (`#546E7A`).

---

### A.16  `jk auth login` — Device Flow Prompt Block

**Commands:** `jk auth login`

**File:** `cli/src/main/java/dev/jkbuild/command/AuthLoginCommand.java` (lines 134–142)

**Current output:**

```

  First copy your one-time code: ABCD-1234
  Then open:                     https://github.com/login/device

  Waiting for authorization…
```

Entirely plain terminal foreground; two-space indent; fixed-width label alignment.

**Why not covered:** This block is not a Wizard (no rail), not a GoalWedge, and uses no theme
colors.  No section defines a "device flow prompt" pattern.

**Recommended treatment (device flow prompt):**

- One-time code value (`ABCD-1234`): `focused-text` (bold bright-white), because it is the
  critical user action item.
- URL: `link` cyan (`#00BCD4`).
- "Waiting for authorization…" line: `muted-text` with an animated spinner (or static `…` in
  No-ANSI) prefix.
- No `‼` icon — this is informational, not a warning.
- No-Color: same layout, no color, bold retained on the code.
- No-ANSI: plain text.

---

### A.17  `jk auth status` — Per-Forge Status Lines

**Commands:** `jk auth status`

**File:** `cli/src/main/java/dev/jkbuild/command/AuthStatusCommand.java`

**Current output:**

```
github     github.com — authenticated (jk login)
gitlab     (pass --host to check)
gitea      gitea.example.com — not authenticated
```

Entirely plain terminal foreground; forge name left-padded to 10 chars.

**Why not covered:** This tabular format differs from §15 (box-drawn tables).  Authenticated vs.
unauthenticated states carry no semantic color.

**Recommended treatment:**

- Forge name: `link` cyan (`#00BCD4`).
- Host: `body-text`.
- `authenticated` state: `✓` in `success-text` green; status text in `success-text`.
- `not authenticated` state: `✘` in `error-text` pink-red; status text in `error-text`.
- Unknown / deferral line (e.g. "pass --host to check"): `muted-text`.
- No-Color: same layout, no color.
- No-ANSI:
  - `+ github (github.com): authenticated`
  - `- gitea (gitea.example.com): not authenticated`

---

### A.18  `jk build` — Verbose Module Separator

**Commands:** `jk build --verbose`, `jk build --output json`

**File:** `cli/src/main/java/dev/jkbuild/command/BuildCommand.java` (lines 654–656)

**Current output:**

```
══ kernel/core (1/5) ══
```

Plain terminal foreground; `═` box-drawing chars as a horizontal divider between serial module
blocks in verbose mode.

**Why not covered:** §15 covers box-drawn tables; §16 covers trees.  Inter-module horizontal
dividers in verbose/non-TUI output are not addressed.

**Recommended treatment (verbose module separator):**

```
══ <module> (<k>/<N>) ══
```

- `═` characters: `rail` darkGray (`#546E7A`).
- Module name: `body-text`.
- `(<k>/<N>)` count: `muted-text`.
- No-ANSI: `--- <module> (<k>/<N>) ---` in plain text.
- No-Color: same Unicode chars, no color.

---

### A.19  `jk build` — Workspace Unit Completion Line

**Commands:** `jk build` (workspace parallel)

**File:** `cli/src/main/java/dev/jkbuild/command/BuildCommand.java` (lines 594–614)

**Current output (success):**

```
✓ [01 of 16] group:artifact ~~strikethrough~~ took 1.2s
```

**Current output (failure):**

```
✗ [01 of 16] group:artifact — failed
```

- `✓` green; `[01 of 16]` brackets in `darkGray()`; on success: module coord with
  `crossedOut()` strikethrough in `plainWhite()`, then dim italic `took …`.
- `✗` red error; coord in full coord colors (`coloredModule()`); `— failed` in `error()`.

**Why not covered:** `crossedOut()` strikethrough on a completed coord is a unique visual
convention not documented anywhere.  The `[k of N]` bracket-count pattern has no named component.

**Recommended treatment:**

- `[k of N]` count bracket: named component.  Brackets `[` and `]` in `separator` darkGray;
  zero-padded numerator and ` of ` + total in `body-text`.
- Completed item: `crossedOut()` + `plainWhite()` on the coord.  Document the deliberate design
  intent: strikethrough signals "done and removed from concern."
- Failed item: coord in standard §10 coord colors; `— failed` in `error-text`.
- Elapsed time (`took …`): `dim-text` (`dim()` faint attribute).
- No-ANSI:
  - `[k/N] group:artifact: OK  took 1.2s`
  - `[k/N] group:artifact: FAILED`

---

### A.20  Auto-Lock Warning (stderr, outside a goal listener)

**Commands:** `jk build`, `jk sync` (when `jk.toml` changed since last lock)

**File:** `kernel/engine/src/main/java/dev/jkbuild/runtime/AutoLock.java` (lines 120–121)

**Current output (stderr, no ANSI):**

```
jk: auto-lock warning — could not update jk.lock: <exception message>
    Run `jk lock` to resolve manually.
```

Plain `System.err`, no ANSI styling, `jk:` prefix.

**Why not covered:** The guide has no provision for engine-layer warnings emitted to stderr
outside any goal listener.

**Recommended treatment:**

- When inside a goal listener: route via the listener's `warn()` callback so the warning appears
  inline with the TUI.
- When on stderr (fallback):
  - `‼` in `warning-text` amber.
  - Message in `body-text`.
  - Path (`jk.lock`) in `path()`.
  - Follow-on hint line: `muted-text`; inline command (`jk lock`) in `shell` orange.
- No-Color / No-ANSI: keep plain text (stderr bypass is appropriate; already compliant).
- Document: engine-layer warnings must prefer the goal listener's `warn()` callback; fall back to
  this styled stderr format only when not inside a goal.

---

### A.21  `jk tool list` — Plain Tabular Tool Inventory

**Commands:** `jk tool list`

**File:** `cli/src/main/java/dev/jkbuild/command/ToolListCommand.java`

**Current output:**

```
myapp                    com.example:myapp:1.0.0
                         → ~/.jk/bin/myapp
```

Plain terminal foreground; `%-24s` left-pad alignment; `→` path prefix plain.

**Why not covered:** §15 covers box-drawn tables; §16 covers trees.  This is a borderless
two-column inventory.  The `→` launcher path prefix has no documented role.

**Recommended treatment:**

- Tool name: `link` cyan (`#00BCD4`).
- Coordinate: §10 coord colors.
- `→` prefix: `separator` darkGray (`#546E7A`).
- Launcher path: `path()` (`#969DD4`).
- No-Color / No-ANSI: plain text (already effectively compliant).

---

### A.22  `jk jdk pin` — Bare Success Confirmation (unstyled)

**Commands:** `jk jdk pin`

**File:** `cli/src/main/java/dev/jkbuild/command/JdkPinCommand.java` (line 73)

**Current output:**

```
Pinned project to temurin-25
```

Plain terminal foreground; no icon; no color.

**Why not covered:** This is a successful mutating operation with no `✓` icon and no styling,
inconsistent with all other JDK commands.

**Recommended treatment:**

- Apply the bare confirmation line pattern from A.2:
  `✓ Pinned project to <focused pin-name>`
- No-ANSI: `+ Pinned project to <name>`.

---

### A.23  `ProgressBarListener` — Pipeline-Mode Progress Line

**Commands:** `jk lock`, `jk sync`, and other non-build pipeline commands

**File:** `cli/src/main/java/dev/jkbuild/cli/run/ProgressBarListener.java`

**Current output:**

```
· ▰▰▰▰▰▰▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱ [ 25%] 5 of 20 › Lock › parse-build Resolving…
```

`▰`/`▱` block chars (not `█`/`▏▎▍▌▋▊▉`); percent in bold-white gradient-tinted; `›` in
`darkGray()`; goal name in `brightGreen().bold()`; phase name in bold; step message in
`normalGray()`.

**Why not covered:** §7 specifies the GoalWedge + ProgressBar + Counter layout.  §5 specifies
`█`/`▏▎▍▌▋▊▉` block chars.  `ProgressBarListener` uses `▰`/`▱`, has no Counter/clock, and uses
`›` rather than the §7 `·` separator — creating an undocumented third format.

**Recommended treatment:**

Either:

1. **Align** `ProgressBarListener` with §5 (use `█`/`▏▎▍▌▋▊▉` block chars, add Counter) and
   treat it as the same GoalWedge + ProgressBar + Counter layout, or
2. **Document** it explicitly as a "pipeline-mode progress line" — a lighter variant for commands
   without a separate counter:
   - `·` leading separator: `separator` darkGray.
   - `▰`/`▱` fill chars: document alongside §5 as a narrower bar variant.
   - `[ N%]` percentage: `focused-text`.
   - Count segment `5 of 20`: `body-text`.
   - `›` separator: `separator` darkGray.
   - Goal name: `brightGreen().bold()`.
   - Phase name: bold `body-text`.
   - Step message: `muted-text`.
   - No-ANSI: each state transition as a new plain-text line.

The choice between options 1 and 2 is an open question (see Part C).

---

### A.24  `jk cache search` — Continuation and Summary Lines

**Commands:** `jk cache search`

**File:** `cli/src/main/java/dev/jkbuild/command/CacheCommand.java` (lines 355–366)

**Current output:**

```
com.example:mylib  1.0.0, 1.1.0, 2.0.0
… and 5 more (pass --limit 20 or refine the search)
3 coordinates, 8 versions cached
```

Coord + version segments use §10 colors.  Continuation and summary lines are plain terminal
foreground.

**Why not covered:** The `… and N more` line and the `N coordinates, M versions cached` summary
line have no documented styling.

**Recommended treatment:**

- `…` ellipsis: `separator` darkGray.
- "and N more" text: `muted-text`; N in `focused-text`.
- Parenthesized hint: `dim-text` (`dim()` faint attribute).
- Summary line numbers: `focused-text`.
- Summary line nouns: `body-text`.
- No-ANSI / No-Color: plain text (already compliant).

---

## Part B — Compliance Violations

These are cases where the current implementation conflicts with an existing rule in `tui-style.md`.
Each violation needs a code fix; the guide does not need to change.

---

### B.1  Error Icon: `Glyphs.CROSS` is `‼` (U+203C), must be `✘` (U+2718)

**Rule:** §3 states explicitly: "`✘` (U+2718) is the error icon everywhere. ‼ is reserved for
warnings only."

**File:** `cli/src/main/java/dev/jkbuild/cli/tui/Glyphs.java`, line 17

```java
public static final String CROSS = "‼";   // wrong — must be "✘"
```

**All affected call sites (via `Glyphs.CROSS`):**

| File | Lines | Context |
|---|---|---|
| `GoalWedge.java` | 78, 85, 98, 105 | failure and cancel chip/head |
| `ConsoleSpec.java` | 60 | `errorLine()` renders `Glyphs.CROSS + " Error"` |
| `CommandManager.java` | 363 | `finishFailure()` |
| `JdkUninstallCommand.java` | 361 | "Failed to remove" error line |

**Additional direct-literal violations (not using `Glyphs.CROSS`):**

| File | Line | Current | Should be |
|---|---|---|---|
| `SpinnerProgressBar.java` | 211 | `"✗ Failed"` (U+2717) | `"✘ Failed"` (U+2718) |
| `GlobalCancel.java` | 42 | `"‼ "` with `error()` | `"✘ "` — cancel is error state |
| `CacheCommand.java` | 659 | `"‼"` with `t.error()` | `"✘"` |
| `CommandDispatch.java` | 236 | `"‼ Error:"` with `errorLabel()` | `"✘ Error:"` |

**Fix:** Change `Glyphs.CROSS = "✘"` (U+2718).  Update the three direct-literal violations.
All call sites that pass `Glyphs.CROSS` to an error-colored render will be corrected
transitively.

---

### B.2  Warning Icon: `⚠` (U+26A0) used where `‼` (U+203C) is required

**Rule:** §3 table — the warning icon is `‼` in all modes (Nerd Font and Non-Nerd Font).
`⚠` (U+26A0, warning sign) is not in the style guide icon set.

**Affected files:**

| File | Line | Current | Should be |
|---|---|---|---|
| `VerboseListener.java` | 77 | `"⚠"` with `warning()` | `"‼"` |
| `GoalConsole.java` | 160 | `"⚠ "` (no color, buffered) | `"‼"` |
| `ProgressBarListener.java` | 157 | `"⚠ Warning"` prefix | `"‼ Warning"` |
| `AggregateModuleListener.java` | 107 | `"⚠ Warning"` prefix | `"‼ Warning"` |
| `AddCommand.java` | 539 | `"⚠"` with `warning()` | `"‼"` |
| `NewCommand.java` | 671 | `"⚠"` with `warning()` | `"‼"` |

**Fix:** Replace all `"⚠"` literals with `"‼"` (U+203C) or route through a
`Glyphs.WARNING = "‼"` constant.

---

### B.3  Coord Separator Colored `darkGray()` Instead of Default Terminal Foreground

**Rule:** §10 and §2.2 — neutral separators (`:`, `@`, `^`, `=`) use the **default terminal
foreground**.

**File:** `cli/src/main/java/dev/jkbuild/command/ExplainCommand.java`, line 330

```java
sb.append(Theme.colorize(":", t.darkGray())).append(Theme.colorize(p.substring(1), t.coordName()));
```

`":"` is colored `darkGray()` (`#546E7A`); it must be uncolored (default terminal fg).

**Fix:** Remove the `Theme.colorize(":", t.darkGray())` call; emit the `":"` as an uncolored
string.

---

### B.4  Focused Radio/Checkbox Bullet Uses `completedStep()` Instead of `activeStep()`

**Rule:** §2.6 — "Active step bullet (■) and focused radio/checkbox items" use
`wizard-active-step` → `activeStep()` (`#18FFFF` bright-cyan).  `completedStep()` (`#4CAF50`
green) is reserved for items that are already checked/selected.

**File:** `cli/src/main/java/dev/jkbuild/cli/tui/Wizard.java`

Lines 743, 765, 782, 830, 832, 853, 855, 870, 872 — pattern:

```java
isFocused ? Theme.active().completedStep() : Theme.active().darkGray()
```

The focused (but not yet committed) radio/checkbox bullet renders green when it should render
bright-cyan.

**Fix:** Replace `completedStep()` with `activeStep()` in the `isFocused` branch.  Reserve
`completedStep()` for items where `isSelected` is true.

---

### B.5  Wizard Cancellation Missing `✘` Glyph

**Rule:** §18.3 — cancellation renders as:

```
 ╰── ✘ Wizard canceled
```

`✘` in error red must appear before the cancel text.

**File:** `cli/src/main/java/dev/jkbuild/cli/tui/Wizard.java`, lines 196–198

```java
var line = new AttributedStringBuilder()
        .append(" " + message, Theme.active().error())
        .toAttributedString();
```

The `✘` glyph is absent; only the message text is rendered in error red.

**Fix:** Prepend `Glyphs.CROSS + " "` (which becomes `✘ ` after B.1 is fixed) before `message`
in the `printCancellation` method.

---

## Part C — Open Questions

These decisions require discussion before the style guide can be updated.

1. **`ProgressBarListener` bar characters** — Should `▰`/`▱` be standardised alongside `█`/`▏▎▍▌▋▊▉` from §5 as an explicit "narrow bar" variant, or should `ProgressBarListener` be refactored to use the §5 characters?  The visual distinction is meaningful (narrow vs. wide bar) but is not intentional — it is an implementation accident.

2. **`errorLabel()` theme method** — The method is used in `CommandDispatch.java` but is absent from all §2 color tables.  Is it the same as `error()`, or is it a distinct style (e.g. bold red)?  If distinct, it needs a named role and hex value.  If identical, the alias should be removed.

3. **`➜` in post-command advisories** — The `➜` arrow is currently documented only as the `wizard-answer-arrow` role (§2.6, §18.2).  Reusing it in non-wizard post-command confirmations (A.3, A.4) is consistent with the implementation but extends its semantic from "settled wizard answer" to "affirming change summary."  This should be ratified or a distinct role name introduced.

4. **`jk cache info` title row color** — `CacheInfoCommand` uses `activeStep()` (`#18FFFF`) for the title row, violating §15 (`goalChip()` bg `#0F4786`).  Confirm this is a bug and not an intentional differentiation for the cache-info table.

5. **`GlobalCancel` error icon** — `GlobalCancel.java` (line 42) emits a `‼` with `error()` color for process-level cancellation.  Cancellation is conceptually distinct from a task failure.  Confirm that the B.1 fix (change to `✘`) is correct, or define a separate "cancel" semantic with its own icon.

6. **`jk activate` "Skipped" severity** — The "Skipped" variant (A.13) is proposed as a `warning-text` + `‼` state because the activation was not applied.  However, "skipped by user choice" may warrant a softer treatment (e.g. `muted-text` + `•`) rather than a warning.  Confirm intended severity.

7. **`jk doctor` `pruned:` severity** — `pruned` means the tool entry was auto-removed because the symlink target is missing.  Proposed as `warning-text` amber because it is a self-corrected anomaly.  Confirm this is not an error-severity state that warrants `error-text` red.

8. **Post-command advisory `➜` vs. bare confirmation `✓`** — A.2 uses `✓` for "this thing succeeded"; A.3/A.4 use `➜` for "the state has changed."  Are these two separate patterns, or should all post-operation confirmations unify on one icon?  The current split appears intentional (JDK install uses both) but is undocumented.
