# CommandManager TUI — Implementation Plan

Branch: `feat/command-manager-tui` · worktree: `/home/bsant/src/oss/jk-command-manager`

A new TUI component, `CommandManager`, with two presentation modes — **simple task**
and **goal-oriented** — that replaces the current goal/phase TUI for `jk build` and the
other build-like commands, and is **workspace-aware**: it aggregates *all* goals and
phases across *all* modules into a single progress bar and a single phase list.

---

## 1. Decisions (locked)

- **Workspace aggregation:** *shared aggregate listener*. Keep today's one-`Goal`-per-module
  model and sequential module ordering; `CommandManager` subscribes to every module goal and
  sums ticks/estimates into one bar and one merged phase list. (Lowest blast radius on the
  core `Goal` engine.)
- **Phase list labeling:** *module carried in the `›` path*. The header shows the active
  module; each phase line is `{module} › {Phase} › {message}` so interleaved phases from
  different modules stay unambiguous.
- **Mode mapping:** build-like commands → goal view (`build`, `test`, `compile`, `native`,
  `image`, `publish`, `verify-build`, `audit`). Everything else that runs a Goal → simple
  spinner (`lock`, `sync`, `add`, `update`, `clean`, `deny`, `install`, `tool-install`,
  `run`, `script`).

---

## 2. Target UX

### 2.1 Simple-task mode
While running (existing `Spinner` frames, primary→accent gradient, 120 ms):
```
{spinner} Locking…
```
On completion the spinner freezes to its **first glyph** (`·`) and a result line is printed:
```
[green]✓[/] Finished syncing 13 artifacts
```
or
```
[red]✗[/] Failed to sync remote artifacts
```
`…`, the verb (`Locking`/`Syncing`/`Adding`…) and the success/failure message are
command-supplied.

### 2.2 Goal-oriented mode
```
{spinner} Building › acme:api… [bright-black](1m 52s)[/]
▰▰▰▰▰▰▰▰▰▰▰▰▰▰▰▰▰▰▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱ 45% [bright-black][[/]N of D[bright-black]][/]
╰─ ◻ api › Compile › javac 12 sources
   ◻ web › Sync deps › fetching guava
   ✓ api › Parse build › 
   ✓ api › Ensure JDK › 
   … +2 completed
```
- `[ ]` brackets and every `›` separator: **bright-black**. Elapsed `(…)`: bright-black.
- Phase glyphs: `◻` (active/pending), `✓` green (done), `✗` red (failed).
- The `╰─` connector prefixes the **first** list row (bright-black); subsequent rows align
  under it.
- The list **floats**: outstanding (active/pending) rows on top in start order, completed
  rows sink to the bottom; when rows exceed the cap, completed rows collapse to
  `… +N completed`.
- Bar is the aggregate of *all* modules' estimated phase steps using the existing
  tick/estimate/actual + gap-fill machinery (`GoalView.numerator/denominator`).

On completion the whole live region is replaced with a single line:
```
[green]✓[/] Built jktest-0.1.0.jar in 717ms
```
or
```
[red]✗[/] Tests failed while building my-test-1.0.0.jar
```

---

## 3. Current state (verified)

- `cli/.../tui/ProgressBar.java` — single-line 40-segment bar (`▰▱`), moving gradient, owns
  cursor hide/show + OSC 9;4, `static active()` registry read by `GlobalCancel` on Ctrl-C.
  Used standalone by `JdkInstallCommand` (download) and `NewCommand`.
- `cli/.../tui/Spinner.java` — 9-frame spinner (`· ✶ ✸ ✹ ✺ ✹ ✷ ✶ ·`), primary→accent
  gradient, 120 ms. Used by `Clean`, `JdkInstall/Uninstall`, `NewCommand`.
- `cli/.../run/ProgressBarListener.java` — the current **goal** TUI: a `GoalListener` that
  paints `{spin} {24-bar} [pct] N of M › Goal › Phase label` on one line. Built **only** in
  `GoalConsole` AUTO-on-a-TTY.
- `cli/.../run/GoalConsole.java` — `Mode {AUTO,VERBOSE,QUIET,JSON}`; `chooseConsoleListener`
  routes AUTO→`ProgressBarListener`, else `Verbose/Silent/Ndjson`. Always adds
  `EventLogListener`. `isInteractiveTerminal()` gates the bar.
- Core `run/`: `GoalListener` (callbacks: `goalStart, phaseStart, progress, scopeUpdate,
  label, warn, error, phaseFinish, goalFinish`), `GoalView(name,numerator,denominator,
  phasesTotal,phasesComplete,cancelled)` with `fraction()/percent()`, `Goal` with
  `LongAdder numerator/denominator`, parallel `estimateScope` sum seeding the denominator,
  per-phase gap-fill at finish, `snapshot()`, `interactive` flag, `GoalResult(success,
  duration, phases[PhaseReport(name,status,duration)], warnings, errors, cancelled,
  userCancelled)`.
