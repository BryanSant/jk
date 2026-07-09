# `jk tool` universal targets — plan

**Status:** proposed (2026-07-09). Extends the shipped `jk tool` family
(PRD §20) and script support (PRD §19) to full npx / `dotnet tool` /
JBang parity.

**Goal:** `jk tool run <target>` (alias: `jk tool exec`, shell function
`jkx`) runs *anything runnable*; `jk tool install <target>` installs it
and all of its dependencies as a launcher on `$JK_BIN_DIR`. `jkx` becomes
a drop-in replacement for `jbang` for the target forms JBang users
actually use.

---

## 1. Where we are today

Already shipped (this plan extends, it does not green-field):

| Piece | State | Where |
|---|---|---|
| `jk tool run <gav\|file>` | GAV coords (pinned) + `.java/.kt/.kts/.jar` files | `cli/.../command/ToolRunCommand.java`, `ScriptRunner.java` |
| `jk tool install <gav>` | GAV only → env + launcher | `ToolInstallCommand.java`, `kernel/toolchain-jdk/.../tool/ToolLauncher.java` |
| `jkx` | shell function = `jk tool run`, installed by `jk activate` | `cli/src/main/resources/activate/*` |
| Tool envs | `$JK_STATE_DIR/tools/envs/<bin>/env.json`, launcher with baked absolute CAS classpath, LRU-evicted | `ToolLauncher.install` |
| Script headers | `//jk dep`, `//DEPS`, `//JAVA`, `//JAVAC_OPTIONS`, `//JAVA_OPTIONS`, `//SOURCES`, `//KOTLIN` | `kernel/toolchain-jdk/.../script/ScriptHeaderParser.java` |
| Engine hosting | `tool-resolve` + `script-prepare` goals; exec always client-side with inherited stdio | `EngineClient`, `EngineProtocol`, `kernel/engine/.../runtime/{ToolGoals,ScriptGoals}.java` |
| Library catalog | layered short-name → `group:artifact` (bundled / global / local / project) | `kernel/core/.../library/LibraryCatalog.java` |
| Git plumbing | bare-clone cache, `gh:`/`gl:`/`bb:`/`sr:` shorthands, `@ref`/`#ref`/`!subdir`, forge auth, version derivation | `GitFetcher`, `GitUrl`, `InstallCommand.installFromGit`, `docs/git-source-deps.md` |
| App install from dir/git | `jk install [source]` handles current-project / local path / git URL / coord | `InstallCommand.java` |

Gaps this plan closes:

1. No `jk tool exec` alias.
2. `tool run|install` reject: catalog short-names, version-less GAVs,
   directories, web URLs, git URLs, JBang catalogs/aliases.
3. `jk tool install` can't install file/script/dir/URL/git targets.
4. `.kts` runs get **no dependency resolution at all** (delegated to
   `kotlinc -script` with no `-classpath`); `@file:DependsOn` unsupported.
5. JBang directive coverage is partial (`//REPOS`, `//FILES`, `//MAIN`,
   `//RUNTIME_OPTIONS`, `//COMPILE_OPTIONS`, `//PREVIEW`, `//JAVA n+`, …
   missing).
6. No trust model for running remote code.

---

## 2. Target grammar — one classifier, both verbs

A single `ToolTarget.classify(raw, cwd)` (new, `kernel/toolchain`)
produces a sealed type consumed identically by `run` and `install`.
Sniffing order (first match wins; order is the spec):

```
1. PathTarget      raw resolves to an existing file or directory
2. GitTarget       git+https://…, git://…, ssh://…, git@host:…, *.git,
                   gh:/gl:/bb:/sr: shorthand, or a forge repo-root URL
                   (https://github.com/user/repo with no blob path)
3. UrlTarget       http(s)://… (anything not classified git above)
4. GavTarget       contains ':' and parses as group:artifact[:version|@selector]
5. JBangAliasTarget  contains '@' after a bare word: alias@user[/repo][/branch][~path]
                   or alias@host/path  (JBang catalog syntax)
6. CatalogTarget   bare word [@version-selector] — jk library-catalog lookup
```

Rules 5 and 6 share the `name@suffix` surface; §6.1 defines the
deterministic disambiguation (no version-shape guessing).

Notes:

- Rule 1 beats everything: an existing local `./gh:weird-dir` is a path.
  Non-existent paths with a runnable extension still classify as
  `PathTarget` so the user gets a proper "not found" (matches today's
  `ScriptRunner.isRunnableFile` behavior).
