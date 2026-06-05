# Git-Source Dependencies (build-from-source, JitPack-style)

**Status:** Implemented (v1). Resolve-only — `jk` builds a dependency
from a git repo on demand and resolves the result; it does not publish
git-sourced libraries anywhere.

> **Implemented:** `jk.toml`-based git deps now parse (discovery + override),
> materialize into a per-commit `file://` repo, rewrite to an exact coordinate
> pin, resolve through the normal solver, and stamp git provenance into
> `jk.lock`. `jk lock` detects force-moved tags; `jk update` accepts them.
> The "Later" list below (transitive git deps, non-`jk` source builds, build
> sandboxing, signed-tag-by-default) remains future work.

**Scope:** A dependency may point at a git repository instead of a Maven
coordinate. `jk` clones it (reusing forge auth; `GitFetcher` keeps a full bare
clone so tag history is available for versioning), builds it from its
`jk.toml`, locally publishes the artifact, and feeds it to the resolver — the
JitPack model, but first-class and using `jk`'s own build. **`jk.toml`-based
builds only** for now (no Maven/Gradle/sbt source builds).

## What already exists (and the gaps)

The declaration grammar and most plumbing are already in the tree:
- **Declaration:** `[dependencies.<scope>]` entries already accept
  `{ git = "...", tag|branch|rev = "...", path = "...", submodules, verify-signed }`
  → `GitSource` + `GitRefSpec` (`Tag`/`Branch`/`Rev`), parsed by
  `JkBuildParser.parseGitSource`. Git deps carry a synthetic `version = "=git"`.
- **Clone:** `GitFetcher` does bare clone + per-ref checkout under
  `$JK_CACHE_DIR/git/{db,co}/…`, resolves a ref → SHA, and `verifyLocked`
  detects a moved tag.
- **Build/publish primitives:** the `jk build` pipeline (`JavacDriver`/
  `JarPackager`/`ShadowPackager`), `PublishablePom`, `MavenPublisher`, the CAS
  + `Journal`, and the `file://` `FileTransport` from the repo-transport work.
- **Auth:** `ForgeKind.inferFromHost` + `ForgeAuth.resolveSilently`.

**Three gaps closed in v1:**
1. ~~Resolution ignores git deps~~ → `GitSourceResolution` materializes each
   git dep into a `file://` repo and rewrites it to an exact coordinate pin
   before the PubGrub solve (wired into lock / build-first-run / update).
2. ~~`GitFetcher` has no credential auth~~ → `ForgeGitCredentials` feeds jgit a
   `CredentialsProvider` from `ForgeAuth` for private clones.
3. ~~The lockfile `Package` has no git fields~~ → `Package.GitInfo` records the
   canonical URL, resolved SHA, and ref token; stamped at resolve time.

## Version derivation (the core decision)

A consumer asks for a *version*; a git ref isn't one. We derive a Maven/SemVer
version from the ref:

- **Tag → coerced SemVer (immutable).** Strip a leading `v`/`V` and common
  prefixes (`release-`, `<artifact>-`), pad to three components (`1.2` →
  `1.2.0`), preserve pre-release/build metadata (`1.2.3-rc1`, `1.2.3+build`).
  If it can't be coerced to SemVer, fall back to the **raw tag string** as the
  version (Maven version strings are permissive) and warn.
- **Explicit commit (`rev`) → pseudo-version anchored to the nearest tag
  (immutable):** `<nearest-tag>-<yyyyMMdd.HHmmss>-<shortsha>`. Using the cloned
  history + tags, find the nearest tag reachable from the commit (`git describe`
  semantics) and attach the commit's UTC timestamp + 12-char short SHA:
  - nearest **release** `v1.2.3` → `1.2.3-20260601.134752-3f2a9c1b4d5e`
  - nearest **pre-release** `v1.2.4-rc1` → `1.2.4-rc1-20260601.134752-3f2a9c1b4d5e`
  - **no tag** → `0.0.0-20260601.134752-3f2a9c1b4d5e`

  **Why anchor to the prior tag (not bump the patch):** validated against jk's
  comparator (`resolver/Versions.java` → Maven `ComparableVersion`), a numeric
  *timestamp* qualifier sorts **above** the same-core release but **below** a
  higher core. So `1.2.3-<ts>-<sha>` lands between `1.2.3` and `1.2.4`, sorts
  chronologically, and the SHA tie-breaks — all under the real comparator. (My
  first instinct, the Go-style `1.2.4-0.<ts>` "bump," sorted *above* `1.2.4`
  under ComparableVersion — see the load-bearing `GitVersionOrderingTest`.)
  Free here because `GitFetcher` keeps a full bare clone, so tag-distance is
  available without a deeper fetch.
