# The `jk` wrapper (`./jk`)

**Status:** design pillar. Companion to [requirements.md](./requirements.md) tenet #3
(reproducibility) and tenet #5 (adoption first).

## Pillar

> **The wrapper lowers the barrier to entry without lowering the reproducibility bar.**

`jk wrapper` emits a `./jk` (POSIX) and `./jk.bat` (Windows) into the project root so a
fresh clone builds with `./jk build` and *nothing preinstalled*. A project's README can say
"build with `./jk build`" and it just works — the wrapper either delegates to an already
installed `jk`, or downloads, verifies, and caches one (optionally ensuring the engine's
JDK), then runs it.

The wrapper is a **thin bootstrap, not a second implementation.** It carries no build
logic; it only locates or provisions the real `jk` and hands off.

## What the wrapper does, in order

1. **Delegate if possible.** If a compatible `jk` is already on `PATH` or in the jk cache,
   `exec` it directly — no download, near-zero overhead. Because `jk` is a single native
   binary (sub-50 ms cold start, tenet #2), "install" is one file — not a distribution plus
   a JRE, as with the Gradle/Amper wrappers.
2. **Otherwise fetch + verify + cache.** Download `jk-<version>-<os>-<arch>.{xz,zip}`, check
   its SHA-256 against the pinned value, unpack into a **version-keyed** cache
   (`$JK_CACHE_DIR/dist/jk-<version>/`), and write a completion marker so subsequent runs
   skip straight to launch. Optionally `jk jdk ensure` the engine's JDK.
3. **Launch.** Run the resolved binary with the user's arguments, forwarding to the resident
   engine as usual (see [engine.md](./engine.md); engine version-skew is detected and the
   engine transparently replaced).

## The version is locked, not floating

The wrapper resolves the jk version from the project's `[project] jk = "…"` field — the same
place as `jdk`/`java`/`kotlin` — recorded and checksum-pinned in `jk.lock` — the **same discipline as the locked JDK and
locked dependencies** (tenet #3). `./jk build` today, and on CI a year from now, use the
identical tool build → bit-identical artifacts by construction.

This is the cargo/uv model, generalized one level up: those tools stay a single global
binary and get reproducibility from the *lockfile pinning the inputs*. jk treats **its own
version as one more locked toolchain input**, alongside the JDK and the dependency graph.

## Evergreen is opt-in and self-pinning

The newcomer / "just trying jk" case still gets a frictionless, latest-version experience —
without unlocking the toolchain:

- **Version declared** → fetch + verify + cache that exact version; delegate instantly if
  it's already available.
- **No version declared** → resolve the **latest GA on first run**, then *immediately write
  version + hash to `jk.lock`*. This is **pin-on-first-use**, exactly how the dependency
  lockfile's first resolve already behaves. Newcomers get "just works, latest"; the project
  is reproducible from that first build forward.

## Upgrades are explicit

`jk wrapper update` (mirrors `jk update` for dependencies) re-resolves to the latest GA (or
`--target-version <v>`), rewrites the pinned version + hash, and lands as a reviewed commit.
A toolchain bump is a deliberate, diffable change — never a silent drift between machines.

## Why not a truly-evergreen "always latest" wrapper

An always-latest wrapper is simpler UX but trades away tenet #3, so jk deliberately does not
ship it. The reasoning turns on a distinction that is easy to blur:

- **Backward compatibility** — a *newer* jk can build an *older* project. This *is* jk's
  responsibility (jk is declarative TOML with a stable schema, so unlike Gradle it can
  promise it), and it is what keeps `jk wrapper update` cheap and safe.
- **Reproducibility** — the *same* project builds *identically* on every box and over time.
  This requires a *recorded* tool version. Backward compatibility does **not** imply it: a
  new jk can be fully back-compatible yet legitimately change resolver output or a default,
  which is desirable as an opt-in upgrade and disastrous as a silent floating drift.

Concretely, "always latest" would:

- **Break bit-identical output** — two machines on two "latests" are not identical.
- **Thrash the action cache** — action keys include tool identity; a floating version
  invalidates every cache on each bump and can't share a remote cache across clients on
  different floating versions.
- **Weaken the supply-chain posture** — auto-pulling unpinned, uncommitted, unverified code
  contradicts mandatory checksum verification and repo pinning (tenet #7). Even the Gradle
  and Amper wrappers pin a version *and* verify a SHA-256 for exactly this reason.

The wrapper is the on-ramp; the lock is the guarantee.

## CLI surface

| Command | Behaviour |
|---|---|
| `jk wrapper` | Emit `./jk` + `./jk.bat` into the project root, pinned to the current locked jk version (or latest GA, pinned-on-first-use, if none is declared). |
| `jk wrapper update [--target-version <v>]` | Re-resolve the pinned jk version + hash and rewrite it as a reviewed change. |
| `./jk <args>` | The emitted wrapper: delegate to an installed compatible `jk`, else fetch + verify + cache the pinned version, then run. |

## Cross-platform notes

- **POSIX (`./jk`)** — a `/bin/sh` script: `curl`/`wget` download, `shasum`/`sha256sum`
  verify, `tar`/`unzip` extract, hardlink-based download lock, per-OS cache dir.
- **Windows (`jk.bat`)** — a batch bootstrap; download/verify/extract via inline PowerShell
  (`Get-FileHash`, `Net.WebClient`/`curl.exe`), `System.Threading.Mutex` download lock.
  Unlike Amper's wrapper, there is no second-stage `.sh` to run, so no `busybox` shim is
  needed — the native `jk` binary is the payload, launched directly.
- Both honour a completion marker in the version-keyed cache dir: a matching pinned version
  short-circuits before any lock or network I/O (instant hot path).
- Overridable seams for mirrors / offline / CI: `JK_DIST_DOWNLOAD_ROOT`, `JK_CACHE_DIR`,
  and delegation to an existing `JK_HOME`/`PATH` binary.

## Relationship to the rest of jk

- **`jk.lock` / `[project].jk`** — the single source of truth for the pinned version, next to
  the locked JDK (`[project].jdk`) and deps.
- **`jk jdk ensure`** — the wrapper optionally provisions the engine JDK, reusing the
  existing JDK-management subsystem.
- **The engine** — the wrapper only provisions and launches the client; client/engine
  version skew is handled by the engine itself (see [engine.md](./engine.md)).