- Rules 2/3 reuse `InstallCommand.looksLikeGitUrl` / `GitUrl.expand` /
  `splitUrlRef` — move those helpers from the command into
  `kernel/toolchain` so both commands share them.
- `git+https://` is accepted and stripped to `https://` for transport
  (npm-style); it exists purely to force git classification.
- Ref selection on git targets: embedded `@tag`/`#rev` and `!subdir`
  (already the `jk install` grammar), plus explicit `--tag/--branch/--rev`
  flags (PRD §20.1 already sketches `--git … --tag v1.2.0`).
- `--` still separates target args from tool args; everything after it is
  forwarded verbatim.

## 3. One pipeline, two tails

Every target funnels through the same engine-hosted preparation into a
`PreparedTool`:

```
classify → (trust gate) → fetch → identify → resolve deps → compile/build
        → PreparedTool { javaHome, classpath, mainClass | kotlincScript, jvmArgs, provenance }
```

- **`jk tool run`** = prepare + `ToolLauncher.execEphemeral` (client-side,
  inherited stdio — unchanged philosophy: the engine never owns the TTY).
- **`jk tool install`** = prepare + snapshot into
  `$JK_STATE_DIR/tools/envs/<bin>/` + `ToolLauncher.install` launcher into
  `$JK_BIN_DIR`. "Install the target and all of its deps" falls out of the
  snapshot: the env's classpath is the full resolved transitive closure
  (already true for GAV installs; new target kinds inherit it).

Engine protocol: generalize `tool-resolve-request` into a
`tool-target-request` carrying `{ rawTarget, kind, refSpec, binName,
mainOverride, extraDeps, refresh }`. Client and engine are version-locked
(the client self-fetches its matched engine jar), so the old shape can be
evolved in place rather than kept parallel. Fetch/clone/resolve/compile
live engine-side (same place Maven fetch already lives); the **trust
prompt happens client-side before the request is sent** (the engine has
no TTY).

`env.json` grows a `provenance` block, enabling honest `jk tool list`
output and future `jk tool upgrade`:

```json
{ "provenance": {
    "kind": "git",                    // gav | catalog | file | dir | url | git | jbang-alias
    "spec": "gh:acme/widgets@v1.4.0",
    "resolved": { "url": "https://github.com/acme/widgets", "rev": "3f2a9c1…" },
    "sha256": "…" } }
```

## 4. Per-target semantics

### 4.1 Catalog short-name (`jkx ktlint`, `jkx ktlint@1.3.0`)

Look the bare word up in the layered `LibraryCatalog` (project → local →
global → bundled) to get `group:artifact`; the catalog stays
version-free, so the version comes from the target: `name@<selector>` or
default **`latest`** (stable releases only — no SNAPSHOT/rc), resolved
from repo metadata and cached with a TTL (reuse the dep-shorthand
`latest` keyword semantics from `JkBuildParser.isVersionSpecOrKeyword`).
`--refresh` bypasses the TTL. `jk tool install ktlint` records the
concrete resolved version in provenance. Unknown name → the catalog's
existing "did you mean" suggestions.

### 4.2 GAV coords

Today: pinned `g:a:v` only. Add `g:a` (→ `latest`, as above) and
`g:a@selector` (floating, same selector grammar as script `//jk dep`).
`--with g:a:v` (PRD §20.1) injects extra deps into the env — needed for
tools like error-prone or CLIs with driver plugins.

### 4.3 Local files (exists; gains install)

`.java`/`.kt`/`.kts`/`.jar` run as today. New: `jk tool install
hello.java --bin hello` — engine compiles once, jars the classes into the
env dir (immutable snapshot; the launcher must not depend on the source
file continuing to exist), resolves header deps, writes the launcher.
`.kts` install writes a launcher that invokes the provisioned `kotlinc
-script` with the snapshot's `-classpath` (see §5).

### 4.4 Local directories (new)

Checked in order:

1. **`jk.toml` present** → it's a jk project. `run` = single-build goal
   (tests skipped, same as `jk run`) in that dir, then exec its
   `[application]` main — i.e. `jk run` without `cd`. `install` =
   delegate to the existing `InstallCommand` app pipeline (native binary
   / shadow jar / jar+deps into `~/.jk/lib` — already built, just wire the
   delegation).
2. **`jbang-catalog.json` present** → a catalog dir; requires the JBang
   alias syntax (`alias@<dir>`), see §6.
3. **`main.java` present** → JBang folder convention: run `main.java` as
   the script; its `//SOURCES`/`//FILES` pull in siblings relative to the
   dir.
