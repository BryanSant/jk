# One daemon, many versions: engine lifecycle, storage unification, signed releases

Status: PROPOSED (2026-07-13). Ground rules for multi-version jk coexistence — storage
layout, upgrade choreography, delegation, signing, `jk self update`.

## 0. What exists today (the deviation is real)

Two systems handle "get a runnable jk onto this machine":

| | Bootstrap path (install.sh / self-fetch) | Lock-pinned toolchain path |
|---|---|---|
| Client binary | `~/.jk/bin/jk` — ONE mutable slot, overwritten | n/a |
| Engine jar | `~/.jk/lib/jk-engine-<v>.jar` — one mutable slot; `EngineJarFetcher` self-fetches the client's own baked version | CAS blobs |
| Version skew | client **kills** a mismatched engine and starts its own | resolver-mediated |
| AOT | trained locally into `state/engine/engine-<hash>.aot`, keyed by jar+JDK identity, `.noaot` sticky marker | payloads ride the CAS |
| Verification | `SHA256SUMS` fetched from the same origin (integrity, NOT authenticity) | CAS hash after first fetch |

The bootstrap path predates the CAS discipline; the toolchain path grew up with it. The
kill-on-skew behavior means a newer client stomps a busy older engine (and vice versa),
and nothing is signed.

## 1. Ground rules

**R1 — All immutable bytes live in the CAS. Period.** Engine jars, client binaries, worker
jars, release payloads — one store, one integrity model, one GC (the AccessLedger),
and the never-share-an-inode rule already enforced. `~/.jk/lib` dies.

**R2 — Versions materialize side-by-side; one mutable pointer.**

```
~/.jk/
├── bin/jk                    → symlink to versions/<current>/bin/jk (atomic flip)
├── versions/<v>/             # immutable once materialized, COPIED out of the CAS
│   ├── bin/jk                # native client for this host
│   ├── lib/jk-engine.jar
│   └── manifest.toml         # artifact shas, worker pins, protocol version
├── state/engine/
│   ├── current.sock|pid|log… # the ONE daemon's runtime files (per-instance, as today)
│   └── <v>/                  # version-scoped DERIVED state: the trained .aot, .noaot
├── cache/                    # shared: CAS, actions, repos, jdks — version-agnostic
└── config.toml               # shared; forward-compatible (unknown keys ignored, never
                              # rewritten by an older version — read-modify-write preserves
                              # unknown content verbatim)
```

**R3 — AOT caches are derived state, never shipped.** JEP 514 caches are host + JDK-identity
+ jar specific. They are trained locally (existing flow, which is already right), live in
`state/engine/<v>/`, and are never CAS release payloads. If the toolchain path ships `.aot`
payloads today, that stops — a pinned engine trains its own on first clean stop, exactly
like the bootstrap engine.

**R4 — Exactly one daemon, and it is the newest version materialized on the machine.**
Pinned older versions never daemonize (see R6). "Newest locally available wins" also
answers cold start: the spawner scans `versions/`, launches the max.

**R5 — Upgrades hand off immediately; nobody drains against live traffic.** (This is the
one place this plan disagrees with the sketch — see §2.)

**R6 — Delegation is downward only; upward is replacement.**
- A build pinned to an *older* jk: the daemon materializes that version and execs its
  engine as a **child process over stdio** — the exact worker pattern (NDJSON,
  protocol-prefixed lines, one job per process, exits when done). No second daemon, no
  socket, no AOT warmup expectations.
- A client (or pin) *newer* than the daemon: the daemon must not supervise semantics it
  postdates. Instead the client/daemon materializes the newer version and asks the old
  daemon to **yield** (§2). The newer engine becomes the daemon; the old one drains its
  in-flight jobs as a lame duck and exits.

**R7 — The handshake is frozen forever.** The first line of every connection, and the
`yield` verb, form a tiny protocol-zero that every jk version past and future speaks:
`{"t":"hello","jk":"<version>","proto":<int>}` → the daemon answers with its own. Everything
else may change behind a protocol-version bump; this must not. This is the one piece to
get exactly right *now* — it's what makes every future upgrade path possible.

## 2. Upgrade choreography: takeover, not lull-waiting

The sketch's drain mode (keep accepting requests, swap when jobs hit zero) has a starvation
hole: a busy CI box never reaches an organic lull, so it never upgrades — and "accept new
work while hoping for quiet" makes the lull *less* likely. Invert it, nginx-style:

