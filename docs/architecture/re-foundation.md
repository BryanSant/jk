# jk architecture re-foundation (toward 1.0)

Status: in progress. Goal: a strict **engine-as-server / front-end-as-client** separation so the
CLI is one interchangeable client among future front-ends (IntelliJ plugin, VS Code extension, web
app, GitHub Action). Pre-1.0: breaking changes are welcome; no back-compat shims.

## North-star architecture

```
              ┌─────────────── front-ends (clients) ───────────────┐
   cli   │  IntelliJ plugin  │  VS Code ext  │  web app  │  GH Action
     │        (each: presentation + user interaction ONLY)
     ▼
  ┌──────────────────────────── jk-api (published surface) ────────────────────────────┐
  │  BuildService (facade)   Session/BuildRequest/BuildResult (DTOs)                     │
  │  GoalListener event stream   BuildStep + Worker SPIs   domain model (Coordinate, …)  │
  └──────────────────────────────────────────────────────────────────────────────────┘
     ▲
  engine (BuildService impl: scheduler, pipeline, tasks)  ── forks ──▶  plugin workers
     │
  toolchain · resolver · io · core · model
```

Rules:
- **The kernel (model→engine) is the server. It has NO process-global mutable state** — everything
  request-scoped flows through `Session`/`BuildRequest`. Two builds must be able to run in one JVM.
- **Front-ends never reach into engine internals** — only `jk-api` (`BuildService` + DTOs + events).
- **CLI-local presentation state is allowed** (`Theme.active`, glyph choice, ANSI): it is client-side
  and per-process; a web/IDE client brings its own presentation and never links it.
- One coherent SPI for "a thing that does build work" (`BuildStep`), and one for out-of-process tools
  (`WorkerClient`). No dead/aspirational contracts.

## Module layering (current, extended)

`model → core → {io, resolver, toolchain} → engine → cli`; `plugin-api → model`.
- `Session` and moved config value-types live in **`core`** (seen by io/resolver/toolchain/engine/cli).
- `BuildService` + `BuildRequest`/`BuildResult` live in **`engine`** (the service tier).
- The published **`jk-api`** surface is `model` + `core`'s DTOs + the engine service interface.
  M6 decides whether to physically extract a `jk-api` module or designate `model`+interfaces as it.

## Milestones (execute in order; skip nothing, but merge where cheap)

- **M1 — Session + de-globalize the kernel.** New `Session` (core) carrying `{JkConfig, workingDir,
  cacheDir, jdksDir, jvm settings, jdk/graal selection, output sink, cancel token}`. Thread through
  `BuildPipeline.Inputs`/`PhaseContext`. Remove kernel globals: `ActiveConfig` (core/io/engine reads),
  `JvmOptions.processSettings/heapPlan`, `WorkerSlots` static, `BuildPipeline.TEST_GATE/parallelTests`,
  `jk.jdk`/`jk.graal` system properties, `Quietable`'s `System.setOut`. Move `JvmOptions.Settings`
  (value) to `core`; the JVM-applying logic stays in `engine`. `GlobalCancel` becomes a cooperative
  per-session token (CLI keeps a process-level Ctrl-C handler that signals it).
- **M2 — BuildService facade + hoist orchestration.** Move `BuildCommand`'s workspace driver
  (parallel scheduler, `topoSortModules`, `ensureWorkspaceLockFresh`, memory planning, one shared
  ETA/calibration planner used by both build and explain) and the `JdkInstall` goal DAG into engine
  services. `build/explain/lock/installJdk(request, GoalListener) → result`. Typed `BuildResult`.
- **M3 — BuildStep SPI.** Promote `coreBuilder`'s inline phase-body lambdas to `BuildStep` classes
  (Strategy + Template-Method); `coreBuilder` becomes an assembler. Delete the dead
  `PluginContext.contribute`/`Plugin#register` or make it the in-process face of `BuildStep`.
