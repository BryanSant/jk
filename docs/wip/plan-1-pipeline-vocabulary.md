# jk pipeline vocabulary refactor — Command / Pipeline / Phase / Step / Tick

## Context

jk's execution model accreted overlapping terms that name the same or adjacent
things, creating cognitive load and real bugs:

- **Two granularities were both called "phase."** The coarse plugin-facing landmarks
  are the `Anchor` enum (`resolve/compile/test/package/run-prepare`); the fine
  engine-internal DAG nodes are the `Phase` type (`compile-java`, `sync-deps`, …).
  Describing either as "the phase" is ambiguous.
- **Three nouns for one CLI concept.** A built-in `Command`, a plugin-contributed
  `Verb`, and the `Goal` a command runs are largely the same idea wearing three names.
- **Stringly-typed, drifted step names.** ~48 inline `Phase.builder("…")` literals with
  no constants; the same concept spelled 6 ways (`sync-deps` / `resolve` /
  `resolve-deps` / `resolve-coord` / `resolve-formatters` / `resolve-jar-deps`),
  `compile-kt` vs `compile-kotlin`, etc. Cross-step edges are raw `.requires("…")`
  strings duplicated from producers — a typo is a silent missing-dependency.

**Decided model** (validated with the user; Verb≡Command confirmed, Goal is the
execution of a Command so it becomes a first-class *Pipeline*, not a third peer noun):

```
Command   invokable `jk <name>` — built-in OR plugin-contributed (absorbs "Verb")
   │ runs
Pipeline  the execution: an ordered, composable set of Phases (was "Goal")
   │
Phase     coarse stage: resolve · compile · test · package · run  (was "Anchor")
   │
Step      fine work-unit within a Phase: compile-java, resolve-deps  (was fine "Phase")
   │
Tick      progress sub-unit within a Step: one source · test · artifact  (was "scope")
```

**Scope decisions:** breaking changes are acceptable (pre-1.0; backwards-compat is a
non-goal — maximal correctness now). The rename extends to the serialized wire
(engine JSONL, Web-UI SSE, public `jk --json`) and all clients. Steps will explicitly
declare their owning Phase. Canonical Step identity is **`{phase}-{step}`**
(e.g. `compile-java`, `resolve-deps`); the running Command is display context, rendered
`command ▸ phase ▸ step` where it adds value, not baked into Step identity (steps are
shared across commands via pipelines).

## Target rename map

| Old (Java) | New (Java) | Old wire token(s) | New wire token(s) |
|---|---|---|---|
| `Goal`, `Goal.Builder` | `Pipeline`, `Pipeline.Builder` | `goal-start`/`goal-finish`/`goal-diagnostic`, `goal-progress` (SSE), `goalName` | `pipeline-start`/`pipeline-finish`/`pipeline-diagnostic`, `pipeline-progress`, `pipelineName` |
| `GoalResult`/`GoalListener`/`GoalKey`/`GoalView`/`GoalConsole`/`CompositeGoalListener` | `Pipeline*` equivalents | — | — |
| fine `Phase`, `Phase.Body`, `Phase.builder` | `Step`, `Step.Body`, `Step.builder` | `phase-start`/`phase-finish`, `plan-phase`, `history-phase` | `step-start`/`step-finish`, `plan-step`, `history-step` |
| `PhaseKind` (SYNC/IO/CPU) | `StepKind` | (not serialized) | — |
| `PhaseContext`/`DefaultPhaseContext`/`PhaseStatus`/`PhaseTimings` | `StepContext`/`DefaultStepContext`/`StepStatus`/`StepTimings` | — | — |
| `scope`/`estimateScope()`/`updateScope()`; "interpolation ticker" | `ticks`/`estimateTicks()`/`addTicks()`; rename timer → "interpolation timer" | `scope-update` | `tick-update` |
| `Anchor` enum + `StepSpec.after/before(Anchor)`, `afterAnchor()` | `Phase` enum + `after/before(Phase)`, `afterPhase()` | values `resolve/compile/test/package/run-prepare`; fields `after`/`before` | **unchanged** (already the Phase names) |
| `VerbSpec`/`VerbExec`, `BuildPluginContext.verb()`, `Declarations.verb()` | `PluginCommandSpec`/`PluginCommandExec`, `.command()` | `verb`/`verb-out`/`verb-args`, `unknown-verb`/`verb-failed` | `command`/`command-out`/`command-args`, `unknown-command`/`command-failed` |
| `PluginVerbs`/`PluginVerbReport`, `CommandDispatch.tryPluginVerb` | `PluginCommands`/`PluginCommandReport`, `tryPluginCommand` | `plugin-verb-request`/`plugin-verb-ack`, field `verb`; manifest `deploy-verb` | `plugin-command-request`/`plugin-command-ack`, field `command`; manifest `deploy-command` |
| android `*Verb` classes | android `*Command` classes | — | — |

