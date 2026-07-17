# The web dashboard (Vue SPA)

**Status: implemented (v1 — Activity + Status views).** Companion doc: [`http.md`](http.md) (the
embedded engine HTTP server that hosts this). This doc covers phase 5 of that plan.

A single-page dashboard for the resident engine: live build activity, engine vitals, and — over
time — build history and trends. The long-term shape is a stripped-down, JVM-centric, single-user
Buildkite. The near-term shape is deliberately tiny: a handful of hand-written files, no build
step, served straight from the engine jar.

## Constraints

- **No build toolchain.** No npm, no bundler, no transpile. The dashboard is plain files checked
  into `clients/web/src/main/resources/web/`, served as-is. A contributor edits a file, restarts
  the engine (or points `web-root` at their checkout — see Development below), refreshes.
- **The framework comes from the CDN; everything else is self-hosted.** Vue loads from unpkg —
  version-pinned in the URL and integrity-locked with SRI (a compromised CDN must not be able to
  script a page that can trigger builds); the classpath CSP allows exactly that one external
  script origin. Deliberate trade-off: the dashboard's first load needs internet (unpkg serves
  pinned URLs `immutable`, so the browser caches it thereafter), and jk ships no framework bytes.
  Everything else — app code and styles — comes from the engine itself; the JetBrains Mono and
  Material Icons webfonts load from Google Fonts (the CSP's permitted style/font origins), and the
  rest of the security posture (no CORS, strict Host checking) still assumes a self-contained origin.
- **Small.** The shipped tree is a handful of hand-written files; assets stream from the jar
  (nothing is cached in the engine's heap). Keep it that way.

## Why full Vue (global build, from the CDN)

The dashboard started on petite-vue (Vue's ~6 KB progressive-enhancement distribution) and moved
to full Vue 3 once the trajectory was clear: the Buildkite-shaped roadmap (history, trends,
richer components) outgrows a feature-frozen library, and full Vue keeps the same template
syntax while adding real components, computed properties, watchers, and an ecosystem escape
hatch. The pinned artifact is `vue.global.prod.js` — the *full* build whose runtime compiler
compiles the in-DOM template in `index.html` at load, which is what preserves the no-build-step
constraint (the runtime-only build would require precompiled render functions, i.e. a bundler).
The production build, not `vue.global.js` — the dev build is 3× the size and exists for its
warnings; point `web-root` at a checkout and swap the `<script>` line when debugging Vue itself.
**Moving the pin** means updating both the URL and the `integrity` hash in `index.html`
(`openssl dgst -sha384 -binary vue.global.prod.js | openssl base64 -A`).

Still no router (the dashboard is one page with tab-like view switching on a reactive field;
`location.hash` mirrors the active view so links/refresh work) — vendor `vue-router` the same
way if real routes ever appear.

## File layout

```
clients/web/src/main/resources/web/
├── index.html          # the whole app shell: header, view tabs, the in-DOM Vue template
├── app.js              # createApp + store + view logic (ES module)
├── api.js              # fetch wrapper, token bootstrap, SSE client (ES module)
├── fold.js             # pure event-folding logic — no browser globals, node-testable
├── jk-logo.svg         # favicon + header mark: outlined JetBrains Mono glyphs (OFL), CRT-green
└── style.css           # hand-written; dark-only (Jk Dark)
```

`index.html` loads Vue from `https://unpkg.com/vue@3.5.39/dist/vue.global.prod.js`
(version-pinned, SRI `integrity` + `crossorigin`) and `app.js` with `defer`. No inline scripts or
style attributes, so a `Content-Security-Policy` header rides every <em>classpath</em>-served
response: `default-src 'self'; script-src 'self' 'unsafe-eval' https://unpkg.com; style-src
'self' https://fonts.googleapis.com; font-src https://fonts.gstatic.com` — the `unsafe-eval` is
Vue's runtime template compiler, the price of the no-build-step constraint; unpkg (Vue, ECharts) and
Google Fonts (JetBrains Mono, Material Icons) are the only permitted external origins. Disk-served `web-root` content (user reports
with inline styles of their own) is deliberately not CSP-gated.

## Architecture

Three small layers, one file each:

- **`api.js`** — the only place that talks HTTP:
  - `token()`: on first load, read `location.hash` (`#t=<token>` — printed by
    `jk engine status`), stash in `sessionStorage`, `history.replaceState` the fragment away.
    Subsequent loads read `sessionStorage` directly.
  - `get(path)` / `post(path, flatObj)`: `fetch` with `Authorization: Bearer <token>` when a token
    is held; a `401` response flips the store into its "token needed" state, which renders the
    one-line instruction to run `jk engine status` and click the printed URL.
  - `events(onEvent)`: an `EventSource` on `/api/events` (appending `?access_token=` only when the
    page origin is non-loopback — `EventSource` cannot send headers). Reconnects with capped
    exponential backoff. The engine never idles out — it is resident until `jk engine stop` (see
    `http.md`) — so a disconnect
    only ever means an explicit `jk engine stop`, a version-skew respawn after a `jk` upgrade, or
    a crash — the offline banner says so ("engine stopped — run any jk command to restart it"),
    and the first successful reconnect refetches `/api/status` to resync.
- **`app.js`** — one root component (`Vue.createApp({...}).mount('#app')`, Options API — the
  in-DOM template needs no SFC/build machinery):
  - `view` — `'activity' | 'status'` (v1), mirrored to `location.hash`.
  - `status` — the `/api/status` payload, refreshed on load, on SSE reconnect, and on a slow
    (30 s) timer as a fallback; the SSE stream is the primary freshness signal, not polling.
  - `cards` — `/api/events` entries folded (via `fold.js`, bounded at 50 cards) into per-request
    cards: a `request-start` opens a card, `module-start`/`pipeline-finish`/`module-finish` events
    update its rows, `request-finish` closes it with outcome + duration. Bounded so a long-lived
    tab can't grow the page without limit. A running card carries a **progress bar** and an **ETA
    countdown** using the *identical* method as the CLI: the engine already computes the weight-based
    plan (`EffortWeights`/`StepTimings`/`Calibration`) and the re-projected ETA, and now publishes
    the same numbers to the SSE hub — `plan` (total weight), `pipeline-progress` (per-module
    `numerator`/`denominator`) and `eta` (millis). `fold.js` aggregates the per-module counters
    (`weightNumerator`/`weightDenominator`, plan-seeded so a workspace bar can't jump backward);
    `app.js` `progress()` is `numerator/denominator` clamped to 99% while running (100% on finish),
    and `eta()` is `eta − elapsed` as a live countdown (`~` remaining, `+` on overrun) — matching
    `ProgressBar`/`CommandManager`. The bar uses the CLI's indigo→magenta gradient and a CSS width
    transition. On load (and after every reconnect) the feed is also
    **backfilled** from the persisted journal via `GET /api/history` (`fold.js` `seedFromHistory`),
    so a reload or an engine restart no longer starts from an empty feed. Seeding is reconciled
    with live cards by `(dir, finishedAt)` — a run present in both feeds yields one card, tagged
    with its durable `historyId` so it becomes deletable.
  - `connection` — `'connecting' | 'live' | 'offline' | 'unauthorized'`, driving the banner.
- **`index.html` templates** — Vue directives over the store, compiled in-DOM at load. One root component so far;
  `v-for` over activity cards, `v-if` per view.

## Views

**v1 ships two:**

- **Activity** (default) — live feed of engine requests as cards: workspace/project dir, per-pipeline
  rows appearing as `pipeline-finish` events land, outcome badge and wall-clock on completion. This is
  the "trigger something with the CLI, watch it here" feedback loop. Past runs are backfilled from
  the persisted journal (see below), so the feed is populated even on a cold load. A finished card
  carries a **delete** affordance (like removing a GitHub Actions run) — `DELETE /api/history?id=`,
  then the card is dropped locally. A "Build…" button (POST `/api/build` with a directory string)
  exists but is secondary — the CLI remains the primary trigger; the button proves the mutation
  path end-to-end. Each card's step strip is a self-contained `step-chain` component that never
  wraps: it's one horizontal chain anchored to the newest step, with earlier steps pushed off the
  left. There is no scrollbar — when steps are hidden, a `◂` / `▸` button appears at that edge to
  page the view (the track is moved via `scrollLeft`, `overflow:hidden`).
- **Status** — the `jk engine status` numbers, rendered: version, pid, uptime, heap used/committed
  /max and RSS (with a small inline bar, no chart library), active connections/pipelines, the
  resolved `web-root`, plus a **Cache panel** (the `jk cache info` sections — CAS blobs, action
  cache, plugin jars, run logs, format stamps — with a utilization meter against the configured
  ceiling and the last-pruned age, fed by `GET /api/cache` and polled only while the Status view
  is open) and the **engine log** on its own full-width row so lines don't wrap.
  Below the vitals sits the **build-stats section** (implemented),
  fed by `GET /api/metrics` (the engine's running aggregates — see [`metrics.md`](metrics.md)):
  KPI tiles for total builds recorded, success rate, per-kind average duration and total time
  building, plus a per-step table (runs / avg / min / max / total / failed, top 10 by total time).
  A plugin verb runs as its own step, so the step table is also the per-plugin view.
  Refreshed with the same 30 s fallback poll as the vitals; an empty store shows a quiet
  placeholder line.

Build **history now persists** (implemented): every engine build — whether or not a dashboard is
watching — is journaled to `~/.jk/state/builds/journal/<id>/` (`record.json` plus snapshot copies
of `test-results.md`, `jk.lock`, and flattened diagnostics), served by `GET /api/history`
(list + `?id=` detail) and `GET /api/history/artifact`, deletable via `DELETE /api/history?id=`,
and reachable headlessly through `jk history list|show|rm`. Retention is bounded by age and total
disk usage (`[history]` in `config.toml`) and enforced at the engine's idle-boundary GC. See
[`build-history.md`](build-history.md).

**Later, additive:** a dedicated **Builds** history view and **Trends** (duration over time,
cache-hit rates) on top of the same journal. Charts at that point are hand-drawn inline SVG
(sparklines, bars) before any charting library is considered — the data volumes here are tiny.

Server-rendered content in `web-root` (build reports a pipeline drops as `.html`/`.json`/`.md`)
is linked, not embedded: activity cards and future build-detail views link to
`/reports/<file>.html` etc., and the static handler serves the current bytes. The SPA never
assumes those files exist.

## Styling

Hand-written `style.css`, no framework. **Dark only, deliberately** — the palette is lifted from
[`tui-style.md`](tui-style.md) (Jk Dark) so the dashboard and the terminal UI read as one product:
same accent hues for ok/warn/error/building states, same restraint (color means state, not
decoration). UI text is the system stack (`system-ui, sans-serif`); paths, coords, durations, and
consoles use **JetBrainsMono Nerd Font Mono when installed locally** (the TUI already wants a Nerd
Font), falling back to Google's JetBrains Mono webfont (`fonts.googleapis.com`, a CSP-permitted
style/font origin), then `ui-monospace`. One spacing scale. Status/step markers are Unicode glyphs
(✓ ✘ ⊘ · ◂ ▸); the card action buttons (rebuild / delete) use Material Icons from Google Fonts.

## Development workflow

`web-root` beats the classpath in the static handler's resolution order (see `http.md`), so
front-end iteration needs no engine rebuild:

```toml
[http]
web-root = "/home/you/src/oss/jk/clients/web/src/main/resources/web"
```

Edit, refresh. Disk content is served `Cache-Control: no-cache`, so the browser revalidates every
load. Ship by simply having the same files in the jar and removing the override.

## Testing

- The store's event-folding logic (SSE events → activity cards) is the only real logic; it lives
  as pure functions in `fold.js` (no browser globals) and is tested headlessly with `node --test`
  (`clients/web/src/test/js/fold.test.mjs`, run via the `WebClientFoldTest` JUnit wrapper) — no
  browser, no framework, no new toolchain (Node is only a *test-time* convenience, not a build
  dependency; the suite is skipped when Node is absent).
- End-to-end: the engine's HTTP test suite (see `http.md`) already binds a real server; one
  Playwright-driven smoke pass (load, see status numbers, trigger a build, watch the card close)
  runs manually per release rather than in CI, matching how the TUI is verified against a real
  binary.

## Implementation phases

1. **Shell + Status view** — `index.html`/`style.css`/`api.js` token bootstrap + `/api/status`
   rendering. Proves static serving, CSP, and auth end-to-end.
2. **Activity view** — SSE client, event folding, offline/reconnect banner (including surviving a
   `jk engine stop` + respawn cycle) verified against a real engine.
3. **Build trigger** — the POST path + unauthorized-state UX.
4. **History/Trends** — lands with the corresponding server endpoints, inline-SVG charts.
