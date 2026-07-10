// SPDX-License-Identifier: Apache-2.0
// Folds the /api/events stream into activity cards — pure functions of (cards, event), no browser
// globals, so the logic is testable headlessly with `node --test` (see docs/webclient.md).

/** Cards kept in the activity feed — a long-lived tab must not grow the page without limit. */
export const MAX_CARDS = 50;

/** Console-tail lines kept per running card. */
export const MAX_OUTPUT_LINES = 8;

/** Failure diagnostics kept per card (the server already bounds what it publishes). */
export const MAX_DIAGNOSTICS = 12;

/**
 * Fold one SSE event into the newest-first card list, mutating and returning it.
 * An event is `{type, data, at}` where `data` is the parsed flat JSON payload the engine
 * publishes and `at` is the client-clock receipt time (fold stays clock-free and pure):
 * request-start/finish carry requestId/kind/dir (+ coord on start when the project's jk.toml
 * parses; + success/cancelled/millis on finish); module/phase/output/goal events carry
 * requestId/dir plus their specifics.
 */
export function foldEvent(cards, event) {
  const d = event.data || {};
  switch (event.type) {
    case 'request-start': {
      cards.unshift({
        id: d.requestId,
        kind: d.kind || 'request',
        dir: d.dir || '',
        coord: d.coord || null,
        state: 'running',
        startedAt: event.at ?? null,
        finishedAt: null,
        millis: null,
        cancelled: false,
        success: null, // tri-state: null = engine didn't say (socket requests) — derive from modules
        modules: [],
        phases: [],
        output: [],
        diagnostics: [],
      });
      if (cards.length > MAX_CARDS) cards.length = MAX_CARDS;
      break;
    }
    case 'module-start': {
      const card = byId(cards, d.requestId);
      if (card) moduleRow(card, d.dir).state = 'running';
      break;
    }
    case 'phase-start': {
      const card = byId(cards, d.requestId);
      if (card) phaseRow(card, d.phase).state = 'running';
      break;
    }
    case 'phase-finish': {
      const card = byId(cards, d.requestId);
      if (card) phaseRow(card, d.phase).state = phaseState(d.status);
      break;
    }
    case 'output': {
      const card = byId(cards, d.requestId);
      if (card && typeof d.line === 'string') {
        card.output.push({ dir: d.dir || '', line: d.line });
        if (card.output.length > MAX_OUTPUT_LINES) card.output.splice(0, card.output.length - MAX_OUTPUT_LINES);
      }
      break;
    }
    case 'diagnostic': {
      const card = byId(cards, d.requestId);
      if (card && card.diagnostics.length < MAX_DIAGNOSTICS) {
        card.diagnostics.push({
          phase: d.phase || '',
          code: d.code || '',
          message: d.message || '',
          test: d.test || '',
          exceptionClass: d.exceptionClass || '',
        });
      }
      break;
    }
    case 'goal-finish': {
      const card = byId(cards, d.requestId);
      if (card) {
        const row = moduleRow(card, d.dir);
        row.state = d.success ? 'success' : 'failed';
      }
      break;
    }
    case 'module-finish': {
      const card = byId(cards, d.requestId);
      if (card) {
        const row = moduleRow(card, d.dir);
        row.state = d.success ? 'success' : 'failed';
        row.millis = d.millis ?? row.millis;
      }
      break;
    }
    case 'request-finish': {
      const card = byId(cards, d.requestId);
      if (card) {
        card.state = 'finished';
        card.finishedAt = event.at ?? null;
        card.millis = d.millis ?? null;
        card.cancelled = !!d.cancelled;
        card.success = typeof d.success === 'boolean' ? d.success : null;
        card.output = []; // the console tail is an in-flight affordance; finished cards are compact
      }
      break;
    }
    default:
      break; // unknown event types are future vocabulary, never an error
  }
  return cards;
}

/**
 * Seed the feed with persisted history records (the `/api/history` array of full `record.json`
 * objects) so a reload or an engine restart doesn't lose past builds. Idempotent and safe to call
 * repeatedly (on load and after every reconnect): a run already present as a live SSE card is not
 * duplicated — instead the live card is tagged with its `historyId` so it becomes deletable. Live
 * cards key on the numeric engine request id (which resets per engine start); history entries key on
 * the durable string id, so the two never collide and are reconciled here by (dir, finishedAt).
 */
