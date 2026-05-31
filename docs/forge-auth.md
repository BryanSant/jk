# Forge Authentication — Multi-provider Auth & API Access

**Status:** Implemented. The `dev.jkbuild.forge` library layer (provider
model, device flow, token store, resolution chain) is built and tested in
`:io`; the `jk auth` command surface (`login`/`logout`/`status`/`token`)
lives in `:cli`; and OAuth client IDs are configurable via `ForgeAuthConfig`
in `:core`. What remains is operational: registering real OAuth apps and
verifying non-GitHub endpoint details (see "Remaining").
**Scope:** How `jk` obtains a token to call a git forge's API, modeled on
`gh auth login` but generalized across **GitHub, GitLab, Gitea/Forgejo
(incl. Codeberg), Bitbucket, and future providers**. Defines the `jk auth`
command surface, the token-resolution chain, the OAuth device flow, and
on-disk token storage. Does NOT specify which forge APIs `jk` calls or for
what feature — that lands with the feature that needs it.

## Motivation

`jk` needs to talk to git-forge APIs (e.g. for package/registry features).
The friendly, well-trodden UX is the one `gh` ships: the user runs a login
command, gets a short code, approves it in a browser, and a reusable token
is stored locally. We replicate that experience while staying
self-contained, multi-provider, and CI-friendly.

Two non-starters, ruled out up front:

- **Username/password.** GitHub removed password auth for the API in 2020,
  and the other forges discourage it for automation too.
- **A shipped client secret.** CLIs can't keep a secret. The OAuth **device
  flow** is designed precisely for this — it needs only a *public*
  `client_id`, no secret, and no local redirect server.

## Why provider is an explicit axis (not a `--github` flag)

We support many forges, so the design must not privilege GitHub. Two axes
are in play and they are **independent**:

- **Provider** — the forge *software* (its endpoint shape and quirks):
  GitHub, GitLab, Gitea/Forgejo, Bitbucket.
- **Host** — the concrete *instance*: `github.com` vs a self-hosted GHE;
  `gitlab.com` vs a private GitLab; any number of Gitea/Forgejo hosts
  (`codeberg.org`, `git.example.org`, …).

Boolean switches (`--github`, `--gitlab`, …) don't scale — they'd be a
mutually-exclusive flag soup and couldn't express "Gitea on *this* host."
So:

```
jk auth login  [provider] [--host HOST] [--with-token]
jk auth logout <provider> [--host HOST]
jk auth status [provider] [--host HOST]
jk auth token  [provider] [--host HOST]      # print resolved token (gh-compatible)
```

- `<provider>` is a positional enum: `github | gitlab | gitea | bitbucket`
  (aliases: `gh`, `glab`, `forgejo`, `codeberg`, `bb`). Resolved by
  `ForgeKind.fromId`.
- `--host` selects the instance. Omitted, it defaults per provider
  (`github.com`, `gitlab.com`, `bitbucket.org`); **Gitea/Forgejo has no
  default and requires `--host`** (`ForgeKind.defaultHost()` is empty).
- `--with-token` reads a token from stdin (PAT / app password) — the
  universal fallback for headless/CI and for providers without a device
  flow.

`AuthCommand` is the parent verb (prints usage, returns 64, matching
`ToolCommand`).

### Auto-detecting the provider

For `login` and `token`, the provider is optional: omit it and jk infers it
from the repo's git `origin` remote (`GitForgeDetector`, in `:io`). It reads
`remote.origin.url` from the git config (walking up to the repo root, and
following a `.git`-file pointer for worktrees/submodules), extracts the host
via `GitUrl.canonicalize` (handles https, `ssh://`, and scp `git@host:path`
forms), resolves `~/.ssh/config` `Host`→`HostName` aliases (so
`git@work-gh:…` maps to github.com), and maps the resulting host through
`ForgeKind.inferFromHost`. Only well-known public hosts resolve; a
self-hosted instance on an unrecognised domain yields nothing and the user
must name the provider. An explicit `--host` always overrides the detected
host.

## Provider capability model

Providers differ in what auth they support; the design treats the device
flow as an **optional capability** with token-paste as the universal floor.
Encoded in `ForgeKind`:

