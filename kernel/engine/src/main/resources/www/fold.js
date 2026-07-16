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
 * parses; + success/cancelled/millis on finish); module/step/output/pipeline events carry
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
        // Every build is a list of module rows (a single-project build has one, keyed by the empty
        // SINGLE_GOAL_DIR); each row carries its OWN step chain, so the card shows a chain per
        // module rather than one merged strip.
        modules: [],
        // Weight-based progress, identical to the CLI: the engine streams per-module pipeline
        // numerator/denominator (weight units); mods holds the latest per dir, and the card's bar
        // is their sum over the plan total. planWeight seeds the denominator so a workspace bar
        // never jumps backward as later modules start. etaMillis/etaAt drive the ETA countdown.
        mods: {},
        planWeight: 0,
        etaMillis: null,
        etaAt: null,
        output: [],
        // Failure output is kept per module (moduleRow.diagnostics), keyed by the diagnostic's dir,
        // so the dashboard nests each failure under its failed module inside "failure details".
      });
      if (cards.length > MAX_CARDS) cards.length = MAX_CARDS;
      break;
    }
    case 'module-start': {
      const card = byId(cards, d.requestId);
      if (card) {
        const row = moduleRow(card, d.dir);
        row.state = 'running';
        if (d.coord) row.coord = d.coord;
      }
      break;
    }
    case 'step-start': {
      const card = byId(cards, d.requestId);
      if (card) stepRow(card, d.dir, d.step, d.phase).state = 'running';
      break;
    }
    case 'step-finish': {
      const card = byId(cards, d.requestId);
      if (card) stepRow(card, d.dir, d.step, d.phase).state = stepState(d.status);
      break;
    }
    case 'plan': {
      const card = byId(cards, d.requestId);
      if (card) card.planWeight = d.weight || 0;
      break;
    }
    case 'pipeline-progress': {
      const card = byId(cards, d.requestId);
      if (card) card.mods[d.dir || ''] = { num: d.numerator || 0, den: d.denominator || 0 };
      break;
    }
    case 'eta': {
      const card = byId(cards, d.requestId);
      if (card) {
        card.etaMillis = typeof d.millis === 'number' ? d.millis : null;
        card.etaAt = event.at ?? null;
      }
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
      if (card) {
        const mod = moduleRow(card, d.dir);
        if (mod.diagnostics.length < MAX_DIAGNOSTICS) {
          mod.diagnostics.push({
            step: d.step || '',
            code: d.code || '',
            message: d.message || '',
            test: d.test || '',
            exceptionClass: d.exceptionClass || '',
          });
        }
      }
      break;
    }
    case 'pipeline-finish': {
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
        if (d.coord) row.coord = d.coord;
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
        card.etaMillis = null; // the countdown is an in-flight affordance; a finished card is 100%
      }
      break;
    }
    default:
      break; // unknown event types are future vocabulary, never an error
  }
  return cards;
}

/** Sum of the latest per-module weight numerators — the completed weight so far. */
export function weightNumerator(card) {
  let n = 0;
  for (const dir in card.mods || {}) n += card.mods[dir].num || 0;
  return n;
}

/**
 * The bar's denominator: the plan total when known (stable, so a workspace bar can't jump backward
 * as later modules start), else the sum of per-module denominators (a single-pipeline build has one).
 */
export function weightDenominator(card) {
  let den = 0;
  for (const dir in card.mods || {}) den += card.mods[dir].den || 0;
  return Math.max(card.planWeight || 0, den);
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
      if (rec.buildNumber) live.buildNumber = rec.buildNumber; // and pick up its assigned #number
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
    buildNumber: rec.buildNumber || null,
    kind: rec.kind || 'build',
    dir: rec.dir || '',
    coord: rec.coord || null,
    state: 'finished',
    startedAt: rec.startedAt ?? null,
    finishedAt: rec.finishedAt ?? null,
    millis: rec.millis ?? null,
    cancelled: !!rec.cancelled,
    success: typeof rec.success === 'boolean' ? rec.success : null,
    modules: historyModules(rec),
    output: [],
  };
}

/** A persisted diagnostic → the client's flat failure-output shape (errors only; warnings dropped). */
function historyDiags(diags, dir) {
  return (diags || [])
    .filter((d) => d.severity !== 'warning' && (d.dir || '') === (dir || ''))
    .map((d) => ({
      step: d.step || '',
      code: d.code || '',
      message: d.message || '',
      test: d.test || '',
      exceptionClass: d.exceptionClass || '',
    }));
}

/**
 * Module rows for a persisted record, matching the live card shape (each with its own step chain).
 * A workspace record has `modules[]` each carrying `steps`; a single-project record has no modules
 * and its steps at the top level — synthesize one row from them so backfilled cards match live.
 */
