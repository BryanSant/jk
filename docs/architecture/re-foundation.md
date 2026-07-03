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
- **M5 (partial, safe wins)** — `Scope.canonical()`/`tomlSection()` precomputed into enum fields;
  `GlobalConfig` memoizes `~/.jk/config.toml` parses per `(path,size,mtime)` (was re-parsed 20+×/run).

**Deferred (documented — cascade-heavy, multi-session; NOT started)**
These require pervasive threading or large structural moves and were intentionally not attempted
half-way (would risk a red branch). Rationale noted so the next pass can pick them up cleanly:
- **M1c** — Remaining kernel globals are the "host-JVM channel" statics: `JvmOptions.processSettings`/
  `heapPlan`, `WorkerSlots.slots`, `BuildPipeline.TEST_GATE`/`parallelTests`, the `jk.jdk`/`jk.graal`
  system properties, and `Quietable`'s `System.setOut`. De-globalizing them means threading `Session`
  (JVM tuning + jdk/graal selection + output sink + a per-session test gate/worker-slot pool) through
  the worker-fork layer (`WorkerProcess`, `JvmOptions`, `PluginLoader`) and every worker-launch site,
  plus the leaf provisioning/packaging reads still on the `ActiveConfig` shim (`restore/storePackaged`,
  `artifactFresh`, `TestSupport`, `CompileToolchain`, `KotlinBtaResolver`). `ActiveConfig` is deleted
  only once these are threaded. **Assumption:** until then, those leaf reads observe the ambient
  `SessionContext.current()` — correct for the single-session CLI; the multi-session server needs the
  threading above.
- **M2** — Hoist `BuildCommand`'s ~1,000-line workspace driver + the `Jdk*Command` DAGs into a
  `BuildService` facade in `engine`; unify the explain/build planner.
- **M3** — Extract `coreBuilder`'s inline phase-body lambdas into `BuildStep` classes; remove/realize
  dead `PluginContext.contribute`.
- **M4** — `WorkerClient<Req,Res>` + standardized worker envelope across the 9 plugins; `CompilerWorker`
  bridge; `IdeSdkRegistrar` SPI + move `Intellij*` out of `kernel/toolchain`; de-dup `compat` package.
- **M5 (remainder)** — `Coordinate`/`Module` as single currency + `coordinate()` accessors killing the
  ~43 `indexOf(':')` splits; sealed dependency source-kind; builders retiring telescoping ctors;
  `RepoArtifactResolver` facade; `Hashing` consolidation; one `${ENV}`+repo-TOML parser.
- **M6** — Extract/bless `jk-api`; CLI facades (`ProjectContext`, `CliOutput`, `WorkspaceCommand`,
  `Exit`); delete unreachable parent `run()` bodies; update self-host `jk.toml` + jk.jk mirror.

## Invariants to hold at every commit
- `./gradlew :cli:test` (and the full suite) green.
- No new `System.out`/`System.err`/`System.setOut` in kernel modules; user text routes through
  `PhaseContext`/`GoalListener` (server) or `CliOutput` (client).
- No new process-global mutable statics in the kernel.
