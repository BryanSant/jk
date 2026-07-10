# Build history / journal

**Status: implemented.** Companion docs: [`http.md`](http.md) (the HTTP surface), [`webclient.md`](webclient.md)
(the dashboard), [`engine.md`](engine.md) (the resident engine).

Restarting the engine used to lose every trace of past builds — the dashboard's "Live activity"
feed was live-SSE-only with no backfill, and the rich per-build outcome (`WorkspaceResult` /
`ModuleOutcome`, `GoalResult` / `PhaseReport` / `Diagnostic`, `TestSummary`) lived only in local
variables for the duration of a run and was then dropped. Now every engine build is journaled to
disk, survives restarts, is browsable in the dashboard and a `jk history` CLI, and individual
entries are deletable like a GitHub Actions run.

## On-disk layout

```
~/.jk/state/builds/                     # JkDirs.buildsDir(), override JK_BUILDS_DIR
├── calibration.toml                    # host calibration (moved here from state/; migrated on first read)
└── journal/
    ├── .last-pruned                     # (reserved) prune-cadence stamp
    └── <id>/
        ├── record.json                  # the structured outcome — source of truth
        ├── test-results.md              # snapshot of target/reports/test-results.md (if present)
        ├── jk.lock                      # snapshot of the project's jk.lock (if present)
        └── diagnostics.txt              # flattened errors+warnings, for grep / CLI show
```

Everything lives in `state/` (not `cache/`) so it survives `jk clean`. Snapshots are frozen to the
run — a later rebuild or even deleting the project can't change or remove them.

**Id** — `<UTC yyyyMMdd'T'HHmmssSSS>-<4 hex>`, e.g. `20260710T143022417-3f9a`. Lexicographic order
equals chronological order (newest-first is a reverse sort), and it survives restarts, unlike the
in-memory request counter (which resets to 0 each engine start). The millisecond timestamp plus 4
random hex makes intra-millisecond collisions between concurrent builds vanishing; the final atomic
directory move is the existence check that forces a retry on the rare clash.

**`record.json`** (schema 1): flat scalars — `id, schema, kind, dir, coord?, startedAt, finishedAt,
millis, success, cancelled, exitCode, jkVersion` — plus a nullable `tests` object
(`total/succeeded/failed/skipped`) and three arrays: `modules[{coord,dir,success,exitCode,millis}]`,
`phases[{name,status,millis}]`, `diagnostics[{severity,phase,code,message,test,exceptionClass}]`.

> The engine's `JsonOut` is deliberately flat (scalars + one string array) and can't express those
> arrays-of-objects, so the journal carries its own small nested writer/reader, `journal/Json`. The
> HTTP detail endpoint streams the stored `record.json` bytes verbatim (already valid JSON), so the
> flat-only limit never bites on the wire.

## Capture

`EngineServer` opens a per-request `BuildAccumulator` (keyed by the event-request id) for the
journaled kinds — `build`, `test`, `1build` (single build) — and folds outcomes into it from the
**same** `WorkspaceBuildListener` / `GoalListener` callbacks that publish dashboard events, plus the
runner's terminal result (success/exit, and the `TestSummary` for single builds/tests). At
request-finish it writes the record + snapshots via `BuildJournal.append`.

Crucially, capture is **ungated by dashboard subscribers** — `publishEvent` short-circuits when no
SSE client is connected, but the accumulator and the write do not. A build run from a plain terminal
with no browser open is still journaled. Journaling is best-effort: an append failure is logged and
never propagated (it must not change a build's outcome).

The journal library (`dev.jkbuild.engine.journal`: `BuildRecord`, `Json`, `BuildJournal`) lives in
the engine module. Concurrent builds never contend — each owns a unique `<id>/` and writes only
within it; `append` stages into a dot-prefixed temp dir and moves it into place atomically, so a
reader never sees a half-written entry.

## Retention

Bounded on two independent axes via `[history]` in `~/.jk/config.toml` (machine-scoped, not
project-overridable; env `JK_HISTORY_*` overrides):

```toml
[history]
enabled      = true    # JK_HISTORY_ENABLED       false = no capture, no serving
max-age-days = 30      # JK_HISTORY_MAX_AGE_DAYS   drop entries older than this; 0 = no age limit
max-disk-mb  = 512     # JK_HISTORY_MAX_DISK_MB    total budget; oldest pruned past it; 0 = no cap
```

Pruning runs at the engine's **idle boundary** (`maybeIdleBoundaryGc`), alongside the cache prune
and off the hot path: first drop entries past `max-age-days`, then, if the remaining total exceeds
`max-disk-mb`, delete oldest-first — reclaiming the copied snapshots — until under budget. Ages come
from the id timestamp (no file read), falling back to the directory mtime.

## Surfaces

The engine owns the journal on disk; every reader goes through it.

- **Dashboard** — on load and after each reconnect the feed is backfilled from `GET /api/history`
  (`fold.js` `seedFromHistory`), reconciled with live SSE cards by `(dir, finishedAt)` so a run in
  both feeds is one card. Finished cards get a **delete** button (`DELETE /api/history?id=`).
- **HTTP** — `GET /api/history` (list, or `?id=` detail), `GET /api/history/artifact?id=&name=`,
  `DELETE /api/history?id=`. Reads are read-tier; DELETE always requires the bearer token (CSRF
  defense), even on loopback. See [`http.md`](http.md).
- **CLI** — `jk history list` / `jk history show <id>` / `jk history rm <id>`. The thin CLI never
  reads the journal directly; it always talks to the engine (spawning it if needed) over the NDJSON
  socket protocol (`HISTORY_LIST_REQUEST` / `HISTORY_SHOW_REQUEST` / `HISTORY_DELETE_REQUEST`), and
  the engine replies with flat NDJSON lines the CLI renders with the `Ndjson` helpers it already has
  — no journal logic and no JSON parser added to the binary.

## Calibration move

`calibration.toml` moved from `~/.jk/state/` into `~/.jk/state/builds/`, alongside the journal. It
self-regenerates (an absent file just triggers a fresh probe on the next build), so a file left at
the legacy path is migrated on first read as a best-effort nicety — it preserves the learned EWMA
and skips one cold probe.
