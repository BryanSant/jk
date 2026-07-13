// SPDX-License-Identifier: Apache-2.0
// The dashboard: one root component, two views (Activity, Status), mounted by Vue 3 (global
// build from the CDN — see docs/webclient.md). The in-DOM template lives in index.html; Vue's
// runtime compiler turns it into render functions at load (the CSP 'unsafe-eval' grant).

import { bootstrapToken, get, getText, post, del, events } from './api.js';
import { foldEvent, outcomeOf, moduleSummary, seedFromHistory, weightNumerator, weightDenominator } from './fold.js';

bootstrapToken();

// The build-phase strip: a single horizontal chain that never wraps. New phases advance rightward
// and push earlier ones off the left (out of, or partially out of, view). There is no scrollbar —
// when phases are hidden a ◂ / ▸ nav button appears at that edge to page the view. Anchored to the
// newest phase on mount and whenever the chain grows. See docs/webclient.md.
const PhaseChain = {
  props: { phases: { type: Array, required: true } },
  data: () => ({ atStart: true, atEnd: true }),
  template: `
    <div class="phase-chain-wrap">
      <button v-show="!atStart" type="button" class="chain-nav left" @click="page(-1)"
              aria-label="show earlier phases" title="earlier phases">◂</button>
      <span v-show="!atStart" class="chain-fade left" aria-hidden="true"></span>
      <div class="phase-chain" ref="track">
        <template v-for="(p, i) in phases" :key="p.name">
          <span v-if="i > 0" class="phase-edge" :class="phases[i - 1].state"></span>
          <span class="phase-node" :class="p.state">
            <span v-if="p.state === 'running'" class="spin small"></span>
            <span v-else-if="p.state === 'success'" class="phase-glyph ok">✓</span>
            <span v-else-if="p.state === 'failed'" class="phase-glyph err">✘</span>
            {{ p.name }}
          </span>
        </template>
      </div>
      <span v-show="!atEnd" class="chain-fade right" aria-hidden="true"></span>
      <button v-show="!atEnd" type="button" class="chain-nav right" @click="page(1)"
              aria-label="show later phases" title="later phases">▸</button>
    </div>`,
  // `follow` = keep pinned to the newest phase. True until the user pages away with ◂/▸; re-armed
  // when they page back to the end. While following, every render (new phase, or a phase's node
  // resizing as it goes running→success) re-anchors, so a build that scrolls phases off the left
  // still finishes pinned to the right end — not stranded mid-chain with a ▸ showing.
  data: () => ({ atStart: true, atEnd: true, follow: true }),
  mounted() {
    this.observer = new ResizeObserver(() => this.reflow());
    this.observer.observe(this.$refs.track);
    this.$nextTick(() => this.anchorEnd());
  },
  updated() {
    // Fires after each phase update (state/width change); nextTick lets layout settle first.
    this.$nextTick(() => this.reflow());
  },
  beforeUnmount() {
    if (this.observer) this.observer.disconnect();
  },
  methods: {
    reflow() {
      if (this.follow) this.anchorEnd();
      else this.measure();
    },
    measure() {
      const t = this.$refs.track;
      if (!t) return;
      const max = t.scrollWidth - t.clientWidth;
      this.atStart = t.scrollLeft <= 1;
      this.atEnd = t.scrollLeft >= max - 1;
    },
    anchorEnd() {
      const t = this.$refs.track;
      if (!t) return;
      // Instant jump (not the smooth CSS path): a synchronous read in measure() must see the final
      // scrollLeft, and only user paging should animate.
      const max = Math.max(0, t.scrollWidth - t.clientWidth);
      if (Math.abs(t.scrollLeft - max) > 1) t.scrollLeft = max;
      this.measure();
    },
    page(dir) {
      const t = this.$refs.track;
      if (!t) return;
      this.follow = false; // manual navigation — stop auto-pinning to the end
      t.scrollTo({ left: t.scrollLeft + dir * Math.max(90, Math.round(t.clientWidth * 0.6)), behavior: 'smooth' });
      setTimeout(() => {
        this.measure();
        if (this.atEnd) this.follow = true; // paged back to the newest end → resume following
      }, 260);
    },
  },
};