| Provider | Default host | Device flow | Native CLI piggyback | Native env vars |
|----------|--------------|-------------|----------------------|-----------------|
| GitHub | `github.com` | ✅ | `gh auth token` | `GH_TOKEN`, `GITHUB_TOKEN` |
| GitLab | `gitlab.com` | ✅ | `glab auth token` | `GITLAB_TOKEN` |
| Gitea/Forgejo | — (`--host` required) | ✅ | — | `GITEA_TOKEN`, `FORGEJO_TOKEN` |
| Bitbucket | `bitbucket.org` | ❌ (token paste) | — | `BITBUCKET_TOKEN` |

`ForgeKind` also resolves per-host endpoints — `deviceCodeUri(host)`,
`tokenUri(host)`, `apiBase(host)` — including the github.com → `api.github.com`
vs GHE → `https://HOST/api/v3` split. Bitbucket's device-flow endpoints
throw, since it has none.

> ⚠️ **Endpoint/grant details to verify before shipping.** GitHub's device
> flow is well-established. GitLab, Gitea, and Forgejo each expose an RFC 8628
> device grant, but exact paths and scope strings should be confirmed against
> each provider's current docs (and may be version-gated on self-hosted
> instances). The `ForgeKind` URIs encode the best-known endpoints and are the
> single place to adjust.

## Token resolution chain

Every code path that hits a forge API resolves a token through **one**
function, `ForgeAuth.resolveSilently(kind, host)`. The order is deliberate
— explicit configuration wins, ambient tooling next, then jk's own stored
credential, and only then (interactive) do we prompt. Each `ForgeKind`
supplies the variable names and native CLI to consult, so the chain is
provider-neutral.

| Step | Source | `TokenSource` | Prompts? | On failure / absence |
|------|--------|---------------|----------|----------------------|
| 1 | `JK_<KIND>_TOKEN` (e.g. `JK_GITHUB_TOKEN`) | `JK_ENV` | no | fall through |
| 2 | native env vars (`GH_TOKEN`, `GITLAB_TOKEN`, …) | `NATIVE_ENV` | no | fall through |
| 3 | native CLI (`gh auth token`, `glab auth token`) | `NATIVE_CLI` | no | swallow **any** error, fall through |
| 4 | stored per-host credential | `STORE` | no | fall through |
| 5 | OAuth device flow / token paste | `DEVICE_FLOW` / `PAT` | yes | persist on success |

**Rationale.** jk's own `JK_<KIND>_TOKEN` is first so CI and scripts always
win and never shell out — mirroring how `gh` lets `GH_TOKEN` override its
stored login. Native env vars (step 2) let a repo's existing CI secrets
work unchanged. The native-CLI piggyback (step 3) gives a developer who
already ran `gh auth login` / `glab auth login` zero-friction access. The
stored credential (step 4) is what the interactive flow writes. The device
flow / token paste (step 5) is the only interactive step.

`resolveSilently()` performs steps 1–4 and never prompts (use it for
non-interactive contexts and `--output json` goals). It returns a
`ResolvedToken(value, source)` so callers can branch on provenance — see
"Handling 401". The interactive step 5 is the CLI command's job: it builds
a `DeviceFlow` (or reads a pasted PAT), then calls `ForgeAuth.store(...)`.

### Env-var shape

Per-provider: `JK_GITHUB_TOKEN`, `JK_GITLAB_TOKEN`, `JK_GITEA_TOKEN`,
`JK_BITBUCKET_TOKEN` (`ForgeKind.jkEnvVar()` = `JK_<NAME>_TOKEN`). An env
var applies to its provider regardless of host — the common CI case targets
one instance. Multiple hosts of the same provider are handled by the stored
per-host credentials, populated via explicit `--host` logins.

### Step 3 — piggybacking on a native CLI

Best-effort and non-fatal by contract (`CliTokenProbe`). The provider's
argv (`gh auth token`) is run via `ProcessBuilder`. **Every** failure mode
falls through and never throws: binary not on `PATH` (`IOException`), non-zero
exit (not logged in), empty stdout, or a hang (5-second `waitFor` timeout
force-destroys the process). Providers without a native CLI
(`ForgeKind.nativeCliToken()` empty — Gitea, Bitbucket) skip this step
entirely.

