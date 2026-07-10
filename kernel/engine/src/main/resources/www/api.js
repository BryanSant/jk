// SPDX-License-Identifier: Apache-2.0
// The only file that talks HTTP: token bootstrap, fetch wrappers, and the SSE client.
// See docs/webclient.md and docs/http.md (auth tiers, #t= fragment bootstrap).

const TOKEN_KEY = 'jk-http-token';

/**
 * On load, adopt a token from the URL fragment (`#t=…` — printed by `jk engine status`), stash it
 * in sessionStorage, and scrub it from the address bar. Fragments never leave the browser.
 */
export function bootstrapToken() {
  const match = /^#t=([A-Za-z0-9_=-]+)$/.exec(location.hash);
  if (match) {
    sessionStorage.setItem(TOKEN_KEY, match[1]);
    history.replaceState(null, '', location.pathname + location.search);
  }
}

export function token() {
  return sessionStorage.getItem(TOKEN_KEY);
}

function loopback() {
  return ['127.0.0.1', 'localhost', '[::1]', '::1'].includes(location.hostname);
}

function headers() {
  const t = token();
  return t ? { Authorization: 'Bearer ' + t } : {};
}

/** GET an /api path as parsed JSON. Throws {status} on any non-2xx so callers can branch on 401. */
export async function get(path) {
  const resp = await fetch(path, { headers: headers() });
  if (!resp.ok) throw { status: resp.status };
  return resp.json();
}

/** POST a flat object to an /api path; returns parsed JSON, throws {status, error} on non-2xx. */
export async function post(path, body) {
  const resp = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers() },
    body: JSON.stringify(body),
  });
  const json = await resp.json().catch(() => ({}));
  if (!resp.ok) throw { status: resp.status, error: json.error };
  return json;
}

/** GET an /api path as plain text (the log tail). Throws {status} on any non-2xx. */
export async function getText(path) {
  const resp = await fetch(path, { headers: headers() });
  if (!resp.ok) throw { status: resp.status };
  return resp.text();
}

/** DELETE an /api path (a mutation — always token-bearing). Throws {status} on non-2xx. */
export async function del(path) {
  const resp = await fetch(path, { method: 'DELETE', headers: headers() });
  if (!resp.ok) throw { status: resp.status };
  return resp.json().catch(() => ({}));
}

/** The engine event types the dashboard folds (EventSource needs a listener per named event). */
const EVENT_TYPES = [
  'request-start',
  'plan',
  'module-start',
  'phase-start',
  'phase-finish',
  'goal-progress',
  'eta',
  'output',
  'diagnostic',
  'goal-finish',
  'module-finish',
  'request-finish',
];

/**
 * Open the SSE stream. `onEvent({type, data})` per engine event; `onState('live'|'offline')` as the
 * connection comes and goes. EventSource reconnects on its own; an HTTP-enabled engine never idles
 * out (docs/http.md), so 'offline' only ever means an explicit stop, an upgrade respawn, or a crash.
 * EventSource cannot send headers, so non-loopback origins carry the token as a query parameter.
 */
export function events(onEvent, onState) {
  const query = !loopback() && token() ? '?access_token=' + encodeURIComponent(token()) : '';
  const source = new EventSource('/api/events' + query);
  source.onopen = () => onState('live');
  source.onerror = () => onState('offline');
  for (const type of EVENT_TYPES) {
    source.addEventListener(type, (e) => {
      try {
        onEvent({ type, data: JSON.parse(e.data) });
      } catch {
        // a malformed frame is dropped, never fatal to the stream
      }
    });
  }
  return source;
}
