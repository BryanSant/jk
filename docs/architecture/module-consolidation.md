# Module consolidation & DRY (toward 1.0)

Status: **approved — Proposal B** (2026-07-16), with two owner tweaks locked in: the engine-only
tier is named **`server/`** (not `backend`), and `plugin-api` becomes **`plugin-sdk`** (published
artifact `jk-plugin-sdk`). Final layout: **`shared/ server/ clients/ plugins/`**, packages
`cc.jumpkick.{shared,server,client,plugin}.*`. Goal: the fewest, clearest Gradle modules and the
least duplicated code we can get *without* sacrificing a genuine advantage of a module being broken
out. Pre-1.0: breaking changes welcome — renaming modules, packages, SPIs, and constants is in scope
and encouraged. Successor to [`slim-client.md`](./slim-client.md) (which made `jk` a thin client) and
[`re-foundation.md`](./re-foundation.md) (which made the engine a server).

This doc records the analysis, **three proposals**, and the **approved recommendation (B)**.

---

## 1. The governing fact: the client/engine firewall *is* the module boundary

The native `jk` binary (`:cli`, GraalVM `-Os`) must not link heavy code. Today that is enforced
*structurally*: `:cli` simply does not declare `:engine`/`:io`/`:resolver`/`:toolchain`, so the
compiler cannot reference what isn't on the classpath. Everything heavy is reached over the wire
(`EngineClient`) or through the `ServiceLoader` seam (`InProcessEngine`, impl only present in the
JVM dist / tests).

Consequence for consolidation: **a client-safe module and an engine-only module can never merge** —
the merge would drag JGit/ASM/resolver/transports onto the native image and dissolve the firewall.
So the module *set* is already near constraint-minimal. The current 13 non-plugin modules sort
cleanly into exactly two tiers by one question — *can the native CLI link it?*

| Tier | Modules (current dir) | On native CLI classpath? |
|---|---|---|
| **client-safe** | `jk-api` (`kernel/model`), `plugin-sdk`, `support`, `core`, `client-io`, `toolchain-jdk`, `engine-api` | **yes** |
| **engine-only** | `io`, `resolver`, `toolchain`, `engine` | **no** |
| clients | `cli`, `cli-engine` (bridge) | — |
| plugins | 12 worker modules | forked subprocess; on neither compile classpath |

This is why **`kernel/` → `server/` is a misnomer**: `kernel/` holds *seven* client-safe modules
and only *four* engine-only ones. A wholesale rename would file the client-safe majority under
"server". The correct move is to **split `kernel/` across `shared/` and `server/`**, giving the
owner's proposed four-tier layout its natural definition:

> **`shared/` = exactly what the native CLI can link. `server/` = exactly what only the engine
> links.** The directory boundary becomes the firewall, compiler-enforced.

---

## 2. Direct answers to the owner's questions

- **"Plugins need the wire protocol — is that `plugin-sdk` or `engine-api`?"** → **`plugin-sdk`.**
  There are *two* protocols sharing *one* codec (`Jsonl`, in `plugin-sdk`):
  - host↔plugin: `cc.jumpkick.plugin.protocol.*` in **`plugin-sdk`** — this is what plugins speak.
  - client↔engine: `cc.jumpkick.engine.protocol.EngineProtocol` in **`engine-api`** — what
    front-ends (CLI, IDE, web) speak. **No plugin depends on `engine-api`, and none should.**
  A 3rd-party plugin needs `plugin-sdk` and nothing else.

- **"`PublishablePom` vs `Pom` — one object for both?"** → **No — two objects, one per direction.**
  They are orthogonal concerns wearing the same name (the confusing "similar-but-different" smell):
  - *read* side: `Pom` (parsed) and `EffectivePom` (resolved) are near-identical — **collapse
    `EffectivePom` into `Pom`**; stays engine-only in `:io`.
  - *write* side: `PublishablePom` (publish-grade) and `PomExporter` (export/import) are heavy
    copy-paste (`escape()` is byte-identical; `mavenScope`, preamble, dependency emit all dup) —
    **merge into one `PomWriter` with a mode**; lives client-safe in `toolchain-jdk`
    (`PublishablePom` moves out of `jk-api`/model — breaking, fine pre-1.0).
  Do *not* merge read+write into one type: opposite directions, opposite tiers.

- **"Do we need a separate `io` module (just repo + S3 models)?"** → **Yes, keep it.** It is not
  "a few model classes" — it is 2.2k LOC of coherent engine-only fetch/publish machinery
  (`MavenRepo`, transports incl. `S3Transport`+`SigV4Signer`, metadata parse/cache, effective-POM
  builder). It is exactly the network/signing surface the slim client omits; folding it into
  `client-io` would breach the firewall. The repo *model* is single-sourced, not duplicated —
  `client-io/repo/*` is the disjoint on-disk/credential side that `io` *consumes*.

