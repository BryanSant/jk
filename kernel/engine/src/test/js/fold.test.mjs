// SPDX-License-Identifier: Apache-2.0
// Headless tests for the dashboard's event-folding logic (docs/webclient.md). Run by
// WebClientFoldTest via `node --test`, which copies fold.js to fold.mjs and passes its path in
// JK_FOLD_MJS (fold.js's .js extension would be treated as CommonJS by a bare node import).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';

const { foldEvent, outcomeOf, MAX_CARDS } = await import(pathToFileURL(process.env.JK_FOLD_MJS));

const start = (id, dir) => ({ type: 'request-start', data: { requestId: id, kind: 'build', dir } });
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