Vue.createApp({
  data: () => ({
    view: location.hash === '#status' ? 'status' : 'activity',
    connection: 'connecting', // 'connecting' | 'live' | 'offline' | 'unauthorized'
    status: null, // the /api/status payload
    metrics: null, // the /api/metrics payload (running build aggregates), shown on the Status view
    cache: null, // the /api/cache payload (cache breakdown), shown on the Status view
    engineLog: '', // the /api/log tail, shown on the Status view
    cards: [], // folded activity, newest first
    buildDir: '',
    buildError: null,
    browser: null, // the /api/fs payload while the workspace picker is open, else null
    now: Date.now(), // 1s tick driving elapsed counters and "ago" stamps
  }),

  mounted() {
    events(
      (event) => foldEvent(this.cards, { ...event, at: Date.now() }),
      (state) => {
        const wasOffline = this.connection === 'offline';
        if (this.connection !== 'unauthorized' || state === 'live') this.connection = state;
        if (state === 'live' && wasOffline) {
          this.refresh(); // resync after an engine restart
          this.loadHistory(); // re-seed persisted runs (dedupe keeps this idempotent)
        }
      },
    );
    this.refresh();
    this.loadHistory(); // backfill past builds so a reload/restart doesn't start from an empty feed
    setInterval(() => this.refresh(), 30_000); // slow fallback; SSE is the primary signal
    setInterval(() => (this.now = Date.now()), 1_000);
  },

  methods: {
    setView(view) {
      this.view = view;
      history.replaceState(null, '', '#' + view);
      if (view === 'status') this.refresh();
    },

    // Progress percentage for a running card's bar — the identical weight-based model as the CLI
    // (ProgressBar): fraction = numerator/denominator of the engine's weight units, aggregated across
    // modules (fold.js). Clamped to 99% while running; only a finished card is 100% — matching
    // ProgressBarListener. No client-side estimation: the engine already did all the weight math.
    progress(card) {
      if (this.outcome(card) !== 'running') return 100;
      const den = weightDenominator(card);
      if (den <= 0) return 0; // no weight yet (before goal-start) — the bar fills in a beat
      return Math.min(99, Math.round((100 * weightNumerator(card)) / den));
    },

    // Live ETA countdown for a running card — the same calibrated millis the CLI countdown and
    // `jk explain` show. remaining = eta − elapsed; overrun flips to '+'. Empty when no estimate
    // (the elapsed "+Ns" timer already covers count-up). Re-emitted eta events retarget it.
    eta(card) {
      if (this.outcome(card) !== 'running') return '';
      const ms = card.etaMillis;
      if (ms == null || ms <= 0 || card.startedAt == null) return '';
      const remaining = ms - (this.now - card.startedAt);
      return remaining >= 0 ? '~' + this.fmtClock(remaining) : '+' + this.fmtClock(-remaining);
    },

    // mm:ss-style clock mirroring the CLI's CommandManager.fmtClock: "42s" / "1m 02s" / "1h 05m 09s".
    fmtClock(ms) {
      const s = Math.max(0, Math.round(ms / 1000));
      if (s < 60) return s + 's';
      const pad = (n) => String(n).padStart(2, '0');
      const m = Math.floor(s / 60);
      if (m < 60) return m + 'm ' + pad(s % 60) + 's';
      return Math.floor(m / 60) + 'h ' + pad(m % 60) + 'm ' + pad(s % 60) + 's';
    },

    outcome(card) {
      return outcomeOf(card);
    },

    summary(card) {
      return moduleSummary(card);
    },

    // A build is "compact" (one phase chain under the header, no module-name rows) when it has at
    // most one module — a single-project build, or a 1-module workspace. Multi-module builds render
    // a bullet+name row per module, each with its own chain.
    compact(card) {
      return card.modules.length <= 1;
    },

    // The single chain shown under the header for a compact card (the one module's phases, if any).
    singleChain(card) {
      return card.modules[0] ? card.modules[0].phases : [];
    },

    // The lone module of a compact card — carries the phases and any failure output shown inline.
    singleModule(card) {
      return card.modules[0] || null;
    },

    // Multi-module cards split their module rows across two peer accordions: the failed modules
    // (kept open) and everything else — succeeded, still-running, skipped, cancelled — which rolls
    // up under a "success details" accordion that is open while running and collapsed once done. A
    // module carrying failure output counts as failed even if its state was never marked (covers
    // request-level errors that land on a synthetic row).
    failedModules(card) {
      return card.modules.filter((m) => m.state === 'failed' || m.diagnostics.length > 0);
    },
    okModules(card) {
      return card.modules.filter((m) => m.state !== 'failed' && m.diagnostics.length === 0);
    },

    // A module row's label: the artifact name from its coord (e.g. "core"), else the dir's tail.
    moduleLabel(m) {
      if (m.coord) {
        const i = m.coord.lastIndexOf(':');
        return i >= 0 ? m.coord.slice(i + 1) : m.coord;
      }
      return this.shortDir(m.dir);
    },

    async refresh() {
      try {
        this.status = await get('/api/status');
        if (this.connection === 'unauthorized') this.connection = 'live';
        if (this.view === 'status') {
          this.engineLog = await getText('/api/log?lines=100');
        }
      } catch (e) {
        if (e.status === 401) this.connection = 'unauthorized';
      }
      // Separate try: a metrics hiccup must not blank the status vitals.
      try {
        this.metrics = await get('/api/metrics');
      } catch (e) {
        if (e.status === 401) this.connection = 'unauthorized';
      }
      // Cache breakdown only while it's visible — the endpoint walks the CAS, so don't poll it
      // from the Activity view.
      if (this.view === 'status') {
        try {
          this.cache = await get('/api/cache');
        } catch (e) {
          if (e.status === 401) this.connection = 'unauthorized';
        }
      }
    },

    // ---- the Status view's Cache panel (/api/cache) ----

    cacheUtilizationPercent() {
      const c = this.cache;
      if (!c || c.maxBytes <= 0) return 0;
      return Math.min(100, Math.round((100 * c.totalBytes) / c.maxBytes));
    },

    prunedAgo() {
      const at = this.cache?.lastPrunedMillis;
      if (!at) return 'never';
      const days = Math.floor((this.now - at) / 86_400_000);
      if (days <= 0) return 'today';
      return days === 1 ? '1 day ago' : days + ' days ago';
    },

    count(n) {
      return n == null ? '—' : n.toLocaleString();
    },

    // ---- the Status view's build-stats section (running aggregates from /api/metrics) ----

    // The machine-wide invocation rows (one per kind: build, test), stable order.
    metricsGlobal() {
      return (this.metrics || [])
        .filter((r) => r.scope === 'global')
        .sort((a, b) => a.kind.localeCompare(b.kind));
    },

    // The machine-wide per-phase rows, biggest total first, capped for the panel.
    metricsPhases() {
      return (this.metrics || [])
        .filter((r) => r.scope === 'phase')
        .sort((a, b) => b.okTotalMillis - a.okTotalMillis)
        .slice(0, 10);
    },

    // Sums across kinds for the KPI tiles: total runs, ok, failed+cancelled, total wall-clock.
    metricsTotals() {
      const g = this.metricsGlobal();
      const sum = (f) => g.reduce((acc, r) => acc + f(r), 0);
      const ok = sum((r) => r.okCount);
      const bad = sum((r) => r.failCount) + sum((r) => r.cancelledCount);
      return { runs: ok + bad, ok, bad, totalMillis: sum((r) => r.okTotalMillis + r.failTotalMillis) };
    },

    successRate() {
      const t = this.metricsTotals();
      return t.runs === 0 ? '—' : Math.round((100 * t.ok) / t.runs) + '%';
    },

    // Backfill the feed from the persisted journal (/api/history), reconciled with live cards.
    async loadHistory() {
      try {
        const records = await get('/api/history');
        seedFromHistory(this.cards, records);
      } catch (e) {
        if (e.status === 401) this.connection = 'unauthorized';
      }
    },

    // Delete a finished run from history (engine + disk), then drop its card locally.
    async deleteCard(card) {
      if (!card.historyId) return;
      try {
        await del('/api/history?id=' + encodeURIComponent(card.historyId));
        const i = this.cards.indexOf(card);
        if (i >= 0) this.cards.splice(i, 1);
      } catch (e) {
        this.buildError =
          e.status === 401
            ? 'unauthorized — open the tokenized URL printed by `jk engine status`'
            : 'could not delete this run';
      }
    },

    // ---- the workspace picker (Browse…) ----
    async openBrowser() {
      // Start from the typed path when it looks absolute; the server defaults to $HOME otherwise.
      const seed = this.buildDir.trim().startsWith('/') ? this.buildDir.trim() : null;
      await this.browseTo(seed);
    },

    async browseTo(dir) {
      this.buildError = null;
      try {
        this.browser = await get('/api/fs' + (dir ? '?dir=' + encodeURIComponent(dir) : ''));
      } catch (e) {
        if (e.status === 401) {
          this.buildError = 'unauthorized — open the tokenized URL printed by `jk engine status`';
          this.browser = null;
        } else if (this.browser) {
          // an unreadable subdir: stay where we are
        } else {
          this.buildError = 'could not list that directory';
        }
      }
    },

    chooseBrowsed() {
      this.buildDir = this.browser.dir;
      this.browser = null;
    },

    closeBrowser() {
      this.browser = null;
    },

    joinPath(dir, name) {
      return dir.endsWith('/') ? dir + name : dir + '/' + name;
    },

    async triggerBuild(dir) {
      this.buildError = null;
      const target = (dir ?? this.buildDir).trim();
      if (!target) return;
      try {
        await post('/api/build', { dir: target });
        if (dir == null) this.buildDir = '';
      } catch (e) {
        this.buildError =
          e.status === 401
            ? 'unauthorized — open the tokenized URL printed by `jk engine status`'
            : e.error || 'build request failed';
      }
    },

    // ---- formatting helpers (templates keep zero logic beyond these) ----
    coordParts(card) {
      // "group:name" → colored segments; fall back to the dir's last two path segments.
      if (card.coord && card.coord.includes(':')) {
        const i = card.coord.indexOf(':');
        return { group: card.coord.slice(0, i), name: card.coord.slice(i + 1) };
      }
      const parts = card.dir.split('/').filter(Boolean);
      return { group: null, name: parts.length ? parts[parts.length - 1] : card.dir };
    },
    mib(bytes) {
      return bytes < 0 ? '—' : Math.round(bytes / 1048576) + ' MiB';
    },
    heapUsedPercent() {
      return this.percentOfMax(this.status?.heapUsedBytes);
    },
    heapCommittedPercent() {
      return this.percentOfMax(this.status?.heapCommittedBytes);
    },
    percentOfMax(bytes) {
      const s = this.status;
      if (!s || s.heapMaxBytes <= 0 || bytes == null || bytes < 0) return 0;
      return Math.min(100, Math.round((100 * bytes) / s.heapMaxBytes));
    },
    uptime() {
      const s = this.status;
      if (!s) return '—';
      const total = Math.max(0, Math.floor((this.now - s.startedAt) / 1000));
      const h = Math.floor(total / 3600);
      const m = Math.floor((total % 3600) / 60);
      return h + 'h ' + m + 'm ' + (total % 60) + 's';
    },
    elapsed(card) {
      if (card.startedAt == null) return '';
      return '+' + Math.max(0, Math.floor((this.now - card.startedAt) / 1000)) + 's';
    },
    ago(card) {
      if (card.finishedAt == null) return '';
      const s = Math.max(0, Math.floor((this.now - card.finishedAt) / 1000));
      if (s < 60) return 'just now';
      if (s < 3600) return Math.floor(s / 60) + 'm ago';
      return Math.floor(s / 3600) + 'h ago';
    },
    duration(millis) {
      if (millis == null) return '';
      if (millis < 1000) return millis + ' ms';
      if (millis < 60_000) return (millis / 1000).toFixed(1) + ' s';
      return Math.floor(millis / 60_000) + 'm ' + Math.round((millis % 60_000) / 1000) + 's';
    },
    shortDir(dir) {
      const parts = dir.split('/').filter(Boolean);
      return parts.length > 2 ? '…/' + parts.slice(-2).join('/') : dir;
    },
    lastSegment(dir) {
      const parts = dir.split('/').filter(Boolean);
      return parts.length ? parts[parts.length - 1] : dir;
    },
    glyph(outcome) {
      return { running: '', success: '✓', failed: '✘', cancelled: '⊘', finished: '·' }[outcome] || '';
    },
    connectionLabel() {
      return {
        connecting: 'connecting…',
        live: 'live',
        offline: 'engine stopped — run any jk command to restart it',
        unauthorized: 'unauthorized — open the URL printed by `jk engine status`',
      }[this.connection];
    },
  },
})
  .component('phase-chain', PhaseChain)
  .mount('#app');