- **Workspace builds:** `BuildCommand.runWorkspaceBuild` topo-sorts modules and calls
  `runForDir(module)` **per module** → each module = its own `Goal` + its own
  `GoalConsole.run` (its own bar), printed under `══ module (i/N) ══`. **No aggregation today.**
- Primitives: `Ansi` (cursorUp/Down/PrevLine/toColumn, ERASE_LINE_TO_END,
  ERASE_DISPLAY_TO_END, HIDE/SHOW_CURSOR, taskbarProgress), `Rail` (╭ │ ╰ ── glyphs +
  StepState), `Theme` (error/success/warning/darkGray=bright-black/cyan/blue/primary/
  brightGreen/brightCyan + title/progress/spinner/failure gradients).

> Note: this worktree branched from `main@HEAD`, which does **not** include the
> uncommitted session changes on the main checkout (coordinate `Coords` colors, the
> `‼ Canceled by user` rework + removal of the cooperative Ctrl-C bridge, the tree
> "missing → run jk lock" hint, green section headers). Decide up front whether to
> cherry-pick/rebase those in — §11.

---

## 4. New & changed classes

```
cli/.../tui/
  LiveRegion.java          (new)  interface { void renderCanceled(); } + static active() registry
  SpinnerProgressBar.java  (rename of ProgressBar) single-line widget; implements LiveRegion;
                                  backs simple-task mode (spinner + label + freeze-to-first-glyph
                                  + result line). Keeps moving gradient, OSC 9;4, cursor mgmt.
  ProgressBar.java         (new)  PURE embeddable segmented bar: renders "▰▰▱ 45% [N of D]" to a
                                  String for a given (numerator,denominator); NO cursor/threads.
  CommandManager.java      (new)  controller/facade. Modes: simple(out, verb) / goal(out, name).
                                  Owns animator thread, spinner frame, OSC, multi-line redraw,
                                  finishSuccess(msg)/finishFailure(msg). Implements LiveRegion.
  PhaseRow.java            (new)  {module, phase, status, message} render model for the list.

cli/.../run/
  CommandManagerListener.java (new) GoalListener → drives a CommandManager in goal mode.
                                    Replaces ProgressBarListener for goal commands.
  AggregateView.java          (new) sums numerator/denominator + merges PhaseRows across
                                    multiple module goals; thread-safe.
  WorkspaceBuildView.java     (new) ties N sequential module goals to ONE CommandManager +
                                    AggregateView (header shows the active module).
  GoalConsole.java            (edit) add ConsoleStyle {GOAL, SIMPLE}; route GOAL→
                                    CommandManagerListener, SIMPLE→a spinner-backed listener;
                                    overload run(...) to accept style + display metadata +
                                    GoalResult→message mappers.
  ProgressBarListener.java    (delete) once all goal commands migrate.

cli/.../tui/GlobalCancel.java (edit) repaint LiveRegion.active() (covers both SpinnerProgressBar
                                     and CommandManager) instead of ProgressBar.active().
```

The naming follows the explicit instruction (current `ProgressBar` → `SpinnerProgressBar`;
new `ProgressBar` = the segmented bar). The new `ProgressBar` is deliberately a *subset* of
the old (pure bar string, no line/cursor ownership) so it can be embedded as one line inside
the goal-oriented multi-line region.

---

## 5. Rendering mechanics (goal mode)

- **Live region** = header line + bar line + up to `K` phase rows + optional `… +N completed`.
- **Redraw:** under a single lock, `cursorUp(prevLineCount)` → `ERASE_DISPLAY_TO_END` →
  print region → record new line count. Cursor hidden for the region's lifetime.
- **Width safety (critical):** every emitted row is truncated to the terminal width so no
  soft-wrap desyncs the `cursorUp` math. Confirmed available — `jline-terminal-ffm` is a
  dependency and `Wizard` already builds a `org.jline.terminal.Terminal`; use
  `Terminal.getWidth()` (fallback 80). Mid-run resize is accepted risk (next repaint may be
  briefly imperfect; self-corrects). This is new vs. today's single-line widgets.
- **Coalescing:** progress events arrive on worker threads; they update `AggregateView` state
  only. The 120 ms animator thread is the sole writer that repaints, so event storms don't
  thrash the terminal and the spinner animates smoothly.
- **Float/collapse:** rows partitioned into outstanding (top, by start order) and completed
  (bottom, by finish order); if `outstanding + completed > K`, render all outstanding +
  newest completed, replacing the rest with `… +N completed`.
