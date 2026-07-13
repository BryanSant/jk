# Build metrics — running aggregates and the estimation feedback loop

**Status: implemented.** Companion docs: [`build-history.md`](build-history.md) (the per-run
journal these aggregates are folded from), [`http.md`](http.md) (`GET /api/metrics`),
[`webclient.md`](webclient.md) (the Status view's build-stats section).

The engine keeps **persistent running aggregates** for every build-related job it has run on this
machine: invocation count, total wall-clock, and min/max (avg derived), in four tiers:

1. **Global** — every `build`/`test` invocation on this machine, per kind.
2. **Per project** — the same, keyed by project directory.
3. **Per phase** — every phase name (`compile-java`, `run-tests`, `package-jar`, `plugin-<verb>`,
   …). A plugin executes as its worker jar wrapped in an ordinary phase, so this tier is also the
   per-worker/per-plugin view.
4. **Per project per phase.**

Successful, failed, and cancelled runs are **counted separately** — every invocation is tracked,
but a failed build's duration never pollutes the success stats the estimator learns from (the same
success-only discipline as the `PhaseTimings` ledger and `Calibration`).

## Storage

`~/.jk/state/builds/metrics.json` (`BuildMetrics`, beside `calibration.toml`). It is machine
**state**, not cache: it survives `jk clean`, which is precisely what makes it a useful prior
after the learned-rate ledger (`~/.jk/cache/timings.toml`) is wiped. Two row families:

```json
{
  "schema": 1,
  "invocations": [
    { "kind": "build", "dir": "/abs/project", "coord": "group:name",
      "ok":        { "count": 42, "totalMillis": 91000, "minMillis": 800, "maxMillis": 14000 },
      "failed":    { "count": 3,  "totalMillis": 4100,  "minMillis": 600, "maxMillis": 2200 },
      "cancelled": { "count": 1,  "totalMillis": 300,   "minMillis": 300, "maxMillis": 300 },
      "updated": 1770000000000 }
  ],
  "phases": [
    { "dir": "/abs/project", "phase": "compile-java",
      "ok": {}, "failed": {}, "cancelled": {}, "updated": 1770000000000 }
  ]
}
```

`dir: ""` marks the global tier of either family. `dir` is the primary key (matching the
`PhaseTimings` keys and every estimator lookup); `coord` is a display label only. Averages are
always derived (`totalMillis / count`), never stored.

**Collection** happens at the same request-finish choke point that writes the build journal
(`EngineServer.writeJournal`): the finished `BuildRecord`'s outcome and per-module phase timings
are folded into the store in one locked load-update-write with an atomic replace. Only the
journaled kinds (`build`, `test`) count — lock/sync/tool requests are not build invocations.
Phase samples with status `SKIPPED` teach nothing. Metrics fold even when `[history]` is disabled;
only the journal append itself is gated. Everything is best-effort: a metrics failure can never
fail a build.

## Surfaces

- **`jk status`** — the current project's stats (cwd) plus the machine-wide summary, with a
  per-phase table (top 8 by total time; `--phases` shows all, `--global` skips the project
  section, `--output json` emits the raw rows). A thin RPC (`metrics-request` →
  `metrics-entry`… `metrics-done`) over the engine socket, spawning the engine if needed. Engine
  *process* vitals stay under `jk engine status`.
- **Web UI Status page** — KPI tiles + phase table below the engine vitals, fed by
  `GET /api/metrics` (read-tier auth).

## Feeding the estimator

Three integrations, all success-only, all degrading to the previous behavior on an empty store:

1. **Whole-build prior** (`BuildService.applyHistoryPrior`, applied in the ETA seed and
   `jk explain`): with no trustworthy rate (cold module, uncalibratable host) the estimate used to
   be "count up" (0) — now it becomes the project's historical average. And a seed more than 2× the
   project's historical **max** is clamped down to that bound. One-sided by design: an incremental
   run legitimately beats the historical average, so the prior never clamps up. Kind-precise: it
   reads only `build`-kind history — every consumer is a build flow (`jk test` runs a single test
   goal on its own path and never consults the seeded ETA).
2. **Learned fixed-cost phases** (`EffortWeights.learnedFixedWeight`): phases the per-unit ledger
   never learns (`package-jar`, `package-shadow`, `native-image`, `write-image`, and every
   `plugin-<verb>` — previously a token static constant) are priced from the running average:
   own-project tier first, then the host tier, each requiring ≥ 3 successful samples.
3. **Cold-start tier in `EffortWeights.learned`**: own rate → project median → host median →
   **metrics average (flat)** → static constant. The new tier matters right after `jk clean`:
   the rate ledger is gone, but the metrics store survives, so estimates stay host-real.

## Retention

Pruned at the engine's idle-boundary GC alongside the journal: rows older than the max age are
evicted (a project you stopped building ages out; the global rows are refreshed by every build and
naturally survive), then oldest-first until the file fits the byte cap. Defaults: 2 years /
10 MB. Overrides: `JK_METRICS_MAX_SIZE_MB` / `JK_METRICS_MAX_AGE_DAYS` env vars, else
`[metrics] max-size-mb` / `max-age-days` in `~/.jk/config.toml`.
