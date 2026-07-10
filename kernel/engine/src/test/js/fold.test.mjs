// SPDX-License-Identifier: Apache-2.0
// Headless tests for the dashboard's event-folding logic (docs/webclient.md). Run by
// WebClientFoldTest via `node --test`, which copies fold.js to fold.mjs and passes its path in
// JK_FOLD_MJS (fold.js's .js extension would be treated as CommonJS by a bare node import).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';

const { foldEvent, outcomeOf, moduleSummary, seedFromHistory, MAX_CARDS, MAX_OUTPUT_LINES } = await import(
  pathToFileURL(process.env.JK_FOLD_MJS)
);

const historyRecord = (id, dir, extra = {}) => ({
  id,
  kind: 'build',
  dir,
  coord: 'g:a',
  startedAt: 1000,
  finishedAt: 2000,
  millis: 1000,
  cancelled: false,
  success: true,
  modules: [],
  phases: [],
  diagnostics: [],
  ...extra,
});

const start = (id, dir, extra = {}) => ({
  type: 'request-start',
  data: { requestId: id, kind: 'build', dir, ...extra },
});
const finish = (id, data = {}) => ({ type: 'request-finish', data: { requestId: id, ...data } });

test('request-start opens a running card, newest first', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w/a'));
  foldEvent(cards, start(2, '/w/b'));
  assert.equal(cards.length, 2);
  assert.equal(cards[0].id, 2); // newest first
  assert.equal(cards[0].state, 'running');
  assert.equal(outcomeOf(cards[0]), 'running');
});

test('http-triggered finish carries explicit success', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, finish(1, { success: true, millis: 1435 }));
  assert.equal(outcomeOf(cards[0]), 'success');
  assert.equal(cards[0].millis, 1435);
});

test('socket finish without success derives the outcome from module rows', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'module-start', data: { requestId: 1, dir: '/w/a' } });
  foldEvent(cards, { type: 'module-finish', data: { requestId: 1, dir: '/w/a', success: true, millis: 10 } });
  foldEvent(cards, { type: 'module-finish', data: { requestId: 1, dir: '/w/b', success: false, millis: 5 } });
  foldEvent(cards, finish(1, { millis: 20 })); // no success field — the socket-request shape
  assert.equal(outcomeOf(cards[0]), 'failed'); // any failed module fails the card
});

test('all-success module rows derive success; no rows stay neutral', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'goal-finish', data: { requestId: 1, dir: '/w', success: true } });
  foldEvent(cards, finish(1, {}));
  assert.equal(outcomeOf(cards[0]), 'success');

  foldEvent(cards, start(2, '/x'));
  foldEvent(cards, finish(2, {}));
  assert.equal(outcomeOf(cards[0]), 'finished'); // nothing to derive from
});

test('cancelled wins over derived outcomes', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'goal-finish', data: { requestId: 1, dir: '/w', success: true } });
  foldEvent(cards, finish(1, { cancelled: true }));
  assert.equal(outcomeOf(cards[0]), 'cancelled');
});

test('goal-finish creates a module row when module-start never fired (single-goal requests)', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'goal-finish', data: { requestId: 1, dir: '/w', success: false } });
  assert.equal(cards[0].modules.length, 1);
  assert.equal(cards[0].modules[0].state, 'failed');
});

test('events for unknown request ids and unknown types are ignored', () => {
  const cards = [];
  foldEvent(cards, { type: 'module-start', data: { requestId: 99, dir: '/w' } });
  foldEvent(cards, { type: 'plan-module', data: { requestId: 99 } });
  assert.equal(cards.length, 0);
});

test('the feed is bounded at MAX_CARDS', () => {
  const cards = [];
  for (let i = 1; i <= MAX_CARDS + 7; i++) foldEvent(cards, start(i, '/w/' + i));
  assert.equal(cards.length, MAX_CARDS);
  assert.equal(cards[0].id, MAX_CARDS + 7); // newest kept, oldest shed
});

test('coord and client timestamps ride the card', () => {
  const cards = [];
  foldEvent(cards, { ...start(1, '/w', { coord: 'dev.jkbuild:jk' }), at: 1000 });
  assert.equal(cards[0].coord, 'dev.jkbuild:jk');
  assert.equal(cards[0].startedAt, 1000);
  foldEvent(cards, { ...finish(1, { millis: 500 }), at: 1500 });
  assert.equal(cards[0].finishedAt, 1500);
});