- **M4 — Worker SPI + adapters.** Typed `WorkerClient<Req,Res>` owning locate(`WorkerJar`) + spec
  codec + launch(`PluginLoader`) + demux(prefix from manifest) + exit→result. Standardize the worker
  result envelope + one spec codec in `plugin-api`. Bridge java/kotlin compiler stacks
  (`CompilerWorker`). `IdeSdkRegistrar` SPI + move `Intellij*` out of `kernel/toolchain`. De-duplicate
  the byte-identical `compat` package into one shared module.
- **M5 — Model as single currency.** `Coordinate`/`Module` the one representation: `coordinate()`
  accessors on `Lockfile.Artifact`/`Dependency`, delete the ~43 `indexOf(':')` splits. Sealed
  source-kind discriminator replacing `git:`/`workspace:` magic prefixes. Builders for
  `JkBuild`/`Project`/`Lockfile`/`Artifact`/`Dependency`; retire telescoping ctors. `RepoArtifactResolver`
  facade in `io` (one owner of `"<name>+<url>"` + `"sha256:"` parsing) consumed by `CacheSync` and the
  CLI. Route digests through `Hashing`. Memoize `GlobalConfig`. One `${ENV}`+repo-TOML parser.
  Precompute `Scope.canonical()`.
- **M6 — jk-api + presentation facades.** Finalize the published surface (extract `jk-api` or bless
  `model`+interfaces). CLI facades: `ProjectContext` loader (replaces ~15 workspace-load preambles +
  ~22 error messages), `CliOutput` (`note/wrote/error/header/chip/path`), `WorkspaceCommand` template,
  `Exit` code vocabulary; delete unreachable parent `run()` bodies. Update self-host `jk.toml` and the
  jk.jk mirror.

## Status log

Branch: `refoundation`. Every commit below is green (`./gradlew :cli:test`).

**Done**
- Blueprint (this doc).
- **M1a** — `Session` + `SessionContext` (core); `ActiveConfig` reduced to a shim over them.
- **M1b** — `Session` threaded through `BuildPipeline.Inputs` (delegating ctors default it from
  `SessionContext.current()`, zero call-site churn); hot build-path reads (`BuildPipeline` phase
  bodies, `EffortWeights.predict`) now read `in.session().config()`.
- **M5 — Model as single currency (DONE)** —
  - `Scope.canonical()`/`tomlSection()` precomputed into enum fields; `GlobalConfig` memoizes
    `~/.jk/config.toml` per `(path,size,mtime)`.
  - **M5a** `Hashing` consolidation: added `newDigest`/`hex`/`hashHex`; routed the hand-rolled
    `MessageDigest`/hex copies (`JkWorkerSync`, `SigV4Signer`, publisher `Checksums`, `M2CompatWriter`,
    `JdkInstaller`, `TestStamp`) through it. Shaded worker plugins left isolated.
  - **M5b** single currency for locked artifacts: `Lockfile.Artifact` gained `moduleGroup()`/
    `moduleArtifact()`/`coordinate()` + `checksumHex()`/`sourcesChecksumHex()`; migrated the manual
    `name` splits + `sha256:` strips in `CacheSync`, `ClasspathResolver`, `LockOrchestrator`,
    `InstallCommand`, `IdeSupport` (dropped its duplicate `hexOf`).
  - **M5d** `RepoArtifactResolver` facade: one owner of `"<name>+<url>"` source parsing +
    `locateOrMaterialize`, deduping `CacheSync`/`IdeSupport`/`ClasspathResolver`.
  - **M5e** one `RepositoryToml` parser for the `[repositories.<name>]` table — `${ENV}` interpolation,
    bearer/basic creds, object-store — shared by `GlobalConfig` (lenient: unset var kept literal) and
    `JkBuildParser` (strict: unset var throws), differing only in the injected missing-var policy.
  - **M5c** explicit source-kind predicates on `Dependency`: the `git:`/`workspace:` magic prefixes
    (spelled as literals in ~8 discriminators + constructed in 3) are centralized as
    `WORKSPACE_PREFIX`/`GIT_PREFIX` behind `isWorkspace()`/`isWorkspaceRef`/`workspaceName`/
    `workspaceRef` + the `gitByName()`/`workspace()` factories. Git's `group()` no longer lies. The
    `workspace:` placeholder still travels as a bare module string (it must, until `WorkspaceMerge`
    rewrites it) but its shape is known in exactly one place. A full sealed `Source` type was rejected:
    it cannot hold at the `Lockfile.Artifact.deps()` String boundaries, so it would leak back to strings.
  - **M5f** retired the telescoping constructor ladders: `JkBuild.builder`/`Project.builder` are the
    readable path; deleted `JkBuild`'s 4–10-arg rungs and `Project`'s 10/12-arg rungs (kept the two/one
    genuinely-clear positional shortcuts). Migrated all callers preserving exact semantics.
    - *Observed latent smell (not fixed — out of scope):* `WorkspaceMerge`'s merged `JkBuild` drops the
      module's `[build]` (orderAfter/testWorkerJars/lint) and `[format]` blocks (the old 9-arg rung set
      them null). Preserved faithfully; worth a deliberate follow-up if merged modules should keep them.

