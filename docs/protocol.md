# The jk wire protocol (v1)

The contract between the `jk` client and the resident engine: newline-delimited flat JSON
objects (NDJSON) over a Unix domain socket (macOS/Linux) or token-gated loopback TCP
(Windows). This document is the freeze candidate for 1.0 — after that, changes here follow
the protocol-evolution rules at the bottom.

## Framing

- One message per line; the codec is `Ndjson` (flat objects: string, integer, boolean,
  string-array, flat string-map, and nested-object extraction).
- **Line cap**: 64 MB. A line that long without a terminator is a protocol violation; both
  sides fail the read (`BoundedLineReader`).
- **Client idle timeout**: a streaming client that sees no event for 60 minutes
  (`JK_STREAM_IDLE_MINUTES`, 0 disables) closes the connection and reports the engine dead.
  The server never idle-closes (its connections legitimately wait on cancel-watches).
- **No silent drops of requests**: an unparseable or unknown *request* line is answered with
  `error code=protocol`. Unknown *event* types (server → client) are ignored by readers —
  that is the forward-compat seam that lets fields and event types be added freely.

## Envelope conventions

- `t` is the type discriminator, always first.
- **One null convention**: keys are always emitted; a JSON `null` value means "not
  applicable". Readers treat missing and null identically (the add-a-field seam).
- **One map encoding**: flat string maps are nested objects (`"env":{"K":"V"}`), written by
  `Ndjson.map`, read by `Ndjson.strMap`. No parallel arrays.
- **One location field**: every request that concerns a project carries it as `dir`.
- Numeric "not applicable" is `-1` only where a field predates the null rule and is
  primitive (`testTotal` etc.).

## Protocol zero (FROZEN FOREVER)

`hello` / `hello-ack` / graceful `shutdown force=false` form the tiny handshake every jk
version past and future speaks (engine-versioning-plan R7).

- `hello`: `version` (the sender's REAL version — probes included), `proto` (int),
  `purpose` (`connect` | `probe`).
- `hello-ack`: `version`, `pid`, `startedAt`, `proto`, `draining`.
- **Enforcement** (both directions, since v1): a server that receives `proto >` its own
  answers `error code=version-skew` and closes — it must not serve wire semantics it
  postdates; the client reacts by starting a matching engine (takeover). A client that
  receives a NEWER `proto` in `hello-ack` treats the engine as unusable (same takeover
  path).

## The session envelope

`EngineProtocol.withSession(request, variant, clientEnv, jvmTuning)` is the ONE attachment
point for session state, applied to every hosted request:

- `variant` — the build-variant selector (`release|tier=free`).
- `env` — client-resolved env values for `env:`-indirected plugin config (flat map).
- `jvmMaxRam` / `jvmGc` / `jvmStringDedup` / `jvmArgs` — worker-JVM tuning; because the
  envelope is universal, `--jvm-arg`/`JK_JVM_*` apply to every verb that forks workers.

An empty envelope leaves the request line byte-identical.

## Errors

ONE error envelope: `{"t":"error","code":…,"message":…}`.

| code | meaning | client reaction |
|---|---|---|
| `request-failed` | the verb failed; message says why | surface the message |
| `protocol` | malformed request / framing violation | surface; likely a bug |
| `version-skew` | pin or proto mismatch | upgrade/start matching engine |
| `shutting-down` | engine is draining | retry; successor takes over |
| `auth` | TCP token rejected | re-resolve the endpoint |

`errors[]` arrays on finish messages (`lock-finish`, `workspace-finish`, `forecast-ack`)
are verb *output*, not transport errors, and stay on their messages.

## Terminals

Every async verb ends in exactly one terminal message: `pipeline-finish` (single-pipeline verbs),
`workspace-finish` (workspace builds), or `error`. `pipeline-finish` is self-describing via its
mandatory `kind` field — `build | lock | sync | format | git-fetch | publish | import |
image | tool | script | cache` — each kind's extra fields ride the same message (e.g.
`syncFetched`, `lockPackages`, `toolCoord`).

## Auth (loopback TCP only)

The first line of every connection must be `{"t":"auth","token":…}` — engine→engine
signalling included. The compare is constant-time; a bad token gets `error code=auth`, then
close. Unix-domain connections skip auth (filesystem permissions gate the socket).

## Non-contract vocabularies

The `plan-*`/`explain-*` event families and the history/metrics response shapes are consumed
only by jk's own client and dashboard; they are NOT part of the frozen contract and may
still be consolidated. Everything above this line is the contract.

## Evolution rules after 1.0

1. Adding a field: always allowed (readers ignore unknowns; emit the key with null when not
   applicable).
2. Adding an event type: always allowed (readers no-op unknown events).
3. Adding a request type: allowed; older engines answer `error code=protocol`, which
   clients must treat as "engine too old" (takeover).
4. Renaming, retyping, or removing anything: bump `PROTOCOL`, and the hello gate turns the
   mismatch into a clean takeover instead of undefined behavior.