- **"Rename `kernel/` → `server/`?"** → **Don't rename it wholesale — split it** (see §1).

- **"Relationship of `cli`, `cli-engine`, `engine-api`; can things be dropped?"**
  - `:cli` — the slim client (presentation, arg parsing, `EngineClient`, TUI). Already a thin
    front-end; heavy verbs just forward.
  - `:engine-api` — the client↔engine **wire contract**. **Cannot fold into `:engine`** (the client
    needs the wire types *without* the server). It is the shared seam; keep it (rename it `wire`),
    trim its stray `:core` dependency.
  - `:cli-engine` — **a packaging + test seam, not a fourth concern.** Its `src/main` is 3 files
    (`EngineMain`, `PosixDetach`, `InProcessEngineImpl`); the rest is the relocated CLI test suite.
    It is the *one* module allowed to link both `:cli` and `:engine`, and it emits the shipped
    `jk-engine.jar` + JVM dist. It can be reduced (fix its split packages) or folded into `:cli`
    behind an arch-test — see the proposals.

- **"A clean way for 3rd-party plugin devs (e.g. Quarkus)?"** → The SPI is already clean
  (`plugin-sdk`, dependency-free, JDK-17, process-isolated). The gaps are *packaging/DX*, not the
  API: **no published artifact** (it's local-Maven only, version-locked to `JkVersion`), **no
  `jk new --plugin` scaffold**, and docs/`PluginJar` conflate the 4 public "table" plugins with the
  8 internal "worker" plugins. Fix = publish `plugin-sdk` as `jk-plugin-sdk` (versioned to the
  `jk-compat` contract, not the build number) + a plugin archetype.

- **Web UI** → extract a **resources-only `clients/web` module**; `:engine` takes
  `runtimeOnly(project(":web"))`; the `shadowJar` bundles the assets automatically;
  `StaticContent` (which resolves `/www/*` via `getResource`) needs **zero changes**; **no Java
  moves** (the HTTP server stays server-side). Matches the owner's "own module, assets still bundled
  into engine" decision. (Today it's a build-step-free Vue-from-CDN SPA — no node toolchain to
  extract.)

---

## 3. The DRY program (shared by all three proposals)

Independent of module topology, this is the highest-value clarity work — "merge duplicate code."
Ranked, first wave is low-risk and crosses no boundary:

| # | Consolidate | Into (exists?) | ~LOC | Risk | Notes |
|---|---|---|---|---|---|
| 1 | 23 hand-rolled `ATOMIC_MOVE` sites | `support/AtomicWrites` (exists, **0 adopters**) | 60–100 | low–med | also fixes missing cross-device fallback |
| 2 | 6 duplicate JSON string-escapers | `plugin-sdk/Jsonl.quote` (exists) | ~100 | low | fixes a control-char bug in `VscodeIdeGenerator` |
| 3 | 5 XML escapers (2 exact-dup pairs) | make `support/MinimalXml` escapers public | ~40 | low | |
| 4 | `TokenStore` + `RepoCredentialStore` file plumbing | new `client-io/OwnerOnlyFileStore` | 30–40 | low | `0600` perms + sanitize are identical |
| 5 | 4× retry/backoff loop inside `Http` | one `Http.retry(...)` | ~40 | low | |
| 6 | 3 string-array writers | `plugin-sdk/Jsonl.array()` | ~20 | low | |
| 7 | DOM helper sets + XXE boilerplate (`PomParser`/`MavenMetadata`/`PomImporter`) | new `io/DomXml` | 50–120 | low–med | |
| 8 | ~7 hand-rolled `MessageDigest`+hex | `support/Hashing` (exists) | 60–70 | low–med | `BuildIdentity` blocked (below `support`) |
| 9 | **`PublishablePom` + `PomExporter` → `PomWriter(mode)`** | `toolchain-jdk/mvn` | 150–200 | med | golden-test emitted XML; owner's flagged item |
| 10 | `EffectivePom` → fold into `Pom` | `io/repo` | ~33 | med | engine-only, 11 call-sites |
| 11 | ~127 hand-built flat objects → `Jsonl.obj()…end()` builder | `plugin-sdk/Jsonl` | 150–300 | med | big readability win; `EngineProtocol` is 2.4k LOC |
| 12 | deterministic JAR-entry writers (`ShadowPackager`≡`BootJarPackager`, +5 more) | new `DeterministicJar` | 50–150 | med | reconcile reproducible-build stamps |