1. New engine starts, binds a fresh generation-numbered endpoint
   (`state/engine/gen-<n>.sock` — or a named pipe on Windows), warms up (AOT training can
   come later; first run may be un-AOT'd).
2. New engine atomically replaces the **endpoint file** — `state/engine/endpoint`, a
   one-line pointer (`uds:gen-42.sock` / `pipe:jk-engine-42`) every client reads before
   connecting. Atomic file replace exists on BOTH platforms (POSIX rename;
   `MoveFileEx(MOVEFILE_REPLACE_EXISTING)` on NTFS), so one code path serves Linux, macOS,
   and Windows — no socket-file rename semantics needed anywhere. From this instant every
   NEW connection lands on the new engine.
3. New engine notifies the old one (`yield` on protocol-zero) — belt and suspenders: the
   old engine also detects that `endpoint` no longer names its generation.
4. Old engine stops accepting, finishes in-flight jobs (nothing is killed), writes its AOT
   if it was recording, exits. New engine adopts the .pid/.log naming.

Degraded fallback (kept in the design, expected unused): if endpoint indirection proves
unreliable on some host, the old engine can soft-drain instead — refuse new connections
with a protocol-zero `busy-upgrading` answer (clients retry against the endpoint file),
finish in-flight work, exit. Never a hard kill except `jk self update --force`.

No lull needed. No job killed. The fleet converges immediately. In-flight jobs finish
under the version that started them — correct, since their goals/action keys were
computed by it.

Cross-engine safety during the overlap window (minutes at most): the CAS and action cache
are already concurrent-safe (atomic temp+rename writes, content addressing); per-project
serialization stays on jk.lock as today. Two engines briefly coexisting is the same story
as two user accounts sharing a cache mount.

## 3. Triggers

- **`jk self update`**: resolve `latest/VERSION`, verify + materialize into `versions/<v>/`
  (client binary AND engine jar — the pair is atomic, per releases.md), flip the `bin/jk`
  symlink, start the takeover (§2). Jobs never die; `--force` kills the old daemon and its
  jobs for the impatient (and is the recovery hatch for a wedged engine).
- **A lock pin newer than the daemon**: materialize + takeover, exactly as self update
  (the "eager upgrade" rule) — then the (new) daemon runs the build itself.
- **A lock pin older than the daemon**: child-exec delegation (R6), no takeover.
- **Client newer than daemon** (post-`self update` shells, mixed fleets): client sends
  hello, daemon answers with an older version, client materializes its own version if
  needed and requests yield. This replaces today's kill-on-skew.

## 4. Supply chain: sign the release, pin the hash

`SHA256SUMS` from the same origin as the artifacts authenticates nothing — whoever can
tamper with the jar can tamper with the sums. Two layers, both cheap:

1. **A minisign/ed25519 release key, baked into the client.** The release pipeline signs
   `SHA256SUMS` (one signature per release); every verifier (install.sh via the published
   pubkey, `jk self update` and toolchain fetches via the baked-in key) checks
   signature-then-hash before anything is materialized or executed. Key rotation uses the
   forward-compat pattern the signing schema already established (`next-store-file`): each
   release may carry `NEXT_RELEASE_KEY`, clients trust current ∪ next. Enterprise/air-gap:
   `[release] trusted-keys` in config.toml overrides.
2. **Transparency later, via what jk already ships**: the publisher plugin has sigstore +
   SLSA provenance support — dogfood it. Keyless-sign the same `SHA256SUMS` in CI, publish
   the bundle alongside; clients that can reach the log verify the release workflow's OIDC
   identity. Layer 1 works offline and is the floor; layer 2 adds auditability without
   becoming a hard dependency.

Once a version is in a `jk.lock`, the lock records `version + sha256` — every subsequent
fetch on any machine is CAS-verified against the pinned hash, so the signature check only
gates *first acquisition* of a version, and a compromised release site can't swap bytes
under an existing pin.

## 5. GC

`versions/<v>/` and `state/engine/<v>/` join the AccessLedger like any CAS blob: touch on
every launch/delegation. Prune keeps (a) current, (b) anything touched inside the
retention window, (c) nothing else — a stale pin re-materializes from the CAS or the
release site on demand, because R1 made materialization cheap and reproducible.

## 6. Phases

- **P1 — storage unification** (foundation, no behavior change): engine jar + client
  binary flow through the CAS; `versions/<v>/` materialization; `bin/jk` symlink flip;
  `lib/` retired; AOT confirmed local-only in `state/engine/<v>/`. install.sh writes the
  same layout.
- **P2 — protocol-zero + takeover**: the frozen hello/yield handshake; socket-rename
  handoff; lame-duck drain; kill-on-skew replaced. (Ship P2 in the same release as P1 —
  the first version that speaks protocol-zero is the floor every later upgrade stands on.)
- **P3 — downward delegation**: pinned-older builds exec child engines over stdio via the
  worker pattern; `jk-compat` checked at parse.
- **P4 — signing + `jk self update`**: minisign key in CI + client; update/takeover verbs;
  lock pins record hashes.
- **P5 — GC + polish**: ledger-driven pruning; `jk engine status` shows daemon version,
  lame ducks, and materialized set; sigstore transparency layer.

## 7. The project wrapper (`jk wrapper`)

`jk wrapper` writes `./jk` + `jk.bat` (committed, gradle-wrapper style) so a checkout
builds with its pinned jk on a machine with no — or the wrong — jk installed. Rules:

- **The wrapper is dumb and frozen.** ~50 lines of sh/bat that (1) read the exact pin,
  (2) materialize that client if missing, (3) exec it. It never resolves version
  selectors, never self-updates its own logic (regenerate via `jk wrapper`), and depends
  only on the two surfaces this plan freezes: the release layout (releases.md) and the
  lock's toolchain line.
- **The pin comes from `jk.lock`** — a grep-able single line recording
  `jk = "<version>" sha256 = "<hex>"`. jk.toml holds the *selector*; the lock holds the
  resolution, which is the only thing a shell script should touch. No lock yet → the
  wrapper bootstraps `latest/VERSION` and the real client takes over resolution
  (and locks) from there.
- **Materialize into the SAME `versions/<v>/` tree** — the wrapper is just a third writer
  of the layout self-update and the daemon's toolchain fetches already use. Download the
  platform artifact, verify `sha256` against the LOCK'S pinned hash (this makes wrapper
  verification strong with nothing but `sha256sum`/`CertUtil` — no minisign needed in
  shell for pinned versions; the unpinned-latest bootstrap verifies SHA256SUMS and prints
  the fingerprint it trusted), unpack to `~/.jk/versions/<v>/bin/jk`, exec.