**M1c — kernel globals onto `Session` (essentially done)**
The mutable global *channels that carry request data* now live on `Session` (each a green commit):
- `JvmOptions.processSettings`/`setProcessSettings` → `Session.jvm` (`WorkerTuning`, moved to core).
- `jk.jdk`/`jk.graal` `System.setProperty` → `Session.jdkSpec`/`graalSpec`; toolchain readers read it.
- `BuildPipeline.parallelTests`/`setParallelTests` → `Session.parallelTests`.
- **`ActiveConfig` deleted** — it had been a shim over `SessionContext.current().config()` since M1a;
  all ~40 refs migrated. `SessionContext` is now the single process-wide request holder (adding a
  `ScopedValue` binding would enable concurrent in-JVM builds with no caller changes).
- **`Quietable` relocated** kernel/core → cli: the kernel no longer calls `System.setOut`. Suppressing
  the CLI's own stdout for `--quiet` is a client concern (the engine routes output through
  `PhaseContext`/`GoalListener`, never `System.out`).

**M1c remainder (deferred — shared primitives; documented)**
- `WorkerSlots.slots` and `BuildPipeline.TEST_GATE` — shared *concurrency primitives* (not request
  data). Correct as per-invocation for the single-process CLI; per-session pools are server-hardening
  (a per-build engine context or a `ScopedValue`), not a data-leak fix.
- Leaf `refresh`/`rerun` reads (`restore/storePackaged`, `artifactFresh`, `TestSupport`,
  `CompileToolchain`/`KotlinBtaResolver`) now read the single `SessionContext.current().config()` —
  the request session, one holder. Explicit param-threading would only matter once `SessionContext`
  is made `ScopedValue`-scoped for concurrent in-JVM builds.

**M2 (in progress) — BuildService facade + hoist orchestration**
- `BuildService` (engine) created as the client-facing build front door (pure policy, no stream
  writes — mirrors `LockFlow`). Hoisted from `BuildCommand`: `ensureWorkspaceLockFresh` +
  `workspaceLockStale` (pre-build re-lock guard) and `computeWorkspaceLinks` + `linkModuleArtifacts`
  (post-build artifact placement under `<wsRoot>/target/`). CLI renders results only.
- `BuildPlanForecast` (the single explain/build planner) + `estimateTestCount` moved to the engine;
  `forecastDirtyDirs` hoisted into `BuildService`. The explain/build drift hazard is closed.
- **`WorkspaceScheduler`** (engine) now owns the parallel DAG dispatch (ready-set / topo-level,
  `JkThreads.io()`, fail-fast). Both `runGraphPlain` and `runGraphLive` drive it via a `UnitTask` +
  `LevelSink`; per-unit build + all presentation stay in the CLI callbacks. Verified end-to-end on a
  real 2-module workspace (dep ordering, live view, warm-cache no-op, `--no-parallel`, artifact
  linking, fail-fast exit=1).