- **Elapsed:** `CommandManager` records a start `Instant`; header timer formatted
  `(Xm Ys)` / `(Ys)`; final line uses `GoalResult.duration` (aggregate) formatted
  `in 717ms` / `in 1.23s` (reuse `VerboseListener.fmtDuration` shape).
- **Non-TTY / CI / --no-progress / --quiet:** unchanged — `GoalConsole` keeps returning
  `SilentListener`; `CommandManager` emits nothing interactive. JSON/verbose untouched.

---

## 6. Workspace aggregation (shared aggregate listener)

In `BuildCommand.runWorkspaceBuild`:
1. Topo-sort modules (unchanged).
2. **Pre-estimate** every module goal's scope and seed `AggregateView.denominator` with the
   sum up front, so the single bar doesn't visibly regress as later modules start. (Build each
   module `Goal` first; sum `Phase.estimateScope`.)
3. Create ONE `CommandManager.goal(...)` + ONE `WorkspaceBuildView`.
4. Run modules **sequentially** (current order). For each, attach a module-scoped adapter that
   forwards `progress/scopeUpdate/label/phaseStart/phaseFinish/warn/error` into the shared
   `AggregateView` tagged with the module name; the header's `{workspace}:{module}` reflects
   the running module. The aggregate numerator advances continuously across modules.
5. On the last module finishing (or any failure), `CommandManager.finishSuccess/Failure` with
   a workspace-level message (e.g. `Built 3 modules in 4.1s` / `tests failed in acme:web`).

Single-project builds use the same `CommandManager.goal(...)` with one module (the project's
own `group:artifact`), so the code path is uniform.

---

## 7. GoalConsole integration & the final line

- New overload:
  `run(Goal, Mode, Path cacheRoot, ConsoleSpec spec)` where
  `ConsoleSpec = { Style style, String displayName, String verb, Function<GoalResult,String> ok,
  Function<GoalResult,String> fail }`.
- AUTO + `GOAL` → `CommandManagerListener` (goal view). `goalFinish` calls
  `cm.finishSuccess(spec.ok(result))` / `finishFailure(spec.fail(result))`.
- AUTO + `SIMPLE` → spinner-backed listener wrapping a `CommandManager.simple(verb)`; same
  finish mapping.
- VERBOSE/QUIET/JSON and non-TTY: unchanged.
- The success/failure *text* stays command-owned (build already computes "Built X in Yms" in
  `printSuccessSummary`); we pass it in as the `ok`/`fail` mapper so the listener owns only
  the freeze-and-print, not the wording.

---

## 8. Cancellation

- Introduce `LiveRegion` with a static `active()` registry; `SpinnerProgressBar` and
  `CommandManager` both register on show / unregister on close.
- `GlobalCancel` repaints `LiveRegion.active()` (multi-line region paints all rows
  struck-through / bright-red, bar in failure gradient) then prints the cancel line and halts.
- Re-apply the main-branch decision to print `‼ Canceled by user` and drop the cooperative
  Ctrl-C bridge in `GoalConsole` (see §11) so cancel is immediate here too.

---

## 9. Theme / glyph additions

- Add named glyph constants (check `✓`, cross `✗`, phase-pending `◻`) — a small `Glyphs`
  holder or constants on `Theme`.
- Reuse existing styles: `success()` (green check), `error()` (red cross), `darkGray()`
  (bright-black for brackets/`›`/elapsed/`╰─`), spinner gradient, progress/failure gradients.

---

## 10. Testing

- Rename `ProgressBarTest` → `SpinnerProgressBarTest` (assertions intact); add freeze-to-first-
  glyph + result-line tests.
- New pure-bar `ProgressBarTest`: segment count, percent rounding, `[N of D]`, bright-black
  brackets, color stripping.
- `CommandManagerTest`:
  - simple mode: spinner+verb, success/failure lines, glyph colors.
  - goal mode: header (name/module/elapsed, bright-black `›`/brackets), bar line, phase-row
    ordering (outstanding top, completed sink, `… +N completed` collapse), module in `›` path,
    multi-line redraw (count `cursorUp`/`ERASE_DISPLAY_TO_END`, width truncation), final line.
- `AggregateViewTest`: feed events from 2 simulated module goals → one denominator = sum, one
  merged ordered row list.
- ANSI handling: full-ESC strip regex (`\033\[[0-9;?]*[a-zA-Z]`), color-on by default in tests.
- Update any `GoalConsole` tests for the new `ConsoleSpec` overload.

---

## 11. Rollout (ordered; each step compiles + tests green)

1. ✅ **Done** — main-branch session work cherry-picked onto this branch (commit `3668b6a`):
   Coords colors, `‼ Canceled by user` + cooperative-bridge removal, tree hint, green headers.