4. **Exactly one top-level `.java`/`.kt`/`.kts`** → run it (covers
   single-script gist checkouts).
5. Otherwise: error listing what was looked for.

### 4.5 Web URLs (new)

- **Rewrites first** (JBang-compatible): GitHub `blob` → `raw.githubusercontent.com`,
  GitLab `/-/blob/` → `/-/raw/`, Bitbucket equivalent, gist URLs via the
  gist API (multi-file gists materialize as a directory → §4.4 rules).
  Redirects followed (shorteners work).
- A forge **repo-root** URL classifies as `GitTarget` (§2 rule 2), not a
  download.
- **Trust gate** (§7), then download into
  `$JK_CACHE_DIR/tool-src/<sha256(url)>/<filename>` with ETag sidecar for
  conditional refetch (`--refresh` forces).
- Classify the payload by extension (URL path or `Content-Disposition`),
  falling back to content sniffing (`PK` magic → jar; `//DEPS`/`@file:`
  header → script). Then reuse the file/dir semantics verbatim.
- `//SOURCES` / `//FILES` in a URL script resolve **relative to the raw
  base URL** and are fetched into the same cache dir — JBang does this
  and real catalog scripts rely on it; drop-in dies without it.
- Zip/tarball-of-a-project URLs: deferred (open question §11).

### 4.6 Git URLs (new for `tool`; plumbing exists)

`git+https://…`, `https://…/repo(.git)`, `git@host:…`, `gh:user/repo` —
all normalize through `GitUrl`. Pipeline: trust gate on the canonical
`https` host+path prefix → `GitFetcher` bare clone (cached under
`$JK_CACHE_DIR/git/`, forge-token auth via `ForgeAuth` for private
repos) → checkout the requested ref (default: default-branch HEAD;
`@ref`/`#rev`/`--tag/--branch/--rev`) → apply the **directory semantics
of §4.4** to the checkout (honoring `!subdir`). So:

- repo with `jk.toml` → build & run / install (this is the requirement's
  "must be a jk.toml project we can compile/launch");
- repo with `jbang-catalog.json` → needs `alias@` syntax (§6);
- repo with `main.java` / single script → JBang-compatible run.

Install provenance records canonical URL + resolved SHA; a locked install
never silently drifts (same posture as `docs/git-source-deps.md`).

## 5. Dependency declarations in scripts

### 5.1 `.kts`-style annotations (new parser + the .kts classpath fix)

Support the kotlin-main-kts idiom in `.kts` (and accept it in `.kt`):

```kotlin
@file:DependsOn("com.squareup.okhttp3:okhttp:4.12.0")
@file:Repository("https://repo.maven.apache.org/maven2")
```

New `KtsAnnotationParser` alongside `ScriptHeaderParser` (same
`ScriptHeader` output; a script may mix annotation and `//` directive
styles — union them). jk resolves the deps itself (engine-side, CAS-first,
offline-capable — we do **not** hand resolution to kotlin-main-kts's
embedded Ivy/Maven resolver) and passes `-classpath` to `kotlinc -script`.
This also fixes the standing gap that `.kts` currently runs with no deps
at all: `ScriptGoals`' `kts` mode gains the resolve-deps phase the other
modes already have, and `ScriptRunner.runKtsScript` appends the resolved
classpath.

### 5.2 JBang directive completeness (all file types)

`ScriptHeaderParser` is already shared across `.java`/`.kt`/`.kts`; extend
it (parser change lands everywhere at once):

| Directive | Handling |
|---|---|
| `//REPOS name=url,…` | → repos (named form; bare URL form too) |
| `//FILES target=source` | copy/materialize resources next to classes; URL-relative for remote scripts |
| `//MAIN fqcn` | overrides detected main class |
| `//RUNTIME_OPTIONS …` | alias of `//JAVA_OPTIONS` |
| `//COMPILE_OPTIONS …` | alias of `//JAVAC_OPTIONS` |
| `//JAVA 17+` | `+` = minimum acceptable, pick newest installed ≥ 17 |
| `//PREVIEW` | adds `--enable-preview` to both javac and java |
| `//GAV g:a:v` | informational; recorded in provenance |
| `//DESCRIPTION …` | shown by `jk tool list` |
| `//MODULE`, `//MANIFEST k=v`, `//JAVAAGENT` | parse + honor where cheap; else parse + warn "not yet honored" |
| `//NATIVE_OPTIONS`, `//CDS` | parse + ignore with a note (native tools are PRD §20.4, later) |

