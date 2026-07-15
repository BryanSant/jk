# The embedded HTTP server (`[http]`)

**Status: implemented (phases 1–5).** Companion doc: [`webclient.md`](webclient.md) (the
dashboard SPA this server hosts).

The engine grows an optional, embedded HTTP server: a single-user, localhost-by-default web
front-end for the resident engine — static content plus a small REST/SSE surface, eventually a
stripped-down, JVM-centric, single-user cousin of Buildkite (build history, trends, live feedback).
This is the "web backend" [`engine.md`](engine.md) anticipated when it deferred a stable versioned
wire protocol: because the HTTP server lives *inside* the engine process and calls `BuildService`
and friends directly, it is always the same build as the engine — no protocol versioning is needed,
and the NDJSON socket protocol stays untouched and internal.

## Design constraints (non-negotiable)

- **Disabled by default, zero cost when disabled.** No `[http]` table in `~/.jk/config.toml` →
  no object construction, no thread, no bind, no classpath scan. The enable check is one
  `getTable("http") == null` during engine startup.
- **Zero new dependencies.** The server is the JDK's own `jdk.httpserver` module
  (`com.sun.net.httpserver.HttpServer` — a spec'd JDK module, not internal API), which the engine's
  JDK 25 floor guarantees. Routing, JSON emission, and MIME mapping are small hand-rolled pieces in
  the existing `Ndjson`/`MinimalXml` tradition. No Jetty, no Netty, no JSON library.
- **Advisory, never load-bearing.** The engine's primary role is hosting builds. If the HTTP bind
  fails (port already in use, bad `host` value), the engine logs it loudly, surfaces it in
  `jk engine status`, and **continues serving builds without HTTP**. An HTTP misconfiguration must
  never take down or prevent an engine.
- **Fits the 256 MiB SerialGC heap.** Handlers stream responses; nothing buffers a whole file or
  accumulates unbounded per-request state. Admission control (below) bounds concurrent work.

## Enabling and configuration

**The server is on by default** — loopback-only, mutations token-gated even there — so a fresh
install gets the dashboard with zero configuration. Opting out is explicit: `enabled = false` in
the `[http]` table, or `JK_HTTP_ENABLED=false` in the environment. The table itself remains
user-global machine policy, deliberately not project-overridable, same reasoning as `[engine]`:

```toml
[http]                        # present or absent, the server is on unless disabled
enabled = true                # false = no HTTP at all (env: JK_HTTP_ENABLED)
host = "127.0.0.1"            # default; non-loopback binds require token auth on /api (see Security)
port = 8910                   # default; 0 = OS-assigned, recorded in <key>.http
max-concurrent-requests = 16  # default; 0 = the container-aware core count
www-root = "state/www"        # absolute, or relative to the resolved JK_HOME (~/.jk)
```

Parsed by the `JkHttpConfig` record beside `JkEngineConfig` in `build.jumpkick.config` (`:core`),
via the same lenient `TomlValues` reader: missing file or missing table → the defaults (feature
on); a present table with malformed keys falls back per-key to defaults (config is advisory, never
a build-breaking gate). One asymmetry is deliberate: an *existing but unparseable* config file
disables the server — the file may contain an `enabled = false` the parser can't reach, and an
explicit disable must fail closed, never be undone by a syntax error. Env overrides
`JK_HTTP_ENABLED` / `JK_HTTP_HOST` / `JK_HTTP_PORT` / `JK_HTTP_MAX_CONCURRENT_REQUESTS` /
`JK_HTTP_WWW_ROOT` win over the file (env > user-config > default); the test conventions set
`JK_HTTP_ENABLED=false` so test-spawned engines never open listening sockets as a side effect.

Like `[engine]`, the table is read once at engine start. Enabling or changing it means
`jk engine stop` and letting the next command spawn a fresh engine.

`www-root` resolves against `JkDirs.homeDir()` when relative (the doc'd contract is "relative to
JK_HOME"), so the default is `~/.jk/state/www`. The directory is created lazily on first use, not
at startup.

### Original `threads` knob — why it became `max-concurrent-requests`

The first sketch had `threads = 4` (a physical pool size). That knob can't be honored under
virtual threads: virtual threads run on the JVM-global carrier pool
(`jdk.virtualThreadScheduler.parallelism`, a process-wide property jk deliberately does not set),
sized to the container-aware core count and shared with everything else in the engine. A
per-feature "physical thread count" would require a dedicated platform-thread pool, defeating the
virtual-thread design. What actually needs bounding in a 256 MiB process is **concurrent
in-flight requests**, so that's the knob. `0` keeps the original "match the container-friendly
core count" semantic.

## Placement

- `build.jumpkick.engine.http` package in `:engine` (`kernel/engine`) — engine-only code; the slim
  client never links it, preserving the compiler-enforced engine/front-end split.
- `JkHttpConfig` in `build.jumpkick.config` (`:core`), sibling of `JkEngineConfig`.
- Shipped SPA assets in `kernel/engine/src/main/resources/www/` — rolled into the
  `jk-engine-<version>.jar` fat jar by `:cli-engine:shadowJar` with no build-file changes.
- One new `EnginePaths.Paths` member: `<key>.http` (see Lifecycle).

## Lifecycle

Owned entirely by `EngineServer`, in the same places the accept loop already lives:

1. **Start** — in `EngineServer.run()`, after the lock election and socket bind succeed (the
   engine must be *the* engine before it claims a TCP port). If `JkHttpConfig.resolve()` is empty,
   nothing happens. Otherwise construct `HttpEngineServer`, bind, and write the actual bound URL
   (e.g. `http://127.0.0.1:8910/`) to `~/.jk/state/engine/<key>.http`. Bind failure → log +
   remember the error for status reporting + continue without HTTP.
2. **Serve** — `HttpServer` with `setExecutor(Executors.newVirtualThreadPerTaskExecutor())`
   (factory named `jk-http-`), all requests admission-gated (below).
3. **Stop** — in `EngineServer.cleanup()`: `HttpServer.stop(1)` (one second of grace for in-flight
   responses; SSE streams are closed immediately — clients reconnect to the next engine), delete
   `<key>.http`.
4. **Status** — `jk engine status` gains a `Web UI` line: the URL when serving, `disabled` when no
   table, or the bind error when it failed. `--output json` carries `httpUrl` (null when off).

The engine is resident regardless of HTTP — it never self-terminates and runs until an explicit
`jk engine stop` — so the dashboard is always served by a live process and never vanishes out from
under an open browser tab.

The engine still stops for every explicit reason: `jk engine stop`, a version-skew kill+respawn
after a `jk` upgrade, `SIGTERM`, or a crash. The SPA therefore still needs its
disconnected/reconnect handling (see `webclient.md`) — those interruptions are momentary
(an upgrade respawn) or deliberate, never a silent timeout. No per-request idle accounting and no
SSE in-flight accounting are needed at all.

## Threading and admission control

Facts this design rests on (verified against the tree):

- Carrier threads = `Runtime.availableProcessors()` (cgroup-aware); jk sets no
  `jdk.virtualThreadScheduler.parallelism` override anywhere. Those carriers are shared by every
  virtual thread in the engine: `jk-engine-conn-*` connection threads, per-request build threads,
  `JkThreads.io()`, and now `jk-http-*` handlers.
- Virtual threads are cooperatively scheduled — they unmount at blocking points, and JDK 25 does
  **no time-sliced preemption**. A CPU-bound handler pins its carrier until it blocks.
- The engine's own CPU-heavy work (hashing, fingerprinting) already runs on `JkThreads.cpu()`, a
  bounded **platform** ForkJoinPool of `min(cores, 8)` — not on carriers. Compiles and tests are
  in forked worker JVMs and don't compete at all.

So the starvation scenario — a burst of CPU-heavy HTTP requests occupying every carrier and
delaying build-event dispatch — is real in principle and cheap to prevent. Two rules:

1. **Admission semaphore.** A `Semaphore(maxConcurrentRequests)` guards every request;
   `tryAcquire` failure → immediate `503` + `Retry-After: 1` without doing any work. The cap
   defaults to 16 — a dashboard's initial burst (a handful of assets + a couple of API calls +
   one SSE stream) fits comfortably; nothing legitimate queues.
2. **Handlers are IO-shaped, by rule.** An endpoint that computes (stats aggregation over build
   history, report rendering) submits that computation to `JkThreads.cpu()` and awaits it — the
   virtual thread unmounts while waiting (freeing its carrier), and the CPU work competes inside
   the same bounded pool the engine already budgets for hashing, instead of against the carrier
   pool. This rule goes in the package javadoc and is the review bar for every new endpoint.

With both in place the worst case is `min(cores, 8)` HTTP-origin CPU tasks time-sharing the FJ
pool with the engine's hashing — bounded, fair, and invisible to build correctness.

## Static content

One hand-rolled `StaticHandler` serves two sources, checked in order:

1. **`www-root` on disk** (default `~/.jk/state/www`) — user-supplied assets **and
   server-rendered/appended content**: a running build may append to a `.json` file here, a report
   generator may drop `.html`/`.md` files, and the SPA (or curl) fetches them as plain static
   files. Disk wins so users can override shipped assets and generated data is always current.
2. **Classpath `/www`** (inside the engine fat jar) — the shipped dashboard SPA
   (`webclient.md`). Immutable per engine version.

Resolution and safety:

- Decode the path (`%`-decoding once), reject anything containing `..`, an encoded separator, or a
  NUL; normalize; resolve under the root and verify `normalized.startsWith(root)` — the classic
  traversal gate, applied identically to both sources. Directory requests serve `index.html`; no
  directory listings, ever.
- MIME type from a small hand-rolled extension table (html, css, js, mjs, json, md, svg, png, jpg,
  woff2, ico, txt, wasm; default `application/octet-stream`). `Files.probeContentType` is
  platform-dependent — not used.
- **Caching:** disk content is served with `Cache-Control: no-cache` plus a
  `Last-Modified`/`If-Modified-Since` pair — correct for files a build appends to (each fetch sees
  the current bytes; unchanged files still 304). Classpath assets get
  `Cache-Control: max-age=3600` plus an `ETag` derived from the engine version — a new engine jar
  is a new ETag.
- **Growing files:** a GET snapshots the file's size at open and serves exactly that many bytes
  with a matching `Content-Length` — a concurrent append never corrupts framing; the client's next
  poll sees more. Live tailing, when we want it, is an SSE endpoint's job, not the static
  handler's.
- Empty/missing `www-root` is fine — the classpath SPA still serves; the disk root is only created
  when something writes to it.

## REST surface (v1)

All under `/api/`, dispatched by a tiny hand-rolled router (method + first path segment — no
pattern language until something needs it). Responses are JSON emitted by a small `JsonOut`
writer; request bodies are the flat scalar objects the existing dependency-free `Ndjson` reader
already parses — the same deliberate flat-message discipline as the engine wire protocol.

| Endpoint | Method | Auth | Purpose |
|---|---|---|---|
| `/api/status` | GET | read-tier | Engine vitals — the same numbers as `jk engine status --output json` (version, pid, uptime, heap/rss, active connections/pipelines), plus `httpUrl`. |
| `/api/events` | GET | read-tier | SSE stream of engine lifecycle events (see below). |
| `/api/build` | POST | **token, always** | `{"dir": "/abs/workspace"}` — triggers a build via the same `BuildService` path the socket protocol uses; responds `202` with a request id. Progress is observed on `/api/events`, not the response. |
| `/api/history` | GET | read-tier | The persisted build journal. No `?id=` → a JSON array of the newest entries' full records; `?id=<id>` → that one entry's `record.json`. Backfills the Activity feed on load. See [`build-history.md`](build-history.md). |
| `/api/history/artifact` | GET | read-tier | `?id=<id>&name=<test-results.md\|jk.lock\|diagnostics.txt>` — a snapshot artifact as plain text; `name` is whitelisted so it can't escape the entry dir. |
| `/api/history` | DELETE | **token, always** | `?id=<id>` — delete one entry (like removing a CI run). `200 {"deleted":true}` or `404`. |
| `/api/metrics` | GET | read-tier | The running build aggregates (see [`metrics.md`](metrics.md)): a flat JSON array, one object per tier row (`scope`: `global` / `project` / `step` / `project/step`) with ok/failed/cancelled counts, total/min/max millis, and a pre-computed `okAvgMillis`. Optional `?dir=` keeps one project's rows (the global tiers are always included). Feeds the Status view's build-stats section and `jk status`. |
| `/api/cache` | GET | read-tier | The cache-directory breakdown — the same sections `jk cache info` renders (CAS blobs, action cache, worker JARs, run logs, format stamps: count + bytes each), plus `totalCount`/`totalBytes`, the configured `maxBytes` ceiling, and `lastPrunedMillis` (0 = never). IO-shaped (walks the cache), computed per request. Feeds the Status view's Cache panel. |

Anything mutating is POST/DELETE and token-gated everywhere, including on loopback (see Security).

### SSE (`/api/events`)

`text/event-stream`, one `event:`/`data:` pair per engine event, flat JSON in `data:`. v1 events
are coarse request-level lifecycle (`request-start`, `module-start`, `pipeline-finish`,
`request-finish`, heartbeat comment lines every 15 s to defeat idle proxies and detect dead
clients). Internally a small fan-out: a bounded per-client queue (drop-oldest on overflow — a slow
browser must never backpressure a build) fed by the same listener seams `EngineServer` already
adapts to wire messages. `EventSource` cannot set an `Authorization` header, so on non-loopback
binds this one endpoint accepts the token as an `access_token` query parameter instead.

## Security

Single-user tool, but browsers make even localhost hostile. Layers, cheapest first:

- **Host-header validation (always, all requests).** The `Host` must match the configured
  bind (or a loopback equivalent when bound to loopback); anything else → `421`. This defeats DNS
  rebinding, where a malicious page resolves its own hostname to 127.0.0.1 and reads responses
  from an origin the browser thinks is the attacker's.
- **No CORS headers, ever.** Cross-origin reads stay blocked by the browser's same-origin policy;
  preflighted cross-origin writes fail their preflight.
- **Token on all mutations (both bind modes).** A cross-origin page can still fire a "simple"
  no-preflight POST at `http://127.0.0.1:8910/api/build` — classic localhost CSRF. Requiring
  `Authorization: Bearer <token>` kills it: browsers won't attach custom headers cross-origin
  without a preflight we never approve.
- **Token on all `/api` reads when bound non-loopback.** On `0.0.0.0` the LAN can reach
  build-triggering endpoints; reads of build metadata are gated too. Static content stays
  unauthenticated in both modes — the dashboard shell has to be loadable by a plain browser
  navigation, which cannot carry a header, and it contains no secrets.
- **No TLS in v1.** On a non-loopback bind the token travels plaintext on the LAN — documented,
  acceptable for a trusted home/office network, revisit (or front with a reverse proxy) if that
  stops being true.

### The token is opaque — no JWT

There is no JWT and nothing to decode, on purpose. The token is an opaque random capability — 24
random bytes, URL-safe base64 — generated by `EngineTransport.newToken()`, held in memory, and
persisted to `~/.jk/state/engine/<key>.http-token` (owner-only permissions, `0600`) so the CLI can
hand it to the user. Validation is a constant-time comparison (`MessageDigest.isEqual`) of the
presented bytes against the stored value. That's the entire mechanism.

**The token persists across engine restarts** (it is *not* rotated per start). The file is keyed by
state dir — the same `JK_HOME` always resolves to the same path — and the engine adopts an existing
token on start, minting a fresh one only when no usable file exists. This is what keeps an open
dashboard tab valid across the restarts jk does on its own: a version-skew respawn after a `jk`
upgrade, a crash, a `SIGTERM`. Per-start rotation was never a real defense — the `0600` file *is* the
secret, and anyone who can read it already owns the account — so it only cost every open tab its
session. Because the token now outlives the engine, `EngineServer.cleanup()` deletes the `<key>.http`
URL file but leaves `<key>.http-token` in place.

Rotation is an explicit action, not an accident of restart: **`jk engine rotate-token`** deletes the
persisted token file and stops any running engine (so its in-memory copy is genuinely revoked, not
just replaced on disk). The next `jk` command spawns a fresh engine that mints a new token, and
`jk engine status` prints the new tokenized URL. Use it when a tokenized URL leaks or you otherwise
want to lock out prior holders.

JWT would buy claims, expiry, and third-party verifiability — properties of a multi-user,
multi-service identity world this feature doesn't live in. It would cost a parser, signature
verification, key management, and the standard footgun inventory (`alg=none`, key confusion), all
inside a process that currently ships four third-party jars. If jk ever grows real multi-user
auth, that's an OIDC reverse-proxy conversation, not an embedded-JWT one.

**How the SPA gets the token:** `jk engine status` (and the dashboard's own 401 page) prints the
tokenized URL — `http://127.0.0.1:8910/#t=<token>`. The SPA reads the fragment on load, stashes it
in `sessionStorage`, and immediately strips it from the address bar (`history.replaceState`).
Fragments are never sent in HTTP requests, never logged by servers, and never leave the browser.
The token is stable across engine restarts (above), so a dashboard tab keeps working through an
upgrade/crash respawn without re-running the bootstrap; after `jk engine rotate-token` the tab 401s
and re-bootstraps from the new URL.

## Failure modes

- **Port in use / bad host** → engine logs the bind error, `jk engine status` reports it on the
  `http` line, engine serves builds normally. Never fatal.
- **`www-root` missing** → classpath SPA serves; disk misses fall through silently by design.
- **Slow/dead SSE client** → bounded queue drops oldest events; heartbeat write failure closes
  the stream and releases its in-flight slot.
- **Saturated admission semaphore** → `503 Retry-After: 1`, zero work done.

## Testing

Same style as the engine's existing suite (test seams, no real minutes of sleeping):

- `JkHttpConfig`: table-absent/present/malformed, env layering, relative vs absolute `www-root`.
- `HttpEngineServer` against a real ephemeral-port bind, driven by `java.net.http.HttpClient`:
  static resolution order (disk overrides classpath), traversal attempts (raw, encoded,
  mixed-case), MIME table, 304 behavior, growing-file snapshot reads, Host-header rejection,
  token accept/reject (including timing-safe path), admission 503, SSE event delivery + heartbeat
  + drop-oldest, bind-failure survival (engine keeps serving the socket).
- Lifecycle: the engine is resident and never self-terminates; an explicit `SHUTDOWN` stops it
  cleanly, HTTP server included.

## Implementation phases

1. **`JkHttpConfig`** (`:core`) + tests. No behavior change anywhere.
2. **Server skeleton** — `HttpEngineServer` lifecycle inside `EngineServer.run()`/`cleanup()`,
   `<key>.http` URL file, `jk engine status` line, Host validation, admission semaphore, static
   serving from both sources. This phase is independently shippable and useful (serve generated
   reports from `state/www`).
3. **REST core** — router, `JsonOut`, `/api/status`, token minting/validation +
   `<key>.http-token`.
4. **Events + trigger** — SSE fan-out off the existing listener seams, `/api/build`.
5. **Ship the SPA** — classpath `/www` assets per [`webclient.md`](webclient.md).