- **The wrapper never touches `~/.jk/bin/jk`** — that symlink is the user's machine-global
  install. Wrapper-materialized versions and the global install coexist in `versions/`
  and share the CAS, config, JDKs, and the daemon. The exec'd client then speaks
  protocol-zero like any client: older daemon → yield/takeover; older pin → the daemon
  delegates downward. The wrapper needs no engine awareness at all.
- **Updating the wrapper springboards through the pinned client.** cmd.exe resolves a
  bare `jk` to the project's `jk.bat` (current-dir shadowing), so `jk wrapper` in a
  project dir always runs the PINNED — possibly old — client. That is fine, because the
  `wrapper` subcommand is a springboard, not a generator: `jk wrapper --version <x.y.z|latest>`
  resolves the requested version, materializes it into `versions/<v>/`, and **execs that
  version's own `jk wrapper --emit`** — the new client writes its own scripts and the new
  pin. The old client never needs to know newer script content; it only needs the
  springboard, which ships with the wrapper feature from day one. Bare `jk wrapper`
  (no --version) regenerates at the current pin — the self-repair case. The shell script
  itself stays 100% dumb: no flag interception, nothing to special-case in .bat.

## 8. Open questions

1. Should the *client* also delegate (old client, newer daemon)? Protocol-zero says the
   daemon answers with its version; older clients keep working against a newer daemon only
   if the wire stays backward-compatible per protocol int — that's the compatibility
   contract to state explicitly when P2 lands.
2. Worker/plugin jars are already version-pinned per engine (manifest.toml in
   versions/<v>/); confirm the delegation child resolves ITS worker set, not the daemon's.
3. Wrapper script naming: `./jk` + `jk.bat` as specified. `jk.bat` shadows a PATH `jk`
   inside cmd.exe sessions in that directory (cmd searches the current dir first;
   PowerShell requires `.\` and does not shadow) — intended for builds, and harmless for
   wrapper updates because `jk wrapper --version` springboards (see §7).
