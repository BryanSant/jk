# The slim client: shrinking `jk` to a thin front-end

Status: **in flight — stages 1–4 landed, stage 5 (the dep cut) next** (2026-07-08). This is the
successor arc to
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
classpath. Two build artifacts: a size-optimized client binary (`-Os`, no `-march`) and an engine
artifact that may re-tune for speed (`-march` benefits its SHA-256-heavy hot path, benchmarked
≈1.5x on no-op builds).

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
4. **Split the artifacts:** produce two native images — `jk` (client, -Os) and the engine binary —
   and teach the spawn path to locate the engine artifact. Re-tune engine flags for speed
   independently. (LANDED 2026-07-08: `EngineMain` is the dedicated entrypoint (`jk --engine-server`
   delegates to the same code — the JVM-dist route and the spawn fallback); `:cli:nativeEngineCompile`
   builds the `jk-engine` image speed-first — `-O3`, `-march=x86-64-v3` on amd64 /
   `compatibility` elsewhere, the 256/96 MiB engine heap baked as `-R:` defaults, still
   spawner-overridable via `-Xms`/`-Xmx` so `max-heap-mb` config keeps working — while
   `:cli:nativeCompile`'s client flags stay size-first and untouched. `EngineClient.spawn` resolves
   the artifact `JK_ENGINE_EXE` → `jk-engine[.exe]` sibling of the client binary → the client
   binary + `--engine-server`, and records the choice as the engine log's first line. See
   docs/engine.md "Two artifacts". Note the deliberate ordering vs. this plan's original "once
   `:cli` no longer links the engine": the two-image pipeline landed *before* stage 5's dep cut, so
   both images still link the full classpath — the download-size win lands when stage 5 shrinks the
   client image.)
5. **Then cut `:cli`'s Gradle deps to `:model` (+ api)** and let the compiler enforce it, the same
   way `BuildGraph.BuildUnit` is package-private today.

Each stage lands green on its own; no big-bang.

## Non-goals

- No public/stable RPC API before a second front-end exists (IntelliJ plugin will force this).
- No engine-less "degraded mode" for engine-hosted verbs — the no-fallback rule from
  docs/engine.md stands.