First wave (#1–6): ~300–400 LOC and several types/methods gone, zero boundary impact, no breaking
changes. The POM merge (#9/#10) is the owner's headline item.

---

## 4. Naming reorg (shared by all proposals): `cc.jumpkick.{shared,server,client,plugin}.*`

One package prefix per tier, **one package per module, no split packages.** This kills the current
mess: ~20 packages split across two modules each (`cc.jumpkick.repo` spans `io`+`client-io`;
`cc.jumpkick.compat`/`mvn` span `toolchain-jdk`+`toolchain`; `cc.jumpkick.cli`/`command` span
`cli`+`cli-engine`), and four plugin package collisions (`:auditor`→`audit` clashes with `core`;
`:publisher`→`publish` clashes with `jk-api`; `:image-builder`→`image` clashes with `jk-api`;
`:compat-bridge`→`compat` clashes with `toolchain`). Also finishes the half-done `-runner`→`-er`
rename recorded in `settings.gradle.kts`, and retires the `:jk-api`↔`kernel/model` name/dir mismatch.
Delete the stale untracked build-output dirs at repo root (`core/`, `engine/`, `io/`, `resolver/`,
`toolchain/`, `*-runner/`, `compat/`, `image/`, `runtime/`, `supply-chain*/`, `target/`).

---

## 5. Three proposals

All three: adopt the four-tier layout (§1), the package reorg (§4), extract `clients/web` (§2),
merge `support`→`core`, delete stale dirs, and run the DRY first wave (§3 #1–6). They differ in how
far they consolidate the contract + client modules and how much of the DRY program they take.

### Proposal A — "Re-tier & Rename" (conservative, lowest risk)

Fix the *presentation*; leave the module set otherwise intact.

```
shared/   plugin-sdk  jk-api  core(+support)  client-io  toolchain-jdk  engine-api→wire
server/  io  resolver  toolchain  engine
clients/  cli  cli-engine(split-package fixed)  web
plugins/  (12, re-prefixed cc.jumpkick.plugin.*)
```
- `cli-engine` kept; only its incidental split package fixed (move `EngineMain`/`PosixDetach` to a
  dedicated package). The **compile-enforced firewall stays** — the don't-break-things win.
- DRY: first wave only (#1–6).
- **Non-plugin modules: 13** (−`support`, +`web`). Count flat; clarity way up.
- *For:* the cautious dev. *Against:* leaves the POM dup, the contract confusion, and `engine-api`'s
  `:core` leak unaddressed.

### Proposal B — "Consolidate the contract" (moderate) — **RECOMMENDED**

Proposal A, plus finish the contract and the DRY program.

```
shared/   plugin-sdk        (the published jk-plugin-sdk: SDK + Jsonl codec; JDK-17 leaf)
          jk-api            (domain + scheduler/command SPI + the client↔engine WIRE — absorbs engine-api)
          core(+support)    (config/lock/layout/catalog/manifest; client-safe foundation)
          client-io         (client I/O slice; renamed pkgs so it no longer splits `repo` with io)
          toolchain-jdk     (client JDK/tool flow; renamed pkgs so it no longer splits `mvn`/`compat`)
server/  io  resolver  toolchain  engine
clients/  cli  cli-engine(thin packaging+tests)  web
plugins/  (12)
```
- **Merge `engine-api` into `jk-api`.** First move `CachePruneScheduler` (its only `:core`-coupled
  class) into `:engine`; the remainder is pure `jk-api`-vocabulary wire types + the `Jsonl`-based
  codec. Result: **one client-facing contract** — a front-end links *one* module (`jk-api`) to
  drive the engine; a plugin links *one* module (`plugin-sdk`) to extend it. Trade-off: couples the
  pure domain model to the wire DTOs (the "domain purist" dislikes it; the DX/clarity view prefers
  it). `jk-api` stays zero-external-dependency, JDK 25.
- `cli-engine` kept as a thin packaging+test module (**firewall stays compile-enforced**), split
  package fixed.
- Publish `plugin-sdk` as `jk-plugin-sdk` (independent version) + add `jk new --plugin` scaffold.
- DRY: **entire program** (#1–12), including the `PomWriter` unification (#9/#10).
- **Non-plugin modules: 12** (also −`engine-api`).
- *For:* contributor clarity + DRY + plugin DX, with the firewall intact. *Against:* the domain/wire
  coupling; the POM merge needs golden tests.

### Proposal C — "Fewest modules" (aggressive)

Proposal B, plus collapse the client-safe *implementation* and the packaging seam.

```
shared/   plugin-sdk   jk-api(+engine-api)   core(+support+client-io+toolchain-jdk)
server/  io  resolver  toolchain  engine
clients/  cli(+cli-engine via `engineHost` config + ArchUnit firewall)   web
plugins/  (12)
```
- Merge `client-io` + `toolchain-jdk` into `core` — legal (all client-safe; nothing engine-only
  moves onto the CLI) and drops two modules. **Cost: `core` becomes a ~28k-LOC god-module.**
- Fold `cli-engine` into `:cli` as extra source sets + a `shadowJar`/dist task, keeping `:engine`
  off the main classpath via a dedicated `engineHost` configuration. **Cost: the firewall drops
  from compiler-enforced to ArchUnit-enforced** — the strongest guarantee is traded for one fewer
  module, which cuts against "smallest CLI is sacred".
- **Non-plugin modules: 9.**
- *For:* the "too complicated / fewest lines" dev and the rewrite-everything dev. *Against:* a
  god-module and a weaker firewall — both hurt the clarity the owner is optimizing for.

### Count summary

| | non-plugin modules | firewall | god-module risk | DRY | risk |
|---|---:|---|---|---|---|
| today | 13 | compiler | core borderline | — | — |
| **A** | 13 | compiler | none | first wave | low |
| **B** | 12 | compiler | none | full | low–med |
| **C** | 9 | ArchUnit | **core god-module** | full | med–high |

---

## 6. Recommendation: **Proposal B**

Optimizing for **contributor clarity + DRY** (the owner's stated priority) with **smallest CLI as a
hard constraint**:

- The firewall is a *genuine advantage* of the split, so it stays — and stays **compiler-enforced**
  (rules out C's arch-test regression). Resolver stays separate and plugins stay one-per-module
  (owner requirements; both satisfied A–C).
- Module count is not the real lever — the firewall pins it near 12. **Clarity comes from the
  package reorg, killing split packages/collisions, extracting web, and the DRY pass**, all of which
  B takes in full. C buys three fewer modules by creating a god-`core` and weakening the firewall —
  a net loss for a newcomer trying to navigate, and against "smallest CLI is sacred".
- B's one bold structural move — collapsing `engine-api` into `jk-api` — turns "which of these
  three api-ish modules do I use?" into a crisp two-answer rule: **`plugin-sdk` to *extend* jk,
  `jk-api` to *drive* it.** That is the single biggest clarity win available and the direct answer
  to the owner's `cli`/`cli-engine`/`engine-api` confusion.

Sequencing (each step lands green on its own; no big-bang):
1. Delete stale root dirs; extract `clients/web`; merge `support`→`core`. *(mechanical, zero-risk)*
2. Re-tier `kernel/`→`shared/`+`server/`; the package reorg + plugin re-prefix. *(large diff, zero
   behavior change; do as one sweep so split packages/collisions die together)*
3. DRY first wave (#1–6). *(no breaking changes)*
4. Move `CachePruneScheduler`→`:engine`; merge `engine-api`→`jk-api`; rename `cli-engine`'s split
   packages. *(the contract consolidation)*
5. `PomWriter`/`Pom` unification (#9/#10) + the rest of the DRY program.
6. Publish `plugin-sdk` as `jk-plugin-sdk` + `jk new --plugin`.

Steps 1–3 are pure wins available immediately regardless of the B-vs-C debate.

---

## 7. How each perspective reads the recommendation

- **Supervising architect** — endorses B: preserves the one genuine advantage (compiler-enforced
  firewall), consolidates only where free, avoids a god-module.
- **Cautious dev ("don't break things")** — prefers A but accepts B because the firewall stays
  compile-enforced; vetoes C's arch-test swap.
- **Rewrite-everything dev** — wants C; gets most of the satisfaction from B's full DRY pass + reorg
  without the god-module.
- **3rd-party plugin dev (Quarkus)** — cares only that `plugin-sdk` becomes a published, versioned
  `jk-plugin-sdk` with a scaffold; delivered by B (and the DX program), indifferent to tiers.
- **Security auditor** — wants the `Jsonl` codec kept a small, dependency-free, fuzzable leaf (it
  parses untrusted HTTP bodies too) — B keeps it in the `plugin-sdk` leaf; also flags (out of scope
  here) HTTP loopback reads being unauthenticated and "sandboxed" overstating process-isolation.
  Mild preference against C (bigger reachable surface in a god-`core`).
- **API/SPI DX dev** — B's headline win: one module to *drive* jk, one to *extend* it; and
  `engine-api`'s stray `:core` dep is removed.
- **Technical writer** — the package reorg (all three) ends split packages and collisions; B/C also
  fix the `cli`/`command` split across `cli`+`cli-engine`.
- **"Too complicated / fewest lines" dev** — wants C's count, but the DRY program (−1000+ LOC, in
  B and C) is where the real line reduction is; B delivers it without a god-module.
- **OSS contributor hacking internals** — B removes the similar-but-different code (dedup) and the
  "where does this class live?" split packages, without a 28k-LOC module to get lost in.