Unknown directives stay silently ignored (forward compat, as today).
Groovy `@Grab` support: deferred, listed as a documented drop-in caveat.

## 6. JBang catalogs and aliases

The `alias@ref` grammar (rule 5 in §2), matching JBang's documented
resolution:

- `hello@user` → `github.com/user/jbang-catalog` repo's
  `jbang-catalog.json` (GitHub, then GitLab, then Bitbucket).
- `hello@user/repo[/branch][~path]`, `hello@host/path/to/catalog`, and
  `alias@<local-dir>` all locate a `jbang-catalog.json`.
- From the catalog's `aliases` map, honor: `script-ref` (relative refs
  resolve against the catalog's location — raw URL base or dir),
  `dependencies` (extra deps, like `--with`), `java-options`,
  `arguments` (prepended defaults), `description`, `java` (version).
- The resolved `script-ref` then **recurses through the normal target
  classifier** — a catalog alias is just indirection.
- Trust: gated on the catalog's origin prefix (§7); scripts the catalog
  points at inherit an explicit-origin check only when they leave that
  prefix.

Bare names without `@` never consult JBang catalogs — they are jk-catalog
lookups (§4.1).

### 6.1 Disambiguating `name@version` vs `alias@catalog`

`hibernate@7.0.0.Final` (jk catalog name + version) and `hello@jbangdev`
(JBang alias + catalog) share a surface. **No version-shape sniffing** —
Maven versions are permissive strings (`release12` is a legal version)
and catalog identifiers can be version-shaped (`r1337`, `alpha1.2.3`), so
"looks like semver" cannot be the rule. Classification is deterministic,
in order:

1. **Hard syntax discriminators** (unambiguous by construction):
   - Suffix contains `/` or an *infix* `~` → **JBang catalog ref**
     (`hello@user/repo`, `hello@acme~experimental`). A Maven version can
     never contain `/`, and a selector's `~` is only ever leading.
   - Suffix starts with a selector operator (`=`, `^`, `~`, `>`, `<`) or
     is a version keyword (`latest`, `stable`, …) → **version selector**.
     Operators cannot appear in forge usernames or hostnames.
2. **Catalog existence decides bare-word suffixes** (`myapp@release12`,
   `hello@jbangdev`, `tree@jbang.dev`): if the name left of `@` resolves
   in the layered jk library catalog → **CatalogTarget**, suffix parsed as
   a version; otherwise → **JBang alias** resolution. This is not a
   heuristic — a bare name is only *meaningful* as a jk-catalog target
   when the catalog knows it, so the lookup *is* the semantics.
3. **Forced spellings both ways**, always available:
   - Force version: lead with an operator — `hibernate@=7.0.0.Final` —
     classified by rule 1 regardless of catalog state.
   - Force JBang: use the `/` form — `hello@jbangdev/jbang-catalog` —
     classified by rule 1 regardless of catalog state.
4. **Failed lookups report both interpretations and the forced
   spellings**: *"`myapp` is not in the library catalog, and no JBang
   catalog `release12` with alias `myapp` was found. Use
   `myapp@=<version>` for a version, or `myapp@user/repo` for a JBang
   catalog."*

Drift note: adding a name to the curated jk registry can flip an
unforced `foo@bar` from JBang-alias to Maven-artifact interpretation.
That is the *safe* direction — a curated registry entry wins over an
arbitrary forge username, the flip is visible under first-PR-wins
curation, and the `/` form keeps working forever. The reverse flip
(Maven artifact silently becoming arbitrary forge code) cannot happen.

## 7. Trust model (new, blocking for URL/git)

Running a URL or git repo executes arbitrary code; GAV coords from
configured repos and local paths do not prompt (same line JBang draws).

- Store: `$JK_STATE_DIR/trusted-sources.toml` — list of URL prefixes
  (JBang-style base-URL prefix matching: trusting
  `https://github.com/jbangdev/` covers everything under it).
- Interactive flow on first contact: `[o]nce / [a]lways / [n]o` (no
  10-second timeout — that JBang quirk is a misfeature).
- Non-interactive (no TTY / `--json`): hard error naming the fix:
  `jk trust add https://github.com/acme/`.
- New command family: `jk trust add|list|remove <prefix>`, plus
  `jk trust import --jbang` to seed from `~/.jbang/trusted-sources.json`
  (cheap migration win).
- The check runs **client-side before** the engine request (engine has no
  TTY); the request carries the fact that trust was granted.