function historyModules(rec) {
  const toSteps = (ps) => (ps || []).map((p) => ({ name: p.name || '?', state: stepState(p.status), phase: p.phase || '' }));
  if ((rec.modules || []).length > 0) {
    return rec.modules.map((m) => ({
      dir: m.dir || '',
      coord: m.coord || null,
      state: m.success ? 'success' : 'failed',
      millis: m.millis ?? null,
      steps: toSteps(m.steps),
      diagnostics: historyDiags(rec.diagnostics, m.dir || ''),
    }));
  }
  // Single-project: no modules, steps at top level. Its diagnostics live in the "" bucket, so take
  // every error the record carries (there is only one module to own them).
  return [{
    dir: rec.dir || '',
    coord: rec.coord || null,
    state: rec.cancelled ? 'cancelled' : rec.success === false ? 'failed' : 'success',
    millis: rec.millis ?? null,
    steps: toSteps(rec.steps),
    diagnostics: (rec.diagnostics || [])
      .filter((d) => d.severity !== 'warning')
      .map((d) => ({
        step: d.step || '',
        code: d.code || '',
        message: d.message || '',
        test: d.test || '',
        exceptionClass: d.exceptionClass || '',
      })),
  }];
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

/**
 * Group a module's step rows into a coarse **phase-chain**, in first-encounter order (which is
 * pipeline order): the first step of a not-yet-seen phase appends a phase node; later steps of that
 * phase attach to it. The client stays "dumb" — it keys on whatever `phase` wire-string the steps
 * carry, so a future/plugin phase just appears as its own node with no code change here. A step with
 * no phase (`''`) forms a node keyed by its own name so it is never dropped (shouldn't happen once
 * every emitted step is phase-tagged). Each node's `state` is derived from its steps
 * (failed › running › skipped/cancelled › success) and it keeps its `steps` for click-to-expand.
 */
export function phaseChainOf(module) {
  const nodes = [];
  const byKey = new Map();
  for (const s of (module && module.steps) || []) {
    const key = s.phase || s.name; // '' phase → keyed by the step name (last-resort, never merged)
    let node = byKey.get(key);
    if (!node) {
      node = { key, phase: s.phase || '', label: phaseLabel(s.phase || s.name), steps: [], state: 'running' };
      byKey.set(key, node);
      nodes.push(node);
    }
    node.steps.push(s);
  }
  for (const node of nodes) node.state = phaseState(node.steps);
  return nodes;
}

/** Display label for a phase wire-name: capitalize the first letter ('compile' → 'Compile'). */
function phaseLabel(wire) {
  return wire ? wire.charAt(0).toUpperCase() + wire.slice(1) : '?';
}

/** A phase node's aggregate state from its steps: failed › running › skipped/cancelled › success. */
function phaseState(steps) {
  if (!steps.length) return 'running';
  if (steps.some((s) => s.state === 'failed')) return 'failed';
  if (steps.some((s) => s.state === 'running')) return 'running';
  if (steps.every((s) => s.state === 'skipped')) return 'skipped';
  if (steps.every((s) => s.state === 'skipped' || s.state === 'cancelled')) return 'cancelled';
  return 'success'; // all terminal, at least one success
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

/**
 * The card's row for a module dir, created on first sight. A single-pipeline (single-project) build
 * emits its step/pipeline events under the empty SINGLE_GOAL_DIR, so it gets exactly one row keyed by
 * `''`. Each row owns its step chain (`steps`).
 */
function moduleRow(card, dir) {
  const key = dir || '';
  let row = card.modules.find((m) => m.dir === key);
  if (!row) {
    row = { dir: key, coord: null, state: 'running', millis: null, steps: [], diagnostics: [] };
    card.modules.push(row);
  }
  return row;
}

/**
 * The chain entry for a step name WITHIN its module (keyed by the event's dir), created in arrival
 * order. Unlike the old global-by-name folding, each module keeps its own chain, so the dashboard
 * shows one lock→compile→test→build strip per module. `phase` (the step's pipeline phase wire-name,
 * or '' when unset) rides the row so the UI can render the phase/step hierarchy.
 */
function stepRow(card, dir, step, phase) {
  const mod = moduleRow(card, dir);
  let row = mod.steps.find((p) => p.name === step);
  if (!row) {
    row = { name: step || '?', state: 'running', phase: phase || '' };
    mod.steps.push(row);
  } else if (phase && !row.phase) {
    row.phase = phase; // a later event carried the phase the first one omitted
  }
  return row;
}

/** Engine StepStatus → chain-node state. */
function stepState(status) {
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