2. Add `LiveRegion` + generalize `GlobalCancel`; add `Glyphs`. (pure addition)
3. Rename `ProgressBar` → `SpinnerProgressBar` (+ test rename, update `JdkInstall`/`New`
   references, `GlobalCancel`). Mechanical, green.
4. Add new pure `ProgressBar` (segmented-bar string renderer) + tests.
5. Add `CommandManager` simple mode + tests; wire simple-mode commands via
   `GoalConsole` `Style=SIMPLE`.
6. Add `CommandManager` goal-mode multi-line view + `CommandManagerListener`; switch
   single-project build-like commands (replace `ProgressBarListener` in AUTO).
7. Add `AggregateView` + `WorkspaceBuildView`; wire `BuildCommand.runWorkspaceBuild` to the
   shared aggregate (header shows active module, rows carry module). Drop the per-module
   `══ module (i/N) ══` banners.
8. Delete `ProgressBarListener`; final cleanup + docs/changelog.

---

## 11b. Implementation status

Delivered on this branch (all `./gradlew test` green):

- ✅ `LiveRegion` + `Glyphs`; `GlobalCancel` repaints the active region.
- ✅ `ProgressBar` → `SpinnerProgressBar` (rename); new pure `ProgressBar`
  segmented-bar renderer.
- ✅ `CommandManager` **simple mode** (spinner + verb → freeze-to-first-glyph +
  `✓`/`✗` result line) and **goal mode** (spinner header + aggregate bar +
  floating phase list, region replaced by the result line on finish).
- ✅ Plumbing: `ConsoleSpec`, `SimpleTaskListener`, `CommandManagerListener`,
  `GoalConsole.run(…, ConsoleSpec)` (simple) and `GoalConsole.runGoal(…, module)`
  (goal).
- ✅ Workspace aggregation: `AggregateContext` + `AggregateModuleListener` +
  `GoalConsole.runGoalInto`; `BuildCommand.runWorkspaceBuild` renders ONE
  aggregate view for AUTO/QUIET (VERBOSE/JSON keep per-module).
- ✅ Migrated to the new view: **`jk build`** (single + workspace),
  **`jk compile`**, **`jk test`**, **`jk native`**.
- ✅ Migrated to simple mode: **`jk lock`**.
- ✅ Tests: `ProgressBarTest`, `SpinnerProgressBarTest`, `CommandManagerTest`
  (simple + goal render), `AggregateModuleListenerTest`.

### Remaining follow-up (not done this session)

Mechanical, same pattern as the migrated commands; left undone to avoid blind
churn on peripheral/report-style commands:

- **Simple mode wiring:** `sync`, `deny`, `update`, `install` (3 run sites),
  `tool-install` → `GoalConsole.run(goal, mode, cache, new ConsoleSpec(verb, ok, fail))`,
  removing their hand-rolled `✓ …` summary.
- **Goal view wiring:** `publish`, `image` → `runGoal(…, module)` (have
  dry-run / tarball-vs-push branches to fold into the success mapper).
- **Report-style (`verify-build`, `audit`):** they print multi-line reports
  (hashes / markdown), not a single result line — decide whether to keep their
  output and only wrap the goal, or restructure. Likely keep custom output.
- **Interactive goals** (`jk new`, `jdk install/uninstall`, `run`, `script`):
  already get `SilentListener` via `goal.interactive()`; no bar to replace.
- **Delete `ProgressBarListener`:** only after every *non-interactive* goal
  command above is migrated (it's still the AUTO listener for the unmigrated
  ones). Then drop the `ProgressBarListener` branch in
  `GoalConsole.chooseConsoleListener`.
- **Pre-sum module scopes** (§6): split goal construction out of
  `BuildCommand.runForDir` so the aggregate denominator is seeded up front and
  the bar doesn't dip as each module starts.
- **Live `jk build` visual pass:** the multi-line region math (cursor-up/erase,
  width truncation, float/collapse) is unit-tested but has not been eyeballed on
  a real terminal — verify with `/run` or a manual build before merge.

## 12. Risks / open items

- **Cursor math vs. line wrap** → enforce per-row truncation to terminal width (JLine width).
- **Concurrency/interleaving** → single render lock + animator-only writes.
- **Denominator stability across sequential modules** → pre-estimate scopes (§6.2);
  if pre-estimation is too costly/side-effecting, fall back to growing-D with smoothing.
- **Cross-module parallelism** is explicitly out of scope (sequential modules chosen); the
  `AggregateView` API is built to allow it later without changing the renderer.
- **EventLog/JSON parity** — ensure the module tag is also surfaced in NDJSON if we later want
  machine-readable per-module events (not required now).
