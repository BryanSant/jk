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
`cc.jumpkick.cli`/`cc.jumpkick.command` import exists anywhere in `kernel/*/src/main`. The one-way
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

**Severity: high (blocks the core north-star).** **Status: SUBSTANTIALLY FIXED** (commits
`ScopedValue-backed SessionContext … L7` + `L7 consumers`).
- `SessionContext` is now `ScopedValue`-backed: `current()` returns the thread's `ScopedValue`
  binding when set (via `where`/`runWhere`), else the process static. Two builds can run concurrently
  in one JVM, each with its own `Session`, with no clobber — proven by `SessionScopeTest`. The static
  `install`/`reset` path is unchanged for the single-build CLI.
- `Session` gained a per-session cooperative `CancelToken` (`defaults()` mints a live one; `null` →
  inert `NONE`). `GlobalCancel` signals `SessionContext.current().cancel().cancel()` before its
  `halt(2)`, and the engine honors it by OR-ing `SessionContext.current().cancelled()` into the
  existing `StepContext.cancelled()` poll — so any step already checking cancellation observes a
  session-level cancel (no new polling sites). The CLI keeps `halt(2)` as its process guarantee.
- **Known limitation (logged, follow-up):** a `ScopedValue` binding does NOT propagate to tasks
  dispatched on the shared `JkThreads.io()` pool (it propagates only to structured forks). So a
  *scoped* (multi-tenant) session's config/cancel is fully honored on the binding thread but async
  worker-pool tasks fall back to the process-static session. The single-process CLI (static session)
  is fully correct. Making concurrent in-JVM builds *fully* isolated across the engine's async
  dispatch needs `StructuredTaskScope` or per-task rebinding of the binding — a scoped follow-up.
  The holder now *supports* concurrency (the structural unlock); the async dispatch must propagate it
  to be *completely* effective.
- Output sink: **not** added to `Session` — the one kernel-console-I/O consumer (`NativeImageDriver`)
  was fixed by routing through the in-scope `StepContext.output` instead (see the NativeImageDriver
  item), which is the existing engine output seam; a redundant `Session` sink was unnecessary.

- `SessionContext` holds `private static volatile Session current` (`kernel/core/.../config/SessionContext.java:17`)
  — a single install-once-per-process slot. **51 non-test call sites** read `current()` (26 cli, 19
  engine, 3 core, 3 io). A second concurrent build's `install()` clobbers the first; **two builds in
  one JVM is not achievable today.**
- `Session` is missing two of the eight fields the doc lists for it — the **output sink** and the
  **cancel token** never landed. Its own javadoc admits "later milestones fold the output sink and the
  cancellation token onto this record" (`Session.java:19-21`). It carries `parallelTests` instead.
- `GlobalCancel` (`cli/.../tui/GlobalCancel.java:22-48`) is a static SIGINT handler that calls
  `Runtime.getRuntime().halt(2)` — process-global and unconditionally fatal to any second build. The
  cooperative token that *does* exist lives on `Pipeline` (`Pipeline.java:73`), not `Session`.
- `Session` is threaded into `BuildPipelines.Inputs` (`BuildPipelines.java:134`), and hot-path step
  bodies read `in.session()`. But the delegating `Inputs` ctors *default the field from*
  `SessionContext.current()` (`BuildPipelines.java:165,197,230`), and ~5 leaf sites still read the
  global directly rather than a threaded value: `TestSupport.java:188`, `CompileToolchain.java:51,165`,
  `KotlinBtaResolver.java:55`, `EffortWeights.java:338,340`. `StepContext`/`DefaultStepContext` do
  not carry `Session` at all (only a `cancelled()` flag).

**Fix plan:** make `SessionContext` `ScopedValue`-backed (keeping the static as a fallback default for
non-scoped call paths); land the output sink + cancel token on `Session`; replace `GlobalCancel`'s
`Runtime.halt` with a per-session cooperative cancel that the CLI signals.

### L8 — `BuildService` is not yet the single front door

