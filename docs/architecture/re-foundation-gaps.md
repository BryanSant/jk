# jk re-foundation — gaps, findings, and remediation log

Status: **in progress.** This document is the companion to [`re-foundation.md`](./re-foundation.md).
Where that doc records the *plan and its status log*, this one records the **gap between the
documented state and the code as audited**, and tracks the remediation work that closes those gaps.

The audit (2026-07-06) verified `re-foundation.md`'s specific claims against the code via five
focused passes: kernel purity, engine/CLI boundary, M5 model-currency, M4 worker SPI, and M1 Session
threading. The headline: the re-foundation is real and the doc is unusually honest, but three marquee
claims describe an *intended end-state* rather than what shipped, and there are a handful of concrete
correctness leaks.

**Load-bearing win that holds:** the kernel has **zero** dependency on the CLI — no
`dev.jkbuild.cli`/`dev.jkbuild.command` import exists anywhere in `kernel/*/src/main`. The one-way
layering (`model → core → {io,resolver,toolchain} → engine → cli`) is intact. M4 (worker SPI) and M5f
(builders) are accurate as claimed.

---

## Legend

Each item has a stable ID (`Q#`/`M#`/`L#`), a severity, the **claim vs reality**, concrete
`file:line` evidence, and a **status** that is updated as work lands. Compromises made while fixing
are logged inline under the item and aggregated in [§ Compromises & follow-ups](#compromises--follow-ups).

Status values: `OPEN` · `IN PROGRESS` · `FIXED` · `DEFERRED (logged)` · `WON'T FIX (rationale)`.

---

## 1. "Engine as server" is structurally staged, not yet effective

The north-star's opening rule (`re-foundation.md:25`): *"The kernel is the server. It has NO
process-global mutable state … Two builds must be able to run in one JVM."* This is currently true in
**structure** but not in **effect**.

### L7 — De-globalization did not de-globalize (`SessionContext.current()` is still a static slot)

**Severity: high (blocks the core north-star).** **Status: OPEN.**

- `SessionContext` holds `private static volatile Session current` (`kernel/core/.../config/SessionContext.java:17`)
  — a single install-once-per-process slot. **51 non-test call sites** read `current()` (26 cli, 19
  engine, 3 core, 3 io). A second concurrent build's `install()` clobbers the first; **two builds in
  one JVM is not achievable today.**
- `Session` is missing two of the eight fields the doc lists for it — the **output sink** and the
  **cancel token** never landed. Its own javadoc admits "later milestones fold the output sink and the
  cancellation token onto this record" (`Session.java:19-21`). It carries `parallelTests` instead.
- `GlobalCancel` (`cli/.../tui/GlobalCancel.java:22-48`) is a static SIGINT handler that calls
  `Runtime.getRuntime().halt(2)` — process-global and unconditionally fatal to any second build. The
  cooperative token that *does* exist lives on `Goal` (`Goal.java:73`), not `Session`.
- `Session` is threaded into `BuildPipeline.Inputs` (`BuildPipeline.java:134`), and hot-path phase
  bodies read `in.session()`. But the delegating `Inputs` ctors *default the field from*
  `SessionContext.current()` (`BuildPipeline.java:165,197,230`), and ~5 leaf sites still read the
  global directly rather than a threaded value: `TestSupport.java:188`, `CompileToolchain.java:51,165`,
  `KotlinBtaResolver.java:55`, `EffortWeights.java:338,340`. `PhaseContext`/`DefaultPhaseContext` do
  not carry `Session` at all (only a `cancelled()` flag).

**Fix plan:** make `SessionContext` `ScopedValue`-backed (keeping the static as a fallback default for
non-scoped call paths); land the output sink + cancel token on `Session`; replace `GlobalCancel`'s
`Runtime.halt` with a per-session cooperative cancel that the CLI signals.

### L8 — `BuildService` is not yet the single front door

**Severity: medium (limits non-CLI front-ends).** **Status: OPEN.**

`BuildService`'s actual public surface is `buildWorkspace(...)` + a lock-freshness guard
(`ensureWorkspaceLockFresh`/`workspaceLockStale`) + link/forecast helpers. There is **no `explain()`,
no `installJdk()`, no `lock()`** — the doc's M2 line "`build/explain/lock/installJdk(request,
GoalListener) → result`" (`re-foundation.md:53`) is a goal, not the code. Consequently the CLI reaches
deep into engine internals: `BuildPipeline` (7 imports), `BuildGraph` (2), `CompileToolchain` (14),
`WorkerClient` (6), `BuildPlanForecast` (2), `JUnitLauncher` (2), plus `JavacDriver`, `ClasspathResolver`,
`ActionCache`, etc. A non-CLI front-end cannot drive explain / single-module build / JDK install through
the facade today.

**Fix plan:** add `explain`, `installJdk`, and single-module `build` entry points to `BuildService`,
each event-emitting and stdout-free, mirroring `buildWorkspace`.

---

## 2. Duplicated orchestration — the drift hazard M2 set out to kill, still open in three places

M2's stated purpose was to close the explain/build drift with one planner. It did that for the parallel
and headless paths, but three parallel implementations remain.

### M4 — `topoSortModules` duplicated in the CLI

**Severity: medium (correctness drift).** **Status: OPEN.**

`BuildCommand.topoSortModules` (`cli/.../command/BuildCommand.java:693`) is a complete second
workspace-DAG topo-sort — including `workspace:` name resolution and `[build].order-after` edges —
duplicating the engine's `BuildGraph` (Kahn sort at `BuildGraph.java:185`, surfaced as
`BuildGraph.Result.topoOrder`, consumed by `buildWorkspace` at `BuildService.java:238`). It is used by
the serial `--no-parallel` path (`BuildCommand.java:551`) **and** by `NativeCommand.java:147`, which
reaches across into another command's static. Two orderings that must stay in lockstep by hand.

**Fix plan:** delete the CLI copy; expose the engine's topo order (via `BuildService` or `BuildGraph`)
and route both the serial path and `NativeCommand` through it.

### M5 — serial `--no-parallel` path re-implements the planner

**Severity: medium (correctness drift).** **Status: OPEN.**

`runWorkspaceBuild` (`BuildCommand.java:538`) does not call `buildWorkspace`. It re-implements memory
planning (`applyMemoryPlan`), ETA/calibration (`PhaseTimings`/`Calibration`/median-rate re-projection
at `:610-672`), and the prepare/run loop. The engine owns all of this for the parallel path inside
`buildWorkspace`. (`runWorkspaceHeadless` and `runGraphLive`, by contrast, *are* routed through
`buildWorkspace` — they are not part of this gap.)

**Fix plan:** route `runWorkspaceBuild` through `buildWorkspace` with `workers=1`, keeping only the
serial-specific rendering in the CLI. Fallback if that proves too invasive: extract the shared
ETA/memory-plan into engine helpers both paths call.

### Jdk install DAGs live in the CLI

**Severity: low (no layering violation; single-entry-point consolidation only).** Folded into **L8**.
`JdkInstallCommand`/`JdkEnsureCommand` assemble and run their own goal DAGs in the CLI over engine
primitives (`Goal`, `JdkInstaller`, `GoalConsole`). Addressed by L8's `installJdk` facade.

---

## 3. Concrete correctness leaks

### Q1 — the engine writes user text to `System.err` directly

**Severity: high (live invariant violation).** **Status: OPEN.**

`AutoLock.java:252-253` prints `‼ jk: auto-lock warning — could not update jk.lock: …` and a follow-up
line straight to `System.err`. This directly violates the invariant at `re-foundation.md:275` ("No new
`System.out`/`System.err` in kernel modules; user text routes through `PhaseContext`/`GoalListener`").
For a non-CLI front-end this text lands on a process stream it can't capture or route.

**Fix plan:** route the warning through the engine's user-facing channel (a `GoalListener`/callback or
a returned warning the CLI renders). Must not silently drop the warning.

### NativeImageDriver uses `System.out`/`System.err` in the toolchain

**Severity: medium (inverted dependency).** **Status: OPEN (candidate; assess during L7/Q1).**

`kernel/toolchain/.../tool/NativeImageDriver.java:111,153` forwards the `native-image` subprocess's
streams to `System.out`/`System.err`. It works only because the CLI's `CommandManager` swaps the
streams — a kernel module's correctness depends on client-side behavior. Break the inversion by routing
through the session output sink (depends on L7's output-sink landing).

### M6 — the engine/internal boundary is convention-only

**Severity: medium (encapsulation).** **Status: OPEN.**

`ModulePlan` is a record, so `unit()` (returning engine-internal `BuildGraph.BuildUnit`) is a **public
accessor** — the doc says "not part of the front-end API" but nothing enforces it. The spirit is already
violated elsewhere: `ExplainCommand.java:127,129,239,331` reaches `BuildGraph.BuildUnit` through
`BuildPlanForecast.Module.unit()`.

**Fix plan:** stop exposing `BuildGraph.BuildUnit` through public accessors — either package-private the
component or expose only the sanctioned `coord()`/`dir()`/`goal()`/`weight()`/`fullyCached()`/`cache()`
surface via an interface. Fix `ExplainCommand`'s reach-through.

---

## 4. M5 "single currency" is materially incomplete despite being marked DONE

### Q2 — ~33 hand-rolled coordinate splits remain

**Severity: medium (the exact smell M5 claimed to delete).** **Status: OPEN.**

The `coordinate()` accessors were *added*, but "delete the ~43 `indexOf(':')` splits" largely didn't
happen. ~33 remain. Root cause of the worst cluster: `Resolution.ResolvedModule`
(`kernel/resolver/.../Resolution.java:21`) has no `coordinate()` accessor, and `Coordinate`
(`kernel/model/.../Coordinate.java`) has `of(group,artifact,version)` + `parse(spec)` but **no
`ofModule(module, version)`** helper for the common "group:artifact string + separate version" shape.

- **7-site byte-identical cluster** doing `indexOf(':')` on `ResolvedModule.module()`:
  `ToolResolver.java:70`, `LockOrchestrator.java:306`, `MavenPackageSource.java:185`,
  `NaiveResolver.java:96`, `PubGrubResolver.java:133`, `ScriptRunner.java:613`, `KotlinBtaResolver.java:68`.
- **15 CLI display/styling splits** (per-segment coloring of `group:artifact`): `NewCommand.java:675`,
  `NewJkBuildRenderer.java:103,124`, `ExplainCommand.java:368,377,454`, `theme/Coords.java:86`,
  `tui/GoalWedge.java:65`, `DependencyTree.java:725,779,798`, `pubgrub/Diagnostics.java:247`,
  `TreeCommand.java:263`, `WhyCommand.java:90`, `RemoveCommand.java:118`.
- **8 input/import parsers:** `AddCommand.java:411,491`, `ScriptHeaderParser.java:116`,
  `LibraryCatalog.java:296`, `GradleVersionCatalog.java:153`, `GradleImporter.java:394`,
  `publisher/Sbom.java:202`, `SyncCommand.java:325`.
- **3 guard checks** `pkg.name().indexOf(':') < 0`: `LockOrchestrator.java:396`, `IdeSupport.java:288`,
  `ClasspathResolver.java:74`.

Correctly *excluded* (genuinely not coordinate-splitting): `GitUrl.java:40,103`,
`JdkCatalogClient.java:238`, `PolicyChecker.java:52`, `JkBuildParser.java:806`, `InstallCommand.java:782`.

**Fix plan:** add `Coordinate.ofModule(String module, String version)` (and a `Coordinate.parseModule`
where only `group:artifact` is present) + `Resolution.ResolvedModule.coordinate()`; migrate all sites.
Provide a small display helper for the CLI coloring split so the pattern lives in one place.

### Q3 — `workspace:`/`git:` magic-string leaks

**Severity: low-medium.** **Status: OPEN.**

- `JkBuildRenderer.java:42` re-declares its own `private static final String
  WORKSPACE_PLACEHOLDER_PREFIX = "workspace:"` and uses it at `:157`, despite importing
  `dev.jkbuild.model.Dependency` (which exposes `WORKSPACE_PREFIX`/`isWorkspaceRef`).
- A `git:` repo-source prefix is written in `GitSourceResolution.java:105` (`"git:" + …`) and read in
  `RepoArtifactResolver.java:37` (`startsWith("git:")`) as bare literals with no shared constant.
- `CacheSync.java:298` and `PolicyChecker.java:31` still hand-split `+`-delimited sources instead of
  calling `RepoArtifactResolver.repoName()`.

**Fix plan:** use `Dependency.WORKSPACE_PREFIX` in `JkBuildRenderer`; introduce a shared constant for
the `git:` repo-source prefix (in `io`, beside `RepoArtifactResolver`, since it is a repo-source concept
not a `Dependency` concept); route the two `+`-split stragglers through `RepoArtifactResolver.repoName()`.

### WorkspaceMerge drops `[build]`/`[format]` — confirmed, benign today

**Severity: low (latent).** **Status: DEFERRED (logged).**

`WorkspaceMerge` (`kernel/model/.../WorkspaceMerge.java:93,142`) builds the merged `JkBuild` via
`JkBuild.builder(...)` and never calls `.build(...)`/`.format(...)`, so the merged object gets
`Build.EMPTY`/`FormatConfig.EMPTY`. **Benign today:** every consumer of the merged object uses it only
for lock resolution; `[build].order-after`/`lint` are read from raw-parsed manifests via
`WorkspaceLoader` (`BuildGraph.java:124,178`; `BuildPipeline.java:576`), not the merged object, and no
consumer reads `.format()` off it. It only bites if a future lock-path consumer reads `.build()`/
`.format()` from the merged object.

**Decision:** add a loud guard/comment so the future footgun is explicit; full merge is out of scope
until a consumer needs it. Revisit in final review.

---

## Remediation order

1. **Quick** — Q1 (AutoLock), Q2 (Coordinate), Q3 (magic strings).
2. **Medium** — M4 (topo-sort hoist), M5 (serial path), M6 (boundary teeth).
3. **Large** — L7 (Session de-globalize), L8 (BuildService facade).
4. **Doc hygiene** — reconcile `re-foundation.md`; final supervisor review revisits every compromise.

Invariant held at every commit: `./gradlew :cli:test` green; no new kernel `System.out`/`System.err`;
no new kernel process-global mutable statics.

---

## Compromises & follow-ups

Living log. Every compromise made while fixing an item is recorded here with its item ID, and revisited
in the final review. `(open)` = still a compromise; `(resolved)` = revisited and closed.

- _(none yet — populated as work lands)_

---

## Change log

- 2026-07-06 — document created from the audit; all items OPEN.
