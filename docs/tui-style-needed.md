# TUI Style Guide — Gaps and Exceptions

This document records output scenarios that are not covered by `docs/tui-style.md`, plus
compliance violations found during a cross-codebase audit.  It is a working document: each
section describes current behavior and the recommended treatment.  Accepted items will be merged
into `tui-style.md`; disputed items move to "Open Questions."

> **Status after Phases 1–3 (June 2026):**
> Part B (all 5 violations) and Part C questions 1, 3, 4, 5, 6, 7, 8 are resolved and removed.
> Scenarios A.3, A.4, A.6, A.7, A.8, A.10, A.11, A.13, A.14, A.16, A.17, A.19, A.21, A.22,
> A.23 are implemented and/or documented in `tui-style.md` — removed from this list.

---

## Part A — Uncovered Scenarios

These patterns exist in the codebase today but have no corresponding rule in the style guide,
or the rule exists but the code has not been updated to match it.

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

**Status:** Documented in `tui-style.md` §20 (JDK Download Progress).  `JdkDownloadBar.java`
has not been updated to match the documented pattern — the `·` separator is not in `darkGray()`,
and the label is not in `normalGray()`.

**Remaining work:** Update `JdkDownloadBar` to apply documented theme roles.

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

**Status:** Documented in `tui-style.md` §21 (Bare Confirmation Line).  Implementation uses
`completedStep()` for the check icon instead of `success()` as specified.

**Remaining work:** Change icon style from `completedStep()` to `success()`.  No-ANSI variant
(`+ <name> <verb> <path>`) not yet implemented.

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
‼ The default JDK is now set to temurin-25.0.1 (non-LTS fallback)
```

`‼` in `warning()`; "default" in `focused()`; identifier in `focused()`; `(non-LTS fallback)` in `darkGray()`.

**Status:** Non-LTS fallback line implemented with `BANG+warning()` (Phase 2).  The two named
sub-patterns from the original recommendation are not yet in `tui-style.md`:

- **soft informational notice** — parenthesized body text in `muted-text` (`normalGray()`), no icon.
- **dim-annotation** — trailing `(<qualifier>)` in `darkGray()`.

**Remaining work:** Add a "soft informational notice" and "dim-annotation" entry to `tui-style.md`.

---

### A.9  `jk cache prune` — Structured Summary Line

**Commands:** `jk cache prune`

**File:** `cli/src/main/java/dev/jkbuild/command/CacheCommand.java` (lines 521–555)

**Current output:**

```
Pruned: records expired 1,234 (45.6 MiB), temps 0 (0 B), run-logs 5 (1.2 KiB)
```

Entirely plain terminal foreground in all modes.

**Status:** Not addressed.

**Recommended treatment (structured summary line):**

- Format: plain `body-text` foreground; comma-separated `key N (size)` segments.
- Numbers may use `focused-text` for emphasis; zero-count segments may be omitted.
- No-ANSI / No-Color: same plain format (already compliant).
- Companion warning line (`Warning: evicted N reachable objects…`) on stderr: style with `‼` in
  `warning-text` amber and message in `body-text`.

---

### A.12  `jk sync` — Per-Module Cascade Lines

**Commands:** `jk sync` (workspace)

**File:** `cli/src/main/java/dev/jkbuild/command/SyncCommand.java` (lines 427–439)

**Current output:**

```
kernel/core: 5 fetched, 10 up-to-date, 0 skipped
```

Entirely plain terminal foreground.

**Status:** Not addressed.  (Phase 3.4b styled the post-chip summary lines but not the
per-module cascade lines printed during cascade processing.)

**Recommended treatment:**

- Module path (relative) in `path()` or `coordName()` depending on whether it is a path or a
  coordinate identifier.
- Counts in `focused-text`.
- Verb `fetched` in `success-text` green; `up-to-date` in `muted-text`; `skipped` in
  `muted-text`.
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

**Status:** Documented in `tui-style.md` §25 (JDK Status Column).  `JdkListCommand.java` not
yet updated — currently uses raw method calls rather than named constants; the composite
separator `/` is not yet colored `darkGray()`.

**Remaining work:** Update `JdkListCommand` to apply `darkGray()` to the `/` separator in
composite status labels.

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

**Status:** Documented in `tui-style.md` §29 (Verbose Module Separator).  Phase 2.3 applied
`darkGray()` to the `══` separator chars.  The module coord and `(k/N)` count are not yet
styled (`body-text` and `muted-text` respectively).

**Remaining work:** Apply `body-text` to the module name and `muted-text` to the `(k/N)` count.

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

**Status:** Not addressed.

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

**Status:** Not addressed.

**Recommended treatment:**

- `…` ellipsis: `separator` darkGray.
- "and N more" text: `muted-text`; N in `focused-text`.
- Parenthesized hint: `dim-text` (`dim()` faint attribute).
- Summary line numbers: `focused-text`.
- Summary line nouns: `body-text`.
- No-ANSI / No-Color: plain text (already compliant).

---

## Part C — Open Questions

---

### C.2  `errorLabel()` Theme Method

**Method:** `Theme.errorLabel()` / `JkDarkTheme.errorLabel()`

**Implementation:** `withColor(AttributedStyle.DEFAULT.bold(), NORMAL_RED)` — bold NORMAL_RED
(`#E91E63`), distinct from `error()` which is regular weight.

**Used in:** `CommandDispatch.java` (three sites — `"error:"` prefix and `"✘ Error:"` label),
`CacheCommand.java` (destructive purge body text).

**Status:** Method exists and is used but is absent from all §2 color tables in `tui-style.md`.
Phase 3.3 added §33 (`errorLabel() Role`) describing it as bold error-text used for longer
label-style strings.

**Remaining work:** Confirm the implementation matches the §33 documentation (bold `#E91E63`,
not a separate hue).  Add `errorLabel()` to the §2.1 Status & State table in `tui-style.md`
alongside `error()` with a note that it adds bold — removes the "absent from color tables" gap.