export function seedFromHistory(cards, records) {
  for (const rec of records || []) {
    if (!rec || !rec.id) continue;
    const live = cards.find(
      (c) =>
        c.historyId === rec.id ||
        (typeof c.id === 'number' &&
          c.dir === rec.dir &&
          c.finishedAt != null &&
          Math.abs(c.finishedAt - rec.finishedAt) < 2000),
    );
    if (live) {
      live.historyId = rec.id; // reconcile: the live card is this run — make it deletable
      continue;
    }
    if (cards.some((c) => c.id === 'h:' + rec.id)) continue; // already seeded
    cards.push(historyCard(rec));
  }
  cards.sort((a, b) => (b.finishedAt ?? b.startedAt ?? 0) - (a.finishedAt ?? a.startedAt ?? 0));
  if (cards.length > MAX_CARDS) cards.length = MAX_CARDS;
  return cards;
}

/** One persisted record → a finished card matching {@link foldEvent}'s shape. */
function historyCard(rec) {
  return {
    id: 'h:' + rec.id,
    historyId: rec.id,
    kind: rec.kind || 'build',
    dir: rec.dir || '',
    coord: rec.coord || null,
    state: 'finished',
    startedAt: rec.startedAt ?? null,
    finishedAt: rec.finishedAt ?? null,
    millis: rec.millis ?? null,
    cancelled: !!rec.cancelled,
    success: typeof rec.success === 'boolean' ? rec.success : null,
    modules: (rec.modules || []).map((m) => ({
      dir: m.dir || '',
      state: m.success ? 'success' : 'failed',
      millis: m.millis ?? null,
    })),
    phases: (rec.phases || []).map((p) => ({ name: p.name || '?', state: phaseState(p.status) })),
    output: [],
    diagnostics: (rec.diagnostics || []).map((d) => ({
      phase: d.phase || '',
      code: d.code || '',
      message: d.message || '',
      test: d.test || '',
      exceptionClass: d.exceptionClass || '',
    })),
  };
}

/**
 * A finished card's outcome badge: the engine's explicit success when it sent one (HTTP-triggered
 * builds do), else derived from module rows (socket requests encode their outcome in wire
 * messages, not events): any failed module → failed; all finished and some succeeded → success.
 */
export function outcomeOf(card) {
  if (card.state === 'running') return 'running';
  if (card.cancelled) return 'cancelled';
  if (card.success === true) return 'success';
  if (card.success === false) return 'failed';
  if (card.modules.some((m) => m.state === 'failed')) return 'failed';
  if (card.modules.length > 0 && card.modules.every((m) => m.state === 'success')) return 'success';
  return 'finished';
}

/** One line summarizing a card's module work, e.g. "3 modules · 1 failed" — '' when nothing to say. */
export function moduleSummary(card) {
  const n = card.modules.length;
  if (n === 0) return '';
  const failed = card.modules.filter((m) => m.state === 'failed').length;
  const noun = n === 1 ? 'module' : 'modules';
  return failed > 0 ? `${n} ${noun} · ${failed} failed` : `${n} ${noun}`;
}

function byId(cards, requestId) {
  return cards.find((c) => c.id === requestId);
}

/** The card's row for a module dir, created on first sight (single-goal requests skip module-start). */
function moduleRow(card, dir) {
  let row = card.modules.find((m) => m.dir === dir);
  if (!row) {
    row = { dir: dir || '', state: 'running', millis: null };
    card.modules.push(row);
  }
  return row;
}

/**
 * The card's chain entry for a phase name, created in arrival order. Workspace builds interleave
 * many modules' phases; folding by name aggregates them into one chain (a phase is running while
 * any module runs it), which matches the design's single lock→compile→test→build strip.
 */
function phaseRow(card, phase) {
  let row = card.phases.find((p) => p.name === phase);
  if (!row) {
    row = { name: phase || '?', state: 'running' };
    card.phases.push(row);
  }
  return row;
}

/** Engine PhaseStatus → chain-node state. */
function phaseState(status) {
  switch (status) {
    case 'SUCCESS':
      return 'success';
    case 'FAIL':
      return 'failed';
    case 'CANCELLED':
      return 'cancelled';
    case 'SKIPPED':
      return 'skipped';
    default:
      return 'running';
  }
}
