// SPDX-License-Identifier: Apache-2.0
// The dashboard: one root component, two views (Activity, Status), mounted by Vue 3 (global
// build, vendored — see docs/webclient.md). The in-DOM template lives in index.html; Vue's
// runtime compiler turns it into render functions at load (the CSP 'unsafe-eval' grant).

import { bootstrapToken, get, post, events } from './api.js';
import { foldEvent, outcomeOf } from './fold.js';

bootstrapToken();

Vue.createApp({
  data: () => ({
    view: location.hash === '#status' ? 'status' : 'activity',
    connection: 'connecting', // 'connecting' | 'live' | 'offline' | 'unauthorized'
    status: null, // the /api/status payload
    cards: [], // folded activity, newest first
    buildDir: '',
    buildError: null,
  }),

  mounted() {
    events(
      (event) => foldEvent(this.cards, event),
      (state) => {
        const wasOffline = this.connection === 'offline';
        if (this.connection !== 'unauthorized' || state === 'live') this.connection = state;
        if (state === 'live' && wasOffline) this.refreshStatus(); // resync after an engine restart
      },
    );
    this.refreshStatus();
    setInterval(() => this.refreshStatus(), 30_000); // slow fallback; SSE is the primary signal
  },

  methods: {
    setView(view) {
      this.view = view;
      history.replaceState(null, '', '#' + view);
    },

    outcome(card) {
      return outcomeOf(card);
    },

    async refreshStatus() {
      try {
        this.status = await get('/api/status');
        if (this.connection === 'unauthorized') this.connection = 'live';
      } catch (e) {
        if (e.status === 401) this.connection = 'unauthorized';
      }
    },

    async triggerBuild() {
      this.buildError = null;
      const dir = this.buildDir.trim();
      if (!dir) return;
      try {
        await post('/api/build', { dir });
        this.buildDir = '';
      } catch (e) {
        this.buildError =
          e.status === 401
            ? 'unauthorized — open the tokenized URL printed by `jk engine status`'
            : e.error || 'build request failed';
      }
    },

    // ---- formatting helpers (templates keep zero logic beyond these) ----
    mib(bytes) {
      return bytes < 0 ? '—' : Math.round(bytes / 1048576) + ' MiB';
    },
    heapPercent() {
      const s = this.status;
      if (!s || s.heapMaxBytes <= 0 || s.heapUsedBytes < 0) return 0;
      return Math.min(100, Math.round((100 * s.heapUsedBytes) / s.heapMaxBytes));
    },
    uptime(totalSeconds) {
      if (totalSeconds == null) return '—';
      const h = Math.floor(totalSeconds / 3600);
      const m = Math.floor((totalSeconds % 3600) / 60);
      return h + 'h ' + m + 'm ' + (totalSeconds % 60) + 's';
    },
    duration(millis) {
      if (millis == null) return '';
      return millis < 1000 ? millis + ' ms' : (millis / 1000).toFixed(1) + ' s';
    },
    shortDir(dir) {
      const parts = dir.split('/').filter(Boolean);
      return parts.length > 2 ? '…/' + parts.slice(-2).join('/') : dir;
    },
    connectionLabel() {
      return {
        connecting: 'connecting…',
        live: 'live',
        offline: 'engine stopped — run any jk command to restart it',
        unauthorized: 'unauthorized — open the tokenized URL printed by `jk engine status`',
      }[this.connection];
    },
  },
}).mount('#app');
