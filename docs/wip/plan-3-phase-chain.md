# jk web UI — step-chain → coarse phase-chain

## Context

The Activity view renders a `StepChain` (`www/app.js`): one node per **fine-grained
step**, labeled `phase/step` (`resolve/lock`, `compile/java`, `compile/kotlin`,
`test/run-tests`, `package/jar`, …). On a real build that's a long strip that scrolls
under ◂/▸ paging. We want the chain to show the **coarse pipeline phases** instead —
`Resolve → Compile → Test → Package → …` — with the underlying steps demoted to a
click-to-expand detail.

The wire already carries what we need: `step-start`/`step-finish` SSE events each carry a
`phase` wire-name (`EngineServer.publishStepStart/Finish`), and `fold.js`'s `stepRow()`
already stashes it on every step row (`phase` or `''`). So this is largely a **client-side
regrouping**, not a protocol change.

## Decisions (settled with the user)

- **Dumb client, encounter order.** The chain is built purely from the `phase` wire-strings
  as they arrive — the first step of a not-yet-seen phase appends a phase node (in arrival
  order, which is pipeline order); later steps of that phase attach to it. **No `Phase` enum
  in the JS, no canonical ordering, no pre-seeding of the full pipeline.** A future/plugin
  phase just appears as its own node with a capitalized wire-name. The chain grows
  link-by-link as progress advances.
- **Keep step rows underneath.** The per-step fold is unchanged; the phase-chain is a
  *derived view*. This preserves step granularity for expand + diagnostics.
- **Click-to-expand: inline, single-open.** Clicking a phase reveals its step sub-chain
  inline beneath the chain (accordion, matching the existing "success/failure details"
  pattern); opening one phase collapses any other.
- **Auto-expand the failed phase.** On failure the failed phase opens automatically so the
  red failing step is visible without a click.
- **Failures name phase + step.** The failure line becomes `✘ Phase › step › test ›
  ExceptionClass › message` (today: `✘ step › …`).
- **No history migration.** Prior records are discarded, not backfilled: `jk engine stop`,
  delete `~/.jk/state/builds/journal/`, `jk engine start`.

## Target model

- The Activity card's chain(s) render **phase nodes**; each node owns its steps.
- A phase node's state is derived from its steps: `failed` if any failed → `running` if any
  running → `cancelled`/`skipped` if all such → `success` if all success.
- The wire is unchanged (phase already rides step events). Only the **persisted record**
  gains a per-step `phase` field so reloaded/finished cards render the same chain.
- Scope: Activity view only (compact + per-module chains). Untouched: Projects/Status
  views, CLI/TUI progress, and the SSE event vocabulary.

## Implementation stages (ordered safe → structural)

### Stage 1 — Phase grouping in the fold (`www/fold.js`)
Add pure `phaseChainOf(module)`:
- Walk `module.steps` in arrival order; append a node the first time a `phase` is seen,
  attach subsequent same-phase steps to it. Node = `{ phase, label, state, steps: [...] }`.
- State precedence: `failed` > `running` > `cancelled`/`skipped` > `success`.
- Label = capitalized wire-name (`compile` → "Compile"), so unknown/plugin phases render
  with zero client knowledge.
- Empty-phase (`''`) steps: shouldn't reach the feed after Stage 4; if one slips through,
  it forms a node keyed off its step name so nothing silently vanishes.

Leave the existing `stepRow`/`moduleRow` fold exactly as-is — step rows stay the source of
truth; `phaseChainOf` is derived (and headlessly testable).

### Stage 2 — Rendering: `PhaseChain` component (`www/app.js`, `www/index.html`, `www/style.css`)
- Rename `StepChain` → `PhaseChain` (its paging/anchor logic is node-agnostic — stays).
  Feed it `phaseChainOf(m)` for both the compact chain and per-module chains.
- **Inline single-open expand:** each phase node is a toggle; the open phase renders a
  nested step sub-chain (reusing the step-node rendering: name + ✓/✘/spinner). Opening a
  phase closes any previously-open one (single-open state on the component/module).
- Node label sentence-cased (consistent with the Material capitalization pass).

### Stage 3 — Failures name phase + step (`www/fold.js` or `www/app.js`, `www/index.html`)
- Resolve each `diagnostic`'s phase by joining `d.step` → the module's step row (which
  carries `phase`); no wire change needed.
- Render the failure line as `✘ Phase › step › test › ExceptionClass › message`.
- **Auto-expand:** when a module fails, the failed phase node opens by default (still
  overridable by the single-open toggle), surfacing the failing step in red.

### Stage 4 — Guarantee every feed step carries a phase (`kernel/engine/.../runtime/*.java`)
- Sweep `Step.builder(...)` on the Activity path and tag any missing `.phase(...)`, so the
  dumb grouping never yields a phase-less node. (Nearly all are already tagged — this is a
  small audit + fix, making the "dumb UI" always have a real phase to key on.)

### Stage 5 — Persist phase for reloaded/finished cards (`kernel/engine/.../journal/`, `EngineServer`)
- Add `phase` to `BuildRecord.Step` (record component).
- Populate it at construction: `EngineServer.java:3698`
  (`new BuildRecord.Step(step, status, millis)` → include phase).
- Serialize/deserialize: `journal/Json.java` `stepList` writer (~:88) + `readSteps`
  reader (~:161).
- `fold.js`'s `historyModules()` already reads `p.phase || ''`; new records now supply it.
- **No backfill.** One-time reset: `jk engine stop`, delete `~/.jk/state/builds/journal/`,
  `jk engine start`.

### Stage 6 — Tests
- `clients/web/src/test/js/fold.test.mjs`: keep the step-row assertions; **add**
  `phaseChainOf` cases — multi-step-per-phase collapse, encounter order, state precedence
  (failed > running > success), per-module independence, an unknown/plugin phase string
  appended verbatim, and the diagnostic→phase join.
- Java: extend the journal round-trip test for the new `Step.phase`.

## Verification

- `node --test clients/web/src/test/js/fold.test.mjs` (headless fold logic).
- `./gradlew :engine:test` — journal round-trip + `HttpEngineServerTest` (CSP unchanged).
- Manual e2e: reinstall the engine jar, `jk engine stop`, wipe
  `~/.jk/state/builds/journal/`, `jk engine start`; run a multi-module build and a failing
  build; confirm the phase-chain grows per phase, single-open expand works, and a failure
  auto-expands its phase and reads `✘ Phase › step › …`.

## Risks / sequencing notes

- **Dropped from the first draft:** pre-seeding the whole pipeline up front (user prefers
  the dumb append-as-encountered model) and the history backfill shim (prior history is
  wiped, not migrated).
- Stages 1–3 are pure web (no engine rebuild needed to iterate). Stages 4–5 touch the
  engine and need a jar reinstall + `jk engine stop` to take effect.
- The wire protocol and SSE event set are unchanged; the only persisted-format change is
  the additive `Step.phase` field (Stage 5), and old records are discarded rather than read.