- Install additionally records the artifact/script `sha256` in
  provenance; `jk tool list` can flag when a mutable source (branch,
  bare URL) has drifted since install.

## 8. CLI surface

```
jk tool run <target> [--main <fqcn>] [--java <n>] [--with g:a:v]…
            [--tag|--branch|--rev <ref>] [--refresh] [-- args…]
jk tool exec …           # hidden alias of run (dotnet muscle memory)
jkx <target> …           # real binary: argv[0]-dispatched hardlink to jk
jk tool install <target> [--bin <name>] [same flags as run]
jk trust add|list|remove|import
```

### 8.1 `jkx` becomes a real binary (decided 2026-07-09; **implemented** — `Argv0`/`Jk.rewriteForProgramName`, `JkxLink`, `install.sh`)

The shell function only exists in interactive shells that ran
`jk activate` — it is invisible to `execvp`, so `#!/usr/bin/env jkx`
shebangs, CI steps, `xargs`/`find -exec`, and anything that spawns
`jkx` as a process all fail. JBang's own docs lead with
`///usr/bin/env jbang` shebangs, so a function can't carry the drop-in
claim. Replacement:

- `install.sh` (and `jk activate`, idempotently) creates
  `$JK_BIN_DIR/jkx` as a **hardlink** to the `jk` native binary (same
  dir, same volume — safe on Linux/macOS/NTFS). Fallbacks in order:
  symlink, then an absolute-path exec shim (`#!/bin/sh\nexec <abs-jk>
  tool run "$@"`); Windows additionally gets `jkx.cmd`.
- The native image dispatches on argv[0] (the plumbing for a
  non-default main that sees argv[0] already exists): basename `jkx` →
  prepend `tool run` to argv before config/alias/dispatch, so global
  flags and `jkx --help` (renders `jk tool run` help) behave normally.
  A hardlink also means zero extra download/disk and automatic
  version-match with `jk`.
- The `jkx` shell functions in `activate/*` are removed in the same
  change (the function would otherwise shadow the binary); `jk
  activate` keeps ensuring `$JK_BIN_DIR` is on PATH, which is all the
  binary needs.

- `exec` is a per-command alias (`CliCommand.aliases()` on
  `ToolRunCommand`), hidden from `--help` per the hidden-surface policy;
  documented in `docs/aliases.md` with a `JkAliasTest` case.
- `jk tool list` gains a provenance column
  (`ktlint  1.3.1  gav com.pinterest.ktlint:…`, `bar  git github.com/foo/bar@3f2a9c1`).
- Help text for `run`/`install` documents the target grammar in one
  shared paragraph (single source of truth, rendered into both).

## 9. Code changes (by module)

**`kernel/toolchain`** (new): `ToolTarget` sealed classifier + tests;
`GitUrl`/`looksLikeGitUrl`/`splitUrlRef` move here from
`InstallCommand`; `UrlRewriter` (blob→raw, gist); latest-version
resolution helper (metadata + TTL cache).

**`kernel/toolchain-jdk`**: `ScriptHeaderParser` new directives;
`KtsAnnotationParser`; `ToolLauncher` kts-launcher variant + provenance in
`env.json`; trust store (`TrustedSources`).

**`kernel/engine` / `kernel/engine-api`**: `tool-target-request` protocol
evolution; `ToolGoals` grows fetch-url / clone / dir-build phases (reusing
`GitFetcher`, `MavenRepo`, `ScriptGoals`, the single-build goal);
`ScriptGoals` kts resolve-deps phase + `//FILES` materialization +
URL-relative `//SOURCES`.

**`cli`**: `ToolRunCommand`/`ToolInstallCommand` re-target onto the
classifier (their bodies shrink — mode dispatch moves down);
`ToolExecCommand` not needed (alias, not a class); `TrustCommand` family;
`ToolListCommand` provenance column; argv[0] `jkx` dispatch in `Jk.main`
+ `install.sh`/activate link creation.

**`jk install` convergence (decided 2026-07-09: converge now).**
Top-level `InstallCommand` already handles current-project / local path /
git URL / coord — the same sources the universal pipeline covers. Both
verbs become one implementation: `jk tool install <target>` is the
canonical verb; `jk install [target]` becomes a `VERB_ALIASES` expansion
to `tool install` whose omitted target defaults to `.` (current project).
`InstallCommand.makeInstallApp`'s native-binary / shadow-jar / jar+deps
tail becomes the **dir-target install tail** of the shared pipeline (a
jk-project target installs as an app; a coord/script target installs as a
tool env — same pipeline, different snapshot shape), and the standalone
`InstallCommand` is deleted once its modes are absorbed.