- **`BuildService.buildWorkspace(request, WorkspaceBuildListener)`** landed — the front-end-callable
  entry point that owns the whole workspace build (resolve graph → memory plan → assemble goals →
  schedule → run each goal → link artifacts → fail-fast), emitting `onPlan`/`onModuleStart`(→ a
  `GoalListener`)/`onModuleFinish` events and writing nothing to stdout. The CLI's non-animated path
  (`--verbose`/`--output json`) is now a pure renderer over it (`runWorkspaceHeadless`), replacing
  `runGraphPlain`+`buildUnit`+`report`. A headless front-end (Action, web backend) drives a full
  build with one call. Verified end-to-end on a real 2-module workspace (dep order, warm-cache,
  artifact linking, fail-fast exit=1, `--output json`).
- **Phase 2 (done):** the interactive live view (`runGraphLive`) now also routes through
  `buildWorkspace` — one workspace-build entry point for both headless and live. `buildWorkspace`
  owns the ETA model + emits `onEtaEstimate`, and records the learned `PhaseTimings`/`Calibration`;
  the CLI live view is a pure renderer (`onPlan`→calibrate bar, `onModuleStart`→existing
  `AggregateModuleListener`, `onModuleFinish`→`[k/N]` + deferred output). No `GoalConsole` refactor
  was needed — `AggregateModuleListener` is directly constructible. Removed `buildUnitLive`,
  `UnitOutcome`, the inline pre-scan/ETA. Verified on a real 3-module dep chain (topo order,
  warm-cache shortcut, incremental cascade, artifact linking, fail-fast exit 1). Only TTY *animation*
  is un-observable in non-TTY; the view receives the identical call sequence as before.
- **M2 remainder:** the `--no-parallel` serial path (`runWorkspaceBuild`) still has its own
  prepare/run/ETA loop — a distinct rich serial UX — and could route through `buildWorkspace` (with
  `concurrency=1`) in a later pass; and the `Jdk*Command` install DAGs. Neither is a separation gap
  (both are CLI renderers over engine primitives), just remaining single-entry-point consolidation.

- **M3 — Step SPI + dead-code removal (DONE)** —
  - Deleted the dead `PluginContext` SPI (`project()`/`workDir()`/`contribute(Phase)`/`config()`): it
    described an in-process `Plugin#register` model that never existed — `Plugin` has only
    `manifest()`/`run()`, every plugin runs as a forked worker, and nothing called `contribute()`.
    The real composable-step SPI is `Phase`/`Phase.Body` + typed `GoalKey` context; a separate
    `BuildStep` type would be redundant indirection over `Phase.Body`.
  - Decomposed `coreBuilder`'s ~1000-line monolith: its 12 inline phase bodies (which captured a
    dozen effectively-final locals by closure) are now `private static Phase <name>Phase(StepContext
    cx)` methods over an explicit `StepContext` record — matching the pre-existing tail-phase pattern
    (`shadowPhase`/`sourcesPhase`/`nativeImagePhase`). Bodies relocated verbatim (verified
    byte-identical); `coreBuilder` is now a readable assembly.