- **Branch → `<branch>-SNAPSHOT` (mutable):** e.g. `main-SNAPSHOT`. The
  `-SNAPSHOT` suffix tells the resolver/cache it's mutable; the **exact commit
  SHA is pinned in `jk.lock`**, so a locked build is fully reproducible and
  `jk update` re-resolves the branch tip and rebuilds only if the SHA changed.

### Recommendation for "no tag"
**Do not use a bare git hash as the version** — a hash is opaque and
**unorderable**, which breaks version comparison, conflict resolution, and any
"is this newer?" logic the resolver relies on. Use the **tag-anchored
timestamp pseudo-version** above for a pinned `rev` (immutable, sortable,
carries the SHA), and **`<branch>-SNAPSHOT`** with a lockfile-pinned SHA for
branch tracking.

> **Implemented & validated:** `GitVersion` (core) derives these and
> `GitVersionOrderingTest` (resolver) asserts the orderings against
> `Versions.compare` — the format above is the one that actually sorts
> correctly under Maven's comparator (the earlier SemVer-style guess did not).

## Coordinate discovery

Unlike JitPack (which remaps the group to `com.github.<user>`), `jk` uses the
dependency's **real coordinate read from the cloned repo's `[project]`**
(`group:artifact`). The consumer writes only a name + git ref:

```toml
[dependencies.main]
# coordinate (group:artifact) is discovered from the repo's jk.toml [project]
mylib    = { git = "gh:acme/widgets", tag = "v1.4.0" }
edge     = { git = "https://gitlab.com/acme/edge", branch = "main" }
pinned   = { git = "gh:acme/widgets", rev = "3f2a9c1…" }
submod   = { git = "gh:acme/monorepo", tag = "v2.0.0", path = "libs/core" }
```

Using the real coordinate means a transitive Maven dependency elsewhere on the
same `group:artifact` lines up with the git-built one (one node in the graph),
and conflict resolution works normally. If two git sources resolve to the same
coordinate, that's a conflict surfaced like any other.

### Discovery with override
Discovery is the default; any of the normal inline-coord keys on the same git
table **override** it (and are validated against the build, where feasible):

```toml
fork = { git = "gh:me/widgets-fork", branch = "main",
         group = "com.acme", name = "widgets",   # override discovered coordinate
         version = "1.4.0-acme" }                     # override derived version
```

- **`group` / `name`** override the coordinate discovered from `[project]`.
- **`version` is honored** as an explicit override of the *derived* version.
  The ref (`tag`/`branch`/`rev`) still selects **which commit to build**;
  `version` only relabels the published/resolved artifact — handy for forks, or
  when a tag won't coerce to a clean version. **Precedence:** explicit
  `version` field → derived-from-ref. Likewise explicit `group`/`name` →
  discovered coordinate.

This requires populating `Dependency`'s `module`/version from these keys for
git deps (today git deps carry a synthetic `version = "=git"`); the
materialization step uses the override when present, else discovers/derives.

## Resolution pipeline — "materialization" before the solve

The clean integration is a **pre-resolution phase** that turns each git dep
into (a) a concrete coordinate pin and (b) a local `file://` repo, so the
PubGrub solver never needs to know about git:

1. **Resolve ref → SHA.** `git ls-remote` (or `GitFetcher`) with auth. `rev` is
   already a SHA. Detect moved tags via `verifyLocked` against the lockfile.
2. **Derive the version** (rules above) from the ref + commit metadata.
3. **Cache check.** Key = `canonicalUrl + sha + path`. Immutable
   (tag/rev) hits are reused forever; `branch-SNAPSHOT` re-checks the tip SHA.
4. **Clone + build (on miss).** Check out at the SHA (`GitFetcher` keeps a full
   bare clone, so the tag history the pseudo-version needs is already present),
   locate `jk.toml` (root or `path` subdir), run the `jk build` pipeline to
   produce the jar, and render its POM with `PublishablePom` — stamped with the
   **derived (or overridden) version** and the project's own resolved
   dependencies.
