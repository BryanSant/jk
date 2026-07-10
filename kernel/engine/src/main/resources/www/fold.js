// SPDX-License-Identifier: Apache-2.0
// Folds the /api/events stream into activity cards — pure functions of (cards, event), no browser
// globals, so the logic is testable headlessly with `node --test` (see docs/webclient.md).

/** Cards kept in the activity feed — a long-lived tab must not grow the page without limit. */
export const MAX_CARDS = 50;

/**
 * Fold one SSE event into the newest-first card list, mutating and returning it.
 * An event is `{type, data}` where `data` is the parsed flat JSON payload the engine publishes:
 * request-start/finish carry requestId/kind/dir (+ success/cancelled/millis on finish);
 * module-start/module-finish/goal-finish carry requestId/dir (+ success/millis).
 */
export function foldEvent(cards, event) {
  const d = event.data || {};
  switch (event.type) {
    case 'request-start': {
      cards.unshift({
        id: d.requestId,
        kind: d.kind || 'request',
        dir: d.dir || '',
        state: 'running',
        millis: null,
        cancelled: false,
        success: null, // tri-state: null = engine didn't say (socket requests) — derive from modules
        modules: [],
      });
      if (cards.length > MAX_CARDS) cards.length = MAX_CARDS;
      break;
    }
    case 'module-start': {
      const card = byId(cards, d.requestId);
      if (card) moduleRow(card, d.dir).state = 'running';
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
        card.millis = d.millis ?? null;
        card.cancelled = !!d.cancelled;
        card.success = typeof d.success === 'boolean' ? d.success : null;
      }
      break;
    }
    default:
      break; // unknown event types are future vocabulary, never an error
  }
  return cards;
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
