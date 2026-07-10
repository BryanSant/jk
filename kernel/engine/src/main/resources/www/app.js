// SPDX-License-Identifier: Apache-2.0
// The dashboard: one root component, two views (Activity, Status), mounted by Vue 3 (global
// build from the CDN — see docs/webclient.md). The in-DOM template lives in index.html; Vue's
// runtime compiler turns it into render functions at load (the CSP 'unsafe-eval' grant).

import { bootstrapToken, get, getText, post, del, events } from './api.js';
import { foldEvent, outcomeOf, moduleSummary, seedFromHistory } from './fold.js';

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
  mounted() {
    // Re-measure on size changes (window resize, card expanding) without yanking the view.
    this.observer = new ResizeObserver(() => this.measure());
    this.observer.observe(this.$refs.track);
    this.$nextTick(() => this.anchorEnd());
  },
  beforeUnmount() {
    if (this.observer) this.observer.disconnect();
  },
  watch: {
    // A new phase (live build) advances the chain: keep the newest end in view.
    'phases.length'() {
      this.$nextTick(() => this.anchorEnd());
    },
  },
  methods: {
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
      t.scrollLeft = t.scrollWidth; // newest phase visible; earlier ones pushed off the left
      this.measure();
    },
    page(dir) {
      const t = this.$refs.track;
      if (!t) return;
      t.scrollLeft += dir * Math.max(90, Math.round(t.clientWidth * 0.6));
      setTimeout(() => this.measure(), 260); // after the smooth scroll settles
    },
  },
};

Vue.createApp({
  data: () => ({
    view: location.hash === '#status' ? 'status' : 'activity',
    connection: 'connecting', // 'connecting' | 'live' | 'offline' | 'unauthorized'
    status: null, // the /api/status payload
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

    outcome(card) {
      return outcomeOf(card);
    },

    summary(card) {
      return moduleSummary(card);
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
    // Finished cards render just the artifact name (the group prefix is one hover away); a running
    // card keeps the full group:name inline while it's the feed's focus.
    showCoordGroup(card) {
      return this.outcome(card) === 'running';
    },
    // Tooltip for the coord: the full group:name coordinate when the engine sent one, else the dir.
    coordTip(card) {
      return card.coord || card.dir;
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