test('phases fold per module, each module keeping its own chain', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'phase-start', data: { requestId: 1, dir: '/w/a', phase: 'compile' } });
  foldEvent(cards, { type: 'phase-start', data: { requestId: 1, dir: '/w/b', phase: 'compile' } });
  foldEvent(cards, { type: 'phase-finish', data: { requestId: 1, dir: '/w/a', phase: 'compile', status: 'SUCCESS' } });
  foldEvent(cards, { type: 'phase-start', data: { requestId: 1, dir: '/w/a', phase: 'test' } });
  foldEvent(cards, { type: 'phase-finish', data: { requestId: 1, dir: '/w/a', phase: 'test', status: 'FAIL' } });
  const byDir = (dir) => cards[0].modules.find((m) => m.dir === dir);
  assert.equal(cards[0].modules.length, 2); // two modules, not one merged chain
  assert.deepEqual(
    byDir('/w/a').phases.map((p) => p.name + ':' + p.state),
    ['compile:success', 'test:failed'],
  );
  assert.deepEqual(
    byDir('/w/b').phases.map((p) => p.name + ':' + p.state),
    ['compile:running'], // /w/b's compile is independent of /w/a's
  );
});

test('single-goal phase events (empty dir) become one module with a chain', () => {
  const cards = [];
  foldEvent(cards, start(1, '/proj'));
  foldEvent(cards, { type: 'phase-start', data: { requestId: 1, dir: '', phase: 'compile-java' } });
  foldEvent(cards, { type: 'phase-finish', data: { requestId: 1, dir: '', phase: 'compile-java', status: 'SUCCESS' } });
  assert.equal(cards[0].modules.length, 1);
  assert.equal(cards[0].modules[0].dir, '');
  assert.deepEqual(cards[0].modules[0].phases.map((p) => p.name + ':' + p.state), ['compile-java:success']);
});

test('history backfill maps per-module phases; single-project synthesizes one module', async () => {
  const { seedFromHistory } = await import(pathToFileURL(process.env.JK_FOLD_MJS));
  // workspace record: modules carry their own phases
  const ws = [];
  seedFromHistory(ws, [{
    id: 'w1', kind: 'build', dir: '/w', coord: 'g:w', finishedAt: 5000, success: true,
    modules: [{ coord: 'g:core', dir: '/w/core', success: true, millis: 100, phases: [{ name: 'compile', status: 'SUCCESS' }] }],
    phases: [], diagnostics: [],
  }]);
  assert.equal(ws[0].modules.length, 1);
  assert.equal(ws[0].modules[0].coord, 'g:core');
  assert.deepEqual(ws[0].modules[0].phases.map((p) => p.name + ':' + p.state), ['compile:success']);
  // single-project record: no modules, phases at top level → synthesize one module
  const sp = [];
  seedFromHistory(sp, [{
    id: 's1', kind: 'build', dir: '/p', coord: 'g:p', finishedAt: 6000, success: true,
    modules: [], phases: [{ name: 'compile-java', status: 'SUCCESS' }], diagnostics: [],
  }]);
  assert.equal(sp[0].modules.length, 1);
  assert.deepEqual(sp[0].modules[0].phases.map((p) => p.name + ':' + p.state), ['compile-java:success']);
});

test('output keeps a bounded tail and clears on finish', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  for (let i = 1; i <= MAX_OUTPUT_LINES + 5; i++) {
    foldEvent(cards, { type: 'output', data: { requestId: 1, dir: '/w/m', phase: 'test', line: 'line ' + i } });
  }
  assert.equal(cards[0].output.length, MAX_OUTPUT_LINES);
  assert.equal(cards[0].output.at(-1).line, 'line ' + (MAX_OUTPUT_LINES + 5));
  foldEvent(cards, finish(1, { success: true }));
  assert.equal(cards[0].output.length, 0); // the console tail is an in-flight affordance
});

test('diagnostics accumulate, survive finish, and are capped', async () => {
  const { MAX_DIAGNOSTICS } = await import(pathToFileURL(process.env.JK_FOLD_MJS));
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, {
    type: 'diagnostic',
    data: { requestId: 1, dir: '/w', phase: 'test', code: 'fail', message: 'expected 3 but was 4',
            test: 'adds()', exceptionClass: 'AssertionFailedError' },
  });
  foldEvent(cards, {
    type: 'diagnostic',
    data: { requestId: 1, dir: '/w', phase: 'lock', code: 'resolve', message: 'no versions for com.foo:bar' },
  });
  foldEvent(cards, finish(1, { success: false }));
  assert.equal(cards[0].diagnostics.length, 2); // NOT cleared on finish, unlike output
  assert.equal(cards[0].diagnostics[0].test, 'adds()');
  assert.equal(cards[0].diagnostics[1].phase, 'lock');
  for (let i = 0; i < MAX_DIAGNOSTICS + 5; i++) {
    foldEvent(cards, { type: 'diagnostic', data: { requestId: 1, dir: '/w', phase: 'p', message: 'm' + i } });
  }
  assert.equal(cards[0].diagnostics.length, MAX_DIAGNOSTICS);
});

