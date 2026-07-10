# The web dashboard (petite-vue SPA)

**Status: planned, not yet implemented.** Companion doc: [`http.md`](http.md) (the embedded engine
HTTP server that hosts this). This doc covers phase 5 of that plan.

A single-page dashboard for the resident engine: live build activity, engine vitals, and — over
time — build history and trends. The long-term shape is a stripped-down, JVM-centric, single-user
Buildkite. The near-term shape is deliberately tiny: a handful of hand-written files, no build
step, served straight from the engine jar.

## Constraints

- **No build toolchain.** No npm, no bundler, no transpile. The dashboard is plain files checked
  into `kernel/engine/src/main/resources/www/`, served as-is. A contributor edits a file, restarts
  the engine (or points `www-root` at their checkout — see Development below), refreshes.
- **No CDN, no network.** Everything the page loads comes from the engine itself — the dashboard
  must work on an offline machine, and the server's security posture (no CORS, strict Host
  checking) assumes a self-contained origin. petite-vue is vendored, not linked.
- **Tiny.** petite-vue's IIFE is ~6 KB gzipped; the whole `/www` tree should stay well under
  ~100 KB so it's invisible in the fat jar and in the engine's heap (assets stream from the jar;
  nothing is cached in memory).

## Why petite-vue

petite-vue is Vue's official minimal distribution for progressive enhancement: `@vue/reactivity`
reactive state + template directives (`v-if`, `v-for`, `v-on`, `v-model`, `{{ }}`) directly over
in-page DOM, with no virtual DOM, no components-as-files, no compiler. That is exactly this
dashboard's shape — a few reactive views over JSON endpoints — and it keeps the no-build-step
constraint effortlessly. Two caveats, accepted with eyes open: it is feature-frozen upstream
(fine: it's ~600 lines we vendor and could maintain ourselves), and it has no router (fine: the
dashboard is one page with tab-like view switching on a reactive field; `location.hash` mirrors
the active view so links/refresh work).

If the dashboard ever outgrows this (client-side routing, component libraries, charts with heavy
interactivity), the escape hatch is full Vue's ESM build vendored the same way — the store/API
layer below is framework-portable on purpose.

## File layout

```
kernel/engine/src/main/resources/www/
├── index.html          # the whole app shell: header, view tabs, petite-vue templates
├── app.js              # createApp + store + view logic (ES module)
├── api.js              # fetch wrapper, token bootstrap, SSE client (ES module)
├── fold.js             # pure event-folding logic — no browser globals, node-testable
├── style.css           # hand-written; dark/light via prefers-color-scheme
└── vendor/
    └── petite-vue.iife.js   # vendored, version-pinned in a header comment (v0.4.1)
```

`index.html` loads `vendor/petite-vue.iife.js` and `app.js` with `defer`. No inline scripts or
style attributes, so a `Content-Security-Policy` header rides every <em>classpath</em>-served
response: `default-src 'self'; script-src 'self' 'unsafe-eval'` — the `unsafe-eval` is petite-vue's
expression compiler (`new Function`), the price of the no-build-step constraint. Disk-served
`www-root` content (user reports with inline styles of their own) is deliberately not CSP-gated.

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
    exponential backoff. An HTTP-enabled engine never idles out (see `http.md`), so a disconnect
    only ever means an explicit `jk engine stop`, a version-skew respawn after a `jk` upgrade, or
    a crash — the offline banner says so ("engine stopped — run any jk command to restart it"),
    and the first successful reconnect refetches `/api/status` to resync.
- **`app.js`** — one `PetiteVue.reactive` store, one `createApp(store).mount('#app')`:
  - `store.view` — `'activity' | 'status'` (v1), mirrored to `location.hash`.
  - `store.status` — the `/api/status` payload, refreshed on load, on SSE reconnect, and on a slow
    (30 s) timer as a fallback; the SSE stream is the primary freshness signal, not polling.
  - `store.activity` — a bounded ring (last ~200 events) of `/api/events` entries, folded into
    per-request groups: a `request-start` opens a card, `module-start`/`goal-finish` events update
    its rows, `request-finish` closes it with outcome + duration. Bounded so a long-lived tab
    can't grow the page without limit.
  - `store.connection` — `'live' | 'offline' | 'unauthorized'`, driving the banner.
- **`index.html` templates** — petite-vue directives over the store. No component framework;
  `v-for` over activity cards, `v-if` per view.

## Views

**v1 ships two:**

- **Activity** (default) — live feed of engine requests as cards: workspace/project dir, per-goal
  rows appearing as `goal-finish` events land, outcome badge and wall-clock on completion. This is
  the "trigger something with the CLI, watch it here" feedback loop. A "Build…" button (POST
  `/api/build` with a directory string) exists but is secondary — the CLI remains the primary
  trigger; the button proves the mutation path end-to-end.
- **Status** — the `jk engine status` numbers, rendered: version, pid, uptime, heap used/committed
  /max and RSS (with a small inline bar, no chart library), active connections/pipelines, idle
  policy, the resolved `www-root`.

**Later, additive (needs the `/api/builds` + `/api/stats` endpoints from `http.md`'s later
phases):** a **Builds** history view and **Trends** (duration over time, cache-hit rates). Charts
at that point are hand-drawn inline SVG (sparklines, bars) before any charting library is
considered — the data volumes here are tiny.

Server-rendered content in `www-root` (build reports a pipeline drops as `.html`/`.json`/`.md`)
is linked, not embedded: activity cards and future build-detail views link to
`/reports/<file>.html` etc., and the static handler serves the current bytes. The SPA never
assumes those files exist.

## Styling

Hand-written `style.css`, no framework. Dark and light themes via `prefers-color-scheme`, with the
palette lifted from [`tui-style.md`](tui-style.md) so the dashboard and the terminal UI read as one
product: same accent hues for ok/warn/error/building states, same restraint (color means state,
not decoration). System font stack (`system-ui, sans-serif` + `ui-monospace` for paths/durations),
one spacing scale, no icon font (a few inline SVGs).

## Development workflow

`www-root` beats the classpath in the static handler's resolution order (see `http.md`), so
front-end iteration needs no engine rebuild:

```toml
[http]
www-root = "/home/you/src/oss/jk/kernel/engine/src/main/resources/www"
```

Edit, refresh. Disk content is served `Cache-Control: no-cache`, so the browser revalidates every
load. Ship by simply having the same files in the jar and removing the override.

## Testing

- The store's event-folding logic (SSE events → activity cards) is the only real logic; it lives
  as pure functions in `fold.js` (no browser globals) and is tested headlessly with `node --test`
  (`kernel/engine/src/test/js/fold.test.mjs`, run via the `WebClientFoldTest` JUnit wrapper) — no
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