5. **Local publish.** Write the jar + POM into a per-build Maven-layout
   directory, e.g. `$JK_CACHE_DIR/git-artifacts/<urlhash>/<sha>/m2/`, and record
   the blobs in the CAS + `Journal` (so offline resolve works).
6. **Expose + solve.** Add that directory as a `file://` repository to the
   resolve `RepoGroup` (reusing `FileTransport`), and substitute the git dep
   with an **exact coordinate pin** `group:artifact = <derivedVersion>`. The
   normal resolver then resolves it — plus its transitive Maven deps from the
   usual repos — with no PubGrub changes.

This reuses every existing piece: clone (`GitFetcher`), build (`jk build`),
POM (`PublishablePom`), local repo (`FileTransport`), storage (CAS/`Journal`),
and the solver.

## Auth (reuse forge logic)

Add a jgit `CredentialsProvider` to `GitFetcher`, sourced from forge auth:
`ForgeKind.inferFromHost(uri.host)` → `ForgeAuth.resolveSilently(kind, host)` →
`UsernamePasswordCredentialsProvider(token, "")` (or the provider-appropriate
shape) for HTTPS clones of private repos. Public repos clone anonymously; SSH
URLs keep using the user's SSH agent/keys as today. This is the same chain
`jk auth login` populates, so a developer who logged in once can pull private
git deps with no extra config.

## Lockfile & reproducibility

Extend `Lockfile.Package` (bump the lockfile `version`) for git-sourced
packages with: `git` (canonical URL), `rev` (resolved commit SHA), `ref`
(original tag/branch/rev token), and the existing `checksum` (sha256 of the
built jar). A locked build is then reproducible two ways: re-clone at the
pinned SHA and rebuild deterministically (the build pipeline already stamps
reproducible jars), or restore the cached/CAS artifact by checksum.

`verifyLocked` already detects a tag/branch that was force-moved since lock —
surface that as a lock-mismatch error (supply-chain safety).

## Security notes

Building an arbitrary repo runs its build. `jk.toml` is **declarative** (no
arbitrary build scripts like Gradle/Maven), which materially limits the blast
radius, but compilation, annotation processors, and codegen still execute.
Mitigations: pin the SHA in the lockfile (no silent drift), honor
`verify-signed` for signed-tag verification, and treat build sandboxing as a
later hardening. Document clearly that a git dependency executes that repo's
build at resolve time.

## Phasing

**Phase 1 (this design):**
- Auth in `GitFetcher` (forge-token `CredentialsProvider`).
- Version derivation (`GitVersion`: tag-coercion + pseudo-version + branch-SNAPSHOT).
- Materialization step: clone → build (single-module `jk.toml`) → local
  `file://` publish → coordinate substitution → solve.
- Lockfile git fields + `verifyLocked` wiring.
- Cache keyed by url+sha+path.

**Later:**
- Transitive **git** deps (a git source whose own `jk.toml` has git deps) —
  recurse the materialization with cycle detection.
- Richer multi-module selection beyond a single `path`.
- Non-`jk` source builds (delegate to Maven/Gradle, or via the importer).
- Build sandboxing; signed-tag enforcement by default.

## Decisions (resolved)
- **`rev` pseudo-version:** tag-anchored timestamp form (`v1.2.3` →
  `1.2.3-<yyyyMMdd.HHmmss>-<shortsha>`), validated to sort between the tag and
  the next release under `resolver/Versions.java`. Implemented in `GitVersion`
  (the earlier Go-style `1.2.4-0.<ts>` guess sorted wrong under Maven's
  comparator).
- **Coordinate / version:** discover from `[project]` by default; `group` /
  `name` / `version` keys on the git table override (the ref still selects
  the commit; `version` only relabels).
- **Build sandboxing:** deferred for v1 — rely on SHA-pinning + `verify-signed`;
  document that a git dep runs that repo's build at resolve time.
- **`-SNAPSHOT` re-resolve cadence:** only on `jk update` / `jk lock`. In
  between, the branch is pinned to the locked SHA and not re-fetched, so plain
  `jk build` is reproducible and offline-friendly; `jk update` re-resolves the
  branch tip and rebuilds only if the SHA changed.
