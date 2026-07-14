# The jk engine

`jk` is reversing a documented pillar: [`requirements.md`](requirements.md) said *"No daemon. Each
`jk` invocation is a fresh process."* That was true through v1's early design and is a deliberate,
permanent departure now — not a hedge, not an opt-in mode. (The resident process was briefly called
the daemon; it's the **engine** now.) This doc explains why it exists, and how it works.

## Why

Every `jk` invocation that runs a build sizes its own worker-JVM heaps by probing how much memory
the host has *right now* and dividing it across the JVMs that invocation plans to fork (see
`HeapPlan`/`JvmOptions`/`MemoryProbe` in `kernel/engine/.../worker/`). That's correct for one build
at a time. It's wrong the moment two `jk build`s run concurrently in different terminals: each one
independently sees "8 GiB free" and independently claims up to half of it, and together they
overcommit real RAM — the OOM this project has hit in practice.

The fix isn't smarter per-process math; no amount of per-process caution stops two independently
"conservative" processes from colliding, because neither knows the other exists. The fix is for
there to be exactly one process on the machine (per `jk` state directory — see below) that actually
knows what's running: one resident engine, hosting the build pipeline, coordinating memory and
worker slots across every concurrent request.

## What changes for you

Nothing, most of the time. `jk build` still means `jk build`. The first `jk build` or `jk test`
transparently starts the engine if one isn't already running; every subsequent engine-hosted command
in that shell — or any other shell on the machine, for the same `~/.jk` — talks to the same engine.
It stays warm while you're actively using it and exits on its own after a period of inactivity.

Three things are worth knowing:

- **The engine is load-bearing.** If it can't be reached and can't be started, the affected command
  fails with a clear error rather than silently falling back to running in-process. This is a
  deliberate trade-off (see [Failure modes](#failure-modes)) — the engine is now real infrastructure,
  not a pure optimization you can ignore.
- **It's per `jk` state directory, not per machine.** A different `JK_HOME`/`JK_STATE_DIR` gets its
  own engine. Two CI jobs, or a normal user account and a sandboxed test environment, don't share one
  unless they share that directory.
- **It always matches your `jk` version.** If you upgrade `jk` while an engine from the old version is
  still resident, the next invocation detects the mismatch, restarts the engine, and proceeds — no
  manual step.

## Lifecycle

The native dist ships the engine as a single fat jar — `~/.jk/versions/<v>/lib/jk-engine.jar`, built
from the same sources at the same version as the `jk` client that spawns it (see
[Two artifacts](#two-artifacts) below). The engine is a normal Java app on the jk-managed JDK,
never a native image; the client — a JDK installer and JVM launcher by trade — launches it. The
`jk` client binary itself can *also* run the engine role via an internal, hidden `--engine-server`
flag (mirroring how `jk cache prune --background` already reuses the same binary as a detached
one-shot worker via `CachePruneScheduler`) — that flag route is how the JVM dist runs the engine,
and the fallback when no engine artifact is installed. Both routes execute the exact same code
(`EngineMain`). Concretely:

1. A CLI command that needs the engine checks for a live one by **connecting to its socket and
   pinging it** — not by trusting a PID file, which can't tell "serving" from "still starting up" or
   "PID reused after a reboot." A live engine answers a `ping` with `pong` in well under a second.
2. No answer → the CLI spawns an engine, detached (its own stdin closed, its output going to a log
   file, not the caller's terminal), and waits (a few seconds, bounded) for the new engine's socket
   to come up. Which artifact it spawns is resolved in order: **(a)** a `JK_ENGINE_EXE` env
   override (always treated as a dedicated engine executable — its `main` is the engine loop, no
   flag); **(b)** `~/.jk/versions/<v>/lib/jk-engine.jar` — the side-by-side installed layout, where the version's
   version must equal the client's own version (a missing or version-skewed jar never launches).
   When the released native client finds no matching jar, it downloads one itself before falling
   back — `releases/<its own version>/jk-engine-<version>.jar` from the release site, verified
   against the release's `SHA256SUMS`, ingested into the CAS, and materialized into `versions/<v>/` (`EngineJarFetcher`;
   the client is a JDK installer and JVM launcher by trade — fetching its own engine is the same
   move as installing a JDK to host it, and it makes upgrades self-healing). There is deliberately
   no `jk engine fetch` verb: the fetch is built into the spawn. The JVM dist (which hosts the
   engine itself), `--offline` runs, and `-SNAPSHOT` builds never auto-fetch. The jar is
   launched as `<managed-jdk>/bin/java -XX:+UseSerialGC
   -Xms…/-Xmx… -cp ~/.jk/versions/<v>/lib/jk-engine.jar dev.jkbuild.cli.EngineMain` on the jk-managed
   default JDK. The host JDK must meet the engine's runtime floor (the release that compiled the
   client and jar — a global default pinned older for *project* builds is skipped, since it governs
   worker JVMs, not the engine host); when nothing installed qualifies, the client installs
   exactly the floor release, with no global-default side effects. The spawn line also carries
   an AOT cache (JEP 514, `<engine-state>/engine-<jar-key>.aot`): the first spawn after a jar
   change trains it (`-XX:AOTCacheOutput`, assembled on clean exit — idle recycling makes that
   routine), and every later cold start maps pre-parsed class metadata plus AOT-compiled code,
   taming the fresh JVM's JIT-warmup tail. The key ties the cache to the exact jar because
   `AOTMode=auto` silently ignores a mismatched cache — an unkeyed file would stop helping at
   the first upgrade and never retrain; **(c)**
   the `jk` binary itself, re-invoked with the internal `--engine-server` flag — the JVM dist and
   dev workflows. The choice is recorded as the first line of the engine's log file. Every route
   sizes the heap from `max-heap-mb` (see [Memory target](#memory-target)): real JVM flags on the
   `java` spawn line, `JK_OPTS` on the JVM-dist start-script route.
   Detached means *really* detached: first thing in the engine role, the process moves itself
   into its own POSIX session — `setsid(2)` called directly via an FFM downcall (works on Linux
   *and* macOS, which ships no `setsid(1)` command) — and ignores terminal SIGINT/SIGHUP
   everywhere. So a Ctrl-C aimed at
   the client that happened to spawn it can never take down the engine and the other builds it is
   hosting. (`SIGTERM` to the engine's own PID stays lethal on purpose; cancelling one build is a
   wire-level concern, never a signal.)
3. If two `jk` invocations race to spawn an engine at the same instant, both spawn a candidate
   process; both try to win an advisory file lock (the same `FileChannel.tryLock()` pattern the
   opportunistic cache-prune background job already uses); the loser exits immediately and the
   winner's socket is what both original invocations end up talking to.
4. The engine serves requests — one build, test run, etc. at a time per request, but multiple
   requests concurrently, each isolated in its own build session.
5. The engine then stays resident — there is no idle countdown and it never exits on its own. It
   runs until `jk engine stop` (or the process is killed, or a version-skew upgrade replaces it),
   and a later `jk` invocation after a stop spins a fresh one back up exactly as in step 2.

### Configuring the engine

`~/.jk/config.toml` — the single user-global config file, not a project's `jk.toml` (engine lifetime
and sizing are machine/user policy, not something one project should be able to force on another):

```toml
[engine]
max-heap-mb = 256   # default; env override JK_ENGINE_MAX_HEAP_MB
```

The engine is **resident**: once started it stays running until an explicit `jk engine stop` (or a
version-skew replacement). It does not self-terminate when idle — it's lightweight (see
`max-heap-mb` below), and it also serves the HTTP dashboard, so keeping it warm is the whole point.

`max-heap-mb` — the heap ceiling for the engine *process itself*, applied by the spawning client
(`-Xmx`, alongside `-Xms32m` pre-sizing and SerialGC; a process can't shrink its own ceiling, so
this necessarily lives on the spawn path). Default `256` — this *is* the memory target, enforced.
The engine never needs the runtime's uncapped default (¼ of RAM) because it is pure orchestration:
compiles and tests run in separately-sized worker JVMs. Raise it on a CI box hosting many large
concurrent builds; **`0`** = uncapped. Verify with the `heapMaxBytes` field of
`jk engine status --output json`.

Changing this value takes effect for the *next* engine — `jk engine stop`, then let the next
command spin up a fresh one.

## Two artifacts

The native dist is one image plus one fat jar, with two *classpaths* (slim-client Stages
4+5 — see [slim-client.md](architecture/slim-client.md)), because the two processes have opposite
performance budgets:

- **`jk` (the client, `:cli:nativeCompile`)** is judged on download size, on-disk size, and
  startup latency (`jk hook-env` runs on every `cd`) — a native image built size-first (`-Os`, no
  `-march`, a 128 MiB heap cap) **from the slim classpath**: since Stage 5 the `:cli` module no
  longer links `:engine`/`:io`/`:resolver`/`:toolchain` at all, so the image physically contains
  no engine code. Measured on linux/amd64: **26.9 MiB on disk (8.3 MiB xz-compressed download),
  down from 32.8 MiB** when the image carried the full classpath. Roughly half the remaining size
  is JDK infrastructure the client's own duties require (TLS + HTTP for JDK/auth/registry
  traffic, FFM for the terminal) plus the GraalVM runtime; `MinimalXml` keeps the `java.xml`
  module (Xerces, ~3.7 MiB of image) off the classpath the same way `MinimalTar` avoids
  commons-compress.
- **`~/.jk/versions/<v>/lib/jk-engine.jar` (the engine, `:cli-engine:shadowJar`)** is a long-lived
  resident process, which is exactly what a JVM is best at — so it is a plain Java app, never a
  native image. The client spawns it on the jk-managed JDK; HotSpot's JIT and SHA-256 intrinsics
  serve its hashing-heavy hot path (CAS and classpath fingerprinting on every no-op build), and its
  profile is ordinary JVM flags on the spawn line: SerialGC with the 256 MiB / 96 MiB heap
  numbers from `max-heap-mb`. Its `main` (`EngineMain`) *is* the engine-server loop — no verb
  routing, no flag. Building it is `./gradlew dist` territory (one jar, seconds) — no native-image
  compile, no multi-gigabyte builder process.

Both bake the same `JkVersion`, so the handshake's [version-skew](#version-skew) check works
identically whichever artifact is serving. The JVM dist (`:cli-engine:installDist`) deliberately
stays a single start script: everything is on its classpath, and the JVM engine role is reached
via the same hidden `--engine-server` flag the fallback spawn path uses — on the JVM dist that
flag finds `EngineMain` through the `InProcessEngine` ServiceLoader seam; on the slim client
image (where no engine code exists) it reports plainly that the engine jar is required.

## Manual control

Alongside the automatic lifecycle, three explicit commands:

| Command | Effect |
|---|---|
| `jk engine start` | Eagerly start the engine and wait until it's confirmed live (or fail with a clear error). A no-op if one's already running. Useful for pre-warming a CI container. |
| `jk engine stop` | Gracefully shut the engine down (waits briefly for in-flight work, then stops). Reports "not running" (not an error) if there isn't one. |
| `jk engine status` | Reports whether an engine is running and, if so, its PID, `jk` version, uptime, best-effort memory usage (heap used/committed/max from the runtime, plus process RSS where the OS exposes it — Linux `/proc`), and how many jobs are in flight. `--output json` carries the same numbers as `heapUsedBytes`/`heapCommittedBytes`/`heapMaxBytes`/`rssBytes` (`-1` = unobservable). |

## On-disk layout

Everything lives under `~/.jk/state/engine/` (`state/` because it's mutable per-host runtime state,
not config or cache — see `JkDirs`), keyed by a short hash of the resolved state directory so
different `JK_HOME`/`JK_STATE_DIR` values naturally get independent engines:

```
~/.jk/state/engine/
├── <key>.sock   # Unix domain socket the engine listens on (or a loopback port number on Windows)
├── <key>.token  # Windows only: the shared secret every connection must send first
├── <key>.lock   # advisory lock proving single-instance ownership
├── <key>.pid    # PID + start time, for `jk engine status` display only
├── <key>.log    # the engine's own stdout/stderr — check here if lazy-start fails
└── <key>.log.1  # the previous engine's log, kept one generation deep (see below)
```

Each fresh engine start rotates any existing `<key>.log` to `<key>.log.1` before truncating a new
`<key>.log` — not truncate-in-place. Without this, an engine that crashed would have its own log
destroyed the moment the next command lazily respawns a replacement (which happens automatically,
often before anyone's looked at it) — exactly the file `jk engine status`/the lazy-start error
message point you at for post-mortem. One historical generation is enough for that purpose; this
isn't a general log archive.

The `.lock` file is the actual source of truth for "is an engine running here": it's held for the
whole life of the engine process and released automatically by the OS the instant that process
exits, cleanly or via `kill -9`. The `.pid` file is a display convenience for `jk engine status`,
never trusted on its own — only after a successful ping.

## Wire protocol

Deliberately internal and unversioned. Since an engine can only ever be started by a matching-version
`jk` binary (see [version skew](#version-skew) below), the client and server are always the same
build, and the protocol is free to change between `jk` releases with no compatibility concerns. (The
same engine is expected to eventually back a web backend, IDE plugin, or MCP server too — formalizing
a stable, versioned protocol is deferred until one of those actually needs to talk to an engine it
didn't just spawn itself.)

- **Transport**: a Unix domain socket (plain JDK NIO, no third-party dependency) on macOS/Linux — the
  platform this whole design was built and verified against. On Windows, where JDK NIO's UDS support
  is newer and less consistently available across JDK/OS version combinations, `EngineTransport`
  switches to a loopback TCP port instead, picked by the OS at bind time (`:0`) and recorded (instead
  of a socket path) in the `.sock` file. Because a TCP port isn't filesystem-permission-gated the way
  a socket file is, every connection on this path must send a per-engine shared secret (`.token`,
  written alongside `.sock`) as its very first line, before anything else is processed — connections
  that don't are closed without a reply. `EngineServer`/`EngineClient`'s request-handling code is
  otherwise transport-agnostic; it only ever deals with an already-connected `SocketChannel`.
- **Framing**: newline-delimited JSON, one message per line — the same style `jk`'s existing
  worker-process protocol already uses for compiler/test/git workers, rather than a new
  length-prefixed scheme. Every dynamic string (a path, an error message, a line of build output)
  is escaped the same way that existing protocol already requires.
- **Message shape**: the same `{"t":"<type>", ...}` discriminated-envelope shape jk's existing
  worker protocol already uses (see `WorkerClient`) — connection handshake
  (`hello`/`hello-ack`/`ping`/`pong`), a `build-request`/`test-request` to start an operation and a
  `build-cancel` to abort one, one event type per `WorkspaceBuildListener`/`GoalListener` callback
  (`plan-module`/`module-start`/`progress`/`output`/`warn`/`error`/`goal-finish`/...), and a terminal
  `workspace-finish` or `build-error`. A variable-length collection (the module plan, a goal's
  accumulated diagnostics) is always a burst of repeated single-item messages plus a terminal marker
  (`plan-module`+`plan-phase`+`plan-done`, `goal-diagnostic`*+`goal-finish`) rather than one message
  with a nested JSON array — the hand-rolled codec (`EngineProtocol`, mirroring `Ndjson`) only reads
  flat scalar fields and string arrays, deliberately, to stay dependency-free. See
  `EngineProtocol`/`EngineServer`/`EngineBuildListenerAdapter` for the exact, current vocabulary —
  it's internal and expected to keep changing (see above), so this doc doesn't enumerate every type.
- **Rendering stays identical.** The CLI's live progress bars, spinners, and `--output json` mode
  already consume a build as a stream of listener callbacks (`onPlan`, `onModuleStart`, ...) — that
  was already true before the resident engine existed, because the build core was already built to
  be driven by any front-end, not just the CLI. The engine just moves where those callbacks
  originate from: a
  thin adapter on the CLI side turns each wire message back into the exact same callback the
  renderer already handles when running without an engine. You should never notice a difference in
  what a build prints.

## What runs where

**In the engine:** `jk build` (`BuildService.buildWorkspace` for a workspace, plus a dedicated
single-goal path for a single project with no `[workspace]` table — both fork worker JVMs the same
way, so both needed to move for the OOM fix to actually close), `jk test` (a single project's
compile+test goal), and `jk explain` (`BuildService.explain`, a synchronous read with no worker JVM
forked — hosted mainly for consistency: the engine is the one process that always has a live,
correctly-resolved build graph and caches open), plus the resolver family — `jk lock`, `jk sync`,
and `jk update` (slim-client Wave 1: the PubGrub solve, CAS fetches, and git-source
materialization run engine-side; the CLI renders the streamed events and colorizes them
client-side) — dependency resolution, the build pipeline and scheduler, worker-JVM orchestration
and the shared memory/heap-slot accounting that this whole effort exists to fix, and the on-disk
build/action caches. `jk build` also freshens a stale merged workspace lock engine-side before
building (the request carries `freshenLock`; `jk verify`'s scratch rebuild opts out to keep the
pinned lock verbatim).

Also in the engine (slim-client Wave 2 — the hosted worker verbs): `jk audit`, `jk format`,
`jk publish`, `jk image`, `jk import`, and `jk mvn`/`jk gradle`'s distribution *provisioning* —
the six paths that used to fork plugin workers directly from the CLI process now fork them from
the engine (sized by its shared worker-memory plan), with structured results streaming back as
events (`audit-finding`, `format-file`, `import-note`) and count/summary-carrying `goal-finish`
variants. `jk format`'s formatter-jar resolution (previously an in-client `ToolResolver` run)
moved with it. Pre-flight stays client-side: `jk publish` resolves its repository credential and
GPG passphrase in the client (env/keychain belong to the invocation, not to the engine's inherited
environment) and passes them in the request over the user-owned socket; `jk import` does its
source detection/overwrite checks before sending. The `jk mvn`/`gradle` *exec* of the provisioned
tool deliberately stays in the client with inherited stdio — a foreign build's interactive run
belongs to your terminal; only its download/link moved.

Also in the engine (slim-client Wave 3 — the in-process `BuildPipeline` stragglers): `jk compile`
(the shared pipeline in compile-only mode, `jk test`'s single-goal wire shape), `jk run`'s *build*
half (it rides the existing single-project build request; only the exec of the user's program stays
client-side — it owns your terminal, same reasoning as `jk mvn`/`gradle`'s exec), `jk native` (the
full pipeline plus the native-image tail, the `native-image` child process forked engine-side like
a worker; the cascade speaks `jk build`'s workspace event vocabulary with engine-computed exit
codes), and `jk install`'s heavy halves — the git clone of a `jk install <git-url>` (git runs
in-process in the engine: `GitFetcher` prefers a local `git` command, else bundled
JGit) and the build + cache-install of the project (jar/pom into `~/.m2` and
the local repo index). Pre-flight stays client-side, same rule as always: GraalVM resolution — and,
with consent, its install — happens in the client before the `native`/`install` request is sent
(the prompt owns the terminal; the engine only ever runs an already-resolved toolchain), and `jk
install`'s "make install" (launcher/binary into `~/.jk/bin` + `~/.jk/lib`) runs client-side after the
hosted goal succeeds. `jk explain`'s build-time estimate also moved engine-side: the request
carries the plan-affecting build options, `BuildService.estimateEtaMillis` computes the
schedule-aware ETA next to the plan (closing the long-standing client-side
`BuildPipeline`/`EffortWeights`/`Calibration` reach the re-foundation flagged), and the result
rides back as an `eta` event in the explain burst.

Also in the engine (slim-client Wave 4 — the long tail; Stage 3 of the slim-client migration is
complete with it): `jk tool install` / `jk tool run` / `jk install <g:a:v>`'s Maven tool
resolution (the transitive POM walk + jar fetches run engine-side as one `tool-resolve-request`;
the launcher write into `~/.jk/bin` and the inheritIO *exec* of the tool stay client-side — your
terminal, same rule as every exec), `jk ide`/`idea`/`vscode`'s dependency sync (it simply rides
the hosted `jk sync` against the workspace root; the `.idea`/`.iml`/`.vscode` file *generation* is
cheap, local, and stays client-side), and — the correctness half of the wave — **cache
maintenance as an idle-boundary job**: `jk cache prune`, `jk cache purge`, and `jk clean --cache`
send a maintenance request that the engine executes only when no pipeline is in flight. A fair
read/write gate makes that exact, not best-effort: every hosted pipeline holds the read side for
its whole run, maintenance takes the write side, so a CAS sweep can never delete blobs from under
an in-flight build *in this engine*, and new builds queue briefly behind a running sweep. While
queued, the engine emits a `prune-wait` event so the client can say "waiting for N in-flight
builds" instead of sitting silent. The old post-build detached `jk cache prune --background`
self-spawn is gone from the engine paths — a successful hosted build/sync just enqueues an
engine-internal prune that runs at the next idle boundary. Cross-*process* safety still rides the
on-disk `.prune.lock` (two engines with different state directories can legitimately share one
`JK_CACHE_DIR`), which every hosted maintenance run — and the internal idle job — now takes;
`purge`'s confirmation prompt and dry-run stay client-side. (`jk export gradle`/`maven`, once
penciled in for hosting, turned out to be pure local `jk.toml`+`jk.lock` → text transforms and
deliberately stay client-only.)

**Always in the CLI, never the engine:** anything tied to your terminal or shell — the live TUI,
color/theme, Ctrl-C handling (a signal has to reach the actual foreground process you're looking
at), shell completion generation — and, deliberately, all of `jk jdk install` (including its
non-interactive core, not just the install wizard). A JDK install can't cause the kind of memory
contention the engine exists to prevent, installs are already safe to run concurrently without any
coordination, and a machine that has never even built anything shouldn't need an engine just to
install a JDK. The same rule shapes hosted `jk sync`: its `ensure-jdk` phase *can* trigger a JDK
download, so the sync command pre-flights the ensure/install client-side before sending the
request, and the engine-side phase only ever resolves an already-installed JDK (reporting a
structured "not installed" error otherwise — it never downloads silently). The same pre-flight rule
covers GraalVM for `jk native`/`jk install`: the client resolves (prompting/installing with
consent) before the request, and the engine runs only the resolved toolchain.

## Version skew

Every connection starts with a handshake: the CLI states its version, the engine states its own. If
they don't match — you upgraded `jk` since the engine started — the CLI kills the stale engine,
starts a fresh one (which, having just been launched by the current binary, necessarily matches),
and retries your command against it. You never see this happen; it costs the one extra startup.

## Failure modes

- **The engine disconnects mid-request** (it crashed, or was killed): your command fails immediately
  with a clear error pointing at `jk engine status` for more detail, rather than hanging or silently
  finishing without a result. This is the sharp edge of "the engine is load-bearing" — earlier
  versions of `jk` never had a background process that could disconnect out from under a build, and
  now this failure mode exists. It's an accepted trade-off, not an oversight.
- **The engine is killed externally** (`kill -9`, a machine reboot with no clean shutdown): the next
  `jk` invocation's liveness check simply finds nothing listening, and starts a fresh engine exactly
  as it would if none had ever run. No stale-lock cleanup is needed — the advisory lock is released
  by the OS the moment the process is gone.
- **A `jk engine start`/lazy-start attempt itself fails** — the error points at the engine's own log
  file (`~/.jk/state/engine/<key>.log`) rather than leaving you guessing.

## Memory target

The engine targets **256 MiB** — no longer just an aim: the spawn-time heap ceiling defaults to
exactly that (`max-heap-mb` — see [Configuring the engine](#configuring-the-engine)), with
`-Xms32m` pre-sizing and `-XX:+UseSerialGC` on the spawn line for low GC overhead on a heap this
size. Observable at any time via the
memory line of `jk engine status`. It doesn't do the heavy lifting
itself — compiles
and test runs still happen in forked worker JVMs, sized by the same shared `HeapPlan` machinery as
always, just now coordinated across every concurrent request instead of guessed independently per
process. The engine's own footprint stays small because it deliberately avoids new large resident
caches: dependency-resolution ledgers and build-timing history are small and cheap enough to
re-read from disk per request rather than hold in memory for hours, and anything that's already a
disk-backed cache (the content-addressed build cache, the action cache) stays exactly that.

## Rollout

Landed in phases, each shipped with its own passing test suite and manual verification against a
real native-image binary:

1. **Prerequisite hardening (done).** The engine's per-request session state (`SessionContext`)
   propagates correctly across concurrent in-JVM work (`ContextPropagator`). Worker-JVM memory
   planning (`JvmOptions.planAndApply`) no longer runs unconditionally inside `buildWorkspace` — a
   `WorkspaceRequest.applyMemoryPlan` flag lets a host serving concurrent requests (the engine) plan
   once for its own concurrency instead of letting each call overwrite the shared budget.
2. **Engine skeleton (done).** Process lifecycle, single-instance election (`.lock` `tryLock()`), the
   Unix-domain-socket handshake/liveness/status/shutdown protocol, and `jk engine start/stop/status`.
   Verified end-to-end: start/status/stop, `kill -9` recovery (stale socket/lock cleanup) against the
   real binary. The engine is resident — it never self-terminates; `jk engine stop` drains it.
3. **`jk build` (done).** Every `WorkspaceBuildListener`/`GoalListener` callback streams over the
   wire as its own message type (`EngineServer`'s `wireListener`/`wireGoalListener`,
   `EngineBuildListenerAdapter` client-side); the CLI's existing renderers (`AggregateModuleListener`,
   the headless buffer listener) are unchanged — only where their events originate from changed.
   Verified with a real multi-module workspace: correct compilation/packaging/cross-module
   dependencies, a clear error (no hang) when the engine is killed mid-build, and — the actual bug
   this exists to fix — two concurrent `jk build`s in different directories sharing one engine
   without OOMing.
4. **`jk test` (done).** A single project's test goal reuses the exact same goal-level wire vocabulary
   `jk build` already speaks (tagged with a fixed sentinel `dir` since there's only one goal, not a
   module list) — no new protocol surface beyond the request/response pair and carrying the test
   pass/fail counts on the terminal `goal-finish` event. Verified: passing tests, a failing test
   (correct exit code 4, full failure detail rendered), and two concurrent `jk test` runs sharing one
   engine.
5. **Single-project `jk build` (done).** Closed the gap where only *workspace* builds were
   engine-hosted: a single project with no `[workspace]` table now runs its real (non-test-only)
   build goal through the same single-goal wire vocabulary as `jk test`, on the engine. The build
   outcome summary line (e.g. "project up to date" vs. "project built") carries over the wire on the
   terminal `goal-finish` event so the CLI's post-build message matches the in-process path exactly.
6. **`jk explain` (done).** `BuildService.explain`'s module/phase/edge forecast streams over the wire
   as a burst of messages (one per module, one per phase, one per dependency edge), reconstructed
   client-side into a real `BuildService.ExplainPlan` — no in-process fallback for the plan itself.
   The ETA estimate built on top of it initially stayed client-side; slim-client Wave 3 moved it
   engine-side too (see [What runs where](#what-runs-where)).
7. **Windows transport (done).** `EngineTransport` switches to a loopback TCP port plus a
   shared-secret token (see [Wire protocol](#wire-protocol)) when `os.name` says Windows, instead of
   the Unix domain socket every other platform uses — `EngineServer`/`EngineClient`'s request
   handling is unchanged either way, since both only ever see an already-connected `SocketChannel`.
   Verified by forcing `os.name` in the JVM test suite (no real Windows host available in this
   environment) — a real Windows machine has not exercised this path. Treat it as implemented but
   not field-verified until one does. For that first real Windows hardening pass, two spawn-detach
   notes: the JVM-dist engine spawn should launch via `javaw.exe` (no console attachment, so
   `CTRL_C_EVENT` from the client's console can never reach the engine, and no console window
   flashes) instead of the `jk.bat` → `java.exe` start script; the native `jk.exe` equivalent is
   the `DETACHED_PROCESS`/`CREATE_NEW_PROCESS_GROUP` creation flags, which Java's `ProcessBuilder`
   cannot express — that wants the engine's own launcher (see the slim-client artifact split).
   Until then, the engine role's SIGINT-ignore (which maps to a console-ctrl handler on Windows)
   absorbs console Ctrl-C there too.
8. **Explicitly deferred, not scheduled — precise concurrent memory accounting (demand registry).**
   **Status: DEFERRED (logged), not OPEN** — this is a considered decision, not a known bug. The
   current engine sizes one shared worker-JVM budget for the host's core count once at startup
   (`planSharedWorkerMemoryOnce`), not a live registry that grows/shrinks `HeapPlan`/`WorkerSlots` per
   in-flight request. A demand-registry is real complexity (live recomputation on request churn,
   re-plumbing `HeapPlan`/`WorkerSlots` from static to reactive) that's only worth paying for if the
   coarse sizing actually under-parallelizes in practice — no such evidence exists yet, so building it
   speculatively would be complexity for a hypothetical need.

   Notably, Gradle — at far larger scale — doesn't solve this with a live registry either: a Gradle
   daemon serves one build at a time (concurrency = more daemon *processes*, each with a static
   `-Xmx`), and cross-daemon memory pressure is handled reactively (self-terminate under low system
   memory), not by a proactive shared-budget calculation. That's a data point for leaving this
   deferred, not a reason to skip the check below.

   **Revisit trigger — re-open this item once any of these is actually measured, not assumed:**
   - *Concurrency distribution*: sample `activeConnections` (already tracked, surfaced via `jk engine
     status`) over real usage. If concurrent in-flight requests are rare, the coarse plan already
     matches actual demand and there's nothing to optimize.
   - *Single-build regression*: compare wall-clock time for one isolated build under the engine's
     coarse sizing vs. the pre-engine per-invocation `JvmOptions.planAndApply` (which sized generously
     for that build alone). A measurable regression in the common single-build case is the real cost
     of *not* having a demand-registry.
   - *Queuing-while-idle*: under synthetic concurrent load (2/4/8 simultaneous single-project or
     workspace builds), check whether anything blocks on `WorkerSlots` while host RAM/CPU sits idle —
     that's the literal signal of "under-parallelizes."

   If none of these ever shows a real cost, this item should stay deferred indefinitely — it is not a
   TODO waiting for someone to get to it.
9. **Explicitly deferred, not scheduled — a stable versioned protocol.** Only needed if a second
   front-end besides this CLI ever needs to talk to a `jk` engine directly; see
   [Wire protocol](#wire-protocol).