**Severity: medium (limits non-CLI front-ends).** **Status: PARTIALLY FIXED** (commit
`BuildService.explain facade … L8`). Added `BuildService.explain(entryDir, build, cache) →
ExplainPlan` — a `BuildGraph`-free forecast (dependency-ordered `BuildPlanForecast.Module`s + a plain
`dir → prereq dirs` edge map + width + errors) — plus a `ResolvedGraph` opaque handle
(package-private `graph()`, mirroring `ModulePlan`) and a `resolveGraph`/`forecastDirtyDirs` overload
for the build preflight. `ExplainCommand` and `BuildCommand` migrated and **no longer name
`cc.jumpkick.runtime.BuildGraph` or `BuildGraph.BuildUnit`** — closing the M6-logged leak. The only
remaining `BuildGraph` reference in the CLI is `NativeCommand`'s front-end-safe
`BuildGraph.orderModules(Map)` (the M4 map API, not graph internals).
_Update (2026-07-06):_ **`installJdk` facade landed** — `JdkService` (engine) owns catalog fetch →
spec/keyword resolve → download → extract, emitting progress via `JdkInstallListener`; a headless
front-end installs a JDK with one `install(spec, …)` call. The CLI keeps only presentation (wizard,
download-bar, set-default prompt). Verified end-to-end: `jk jdk install 21` downloaded + extracted
Temurin 21, and a re-run hit the already-installed short-circuit. (The interactivity was never
intrinsic — `jk jdk install <spec>`/`--lts` were always non-interactive; the wizard just collects a
spec the same pipeline installs.) Single-module `build` facade (`BuildService.buildModule`) remains
the one un-hoisted L8 item — offered, not yet requested.

