# The slim client: shrinking `jk` to a thin front-end

Status: **COMPLETE — all five stages landed** (2026-07-08; stage 5, the dependency cut, closed the
arc). This is the successor arc to
[re-foundation.md](./re-foundation.md): that effort made the engine a server behind the
`BuildService` facade; this one makes the `jk` binary a genuinely thin client of it.

## Why

The `jk` binary's UX budget is download size, on-disk size, and shell-integration startup latency
(`jk hook-env` runs on every `cd`). Today the `:cli` Gradle module depends on *everything* —
`:io`, `:resolver`, `:toolchain`, `:engine` — so the native image links the entire kernel even
though the resident engine (same binary, for now) is where that code belongs. Tree-shaking helps,
but the honest fix is that the CLI should not have those classpaths at all.

Combined-resource-usage is the differentiator vs Gradle (whose daemon's memory appetite is its
most-complained-about trait): a tiny client that exits in milliseconds + one memory-disciplined
~256 MiB engine + workers that exit when the build ends.

## Target end-state

The `jk` client is exactly three things:

1. **A build-file reader** — parse/validate `jk.toml`, enough to answer trivially-local questions
   and to render good errors without a round-trip.
2. **A JDK installer/switcher** — the complete toolchain flow (`jk jdk …`, `activate`, `shell`,
   `hook-env`) stays client-side: it must work with no engine running and is on the latency-
   critical shell path.
3. **A display mechanism** — ANSI/TUI rendering, progress, prompts, Ctrl-C, exit codes, shell
   completion. The client renders engine event streams; it never computes what it renders.

Everything else — resolution, fetching, hashing, compiling, packaging, publishing, auditing,
import/export, git-source materialization — is served by the engine (which forks workers for the
truly heavy lifting) over the existing wire protocol.

Dependency rule at end-state: `:cli` depends on `:model` (+ the future thin `jk-api` client
library) and the toolchain-flow code only. No `:io`, no `:resolver`, no `:engine` on the CLI
classpath. Two build artifacts: a size-optimized native client binary (`-Os`, no `-march`) and
the engine as a plain jar directory hosted on the jk-managed JDK — the engine is a normal Java
app, never a native image (long-lived process: HotSpot's JIT and SHA-256 intrinsics serve its
hashing-heavy hot path, and building jars takes seconds instead of a multi-gigabyte image
compile).

## Constraints discovered so far

- **The engine spawn must survive its spawner.** ~~The engine is currently spawned in the client's
  process group~~ (FIXED 2026-07-07: the engine role detaches *itself* via a `setsid(2)` FFM
  downcall — Linux and macOS alike, no `setsid(1)` binary needed — and ignores SIGINT/SIGHUP
  everywhere; see docs/engine.md lifecycle §2. Windows full detach — `javaw.exe` for the JVM
  dist, `DETACHED_PROCESS` for the native exe — is noted in docs/engine.md's Windows item for the
  first real Windows hardening pass.)
- **Verbs that must work engine-less:** `jdk`/`activate`/`shell`/`hook-env` (shell path),
  `--help`/`--version`, and ideally `engine status/stop`. Everything else may lazily spawn.
