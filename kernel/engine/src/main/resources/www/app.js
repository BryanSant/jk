// SPDX-License-Identifier: Apache-2.0
// The dashboard: one root component, two views (Activity, Status), mounted by Vue 3 (global
// build from the CDN — see docs/webclient.md). The in-DOM template lives in index.html; Vue's
// runtime compiler turns it into render functions at load (the CSP 'unsafe-eval' grant).

import { bootstrapToken, get, getText, post, del, events } from './api.js';
import { foldEvent, outcomeOf, moduleSummary, seedFromHistory, weightNumerator, weightDenominator } from './fold.js';

bootstrapToken();

// The build-step strip: a single horizontal chain that never wraps. New steps advance rightward
// and push earlier ones off the left (out of, or partially out of, view). There is no scrollbar —
// when steps are hidden a ◂ / ▸ nav button appears at that edge to page the view. Anchored to the
// newest step on mount and whenever the chain grows. See docs/webclient.md.
const StepChain = {
  props: { steps: { type: Array, required: true } },
  data: () => ({ atStart: true, atEnd: true }),
  template: `
    <div class="step-chain-wrap">
      <button v-show="!atStart" type="button" class="chain-nav left" @click="page(-1)"
              aria-label="show earlier steps" title="earlier steps">◂</button>
      <span v-show="!atStart" class="chain-fade left" aria-hidden="true"></span>
      <div class="step-chain" ref="track">
        <template v-for="(p, i) in steps" :key="p.name">
          <span v-if="i > 0" class="step-edge" :class="steps[i - 1].state"></span>
          <span class="step-node" :class="p.state" :title="stepTitle(p)">
            <span v-if="p.state === 'running'" class="spin small"></span>
            <span v-else-if="p.state === 'success'" class="step-glyph ok">✓</span>
            <span v-else-if="p.state === 'failed'" class="step-glyph err">✘</span>
            {{ stepLabel(p) }}
          </span>
        </template>
      </div>
      <span v-show="!atEnd" class="chain-fade right" aria-hidden="true"></span>
      <button v-show="!atEnd" type="button" class="chain-nav right" @click="page(1)"
              aria-label="show later steps" title="later steps">▸</button>
    </div>`,
  // `follow` = keep pinned to the newest step. True until the user pages away with ◂/▸; re-armed
  // when they page back to the end. While following, every render (new step, or a step's node
  // resizing as it goes running→success) re-anchors, so a build that scrolls steps off the left
  // still finishes pinned to the right end — not stranded mid-chain with a ▸ showing.
  data: () => ({ atStart: true, atEnd: true, follow: true }),
  mounted() {
    this.observer = new ResizeObserver(() => this.reflow());
    this.observer.observe(this.$refs.track);
    this.$nextTick(() => this.anchorEnd());
  },
  updated() {
    // Fires after each step update (state/width change); nextTick lets layout settle first.
    this.$nextTick(() => this.reflow());
  },
  beforeUnmount() {
    if (this.observer) this.observer.disconnect();
  },
  methods: {
    // A step node's label: "phase/step" when the step carries a phase, stripping a redundant leading
    // "phase-" from the step name (compile + compile-java → compile/java; test + run-tests →
    // test/run-tests). No phase → the bare step name.
    stepLabel(p) {
      if (!p.phase) return p.name;
      const prefix = p.phase + '-';
      const short = p.name.startsWith(prefix) ? p.name.slice(prefix.length) : p.name;
      return p.phase + '/' + short;
    },
    // Tooltip: the full, unstripped phase/step so the raw step name is always recoverable on hover.
    stepTitle(p) {
      return p.phase ? p.phase + '/' + p.name : p.name;
    },
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

// The Projects tab's per-project history spark: one vertical bar per recent build (height = wall
// clock, colour = outcome), drawn with Apache ECharts (loaded globally from the CDN — see
// index.html). Kept deliberately chrome-less (no axes, no grid) so it reads as a sparkline inside the
// row. The x-axis is a fixed 30 slots so every bar is a consistent 1/30 width, packed left-to-right:
// a project with one build shows one bar occupying 1/30 of the track, not a single fat bar. Re-renders
// only when its `builds` prop changes (projectsList is a computed, so the 1s tick never thrashes it).
const BUILD_COLORS = { success: '#4caf50', failed: '#e91e63', cancelled: '#ffc107', running: '#3d8fe0' };

/** Fixed number of build slots the spark reserves — a single build fills 1/30 of the width. */
const BUILD_SLOTS = 30;

const BuildBars = {
  props: { builds: { type: Array, required: true } },
  template: `<div class="build-bars" ref="el"></div>`,
  mounted() {
    if (window.echarts) this.chart = echarts.init(this.$refs.el, null, { renderer: 'canvas' });
    this.render();
    // The row's centre column is flex-sized; the chart must follow it (and any window resize).
    this.ro = new ResizeObserver(() => this.chart && this.chart.resize());
    this.ro.observe(this.$refs.el);
  },
  beforeUnmount() {
    if (this.ro) this.ro.disconnect();
    if (this.chart) this.chart.dispose();
  },
  watch: {
    builds() {
      this.render();
    },
  },
  methods: {
    render() {
      if (!this.chart) return;
      const builds = this.builds || [];
      // A fixed BUILD_SLOTS-long category axis: builds fill the leftmost slots (oldest → newest),
      // the rest are empty (zero-height) placeholders so every bar keeps the same 1/30 width.
      const data = [];
      for (let i = 0; i < BUILD_SLOTS; i++) {
        const b = builds[i];
        data.push(
          b
            ? { value: Math.max(1, b.millis || 0), itemStyle: { color: BUILD_COLORS[b.outcome] || '#5c6d78', borderRadius: [2, 2, 0, 0] } }
            : { value: 0, itemStyle: { color: 'transparent' } },
        );
      }
      this.chart.setOption(
        {
          animation: false,
          grid: { left: 1, right: 1, top: 6, bottom: 1, containLabel: false },
          xAxis: {
            type: 'category',
            show: false,
            data: Array.from({ length: BUILD_SLOTS }, (_, i) => i),
            boundaryGap: true,
          },
          yAxis: { type: 'value', show: false, min: 0 },
          tooltip: {
            trigger: 'axis',
            appendToBody: true,
            backgroundColor: '#161d25',
            borderColor: '#2a3742',
            borderWidth: 1,
            padding: [4, 8],
            textStyle: { color: '#cfd8dc', fontSize: 11, fontFamily: 'var(--mono)' },
            axisPointer: { type: 'none' },
            formatter: (ps) => {
              const b = builds[ps[0].dataIndex];
              if (!b) return '';
              const n = b.buildNumber ? '#' + b.buildNumber + ' · ' : '';
              return n + b.outcome + ' · ' + fmtMillis(b.millis);
            },
          },
          series: [{ type: 'bar', data, barWidth: '80%', z: 2 }],
        },
        true,
      );
    },
  },
};

/** A finished record's outcome for the Projects tab: cancelled ▸ success ▸ failed (records are terminal). */
function recordOutcome(r) {
  return r.cancelled ? 'cancelled' : r.success ? 'success' : 'failed';
}

/**
 * Parse the location hash into a route. Flat top-level views plus one detail route:
 * `#project/<url-encoded dir>` opens a single project's page (routed by dir — a coord isn't uniquely
 * reversible to a dir). Anything unrecognised falls back to the activity feed.
 */
function routeFromHash() {
  const h = location.hash || '';
  if (h.startsWith('#project/')) return { view: 'project', dir: decodeURIComponent(h.slice('#project/'.length)) };
  if (h === '#projects') return { view: 'projects', dir: null };
  if (h === '#status') return { view: 'status', dir: null };
  return { view: 'activity', dir: null };
}

/** Cached-vs-total step counts for one record → the build's "N of M steps served from cache". */
function stepCacheStats(rec) {
  const steps = (rec.modules && rec.modules.length)
    ? rec.modules.flatMap((m) => m.steps || [])
    : rec.steps || [];
  let cached = 0;
  let total = 0;
  for (const p of steps) {
    if (p.status === 'CANCELLED') continue; // a cancelled step never ran — not a cache decision
    total++;
    if (p.status === 'SKIPPED') cached++; // ctx.cached() → up-to-date / served from cache
  }
  return { cached, total };
}

/** Compact duration for the chart tooltip (no Vue instance in reach): "820 ms" / "3.4 s" / "1m 05s". */
function fmtMillis(millis) {
  if (millis == null) return '';
  if (millis < 1000) return millis + ' ms';
  if (millis < 60_000) return (millis / 1000).toFixed(1) + ' s';
  return Math.floor(millis / 60_000) + 'm ' + String(Math.round((millis % 60_000) / 1000)).padStart(2, '0') + 's';
}

Vue.createApp({
  data: () => ({
    view: routeFromHash().view, // 'activity' | 'projects' | 'project' | 'status'
    selectedProjectDir: routeFromHash().dir, // the project whose detail page is open (#project/<dir>)
    projectMeta: null, // live /api/project payload (coord + description) for the open project
    connection: 'connecting', // 'connecting' | 'live' | 'offline' | 'unauthorized'
    status: null, // the /api/status payload
    metrics: null, // the /api/metrics payload (running build aggregates), shown on the Status view
    cache: null, // the /api/cache payload (cache breakdown), shown on the Status view
    engineLog: '', // the /api/log tail, shown on the Status view
    cards: [], // folded activity, newest first
    projectHistory: [], // raw /api/history records (up to 200), grouped into the Projects tab
    buildDir: '',
    buildError: null,
    browser: null, // the /api/fs payload while the workspace picker is open, else null
    now: Date.now(), // 1s tick driving elapsed counters and "ago" stamps
  }),

  mounted() {
    events(
      (event) => {
        foldEvent(this.cards, { ...event, at: Date.now() });
        // The build number + journal record are written just after request-finish (writeJournal),
        // so re-pull history a beat later: it reconciles the live card (tagging its #number) and
        // refreshes the Projects tab. Debounced so a burst of finishes triggers one reload.
        if (event.type === 'request-finish') {
          clearTimeout(this._reconcileTimer);
          this._reconcileTimer = setTimeout(() => {
            this.loadHistory();
            this.loadProjectHistory();
          }, 500);
        }
      },
      (state) => {
        const wasOffline = this.connection === 'offline';
        if (this.connection !== 'unauthorized' || state === 'live') this.connection = state;
        if (state === 'live' && wasOffline) {
          this.refresh(); // resync after an engine restart
          this.loadHistory(); // re-seed persisted runs (dedupe keeps this idempotent)
          this.loadProjectHistory();
        }
      },
    );
    this.refresh();
    this.loadHistory(); // backfill past builds so a reload/restart doesn't start from an empty feed
    this.loadProjectHistory(); // so the Projects tab is populated the moment it's opened
    // Back/forward and any hash change re-derive the route (openProject sets the hash, which lands here).
    window.addEventListener('hashchange', () => this.applyRoute());
    if (this.view === 'project' && this.selectedProjectDir) this.loadProjectMeta(this.selectedProjectDir);
    setInterval(() => this.refresh(), 30_000); // slow fallback; SSE is the primary signal
    setInterval(() => (this.now = Date.now()), 1_000);
  },

  computed: {
    // Group the journal into per-project rows for the Projects tab. A computed (not a method) so it
    // recomputes only when projectHistory or the live cards change — never on the 1s clock tick, so
    // the ECharts canvases don't re-render every second. Key = coord when the project has one, else
    // its dir (two dirs sharing a coord fold together; a coord-less project stands on its dir).
    projectsList() {
      const RECENT = 30;
      const groups = new Map(); // key → { records[] } (records arrive newest-first from /api/history)
      for (const rec of this.projectHistory || []) {
        if (!rec || !rec.dir) continue;
        const key = rec.coord && rec.coord.includes(':') ? rec.coord : rec.dir;
        let g = groups.get(key);
        if (!g) {
          g = { key, records: [] };
          groups.set(key, g);
        }
        g.records.push(rec);
      }

      // A project with a build in flight right now shows the pulsing 'running' orb, overriding the
      // last finished outcome. Live running builds live in the SSE cards feed, keyed the same way.
      const runningKeys = new Set();
      for (const c of this.cards || []) {
        if (outcomeOf(c) !== 'running') continue;
        const key = c.coord && c.coord.includes(':') ? c.coord : c.dir;
        if (key) runningKeys.add(key);
      }

      const list = [];
      for (const g of groups.values()) {
        const recs = g.records; // newest-first
        const latest = recs[0];
        const window = recs.slice(0, RECENT); // newest-first slice for stats
        const recent = window
          .slice()
          .reverse() // oldest → newest, left → right for the chart
          .map((r) => ({
            outcome: recordOutcome(r),
            millis: r.millis || 0,
            buildNumber: r.buildNumber || 0,
            finishedAt: r.finishedAt || 0,
          }));

        // Reliability over the shown window: passes / (passes + fails), cancelled excluded — so the
        // number and the coloured bars always describe the same set of builds.
        let passed = 0;
        let ran = 0;
        let totalMillis = 0;
        let timed = 0;
        for (const r of window) {
          const o = recordOutcome(r);
          if (o !== 'cancelled') {
            ran++;
            if (o === 'success') passed++;
          }
          if (r.millis) {
            totalMillis += r.millis;
            timed++;
          }
        }
        const relPct = ran ? Math.round((100 * passed) / ran) : null;
        const parts = this.coordParts(latest); // reuse the card coord-splitter (coord or dir tail)
        const running = runningKeys.has(g.key);

        list.push({
          key: g.key,
          group: parts.group,
          name: parts.name,
          dir: latest.dir,
          state: running ? 'running' : recordOutcome(latest),
          buildNumber: latest.buildNumber || 0,
          // The durable per-project counter (newest build's number) is the true total; fall back to
          // the count of retained records for pre-numbering (schema-1) history.
          total: latest.buildNumber || recs.length,
          avgMillis: timed ? Math.round(totalMillis / timed) : 0,
          lastFinishedAt: latest.finishedAt || 0,
          recent,
          reliability: relPct == null ? '—' : relPct + '%',
          reliabilityClass: relPct == null ? '' : relPct >= 90 ? 'ok' : relPct >= 70 ? 'warn' : 'err',
        });
      }
      // Running projects float to the top, then most-recently-active first.
      list.sort((a, b) => (b.state === 'running') - (a.state === 'running') || b.lastFinishedAt - a.lastFinishedAt);
      return list;
    },

    // The open project's detail page: identity + aggregate metrics + a build-history table, all
    // derived from the journal filtered to this project (same coord/dir grouping as projectsList).
    // Recomputes only when the history, selection, or meta change — not on the 1s clock tick.
    projectDetail() {
      const dir = this.selectedProjectDir;
      if (!dir) return null;
      const RECENT = 30;
      const metaCoord = this.projectMeta && this.projectMeta.coord ? this.projectMeta.coord : null;
      const key = metaCoord || dir; // match how projectsList groups (coord when present, else dir)
      const records = (this.projectHistory || []).filter((r) => {
        const rk = r.coord && r.coord.includes(':') ? r.coord : r.dir;
        return rk === key || r.dir === dir;
      });
      const parts = this.coordParts({ coord: metaCoord || (records[0] && records[0].coord), dir });
      const base = {
        dir,
        group: parts.group,
        name: parts.name,
        description: (this.projectMeta && this.projectMeta.description) || null,
      };
      if (records.length === 0) return { ...base, empty: true, rows: [] };

      const latest = records[0];
      const window = records.slice(0, RECENT);
      let passed = 0;
      let ran = 0;
      let cachedSum = 0;
      let totalSum = 0;
      const durs = [];
      for (const r of window) {
        const o = recordOutcome(r);
        if (o !== 'cancelled') {
          ran++;
          if (o === 'success') passed++;
        }
        const cs = stepCacheStats(r);
        cachedSum += cs.cached;
        totalSum += cs.total;
        if (r.millis) durs.push(r.millis);
      }
      const relPct = ran ? Math.round((100 * passed) / ran) : null;
      const cachePct = totalSum ? Math.round((100 * cachedSum) / totalSum) : null;
      durs.sort((a, b) => a - b);
      const avg = durs.length ? Math.round(durs.reduce((s, x) => s + x, 0) / durs.length) : null;

      const rows = records.slice(0, 50).map((r) => {
        const cs = stepCacheStats(r);
        return {
          id: r.id,
          buildNumber: r.buildNumber || null,
          outcome: recordOutcome(r),
          trigger: r.trigger || null,
          commit: r.commit || null,
          tests: r.tests || null,
          cached: cs.cached,
          cacheTotal: cs.total,
          millis: r.millis,
          finishedAt: r.finishedAt || 0,
        };
      });

      return {
        ...base,
        empty: false,
        total: latest.buildNumber || records.length,
        reliability: relPct == null ? '—' : relPct + '%',
        reliabilityClass: relPct == null ? '' : relPct >= 90 ? 'ok' : relPct >= 70 ? 'warn' : 'err',
        cachePct: cachePct == null ? '—' : cachePct + '%',
        minMillis: durs.length ? durs[0] : null,
        maxMillis: durs.length ? durs[durs.length - 1] : null,
        avgMillis: avg,
        lastFinishedAt: latest.finishedAt || 0,
        rows,
      };
    },
  },

  methods: {
    setView(view) {
      this.view = view;
      history.replaceState(null, '', '#' + view);
      if (view === 'status') this.refresh();
      if (view === 'projects') this.loadProjectHistory();
    },

    // ---- the Projects tab (grouped /api/history + live running overlay) ----

    // Pull the raw journal (newest-first, up to 200 records); projectsList groups it per project.
    async loadProjectHistory() {
      try {
        this.projectHistory = await get('/api/history');
      } catch (e) {
        if (e.status === 401) this.connection = 'unauthorized';
      }
    },

    // The pill/orb glyph for a project's state (Projects tab). Running gets a ▶ play (the Activity
    // feed shows a spinner instead); 'issue' (‼) is reserved for the audit/CVE signal — nothing
    // drives it yet, but the state, colour, and chip are wired so a future signal only sets it.
    projGlyph(state) {
      return { running: '▶', success: '✓', failed: '✘', cancelled: '⊘', issue: '‼', finished: '·' }[state] || '·';
    },

    // "just now" / "5m ago" / "3h ago" / "2d ago" from an epoch-millis stamp (drives the 1s clock).
    agoMillis(ms) {
      if (!ms) return '';
      const s = Math.max(0, Math.floor((this.now - ms) / 1000));
      if (s < 60) return 'just now';
      if (s < 3600) return Math.floor(s / 60) + 'm ago';
      if (s < 86_400) return Math.floor(s / 3600) + 'h ago';
      return Math.floor(s / 86_400) + 'd ago';
    },

    // Full-breakdown elapsed for the "Last built" line: "1d 2h 3m 4s ago" — the largest non-zero unit
    // down to seconds (leading zero units dropped). Drives off the 1s clock like agoMillis.
    agoLong(ms) {
      if (!ms) return 'never';
      let s = Math.max(0, Math.floor((this.now - ms) / 1000));
      const d = Math.floor(s / 86_400); s -= d * 86_400;
      const h = Math.floor(s / 3600); s -= h * 3600;
      const m = Math.floor(s / 60); s -= m * 60;
      const parts = [];
      if (d) parts.push(d + 'd');
      if (h || parts.length) parts.push(h + 'h');
      if (m || parts.length) parts.push(m + 'm');
      parts.push(s + 's');
      return parts.join(' ') + ' ago';
    },

    // ---- the project detail page (#project/<dir>) ----

    // Open a project's page — set the hash (creating a history entry so Back returns to the list);
    // the hashchange listener drives applyRoute, which flips the view and loads the metadata.
    openProject(dir) {
      if (!dir) return;
      location.hash = '#project/' + encodeURIComponent(dir);
    },

    // Re-derive view + selected project from the hash, loading whatever that route needs.
    applyRoute() {
      const r = routeFromHash();
      this.view = r.view;
      this.selectedProjectDir = r.dir;
      if (r.view === 'project' && r.dir) this.loadProjectMeta(r.dir);
      if (r.view === 'projects') this.loadProjectHistory();
      if (r.view === 'status') this.refresh();
    },

    // Live coord + description for the open project, straight from its jk.toml on disk.
    async loadProjectMeta(dir) {
      this.projectMeta = null;
      try {
        this.projectMeta = await get('/api/project?dir=' + encodeURIComponent(dir));
      } catch (e) {
        if (e.status === 401) this.connection = 'unauthorized';
      }
      if (!this.projectHistory.length) this.loadProjectHistory(); // detail rows come from history
    },

    // The "Build" button: kick off a fresh build of this project and jump to the live Activity feed.
    buildProject(dir) {
      this.triggerBuild(dir);
      this.setView('activity');
    },

    // Human label for a build's trigger. Older (pre-capture) records have none → em dash.
    triggerLabel(trigger) {
      return { web: 'Web build', cli: 'CLI build' }[trigger] || '—';
    },

    // Progress percentage for a running card's bar — the identical weight-based model as the CLI
    // (ProgressBar): fraction = numerator/denominator of the engine's weight units, aggregated across
    // modules (fold.js). Clamped to 99% while running; only a finished card is 100% — matching
    // ProgressBarListener. No client-side estimation: the engine already did all the weight math.
    progress(card) {
      if (this.outcome(card) !== 'running') return 100;
      const den = weightDenominator(card);
      if (den <= 0) return 0; // no weight yet (before pipeline-start) — the bar fills in a beat
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

    // Full badge label for a finished Activity card as one string: glyph, optional #buildNumber,
    // and the capitalized outcome — e.g. "✘ #11 Failed", or "✓ Success" before a number is assigned.
    activityBadge(card) {
      const o = this.outcome(card);
      const num = card.buildNumber ? '#' + card.buildNumber + ' ' : '';
      return this.glyph(o) + ' ' + num + o.charAt(0).toUpperCase() + o.slice(1);
    },

    summary(card) {
      return moduleSummary(card);
    },

    // A build is "compact" (one step chain under the header, no module-name rows) when it has at
    // most one module — a single-project build, or a 1-module workspace. Multi-module builds render
    // a bullet+name row per module, each with its own chain.
    compact(card) {
      return card.modules.length <= 1;
    },

    // The single chain shown under the header for a compact card (the one module's steps, if any).
    singleChain(card) {
      return card.modules[0] ? card.modules[0].steps : [];
    },

    // The lone module of a compact card — carries the steps and any failure output shown inline.
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

    // The machine-wide per-step rows, biggest total first, capped for the panel.
    metricsSteps() {
      return (this.metrics || [])
        .filter((r) => r.scope === 'step')
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
            ? 'Unauthorized — open the tokenized URL printed by `jk engine status`'
            : 'Could not delete this run';
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
          this.buildError = 'Unauthorized — open the tokenized URL printed by `jk engine status`';
          this.browser = null;
        } else if (this.browser) {
          // an unreadable subdir: stay where we are
        } else {
          this.buildError = 'Could not list that directory';
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
            ? 'Unauthorized — open the tokenized URL printed by `jk engine status`'
            : e.error || 'Build request failed';
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
        connecting: 'Connecting…',
        live: 'Live',
        offline: 'Engine stopped — run any jk command to restart it',
        unauthorized: 'Unauthorized — open the URL printed by `jk engine status`',
      }[this.connection];
    },
  },
})
  .component('step-chain', StepChain)
  .component('build-bars', BuildBars)
  .mount('#app');