**Docs**: `README` tool section refresh (evergreen up top; target grammar
under how-details), `docs/aliases.md` row, `docs/requirements.md` §19/§20
updates, this plan.

## 10. Phasing (each phase ships alone)

1. **Ergonomics core** — classifier; `exec` alias; `jkx` real binary
   (argv[0] hardlink, §8.1); version-less GAV + `@selector` + `latest`;
   jk-catalog short-names; `--with`.
   *(No new I/O kinds; low risk, immediate daily-driver value.)*
   **Implemented 2026-07-09**: `ToolTarget` (toolchain-jdk) + `ToolCoordSpec`
   (model) + `ToolResolver.pickVersion` (metadata pick, stable-only `latest`);
   the `tool-resolve` wire request carries the spec grammar + `with` array and
   returns the pinned `toolCoord`; phase-gated kinds (dir/url/git/jbang) error
   with a pointer here.
2. **Local completeness** — directory targets (jk-project delegate +
   JBang folder rules); `install` parity for file targets; `jk install`
   → `tool install` convergence (§9); `.kts` dependency fix +
   `@file:DependsOn`; JBang directive completeness.
   *(After this, every JBang **local** workflow works.)*
   **Implemented 2026-07-09**: directives + `.kts` deps (annotations
   neutralized line-preservingly for plain kotlinc); dir targets for
   run; file installs snapshot envs (`.kts` launcher wraps
   `kotlinc -script`); `install` is now a verb alias of `tool install`
   (default target `.`; `--group/--name/--ver` on a file target keeps
   the m2 local-cache mode; `InstallCommand` survives only as the
   delegated app-install pipeline).
3. **Remote** — trust store + `jk trust`; URL targets with rewrites,
   gists, URL-relative `//SOURCES`/`//FILES`, ETag cache.
   **Implemented 2026-07-09**: `TrustedSources`
   ($JK_STATE_DIR/trusted-sources.toml, prefix-matched, checked
   client-side against the URL the user typed), `jk trust
   add|list|remove|import --jbang`, TTY once-prompt with the trust-add
   hint / hard error non-interactively; `UrlRewriter` (GitHub blob,
   GitLab, Bitbucket, single-file gists), download to
   $JK_CACHE_DIR/tool-src/<sha256(url)>/ with payload sniffing for
   extension-less URLs, `//SOURCES`/`//FILES` siblings mirrored from the
   raw base, then the local file pipeline (run + install). Deviations:
   cache is reuse-unless---force instead of ETag conditional GET;
   multi-file gists (API) deferred.
4. **Git + catalogs** — git targets for `tool run|install`;
   `jbang-catalog.json` + `alias@…` (disambiguation per §6.1); `jk trust
   import --jbang`; publish a "jbang → jkx" migration note with the
   honest caveat list (`.jsh`, Groovy, `@Grab`, templates/init are out of
   scope — see Decisions).

Compat proof for phase 4: a vendored corpus test that runs a handful of
real published JBang catalog aliases and scripts (fixtures served from a
local HTTP server + local git repos — no live network in CI).

## 11. Decisions (2026-07-09)

1. **`name@suffix` disambiguation** — no version-shape sniffing; the
   deterministic rules of §6.1 (syntax discriminators → catalog
   existence → forced spellings, dual-interpretation errors).
2. **`jk install` and `jk tool install` converge now** — one pipeline;
   `install` becomes a verb alias for `tool install` defaulting the
   target to `.`; `InstallCommand` absorbed (§9), landing in phase 2.
   **Done** — shipped with phase 2.
3. **`jkx` ships as a real binary** — argv[0]-dispatched hardlink with
   shim fallbacks (§8.1), phase 1; the activate-installed shell
   functions are removed.
4. **`.jsh`, Groovy, `@Grab` deferred** — revisit when jk adds Groovy
   support; until then they are documented drop-in caveats in the
   "jbang → jkx" migration note.

## 12. Open questions

1. **`latest` TTL** — 24h feels right (matches typical metadata caching);
   `--refresh` escapes. Stable-only filter: exclude SNAPSHOT and
   pre-release qualifiers?
2. **Native-classifier tools (PRD §20.4)** — orthogonal; slots in as a
   post-resolve substitution once targets land.
3. **Zip/tarball project URLs** — defer until someone asks.