- **The wire protocol grows per verb.** Today it hosts build/test/explain/verify-rebuild; each
  migrated verb (lock, sync, add's resolution, audit, publish orchestration, import/export) needs
  request/event vocabulary. The protocol stays version-locked to the binary (no compat shims)
  until a second front-end needs stability — same policy as docs/engine.md.
- **Memory ceilings are already enforced** (client 128 MiB / engine 256 MiB, SerialGC everywhere);
  migrating a verb into the engine must respect the 256 MiB budget or delegate to a worker.

## Staged plan

1. **Inventory (mechanical):** for every CLI verb, classify: (a) client-only at end-state,
   (b) engine-hosted, (c) worker-delegated. Record which kernel packages each command class pulls
   today (`jdeps` on the cli jar) — this is the tree-shaking reality check and the cut list.
2. **Detach the engine spawn** (setsid/double-spawn) so client lifetime never governs engine
   lifetime.
3. **Migrate the memory/CPU-heavy verbs first** (they're why the ceilings exist): `lock`/`sync`
   resolution + fetch, `audit`, git-source materialization. Each migration = protocol vocabulary +
   engine handler + client renderer, mirroring how `build`/`test`/`explain`/`verify` moved.
4. **Split the artifacts:** `jk` (client, -Os native image) and a dedicated engine artifact —
   and teach the spawn path to locate the engine artifact. (LANDED 2026-07-08 as two native
   images; REVISED the same day: the engine image was a design overshoot — the engine was always
   meant to run as a normal Java app, so the `jk-engine` image and its `-O3`/`-march` speed
   tuning were dropped with stage 5. What survives from stage 4: `EngineMain` as the dedicated
   engine entrypoint (`jk --engine-server` delegates to the same code — the JVM-dist route and
   the spawn fallback), the spawn-side artifact resolution with the choice recorded as the engine
   log's first line, and the client image's size-first flags. The engine now ships as a single
   fat jar, `~/.jk/lib/jk-engine-<version>.jar`, spawned on the jk-managed JDK with SerialGC +
   the 256/96 MiB heap from `max-heap-mb` as plain JVM flags. See docs/engine.md "Two
   artifacts".)
5. **Then cut `:cli`'s Gradle deps to `:model` (+ api)** and let the compiler enforce it, the same
   way `BuildGraph.BuildUnit` is package-private today. (LANDED 2026-07-08 — see "Stage 5
   as-built" below.)

Each stage lands green on its own; no big-bang.

## Stage 5 as-built

The dependency cut landed as real Gradle modules (compiler-enforced forever), following the
[inventory §4](./slim-client-inventory.md) move order. The as-built module graph:

```
jk (client image, :cli:nativeCompile, -Os; 26.9 MiB on linux/amd64 — see docs/engine.md)
├── :cli            presentation, arg parsing, renderers, EngineClient + wire adapters
├── :model          jk-api: domain currency, Goal/Phase event types, CliCommand model, TestSummary,
│                   MinimalXml (keeps java.xml/Xerces — ~3.7 MiB of image — off this classpath)
├── :core           jk.toml/jk.lock parse+edit, GlobalConfig, LibraryCatalog, deny policy,
│                   ModuleOrder (shared topo-sort), SourceLayout, WorkerTunings,
│                   Versions/DependencyTree/Provenance (offline lock walkers, still package
│                   dev.jkbuild.resolver)
├── :client-io      http (3 classes) · forge (device-flow auth) · credential stores ·
│                   LibraryRegistryClient · Cas/Linking + AccessLedger + ClasspathResolver +
│                   RepoArtifactStore/RepoArtifactResolver/MavenLayout/M2Dirs — the blessed local
│                   CAS read/link surface (add --file, run/install exec classpaths, ide links)
├── :toolchain-jdk  the complete JDK flow (JdkService/JdkEnsure rehomed here) · JavaHomes ·
│                   discovery probes (doctor) · tool/app launcher shims + JarManifest ·
│                   script headers · Gradle/Pom exporters · KotlinResolver constants
├── :engine-api     the wire contract: EngineProtocol codec, EnginePaths/EngineTransport,
│                   WorkspaceRequest/Result, ModulePlan/ModuleOutcome, ExplainPlan, BuildPlan,
│                   BuildForecast, HostedEvents, WorkspaceBuildListener, CachePruneScheduler,
│                   WorkerJarNotFoundException
└── :plugin-api     Ndjson codec

~/.jk/lib/jk-engine-<version>.jar (the engine: a JVM app on the jk-managed JDK, never a native
image; :cli-engine:shadowJar) — additionally links
└── :cli-engine     EngineMain (the engine JVM's main), PosixDetach,
                    InProcessEngineImpl (the seam below), the JVM dist (installDist), and the
                    relocated CLI test suite
    ├── :engine     EngineServer + BuildService/goal factories + pipeline/tasks/workers
    ├── :toolchain  resolver-backed tool installs, compat/import machinery
    ├── :resolver   PubGrub + orchestrators
    └── :io         repo/Maven machinery, transports, effective POMs
```

**The `engineDisabledForTests()` mechanism** (the crux the plan called out): every command's
test-only in-process branch was moved verbatim into `InProcessEngineImpl` (module `:cli-engine`,
package `dev.jkbuild.command` so the bodies keep calling the commands' package-private rendering
helpers). The client-side seam is the `dev.jkbuild.cli.engine.InProcessEngine` interface in
`:cli`, discovered via `java.util.ServiceLoader` (the repo's established pattern — `Probes`):

- **JVM dist / test classpath:** `:cli-engine` is present, so `jk --engine-server` and the
  `jk.test.noEngine` in-process dispatch work exactly as before.
- **Client native image:** `:cli-engine` is absent by construction — the binary physically cannot
  host an engine, and `jk --engine-server` reports that plainly (`install
  ~/.jk/lib/jk-engine-<version>.jar`). `jk cache prune --background` on the slim binary delegates
  to the resident engine instead of running in-process.

Because the tests' in-process dispatch needs the full kernel, the CLI test suite moved to
`:cli-engine` wholesale (same packages; the self-host `cli-engine/jk.toml` carries the
`test-worker-jars` list that used to live in `cli/jk.toml`).

**Wire vocabulary added by the cut** (the two residues that had kept engine code on the client):

- `forecast-request`/`forecast-ack` — `jk build`'s pre-flight (fully-cached shortcut + dirty
  hint + lock-staleness). Previously the client ran `BuildService.resolveGraph`/
  `forecastDirtyDirs`/`workspaceLockStale` in-process — the whole forecaster (and its SHA-256
  hashing) on the -Os client. Now one synchronous round-trip; the engine JVM (whose HotSpot
  SHA-256 intrinsics are exactly right for this) does the hashing. Cost honestly stated: a
  fully-cached `jk build` now touches (and lazily spawns) the engine, which it previously
  avoided.
- `script-prepare-request` — `jk tool run <file>`'s parse/resolve/compile half (`ScriptGoals` in
  the engine, the close of the inventory's flagged ScriptRunner residue). The exec stays
  client-side; only `//JAVA_OPTIONS` is re-parsed locally.

Everything else rides vocabulary that already existed.

## Non-goals

- No public/stable RPC API before a second front-end exists (IntelliJ plugin will force this).
- No engine-less "degraded mode" for engine-hosted verbs — the no-fallback rule from
  docs/engine.md stands.