## OAuth device flow (step 5)

`DeviceFlow` implements the OAuth 2.0 Device Authorization Grant (RFC 8628)
— the same flow behind `gh auth login`'s "copy this code into the browser"
prompt. It is the provider-neutral *mechanism*: it takes the two resolved
endpoints (`DeviceFlow.forHost(http, kind, host, clientId, scope)` sources
them from `ForgeKind`) and drives the request → poll loop.

1. **Request a device code** — `POST` to the device-code endpoint with
   `client_id` + `scope`. Response: `device_code`, `user_code`,
   `verification_uri` (+ optional `verification_uri_complete`), `interval`,
   `expires_in`.
2. **Prompt the user** — `run(Consumer<DeviceCode>)` hands the code to a
   caller-supplied callback so the CLI can print `user_code` /
   `verification_uri` and best-effort open a browser. The code/URL must
   always be printed so headless/SSH sessions still work.
3. **Poll** — `POST` to the token endpoint every `interval` seconds with
   `grant_type=urn:ietf:params:oauth:grant-type:device_code`. Handled
   states: `authorization_pending` (keep polling), `slow_down` (bump the
   interval), `expired_token` / `access_denied` (abort), `access_token`
   present (success).

Both calls go through `Http.postForm` (below), so they inherit jk's retry /
offline / backoff policy. Sleep is injected (`DeviceFlow.Sleeper`) so tests
don't wait real seconds. JSON is parsed with tools.jackson 3
(`JsonMapper`, `JsonNode.path(...).asString()`).

The CLI's prompt callback owns presentation and the (optional) browser
open — kept out of the library so `DeviceFlow` stays headless-testable:

```java
private static void tryOpenBrowser(String uri) {
    String cmd = switch (OsFamily.current()) {   // reuse jk's OS detection
        case MAC -> "open"; case WINDOWS -> "explorer"; default -> "xdg-open";
    };
    try { new ProcessBuilder(cmd, uri).inheritIO().start(); }
    catch (IOException ignored) { /* user copies the URL manually */ }
}
```

### Scopes

Request the minimum needed (the library defaults to a `read:packages`-style
scope passed by the caller); widen only as features demand it. Classic OAuth
App tokens (GitHub) don't expire and have no refresh token — simplest to
manage. GitHub Apps / GitLab give expiring tokens + refresh tokens — more
secure, more code — a possible later upgrade behind the same `TokenStore`.

### OAuth client IDs (configurable)

The device flow needs a *public* OAuth-app `client_id` (no secret). Because a
self-hosted GitHub Enterprise / GitLab / Gitea instance has its **own**
registered app, the client_id can't be a single baked-in constant — it's
resolved per provider and per host:

1. `JK_<PROVIDER>_OAUTH_CLIENT_ID` env var (e.g. `JK_GITHUB_OAUTH_CLIENT_ID`)
   — highest, for CI / one-off overrides;
2. config files — a per-host override beats the provider default;
3. jk's built-in default — **only for the provider's default host**. jk ships
   a registered github.com app (`Ov23liOYrWd84ZK2Eg2n`, under the
   `github.com/jkbuild` org), so `jk auth login github` works out of the box.
   The built-in is deliberately *not* used for non-default hosts: github.com's
   app id is meaningless to a self-hosted GHE instance, which must configure
   its own.

`ForgeKind.defaultOAuthClientId()` holds the built-in per-provider default
(only GitHub has one today; GitLab/Gitea/Bitbucket are empty until apps are
registered).

Config lives under a `[forge]` table in the standard jk config files
(`/etc/jk/jk.toml` < `~/.config/jk/jk.toml` < project `jk.toml`, or an
explicit `--config-file`), parsed by `ForgeAuthConfig` in `:core`:

```toml
# provider default — used for that provider's default host
[forge.github]
client-id = "Iv1.0123456789abcdef"

[forge.gitea]
client-id = "0e9b…"

# per-host override — for a self-hosted instance with its own app.
# Wins over the provider default for that host.
[[forge.host]]
name = "ghe.corp.example"
client-id = "Iv1.fedcba9876543210"
```