`BuildService`'s actual public surface is `buildWorkspace(...)` + a lock-freshness guard
(`ensureWorkspaceLockFresh`/`workspaceLockStale`) + link/forecast helpers. There is **no `explain()`,
no `installJdk()`, no `lock()`** — the doc's M2 line "`build/explain/lock/installJdk(request,
PipelineListener) → result`" (`re-foundation.md:53`) is a goal, not the code. Consequently the CLI reaches
deep into engine internals: `BuildPipelines` (7 imports), `BuildGraph` (2), `CompileToolchain` (14),
`PluginClient` (6), `BuildPlanForecast` (2), `JUnitLauncher` (2), plus `JavacRunner`, `ClasspathResolver`,
`ActionCache`, etc. A non-CLI front-end cannot drive explain / single-module build / JDK install through
the facade today.

**Fix plan:** add `explain`, `installJdk`, and single-module `build` entry points to `BuildService`,
each event-emitting and stdout-free, mirroring `buildWorkspace`.

---

## 2. Duplicated orchestration — the drift hazard M2 set out to kill, still open in three places

M2's stated purpose was to close the explain/build drift with one planner. It did that for the parallel
and headless paths, but three parallel implementations remain.

### M4 — `topoSortModules` duplicated in the CLI

**Severity: medium (correctness drift).** **Status: FIXED** (commit `one workspace topo-sort … M4`).
`BuildGraph` now owns the single implementation: private `modulePrereqs()` + `kahnSort()` are shared
by the graph resolver (`Builder.topoSort`) and a new public `BuildGraph.orderModules(Map)`. The serial
path (`BuildCommand`) and `NativeCommand` route through it; the CLI copy and the cross-command static
reach are deleted. Cycle fallback preserved.
_Bonus finding (resolved):_ tie-break among independent modules is now deterministic declaration order
(was `HashMap` hash order — a latent non-determinism); and the stale `[build.embed-sha]` reference in
the old javadoc/one test described a **non-existent feature** (no `embed-sha` parsing exists) — the
test only passed by hash luck and was corrected to exercise the real `test-plugin-jars` edge.

`BuildCommand.topoSortModules` (`cli/.../command/BuildCommand.java:693`) is a complete second
workspace-DAG topo-sort — including `workspace:` name resolution and `[build].order-after` edges —
duplicating the engine's `BuildGraph` (Kahn sort at `BuildGraph.java:185`, surfaced as
`BuildGraph.Result.topoOrder`, consumed by `buildWorkspace` at `BuildService.java:238`). It is used by
the serial `--no-parallel` path (`BuildCommand.java:551`) **and** by `NativeCommand.java:147`, which
reaches across into another command's static. Two orderings that must stay in lockstep by hand.

**Fix plan:** delete the CLI copy; expose the engine's topo order (via `BuildService` or `BuildGraph`)
and route both the serial path and `NativeCommand` through it.

### M5 — serial `--no-parallel` path re-implements the planner

**Severity: medium (correctness drift).** **Status: FIXED** (commit `serial --no-parallel … M5`).
`WorkspaceScheduler.run` gained a module-concurrency cap (`<=0` = today's batch-per-level parallel,
verbatim; `>0` = a rolling window across levels; `1` = strict serial). `WorkspaceRequest.maxModuleConcurrency`
threads it; `buildWorkspace` clamps the memory-plan width + ETA concurrency to it. `--no-parallel` now
dispatches through `runGraphParallel` with cap=1, so the engine owns the whole serial↔parallel
spectrum and the CLI's duplicate `runWorkspaceBuild` (+ its private ETA/median re-projection,
`buildFailedAt`) is deleted. Verified end-to-end: an 8-module serial build of `jk.jk` in correct
dependency order (model→…→cli), artifact linking, and both success + fail-fast rendering.
_Compromises (logged):_ (1) serial builds now record `StepTimings` (the old path didn't) — an
improvement; (2) under an *intermediate* cap (2..N-1) the scheduler's `remaining` excludes in-flight
units, slightly under-counting the ETA remainder — moot for the only shipped caps (0 and 1); (3) on
fail-fast under a cap, in-flight peers drain in the background rather than being cancelled — moot for
cap=1 (no peers), and the cooperative-cancel wiring is L7's job.

`runWorkspaceBuild` (`BuildCommand.java:538`) does not call `buildWorkspace`. It re-implements memory
planning (`applyMemoryPlan`), ETA/calibration (`StepTimings`/`Calibration`/median-rate re-projection
at `:610-672`), and the prepare/run loop. The engine owns all of this for the parallel path inside
`buildWorkspace`. (`runWorkspaceHeadless` and `runGraphLive`, by contrast, *are* routed through
`buildWorkspace` — they are not part of this gap.)

**Fix plan:** route `runWorkspaceBuild` through `buildWorkspace` with `workers=1`, keeping only the
serial-specific rendering in the CLI. Fallback if that proves too invasive: extract the shared
ETA/memory-plan into engine helpers both paths call.

### Jdk install DAGs live in the CLI

**Severity: low (no layering violation; single-entry-point consolidation only).** Folded into **L8**.
`JdkInstallCommand`/`JdkEnsureCommand` assemble and run their own pipeline DAGs in the CLI over engine
primitives (`Pipeline`, `JdkInstaller`, `PipelineConsole`). Addressed by L8's `installJdk` facade.

---

## 3. Concrete correctness leaks

### Q1 — the engine writes user text to `System.err` directly

**Severity: high (live invariant violation).** **Status: FIXED** (commit `AutoLock … Q1`).
`maybeReLock` now takes a `Consumer<String> warn` sink (mirroring the existing
`JdkEnsure.ensure(..., Consumer<String> warn)` pattern); both callers (`BuildPipelines`,
`SyncCommand`) pass `ctx::output`, so the warning flows through `StepContext.output` →
`PipelineListener` and a non-CLI front-end captures it. Exact two-line text preserved.
_Compromise (logged):_ routed through `output` (verbatim passthrough) rather than the structured
`ctx.warn(...)` channel, so it is not counted in `PipelineResult.warnings()` / the `--jsonl`
`type:"warn"` stream. Chosen to preserve the exact text; revisit if structured classification is wanted.

`AutoLock.java:252-253` prints `‼ jk: auto-lock warning — could not update jk.lock: …` and a follow-up
line straight to `System.err`. This directly violates the invariant at `re-foundation.md:275` ("No new
`System.out`/`System.err` in kernel modules; user text routes through `StepContext`/`PipelineListener`").
For a non-CLI front-end this text lands on a process stream it can't capture or route.

**Fix plan:** route the warning through the engine's user-facing channel (a `PipelineListener`/callback or
a returned warning the CLI renders). Must not silently drop the warning.

### NativeImageDriver uses `System.out`/`System.err` in the toolchain

**Severity: medium (inverted dependency).** **Status: OPEN (candidate; assess during L7/Q1).**

`kernel/toolchain/.../tool/NativeImageDriver.java:111,153` forwards the `native-image` subprocess's
streams to `System.out`/`System.err`. It works only because the CLI's `CommandManager` swaps the
streams — a kernel module's correctness depends on client-side behavior. Break the inversion by routing
through the session output sink (depends on L7's output-sink landing).

### M6 — the engine/internal boundary is convention-only

**Severity: medium (encapsulation).** **Status: FIXED** (commit `compiler-enforce … M6`).
`BuildService.ModulePlan` and `BuildPlanForecast.Module` are now `final class`es (not records) so their
`unit()` accessor drops to package-private — reachable by engine consumers in `cc.jumpkick.runtime`,
invisible to the CLI. `ExplainCommand` migrated to the new public `coord()`/`dir()`; the CLI no longer
calls `.unit()` anywhere.
_Compromise (logged → folded into L8):_ the CLI still names `BuildGraph`/`BuildGraph.BuildUnit`
*directly* via `graph.topoOrder()` (`BuildCommand.java:275`) and `BuildGraph.resolve(...)`
(`ExplainCommand.java:83`, `BuildCommand`). That is a different leak vector (the raw graph API, not the
`ModulePlan`/`Module` accessors this item enforced) and closing it needs a `BuildService` plan/explain
facade — tracked under **L8**.

`ModulePlan` is a record, so `unit()` (returning engine-internal `BuildGraph.BuildUnit`) is a **public
accessor** — the doc says "not part of the front-end API" but nothing enforces it. The spirit is already
violated elsewhere: `ExplainCommand.java:127,129,239,331` reaches `BuildGraph.BuildUnit` through
`BuildPlanForecast.Module.unit()`.

**Fix plan:** stop exposing `BuildGraph.BuildUnit` through public accessors — either package-private the
component or expose only the sanctioned `coord()`/`dir()`/`pipeline()`/`weight()`/`fullyCached()`/`cache()`
surface via an interface. Fix `ExplainCommand`'s reach-through.

---

## 4. M5 "single currency" is materially incomplete despite being marked DONE

### Q2 — ~33 hand-rolled coordinate splits remain

**Severity: medium (the exact smell M5 claimed to delete).** **Status: PARTIALLY FIXED**
(commit `Coordinate.ofModule … Q2/Q3`). Added `Coordinate.ofModule(module, version)` +
`Resolution.ResolvedModule.coordinate()`; migrated the **7-site cluster** and `SyncCommand`'s plugin
parse (**8 splits eliminated**). The remaining ~25 are genuinely *not* the `ResolvedModule→Coordinate`
shape and were intentionally left: 7 bespoke input parsers (`@version` floating logic, `null`-returning
branches, `String[]` returns — `AddCommand`, `ScriptHeaderParser`, `LibraryCatalog`,
`GradleVersionCatalog`, `GradleImporter`, `Sbom`), 3 lockfile-artifact guards (`indexOf(':')<0` on a
`Lockfile.Artifact`, not a `Coordinate`), and ~11 CLI/resolver *display-coloring* splits.
_Compromise (logged):_ the display-coloring splits could not adopt the shared `Coords` helper without
changing rendered output bytes (three divergent bare-name colors; bold variants; two are in the
`resolver` module which cannot depend on cli's `Coords`). Revisit whether a display helper is worth a
deliberate output-normalizing pass.

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
  `tui/PipelineWedge.java:65`, `DependencyTree.java:725,779,798`, `pubgrub/Diagnostics.java:247`,
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

**Severity: low-medium.** **Status: MOSTLY FIXED** (commit `Coordinate.ofModule … Q2/Q3`).
`JkBuildRenderer` now uses `Dependency.isWorkspace()`; `RepoArtifactResolver.GIT_SOURCE_PREFIX` is the
single owner of the `git:` repo-source prefix (write in `GitSourceResolution`, read in
`RepoArtifactResolver`); `CacheSync` routes `+` parsing through `RepoArtifactResolver.repoName()`.
_Compromises (logged, to revisit):_
1. `CacheSync` still scans `+` a second time for the URL half because `io` exposes only `repoName`, not
   a `repoUrl`. Add `RepoArtifactResolver.repoUrl(String)` (or a parsed-source record) and delegate.
2. `PolicyChecker` (`core`) still hand-splits `+` — **DEFERRED by layering**: `core` cannot depend on
   `io` (io depends on core), and it wants the URL half which the io facade doesn't expose. Correct
   factoring is a shared repo-source parser in a layer both see (`core`/`model`) that `io`'s resolver
   then delegates to. Revisit in the final pass.

- `JkBuildRenderer.java:42` re-declares its own `private static final String
  WORKSPACE_PLACEHOLDER_PREFIX = "workspace:"` and uses it at `:157`, despite importing
  `cc.jumpkick.model.Dependency` (which exposes `WORKSPACE_PREFIX`/`isWorkspaceRef`).
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
`WorkspaceLoader` (`BuildGraph.java:124,178`; `BuildPipelines.java:576`), not the merged object, and no
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

- **[Q1] (open)** auto-lock warning routes via `StepContext.output` (verbatim) not the structured
  `warn()` channel — not counted in `PipelineResult.warnings()`/jsonl. Intentional to preserve exact text.
- **[Q2] (open)** ~11 CLI/resolver display-coloring coordinate splits left un-deduped — adopting the
  shared `Coords` helper would change rendered output bytes; `resolver`'s two cannot see cli's `Coords`.
- **[Q3] (resolved)** `CacheSync` double-`+`-scan and **[Q3] (resolved)** `PolicyChecker`'s
  layering-blocked hand-split are BOTH closed by a shared `cc.jumpkick.lock.RepoSource` (in `core`,
  visible to core/io/resolver): one owner of the `<name>+<url>` split with a strict nullable `name()`
  (mirrors `repoName`) and a lenient `url()` (mirrors PolicyChecker), each preserving its exact
  boundary contract (they intentionally diverge only on a trailing `+`, documented + unit-tested).
  `RepoArtifactResolver.repoName` delegates to it; `CacheSync` parses once; `PolicyChecker` uses it
  directly. The M5d "single owner of `<name>+<url>`" goal is now fully realized.
- **[M6] (resolved by L8)** the CLI's direct `BuildGraph`/`BuildUnit` use (`BuildCommand:275`,
  `ExplainCommand:83`) is closed — both now go through `BuildService.explain`/`resolveGraph`. Only
  `NativeCommand.orderModules` (front-end-safe map API) names `BuildGraph`.
- **[L7] (open, follow-up)** `ScopedValue` bindings don't propagate to `JkThreads.io()` pool tasks —
  scoped multi-tenant sessions are fully isolated only on the binding thread; async dispatch needs
  `StructuredTaskScope`/per-task rebinding. CLI (static session) unaffected.
- **[L8] (open, minor)** `ResolvedGraph` wraps `BuildGraph.Result` (package-private `graph()`) rather
  than fully flattening it — deliberate, to keep the fully-cached-shortcut behavior + timing window
  byte-identical. `ResolvedGraph.moduleDirs()` is currently unused (kept as a front-end-safe accessor).
- **[L8] (deferred, scope decision)** `installJdk` and single-module `build` facades not added:
  `jk jdk install` is an inherently *interactive* Pipeline DAG (wizard/prompts, default-adoption) whose
  mechanical core already runs on engine primitives (`Pipeline`/`JdkInstaller`/`PipelineConsole`) — the same
  "CLI renderer over engine primitives" pattern the doc accepts for the serial/native paths; and the
  single-project build path already drives compile/test/package through engine primitives
  (`BuildPipelines.coreBuilder`). Hoisting either behind a `BuildService` method is additive future
  work, not a layering violation. Flagged for the owner to prioritize.

---

## Final review (2026-07-06)

Independent re-audit of the invariants after all items landed (each verified by grep + a clean
`:cli:test`/`:engine:test`/`:core:test`/`:toolchain:test`/`:model:test` run):

- **No kernel `System.out`/`System.err`/`setOut`** outside comments, EXCEPT
  `plugin-api/.../process/PluginMain.java` — a forked plugin process's `main()` reporting fatal
  bootstrap errors (no jk output infra exists yet in that process). **Accepted exception** (a separate
  process's last-resort stderr, not the engine writing user text). Q1 (`AutoLock`) and L7c
  (`NativeImageDriver`) leaks confirmed gone.
- **CLI names no engine build internals:** zero `BuildGraph.BuildUnit` / `.unit()` references in
  `cli/src/main`; `topoSortModules` deleted; only `NativeCommand`'s front-end-safe
  `BuildGraph.orderModules(Map)` remains. (M4 + M6 + L8.)
- **Kernel → CLI layering:** zero `import cc.jumpkick.cli`/`command` in any kernel/plugin-api main
  source. Intact.
- **Coordinate-split cluster** (the 7 `ResolvedModule → Coordinate` sites) eliminated; remaining
  `indexOf(':')` are the documented display-coloring (`DependencyTree`, `Diagnostics`) and
  lockfile-guard (`LockOrchestrator`) sites, intentionally left.

Remaining documented follow-ups (not regressions — future work): ~~**L7** `ScopedValue` propagation
to the `JkThreads.io()` worker pool~~ (DONE since — both `JkThreads` pools are wrapped in
`ContextPropagatingExecutorService`, which rebinds the session per submitted task via the
`ContextPropagator` hook `SessionContext` installs); **L8** first-class `installJdk`/single-module
`build` facades (their mechanical cores already run on engine primitives; `installJdk` has since
landed as `JdkService`); **Q2** an optional output-normalizing pass to dedup the CLI coordinate
display-coloring. The `SessionCancel` bind-once DI seam and the accepted concurrency
primitives (`PluginSlots`/`TEST_GATE`/`HeapPlan.heapPlan`) are not request-data globals.

Verdict: every audited gap is FIXED or a documented, rationalized deferral; all logged compromises are
resolved or intentional; the build is green.

## Post-review regression: the daemon adapter re-leaked `BuildUnit` (2026-07-07, FIXED)

The resident-daemon work (landed after the final review above) added
`cli/.../engine/EngineBuildListenerAdapter.java` (the engine adapter, in the then-`daemon` package), which reconstructed `BuildService.ModulePlan` and
`BuildPlanForecast.Module` from wire events by directly constructing synthetic
`new BuildGraph.BuildUnit(...)` — re-breaking the "zero `BuildGraph.BuildUnit` in `cli/src/main`"
invariant (M6/L8) that the review had just verified. The constructors were public precisely because
the adapter needed them.

**Fix (same day):** the reconstruction moved engine-side behind two wire factories —
`BuildService.ModulePlan.fromWire(dir, coord, pipeline, weight, fullyCached, cache)` and
`BuildPlanForecast.Module.fromWire(dir, coord, steps, …)`. Both `BuildUnit`-taking constructors
dropped to package-private, and `BuildGraph.BuildUnit` itself is now **package-private**, so the
boundary is compiler-enforced for good: no code outside `cc.jumpkick.runtime` can name or construct
a unit. The adapter names only `BuildService`/`BuildPlanForecast` facade types. Re-verified: zero
`BuildGraph.BuildUnit` references in `cli/src/main`; `NativeCommand`'s `orderModules(Map)` remains
the sole (front-end-safe) `BuildGraph` mention.

## Change log

- 2026-07-07 — daemon-adapter `BuildUnit` regression found in a docs-vs-code review and fixed
  (`fromWire` factories; `BuildUnit` package-private, boundary now compiler-enforced).
- 2026-07-06 — document created from the audit; all items OPEN.
- 2026-07-06 — Quick (Q1/Q2/Q3), Medium (M4/M5/M6), and Large (L7/L8) all landed green; Q3 layering
  compromise revisited and resolved (`RepoSource`); re-audit + final review complete.
- 2026-07-06 — Quick phase (Q1 FIXED, Q2/Q3 mostly fixed) + Medium M4/M6 FIXED landed green
  (`:cli:test` + `:engine:test`). Compromises logged. M5, L7, L8 remaining.
