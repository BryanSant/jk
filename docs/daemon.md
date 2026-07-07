# The jk daemon

`jk` is reversing a documented pillar: [`requirements.md`](requirements.md) said *"No daemon. Each
`jk` invocation is a fresh process."* That was true through v1's early design and is a deliberate,
permanent departure now — not a hedge, not an opt-in mode. This doc explains why, and how the
daemon works.

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
knows what's running: one resident daemon, hosting the build engine, coordinating memory and worker
slots across every concurrent request.

## What changes for you

Nothing, most of the time. `jk build` still means `jk build`. The first `jk build` or `jk test`
transparently starts the daemon if one isn't already running; every subsequent daemon-hosted command
in that shell — or any other shell on the machine, for the same `~/.jk` — talks to the same daemon.
It stays warm while you're actively using it and exits on its own after a period of inactivity.

Three things are worth knowing:

- **The daemon is load-bearing.** If it can't be reached and can't be started, the affected command
  fails with a clear error rather than silently falling back to running in-process. This is a
  deliberate trade-off (see [Failure modes](#failure-modes)) — the daemon is now real infrastructure,
  not a pure optimization you can ignore.
- **It's per `jk` state directory, not per machine.** A different `JK_HOME`/`JK_STATE_DIR` gets its
  own daemon. Two CI jobs, or a normal user account and a sandboxed test environment, don't share one
  unless they share that directory.
- **It always matches your `jk` version.** If you upgrade `jk` while a daemon from the old version is
  still resident, the next invocation detects the mismatch, restarts the daemon, and proceeds — no
  manual step.

## Lifecycle

The `jk` binary itself is both the client you invoke and the daemon it starts — there's no separate
daemon binary. An internal, hidden flag distinguishes the two roles at startup (mirroring how `jk
cache prune --background` already reuses the same binary as a detached one-shot worker via
`CachePruneScheduler`). Concretely:

1. A CLI command that needs the engine checks for a live daemon by **connecting to its socket and
   pinging it** — not by trusting a PID file, which can't tell "serving" from "still starting up" or
   "PID reused after a reboot." A live daemon answers a `ping` with `pong` in well under a second.
2. No answer → the CLI spawns a daemon: the same `jk` binary, re-invoked with the internal
   daemon-server flag, detached (its own stdin closed, its output going to a log file, not the
   caller's terminal), and waits (a few seconds, bounded) for the new daemon's socket to come up.
3. If two `jk` invocations race to spawn a daemon at the same instant, both spawn a candidate
   process; both try to win an advisory file lock (the same `FileChannel.tryLock()` pattern the
   opportunistic cache-prune background job already uses); the loser exits immediately and the
   winner's socket is what both original invocations end up talking to.
4. The daemon serves requests — one build, test run, etc. at a time per request, but multiple
   requests concurrently, each isolated in its own build session.
5. After being idle (no in-flight requests) for a configured period, the daemon exits on its own. A
   future `jk` invocation spins a fresh one back up exactly as in step 2 — this is silent and
   automatic, never something you need to think about.

### Configuring the idle timeout

`~/.jk/config.toml` — the single user-global config file, not a project's `jk.toml` (daemon lifetime
is a machine/user policy, not something one project should be able to force on another):

```toml
[daemon]
idle-minutes = 120   # default
```

- **A positive number** — minutes of no in-flight requests before the daemon exits. Default `120`.
- **`0`** — exit as soon as the current workload finishes; don't linger even briefly.
- **`-1`** — never self-terminate. Useful on a CI runner or a workstation where you'd rather keep the
  daemon (and whatever it's warmed — the resolved dependency graph, worker-slot accounting) resident
  indefinitely and manage its lifetime yourself.

Changing this value takes effect for the *next* daemon — `jk daemon stop` then let the next command
spin up a fresh one, or just wait for the current one to naturally recycle.

## Manual control

Alongside the automatic lifecycle, three explicit commands:

| Command | Effect |
|---|---|
| `jk daemon start` | Eagerly start the daemon and wait until it's confirmed live (or fail with a clear error). A no-op if one's already running. Useful for pre-warming a CI container. |
| `jk daemon stop` | Gracefully shut the daemon down (waits briefly for in-flight work, then stops). Reports "not running" (not an error) if there isn't one. |
| `jk daemon status` | Reports whether a daemon is running and, if so, its PID, `jk` version, uptime, the `idle-minutes` it's operating under, best-effort memory usage, and how many requests are in flight. |

## On-disk layout

Everything lives under `~/.jk/state/daemon/` (`state/` because it's mutable per-host runtime state,
not config or cache — see `JkDirs`), keyed by a short hash of the resolved state directory so
different `JK_HOME`/`JK_STATE_DIR` values naturally get independent daemons:

```
~/.jk/state/daemon/
├── <key>.sock   # Unix domain socket the daemon listens on
├── <key>.lock   # advisory lock proving single-instance ownership
├── <key>.pid    # PID + start time, for `jk daemon status` display only
└── <key>.log    # the daemon's own stdout/stderr — check here if lazy-start fails
```

The `.lock` file is the actual source of truth for "is a daemon running here": it's held for the
whole life of the daemon process and released automatically by the OS the instant that process
exits, cleanly or via `kill -9`. The `.pid` file is a display convenience for `jk daemon status`,
never trusted on its own — only after a successful ping.

## Wire protocol

Deliberately internal and unversioned. Since a daemon can only ever be started by a matching-version
`jk` binary (see [version skew](#version-skew) below), the client and server are always the same
build, and the protocol is free to change between `jk` releases with no compatibility concerns. (The
same engine is expected to eventually back a web backend, IDE plugin, or MCP server too — formalizing
a stable, versioned protocol is deferred until one of those actually needs to talk to a daemon it
didn't just spawn itself.)

- **Transport**: a Unix domain socket (plain JDK NIO, no third-party dependency). Windows needs its
  own transport story later (named pipes, or a loopback-TCP fallback) — not designed yet.
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
  with a nested JSON array — the hand-rolled codec (`DaemonProtocol`, mirroring `Ndjson`) only reads
  flat scalar fields and string arrays, deliberately, to stay dependency-free. See
  `DaemonProtocol`/`DaemonServer`/`DaemonBuildListenerAdapter` for the exact, current vocabulary —
  it's internal and expected to keep changing (see above), so this doc doesn't enumerate every type.
- **Rendering stays identical.** The CLI's live progress bars, spinners, and `--output json` mode
  already consume a build as a stream of listener callbacks (`onPlan`, `onModuleStart`, ...) — that
  was already true before the daemon existed, because the engine was already built to be driven by
  any front-end, not just the CLI. The daemon just moves where those callbacks originate from: a
  thin adapter on the CLI side turns each wire message back into the exact same callback the
  renderer already handles when running without a daemon. You should never notice a difference in
  what a build prints.

## What runs where

**In the daemon:** `jk build` (`BuildService.buildWorkspace`, every module in a workspace) and `jk
test` (a single project's compile+test goal) — dependency resolution, the build pipeline and
scheduler, worker-JVM orchestration and the shared memory/heap-slot accounting that this whole
effort exists to fix, and the on-disk build/action caches.

**Always in the CLI, never the daemon:** anything tied to your terminal or shell — the live TUI,
color/theme, Ctrl-C handling (a signal has to reach the actual foreground process you're looking
at), shell completion generation — and, deliberately, all of `jk jdk install` (including its
non-interactive core, not just the install wizard). A JDK install can't cause the kind of memory
contention the daemon exists to prevent, installs are already safe to run concurrently without any
coordination, and a machine that has never even built anything shouldn't need a daemon just to
install a JDK.

**Not yet daemon-hosted** (still runs in-process, unaffected by the daemon):
- `jk explain` — a read-only dry-run forecast. It never forks a worker JVM, so it doesn't contribute
  to the memory contention the daemon exists to fix; hosting it would also require extending the
  engine to cover its client-side ETA computation, which today reaches into `BuildPipeline`/
  `EffortWeights`/`Calibration` directly from the CLI (a known, pre-existing gap, not a new one).
  Revisit only if `explain`'s own cost becomes a real concern.
- Single-project `jk build` (a project with no `[workspace]` table) — only *workspace* builds are
  daemon-hosted so far. A single-project build forks worker JVMs the same way a workspace module
  does, so this is a real, tracked gap toward the OOM fix, not a deliberate scope boundary the way
  `explain` is — closing it needs extracting a clean engine-level facade from `BuildCommand`'s
  single-module path (`runForDir`/`prepareModule`/`runPrepared`), which today is tightly interleaved
  with CLI-only concerns (the TUI single-module bypass, workspace-relative calibration) in a way
  `buildWorkspace` and the test goal weren't. Until this lands, running several single-project builds
  concurrently in different terminals is still exposed to the original OOM bug.

## Version skew

Every connection starts with a handshake: the CLI states its version, the daemon states its own. If
they don't match — you upgraded `jk` since the daemon started — the CLI kills the stale daemon,
starts a fresh one (which, having just been launched by the current binary, necessarily matches),
and retries your command against it. You never see this happen; it costs the one extra startup.

## Failure modes

- **The daemon disconnects mid-request** (it crashed, or was killed): your command fails immediately
  with a clear error pointing at `jk daemon status` for more detail, rather than hanging or silently
  finishing without a result. This is the sharp edge of "the daemon is load-bearing" — earlier
  versions of `jk` never had a background process that could disconnect out from under a build, and
  now this failure mode exists. It's an accepted trade-off, not an oversight.
- **The daemon is killed externally** (`kill -9`, a machine reboot with no clean shutdown): the next
  `jk` invocation's liveness check simply finds nothing listening, and starts a fresh daemon exactly
  as it would if none had ever run. No stale-lock cleanup is needed — the advisory lock is released
  by the OS the moment the process is gone.
- **A `jk daemon start`/lazy-start attempt itself fails** — the error points at the daemon's own log
  file (`~/.jk/state/daemon/<key>.log`) rather than leaving you guessing.

## Memory target

The daemon aims to sit near **256 MiB** while idle. It doesn't do the heavy lifting itself — compiles
and test runs still happen in forked worker JVMs, sized by the same shared `HeapPlan` machinery as
always, just now coordinated across every concurrent request instead of guessed independently per
process. The daemon's own footprint stays small because it deliberately avoids new large resident
caches: dependency-resolution ledgers and build-timing history are small and cheap enough to
re-read from disk per request rather than hold in memory for hours, and anything that's already a
disk-backed cache (the content-addressed build cache, the action cache) stays exactly that.

## Rollout

Landed in phases, each shipped with its own passing test suite and manual verification against a
real native-image binary:

1. **Prerequisite hardening (done).** The engine's per-request session state (`SessionContext`)
   propagates correctly across concurrent in-JVM work (`ContextPropagator`). Worker-JVM memory
   planning (`JvmOptions.planAndApply`) no longer runs unconditionally inside `buildWorkspace` — a
   `WorkspaceRequest.applyMemoryPlan` flag lets a host serving concurrent requests (the daemon) plan
   once for its own concurrency instead of letting each call overwrite the shared budget.
2. **Daemon skeleton (done).** Process lifecycle, single-instance election (`.lock` `tryLock()`), the
   Unix-domain-socket handshake/liveness/status/shutdown protocol, the idle-timeout policy (0/-1/N),
   and `jk daemon start/stop/status`. Verified end-to-end: start/status/stop, `kill -9` recovery
   (stale socket/lock cleanup), and `idle-minutes` semantics against the real binary.
3. **`jk build` (done).** Every `WorkspaceBuildListener`/`GoalListener` callback streams over the
   wire as its own message type (`DaemonServer`'s `wireListener`/`wireGoalListener`,
   `DaemonBuildListenerAdapter` client-side); the CLI's existing renderers (`AggregateModuleListener`,
   the headless buffer listener) are unchanged — only where their events originate from changed.
   Verified with a real multi-module workspace: correct compilation/packaging/cross-module
   dependencies, a clear error (no hang) when the daemon is killed mid-build, and — the actual bug
   this exists to fix — two concurrent `jk build`s in different directories sharing one daemon
   without OOMing.
4. **`jk test` (done).** A single project's test goal reuses the exact same goal-level wire vocabulary
   `jk build` already speaks (tagged with a fixed sentinel `dir` since there's only one goal, not a
   module list) — no new protocol surface beyond the request/response pair and carrying the test
   pass/fail counts on the terminal `goal-finish` event. Verified: passing tests, a failing test
   (correct exit code 4, full failure detail rendered), and two concurrent `jk test` runs sharing one
   daemon.
5. **Explicitly deferred, not scheduled:**
   - `jk explain` and single-project `jk build` daemon-hosting — see
     [What runs where](#what-runs-where) for why these are open gaps rather than finished work.
   - More precise (rather than coarse) memory accounting across concurrent requests — the current
     daemon sizes one shared budget for the host's core count at startup, not a live demand-registry
     that grows/shrinks per in-flight request. Revisit only once real usage shows the coarse sizing
     under-parallelizes; building the precise version speculatively isn't warranted yet.
   - A stable versioned protocol, if a second front-end besides this CLI ever needs to talk to a `jk`
     daemon directly.