`AuthLoginCommand.oauthClientId(kind, host, env, config)` is the (pure,
network-free, unit-tested) resolver: env first, then
`ForgeAuthConfig.oauthClientId(providerId, host)` (host map → provider map).
When nothing supplies a client id, interactive login fails with a message
pointing at the env var, the `[forge.<provider>]` table, or `--with-token`.

## Token storage

`TokenStore` persists tokens **keyed by host** under `~/.jk/credentials/`,
one file per host (`JkDirs.home()`, honouring `JK_HOME`). This generalizes
the original single-file `~/.jk/github-token` design: a developer can be
logged into `github.com`, a private GHE, and `codeberg.org` at once.

Guarantees:

- Directory `rwx------`, each token file `rw-------` on POSIX; a best-effort
  no-op on Windows (the profile dir is already per-user).
- Host inputs are normalized (a URL like `https://github.com/o/r` is stored
  under the bare host `github.com`) and sanitized to a safe filename.
- The token is never logged and never echoed by `jk auth status`.
- A per-host file layout matches `gh`'s `hosts.yml` model. OS keychain
  integration (macOS Keychain / Windows Credential Manager / Linux Secret
  Service) is a worthwhile later upgrade behind this same interface.

## Using a resolved token

```java
ForgeAuth auth = new ForgeAuth();
String token = auth.resolveSilently(ForgeKind.GITHUB, host)
        .map(ResolvedToken::value)
        .orElseThrow(/* trigger jk auth login */);

HttpResponse<byte[]> resp = http.get(ForgeKind.GITHUB.apiBase(host).resolve("/user"),
        Map.of("Authorization", "Bearer " + token,
               "Accept", "application/vnd.github+json"));
```

### Handling `401`

A `401` means the token was revoked or expired. Only clear the store when
the token actually *came from the store* (`ResolvedToken.source() ==
STORE`) — clearing `~/.jk/credentials/<host>` does nothing useful if the
rejected token came from `JK_<KIND>_TOKEN`, a native env var, or a native
CLI; surface a "your env/CLI token was rejected" message in those cases
instead. The `TokenSource` enum exists precisely to make this branch clean.

## Implemented vs. remaining

**Implemented in `:io` (`dev.jkbuild.forge`), with tests:**

- `ForgeKind` — provider model: hosts, capabilities, endpoints, env-var and
  native-CLI metadata, id/host lookup.
- `DeviceFlow` — RFC 8628 device grant over `Http.postForm`, endpoint-agnostic
  with a `forHost` factory and injectable `Sleeper`.
- `TokenStore` — per-host credential files under `~/.jk/credentials/`,
  `0600`/`0700`.
- `CliTokenProbe` — best-effort native-CLI token piggyback.
- `ForgeAuth` — the 4-step silent resolution chain + `store`/`logout`,
  with injectable env and CLI probe.
- `Http.postForm(URI, Map)` — form-POST with the shared retry/offline policy,
  returning sub-500 responses so the poll loop can read 4xx states.
- `ForgeAuthConfig` (`:core`) — parses `[forge]` client-id config across the
  standard layers; resolved env-first by `AuthLoginCommand.oauthClientId`.
- `jk auth` commands (`:cli`) — `login` (device flow / `--with-token`),
  `logout`, `status`, `token`; positional provider + `--host`.

**Remaining (operational, not code):**

1. **GitHub: done** — app registered under `github.com/jkbuild`, client id
   `Ov23liOYrWd84ZK2Eg2n` baked in as the built-in default. (Ensure "Enable
   Device Flow" stays ticked in the app settings.) **GitLab/Gitea/Bitbucket:**
   register apps to obtain public `client_id`s and add them to
   `ForgeKind.defaultOAuthClientId()`; until then those providers rely on
   `JK_<PROVIDER>_OAUTH_CLIENT_ID` or `[forge.*]` config. No client secret is
   needed for the device flow.
2. **Verify GitLab/Gitea/Forgejo endpoint paths, scopes, and version
   requirements** (see the warning above) before enabling those providers.

## References

- RFC 8628 — *OAuth 2.0 Device Authorization Grant* (canonical state machine).
- GitHub Docs — *Authorizing OAuth apps* → "Device flow".
- GitLab Docs — *OAuth 2.0 identity provider* → device authorization grant.
- Gitea/Forgejo Docs — *OAuth2 provider* → device flow.