**Naming note — `BuildPipeline` collision:** the engine's build-pipeline assembler class
`BuildPipeline` (with `coreBuilder()`) coexists with the new runtime type `Pipeline`
(they don't collide as symbols — like `String`/`StringBuilder`). `BuildPlan` already
exists in `kernel/engine-api`, so keep `BuildPipeline` as the assembler name; rename the
`kernel/engine/.../runtime/*Goals.java` factories → `*Pipeline`(s) with methods
`xxxGoal()` → `xxxPipeline()`.

## Sequencing (avoids the `Phase` name moving onto itself)

No `.java` file imports both fine `Phase` and `Anchor`, so the collision is only
conceptual — but do the fine rename first anyway:

1. **fine `Phase` → `Step`** (+ `PhaseKind`→`StepKind`, `PhaseContext`→`StepContext`,
   `scope`→`ticks`, wire `phase-*`→`step-*`). Frees the name `Phase`.
2. **`Anchor` → `Phase`** (plugin-api enum + `StepSpec` + the 3 plugins; wire values
   and `after`/`before` field names stay).
3. **`Goal` → `Pipeline`** (model + engine factories + cli listeners/TUI; wire `goal-*`→`pipeline-*`).
4. **`Verb` → `Command`** (plugin-api + engine + cli + wire `verb`→`command`).
5. **De-stringify + standardize** step names (constants + drift collapse).
6. **Step→Phase explicit membership** (structural; build pipeline first).
7. **Web-UI + docs** sweep.
8. **Verification.**

## Work streams & representative files

Renames are broad but mechanical; each stream is a symbol rename + import fixups. Search
with `/usr/bin/grep` (NOT the shell `grep`, which skips gitignored dirs) and remember the
package path contains `/build/jumpkick/`, so do **not** filter out `/build/`.

**Stream 1–4 (renames), representative anchors:**
- Model types: `kernel/model/src/main/java/build/jumpkick/run/{Goal,Phase,PhaseKind,PhaseContext,DefaultPhaseContext,PhaseStatus,GoalResult,GoalListener,GoalKey,GoalView}.java`
- Engine factories: `kernel/engine/src/main/java/build/jumpkick/runtime/*Goals.java`, `BuildPipeline.java`, `PhaseTimings*.java`; the plugin-command path `runtime/PluginVerbs.java`, `runtime/PluginBuild.java`
- Wire (single-sourced Java constants): `kernel/engine-api/.../engine/protocol/EngineProtocol.java` (the `GOAL_*`/`PHASE_*`/`PLUGIN_VERB_*`/`SCOPE_UPDATE` constants), `PluginVerbReport.java`
- Engine SSE producer: `kernel/engine/.../engine/EngineServer.java`
- CLI adapters/consumers: `cli/.../cli/engine/Engine{Build,Resolve,Worker}*Adapter.java`, `cli/.../cli/run/{GoalConsole,CompositeGoalListener,ProgressBarListener,VerboseListener,EventLogListener,JsonlShape,...}.java`, `cli/.../cli/tui/GoalWedge.java`, `cli/.../cli/CommandDispatch.java`
- Plugin-api: `plugin-api/.../plugin/build/{Anchor,StepSpec,VerbSpec,VerbExec,BuildPluginContext,BuildPluginHarness}.java`
- Plugins referencing `Anchor` / android `*Verb`: `plugins/{android,protobuf,spring-boot}/...`
- Model command layer stays `Command`/`CliCommand`; only `Command#name()` javadoc ("The verb…") updates.

**Stream 5 — de-stringify + standardize:**
- Add `kernel/model/src/main/java/build/jumpkick/run/StepNames.java` — canonical `public static final String` constants, named `{phase}-{step}`. Collapse drift to one name per concept:
  - resolve phase: `RESOLVE_DEPS = "resolve-deps"` (replaces `sync-deps`/`resolve`/`resolve-deps`), `RESOLVE_COORD`, `RESOLVE_FORMATTERS`, `RESOLVE_JAR_DEPS`, `RESOLVE_KOTLINC`
  - compile phase: `COMPILE_JAVA`, `COMPILE_KOTLIN` (replaces `compile-kt`), `COMPILE_TEST`, `COMPILE_KSP`
  - test phase: `TEST_RUN` (was `run-tests`)
  - package phase: `PACKAGE_JAR`, `PACKAGE_SHADOW`, `PACKAGE_SOURCES`, `PACKAGE_NATIVE`
  - setup/finalize: `PARSE_BUILD`/`PARSE_LOCK`/`PARSE_SCRIPT`, `ENSURE_JDK`, `WRITE_STAMP*`
- Replace every `Step.builder("…")` literal and every `.requires("…")` edge (incl. the
  helper lists `javaCompileRequires`/`kotlinCompileRequires`/`packageRequires` in
  `BuildPipeline.java`) with the constant. This eliminates producer/consumer duplication.

**Stream 6 — Step→Phase explicit membership (structural):**
- Add a `Phase phase` field to `Step.Builder` (`kernel/model/.../run/Step.java`), required.
- Build pipeline: annotate each step with its Phase (the 5 already exist as the enum); derive the plugin anchor→step ordering (`BuildPipeline.pluginStepPhase`/`packageRequires`, which today hardcode `"package".equals(...)`/`"compile".equals(...)`) from the Phase field instead of raw string compares.
- Non-build pipelines (lock/sync/audit/format/cache/etc.) do not map onto build phases. Treat `Phase` as a **per-pipeline** coarse grouping: define each pipeline's phase set where its steps live (e.g. lock → resolve/lock/write; sync → parse/sync/finalize). Start with the build pipeline (lowest risk, phases already defined), then extend pipeline-by-pipeline. Flag any pipeline whose phases aren't obvious for a follow-up decision rather than forcing a fit.
- Display: render `command ▸ phase ▸ step` in the TUI/`GoalWedge`/listeners; Step id stays `{phase}-{step}`.

**Stream 7 — Web-UI + docs (wire consumers that hard-code the tags):**
- `clients/web/src/main/resources/www/api.js` (the `EVENT_TYPES` SSE allow-list), `www/fold.js` (`case 'goal-progress'` → `'pipeline-progress'`, `d.phase`→`d.step`, etc.), `www/app.js`, `www/index.html` (phase→step rendering tiers), and the node test `clients/web/src/test/js/fold.test.mjs`.
- Docs: `docs/{protocol,webclient,http,engine,authoring-plugins,build-plugins-plan,plugin-refactor}.md` + the `Anchor.java` javadoc line referencing `Phase.requires`.

**Tests that assert wire strings (update in lockstep):** `EngineProtocolTest`,
`BuildPluginHarnessVerbTest` (→ `…CommandTest`), `PluginBuildDeclarationsTest`,
`ThirdPartyPluginTest` (external third-party-plugin contract fixture — hardcodes
`"t":"verb"` etc.), `fold.test.mjs`, `PhaseWeightTest`/`PhaseTimingsTest`.

## Verification

Per stream, then end-to-end:

1. **Compiles:** `./gradlew compileJava compileTestJava --offline --continue` green across all modules (watch for `build`-package-path false-negatives when grepping for leftovers — use `/usr/bin/grep` + `find`).
2. **Wire unit tests:** `EngineProtocolTest`, the renamed plugin-command tests, `PhaseTimings`/weight tests, and `node clients/web/src/test/js/fold.test.mjs`.
3. **End-to-end (mirrors the earlier proof):** `./gradlew nativeCompile` + `:cli-engine:shadowJar`; stage binary + engine jar; `install.sh`; `./gradlew installLocal`; then in `../bjs/simple`: `jk clean && jk build && jk test` — confirms Pipeline→Phase→Step→Tick execution + progress.
4. **Plugin wire paths:** build a plugin-backed project (protobuf or spring-boot) to exercise the `describe`/`step`/`after`/`before` Phase wire; and invoke an android plugin *command* (former verb) to exercise `plugin-command-request`/`command-out`.
5. **Web UI:** start the engine HTTP server, load the web client, confirm SSE `pipeline-*`/`step-*` events render live (fold.js reducer) — or at minimum `fold.test.mjs` green.

## Risks

- **Wire is the blast radius, not the Java rename.** The Java type renames are safe; the
  risk is the string tokens defined in ≥3 decoupled places (engine protocol, Web-UI JS,
  `jk --json`) plus the third-party-plugin contract fixture. Change each token in every
  place at once; a mismatch fails silently (dropped SSE event / missing dependency).
- **`PhaseTimings` disk ledger** is keyed by phase-name strings; renaming invalidates
  cached calibration data (acceptable — breaking OK; ledger self-heals).
- **Stream 6 is the only structural change** (per-pipeline Phase membership). Land it
  pipeline-by-pipeline behind the completed renames so a hard-to-classify pipeline
  doesn't block the vocabulary cleanup.