- **M4 — worker SPI (PARTIALLY DONE)** —
  - **Done — `compat` de-dup:** `compat-bridge` carried 7 (near-)duplicate `dev.jkbuild.compat`
    classes (`BuildTool`/`InstalledTool`/`PassthroughEnv`/`ToolDistribution`/`ToolInstaller`/
    `ToolProvisioning`/`ToolRegistry`) already provided by `:toolchain` (a dependency). Deleted the
    worker's copies; the two cosmetically-divergent ones were functionally identical.
  - **Done — IDE registrar out of the kernel:** `IntellijSdkRegistrar` (IDE SDK-table XML writing,
    zero toolchain-internal users) moved from `kernel/toolchain/jdk` to `cli/command/ide` beside its
    sibling `IntellijIdeGenerator`; reads IntelliJ's config dirs via toolchain's now-public
    `IntellijJdkTable.defaultVendorRoots`. `IntellijJdkDir`/`IntellijJdkTable` stay — they are genuine
    JDK-registry/discovery classes used by `JdkRegistry` + the probe chain. A dedicated
    `IdeSdkRegistrar` *interface* was judged premature: only IntelliJ registers SDKs today (VS Code
    uses `settings.json` runtimes, a structurally different path) — introduce it if a second registrar
    lands.
  - **Deferred (needs its own session + native rebuild verification) — typed `WorkerClient<Req,Res>`
    envelope + `CompilerWorker` bridge.** The *transport* (`WorkerProcess`: fork + protocol-line split
    + two-way `converse`), *command assembly* (`JvmOptions.javaCommand`, `PluginLoader.command`), and
    *jar location* (`WorkerJar`) are already shared. What remains is the *typed messaging* layer: each
    of the 9 plugin workers emits its own ad-hoc NDJSON vocabulary (`##JKGIT:`, compile diagnostics,
    `ready`/`RUN`/`DONE`, …) that its host caller hand-parses. Target design: a canonical result
    envelope (`{kind: event|result|error|exit, prefix, payload}`) over the existing `ProtocolWriter`,
    and a generic host `WorkerClient<Req,Res>` that serializes a typed request and folds envelope lines
    into typed `Res` + a stream of events — collapsing the bespoke parse loops in `GitFetcher`,
    `WorkerJavac`/`KotlincDriver` (the `CompilerWorker` bridge unifies these two behind one typed
    compile client), and the ServiceLoader plugins. This rewrites the host↔worker wire format for all
    9 workers, so it must land with a `./gradlew nativeCompile` + installed-binary smoke test, not a
    unit-test-only pass — hence deferred rather than rushed.
- **M6** — Extract/bless `jk-api`; CLI facades (`ProjectContext`, `CliOutput`, `WorkspaceCommand`,
  `Exit`); delete unreachable parent `run()` bodies; update self-host `jk.toml` + jk.jk mirror.

## Long-term target: the workspace-build path (records the end state we're converging on)

One engine entry point, `BuildService.buildWorkspace(WorkspaceRequest, WorkspaceBuildListener)`, owns
*everything* about running a workspace build — because all of it is engine knowledge, reusable by any
front-end:
- **Orchestration:** resolve graph → memory plan → assemble goals → schedule (dep order, concurrent
  levels) → run each goal → link artifacts → fail-fast.
- **Estimation model:** the ETA (schedule-aware `EffortWeights.scheduleMillis` + `Calibration` cold
  anchor + per-module warm/cold rate) is computed in the engine and **emitted as `onEtaEstimate`
  events** — the front-end only renders the number. Re-projected from measured throughput per level.
- **Learning:** the per-module `PhaseTimings` ledger (EWMA) and host `Calibration` are recorded by the
  engine after a successful build — so every front-end's future estimates improve, not just the CLI's.

Front-ends are **pure renderers over `WorkspaceBuildListener`**: `onPlan` (per-module weights →
calibrate a bar), `onModuleStart` (→ return that module's `GoalListener`; the CLI returns its existing
`AggregateModuleListener`), `onModuleFinish`, `onEtaEstimate`, `onWorkspaceFinish`. The CLI live TUI,
the headless `--verbose`/`--output json` path, a GitHub Action, an IDE, and a web backend all consume
the same events. `WorkspaceRequest.dirtyHint` lets a caller that already forecasted (the CLI's
fully-cached "all up to date" shortcut) avoid a redundant forecast; a headless caller passes null.

Only genuinely client-local concerns stay in the CLI: TTY animation (`CommandManager`), deferred-output
ordering, glyph/theme choice, and the fully-cached-shortcut *rendering* decision.

## Invariants to hold at every commit
- `./gradlew :cli:test` (and the full suite) green.
- No new `System.out`/`System.err`/`System.setOut` in kernel modules; user text routes through
  `PhaseContext`/`GoalListener` (server) or `CliOutput` (client).
- No new process-global mutable statics in the kernel.