test('module summary counts modules and failures', () => {
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  assert.equal(moduleSummary(cards[0]), '');
  foldEvent(cards, { type: 'module-finish', data: { requestId: 1, dir: '/w/a', success: true, millis: 5 } });
  assert.equal(moduleSummary(cards[0]), '1 module');
  foldEvent(cards, { type: 'module-finish', data: { requestId: 1, dir: '/w/b', success: false, millis: 5 } });
  assert.equal(moduleSummary(cards[0]), '2 modules · 1 failed');
});

test('seedFromHistory adds finished cards, newest first', () => {
  const cards = [];
  seedFromHistory(cards, [
    historyRecord('20260101T000000000-aaaa', '/w/a', { finishedAt: 1000 }),
    historyRecord('20260101T000001000-bbbb', '/w/b', { finishedAt: 5000 }),
  ]);
  assert.equal(cards.length, 2);
  assert.equal(cards[0].historyId, '20260101T000001000-bbbb'); // newest first
  assert.equal(outcomeOf(cards[0]), 'success');
  assert.equal(cards[0].id, 'h:20260101T000001000-bbbb');
});

test('seedFromHistory is idempotent (no duplicate on re-seed)', () => {
  const cards = [];
  const rec = historyRecord('20260101T000000000-aaaa', '/w/a');
  seedFromHistory(cards, [rec]);
  seedFromHistory(cards, [rec]);
  assert.equal(cards.length, 1);
});

test('seedFromHistory reconciles a live card instead of duplicating it', () => {
  const cards = [];
  foldEvent(cards, start(7, '/w/a'));
  foldEvent(cards, finish(7, { success: true, millis: 1000 }));
  cards[0].finishedAt = 2000; // matches the record below
  seedFromHistory(cards, [historyRecord('20260101T000000000-aaaa', '/w/a', { finishedAt: 2000 })]);
  assert.equal(cards.length, 1); // live card reused, not duplicated
  assert.equal(cards[0].id, 7); // still the live numeric-id card
  assert.equal(cards[0].historyId, '20260101T000000000-aaaa'); // now deletable
});

test('seedFromHistory respects MAX_CARDS', () => {
  const cards = [];
  const records = [];
  for (let i = 0; i < MAX_CARDS + 10; i++) {
    records.push(historyRecord('id-' + String(i).padStart(4, '0'), '/w/' + i, { finishedAt: 1000 + i }));
  }
  seedFromHistory(cards, records);
  assert.equal(cards.length, MAX_CARDS);
});

test('weight progress aggregates numerator/denominator across modules', async () => {
  const { weightNumerator, weightDenominator } = await import(pathToFileURL(process.env.JK_FOLD_MJS));
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'plan', data: { requestId: 1, weight: 300 } });
  foldEvent(cards, { type: 'goal-progress', data: { requestId: 1, dir: '/w/a', numerator: 50, denominator: 100 } });
  foldEvent(cards, { type: 'goal-progress', data: { requestId: 1, dir: '/w/b', numerator: 20, denominator: 100 } });
  assert.equal(weightNumerator(cards[0]), 70);
  // denominator = max(planWeight 300, sum of module dens 200) = 300 — stable, no backward jump
  assert.equal(weightDenominator(cards[0]), 300);
});

test('weight denominator falls back to summed module dens when no plan (single build)', async () => {
  const { weightDenominator } = await import(pathToFileURL(process.env.JK_FOLD_MJS));
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'goal-progress', data: { requestId: 1, dir: '', numerator: 4, denominator: 12 } });
  assert.equal(weightDenominator(cards[0]), 12);
});

test('goal-progress updates latest per dir (no double count on repeat)', async () => {
  const { weightNumerator } = await import(pathToFileURL(process.env.JK_FOLD_MJS));
  const cards = [];
  foldEvent(cards, start(1, '/w'));
  foldEvent(cards, { type: 'goal-progress', data: { requestId: 1, dir: '/w/a', numerator: 10, denominator: 100 } });
  foldEvent(cards, { type: 'goal-progress', data: { requestId: 1, dir: '/w/a', numerator: 80, denominator: 100 } });
  assert.equal(weightNumerator(cards[0]), 80); // latest wins, not 10+80
});

test('eta is captured and cleared on finish', () => {
  const cards = [];
  foldEvent(cards, { ...start(1, '/w'), at: 1000 });
  foldEvent(cards, { ...{ type: 'eta', data: { requestId: 1, millis: 5000 } }, at: 1200 });
  assert.equal(cards[0].etaMillis, 5000);
  assert.equal(cards[0].etaAt, 1200);
  foldEvent(cards, finish(1, { success: true }));
  assert.equal(cards[0].etaMillis, null); // countdown stops on finish
});
