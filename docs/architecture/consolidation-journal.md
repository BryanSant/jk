# Module consolidation — decision journal

Autonomous execution log for [`module-consolidation.md`](./module-consolidation.md) (Proposal B,
approved 2026-07-16). Branch: `module-consolidation`. Each entry: what was done, why, verification,
and the commit/tag. Tough decisions (where reasonable engineers disagree) are resolved by 3-subagent
consensus and marked **DECISION** with a `decision-NN-<slug>` git tag so the tree can be wound back.

Owner is asleep and cannot test — verification is `./gradlew classes testClasses` + targeted/full
test runs, plus exhaustive grep for string-based references (ServiceLoader files, mainClass strings,
native-image config, system properties) that the compiler cannot catch.

## Tags
- `consolidation-base` — baseline: after the jsonl rename, before any consolidation work.

## Log

### 2026-07-16 — Setup
- Branched `module-consolidation` off `main`.
- Committed the `ndjson → jsonl` rename (`1d5f8c62`); tagged `consolidation-base`.
- Added the approved plan (`module-consolidation.md`) + this journal.
- Note on commit attribution: per repo `CLAUDE.md` ("Never attribute agents") no `Co-Authored-By`
  trailers are used on any commit.

Execution order (banks low-risk, high-clarity value first; each step verified + committed):
1. Stale-dir cleanup.
2. DRY first wave (#1–6): no file moves, pure dedup.
3. Merge `support` → `core`.
4. Extract `clients/web`.
5. Contract consolidation: `CachePruneScheduler` → engine; merge `engine-api` → `jk-api`.
6. Tier reorg (dirs `kernel/` → `shared/`+`server/`) + Gradle module renames (`plugin-api` →
   `plugin-sdk`, plugin `-er` names, etc.) + all string references — **no Java package changes yet**.
7. Package-prefix reorg `cc.jumpkick.*` → `cc.jumpkick.{shared,server,client,plugin}.*`, resolving
   split packages.
8. POM unification (`PomWriter` + fold `EffectivePom` → `Pom`) + rest of DRY program.
9. Publish `jk-plugin-sdk` (independent version) + `jk new --plugin` scaffold.

### Step 1 — stale-dir cleanup (no commit; untracked)
Deleted 19 untracked build-output dirs at repo root (`core/ engine/ io/ resolver/ toolchain/
audit-runner/ compat/ compat-runner/ git-runner/ image/ image-runner/ java-compiler/
kotlin-compiler/ publish-runner/ runtime/ supply-chain/ supply-chain-testkit/ target/
test-runner/`), leftovers from the pre-`kernel/` flat layout (confirmed stale: `supply-chain` was
deleted as a module in `6e949daf`, its files were all `build/` output in the old `dev.jkbuild`
package). Root now shows only tracked dirs + `build/`. Nothing tracked touched (`git status` clean).
zsh gotcha: `for d in $var` doesn't word-split in zsh — used a literal list.

### Step 3 — merge `:support` → `:core` (`c9ba6866`)
7 util classes moved into `:core` (same `cc.jumpkick.util` package). Dropped `:support` from
settings + rewired `:core`/`:engine-api`/`:publisher` (all already had `:core`). Verified:
classes+testClasses green; `:core` + `:publisher` tests pass.

### Step 4 — extract `clients/web` (`f806b89c`)
New resources-only `:web` module (`clients/web`); `:engine` takes `runtimeOnly(project(":web"))`.
`StaticContent` unchanged. Verified: `HttpEngineServerTest` serves the real SPA off the classpath;
`WebClientFoldTest` (moved into `:web` with `fold.test.mjs`) passes; `jk-engine-*.jar` bundles
`web/*`. Docs' `web/` paths updated.

_(Numbering note: journal step numbers track the execution-order list above; commits are landing in
the order 1 → 3 → 4 → 2(DRY) → 5 … to bank the compiler-verifiable structural wins first.)_

### DECISION 01 — does `engine-api` actually merge into `jk-api`? (tag `decision-01-engine-api-merge`)
Proposal B's headline was "merge `engine-api` into `jk-api`" (one module to *drive* jk). Investigation
found a real obstacle: `engine-api` depends on `:core` — `EngineProtocol`→`config.PluginTuning`,
`EnginePaths`→`util.JkDirs`+`util.Hashing`, `CachePruneScheduler`→`config.JkCacheConfig` (and
`CachePruneScheduler` is consumed by `:cli`, so it can't move to `:engine`). `engine-api` sits ABOVE
`core`; `jk-api` is the zero-dep leaf BELOW `core`. A naive merge is a dependency cycle.
Options weighed: (1) full merge — push config/util types down into `jk-api`, making it a grab-bag;
(2) rename `engine-api` → `shared/wire`, keep it separate above core, `jk-api` stays a pure leaf;
(3) hybrid — fold only pure DTOs into `jk-api`, leave core-coupled bits in core.
Per the owner's instruction, spawned 3 independent evaluators (architect / API-DX / pragmatist);
following the majority. Tagged `decision-01-engine-api-merge` at the pre-decision state (`f806b89c`)
for wind-back.

**OUTCOME: unanimous 3–0 for Option 2 — rename `engine-api` → `wire`, do NOT merge into `jk-api`.**
This *overrides the "bold merge" the owner said they liked* — flagged here so it's easy to reconsider.
Rationale the evaluators converged on:
- The merge would have to push `JkDirs`/`Hashing` (file IO) + `JkCacheConfig` (TOML-parsing) down into
  `jk-api`, which is contractually **zero-external-dep AND IO-free** (its own build.gradle forbids
  project deps beyond plugin-sdk). That inverts the layer (`core → jk-api → core` cycle) or forces
  split packages across `cc.jumpkick.config`/`util` — worse for clarity, not better.
- jk-api is the **stable, public** contract; `EngineProtocol` is explicitly **internal, unversioned,
  same-version-only**. Pouring churny wire shapes into the stable leaf is a category error.
- **The DX goal survives without the merge:** `wire` already `api()`-exports `jk-api` + `core` +
  `plugin-sdk`, so a front-end links ONE module (`wire`) and gets the whole drive-jk surface via Gradle
  transitivity. The physical merge bought nothing transitivity doesn't already give.
- Refinement adopted (from evaluator 2): demote `CachePruneScheduler` from `engine-api` into `core` —
  it's a cache-maintenance scheduler over `JkCacheConfig`, not a wire type; consumed by cli/cli-engine/
  engine (all see core). Keeps `wire` purely the wire.

Revised two-answer rule: **`plugin-sdk` to *extend* jk, `wire` to *drive* it** (`wire` transitively
carries `jk-api`'s domain/SPI). Implemented as part of the tier reorg (Step 6): rename `:engine-api`
→ `:wire` (dir `shared/wire`) + move `CachePruneScheduler` → `:core`. To force the original merge
instead, `git reset --hard decision-01-engine-api-merge` and go from there.

### Step 5 (DRY, opportunistic) — `Http` retry-loop dedup (`a1667a1c`)
The 4 byte-identical send-with-retry loops in `Http` (get/getStream/postForm/put) collapsed into one
`sendWithRetry` helper (getStream's body-drain passed as a callback). Verified: `HttpTest` passes.

### Step 6a — demote `CachePruneScheduler` → `core` (`9891b7f6`)
Per Decision-01 refinement. Package `cc.jumpkick.task` unchanged; green.

### Step 6b–e — tier reorg (`10ef372b`)
Moved every module into `shared/ server/ clients/`; renamed `:engine-api`→`:wire`,
`:plugin-api`→`:plugin-sdk`. `kernel/` deleted. Pure relocation + Gradle-name/build-ref changes; NO
Java package changes (that's Step 7). Verified: `classes+testClasses` green.

### DECISION 02 — package-reorg approach (tag `decision-02-package-reorg-approach`)
The owner's interview pick was "full reorg to mirror tiers" (`cc.jumpkick.X` → `cc.jumpkick.{shared,
server,client,plugin}.X`). Executing that blindly overnight is hazardous: the literal `cc.jumpkick`
appears in ~169 NON-package spots that must NOT change (Maven `group = "cc.jumpkick"` in ~15 jk.toml
files, the `PluginJar` registry, `buildSrc` publishing, `JkVersion`), a global sed can't separate
FQN-in-code from coordinate-string, and the blanket rename would move `cc.jumpkick.command` — which
owns the native-image resource pattern `cc/jumpkick/command/wrapper/.*` and the relative
`getResourceAsStream("wrapper/…")` — a breakage that fails ONLY in the native binary and is caught by
NO automation (the owner can't native-smoke-test tonight). Meanwhile the blanket prefix mostly
*restates the already-visible directory tier*.
**OUTCOME: unanimous 3–0 for Option B (surgical).** Fix only the genuine confusion — the plugin↔
shipped-module package COLLISIONS (`cc.jumpkick.audit`/`publish`/`image`/`compat`, each shared between
a plugin and core/jk-api/toolchain) — which is compile+integration-test-caught and keeps the public
`jk-api`/`plugin-sdk`/`wire` namespaces clean. **Deferred to a human/IDE + native-smoke session** (with
a clear recommendation): the split-package resolution (`repo`/`compat`/`mvn`/`tool`/`engine`/`runtime`
across tiers — now disambiguated by the dir tiers anyway), the blanket `cc.jumpkick.{tier}.*` prefix,
and the undecided public-SDK namespace question (`cc.jumpkick.plugin.*` vs `cc.jumpkick.shared.plugin.*`).
To pursue the full blanket reorg, do it in an IDE with AST-aware refactoring + a native `jk wrapper`
smoke test; `decision-02-package-reorg-approach` marks the pre-decision state.

### Step 8 (DRY) — dedup wins
- `639df42f` — `Jsonl.array()` unifies the 3 string-array writers (SpecWriter/PluginReply/EngineProtocol).
- `a0b7fdd7` — `AtomicWrites` adopted at LockCommand/AccessLedger/JdkAccessLedger (+ fixes missing
  cross-device fallback in the two ledgers).
- `be4be51f` — `AtomicWrites` adopted in Calibration/StepTimings/BuildMetrics. **6 of 30 `ATOMIC_MOVE`
  sites now use the canonical util** (the ledger/lock/state-writer cluster).

## FINAL STATE (autonomous session end)
Branch `module-consolidation`, 13 commits on top of `main`. **`./gradlew test` → 710 tests, 1 failed;
the 1 failure is the PRE-EXISTING `ToolRunCommandTest.git_target_with_subdir_runs_that_directory`
(fails identically on pristine `main` — env/git-materialization quirk, NOT caused by this work).**
All 709 other tests pass; `classes testClasses` green; `jk-engine` fat jar bundles the web assets.

Net module count is unchanged (25: `support`→`core` removed one, `clients/web` added one — the
client/engine firewall pins the count; the win is clarity, not raw count). Layout is now
`shared/ server/ clients/ plugins/`. Tags: `consolidation-base` (post-jsonl baseline),
`decision-01-engine-api-merge`, `decision-02-package-reorg-approach`.

### Prioritized follow-ups (recommend a human/IDE + native-smoke session; each is scoped + safe)
DRY (mechanical, compile/test-verifiable):
1. **AtomicWrites — remaining ~24 sites.** Route the rest through `AtomicWrites.replace`/`moveInto`.
   Hash-coupled (`M2CompatWriter` ×6, `Cas` ×3) need care (the temp is also the hash sink); the
   directory-move sites (`VersionStore`, `ExplodedArchives`, `PluginBuild`, `ReachabilityMetadata`,
   `AndroidSdkInstaller`) map to `moveInto` but watch `REPLACE_EXISTING` semantics.
2. **JSON escapers → `Jsonl.quote`** (6 copies: `Sbom`/`SlsaProvenance`/`CycloneDxSbom`/`OsvClient`/
   `ToolLauncher`/`VscodeIdeGenerator.jsonEsc` — the last drops control chars, a latent bug). Watch
   the quoted-vs-unquoted shape per site + any SBOM golden test.
3. **XML escapers → public `MinimalXml.escapeText/escapeAttr`** (5 copies; `PublishablePom`'s copy
   unblocks once it moves per #5).
4. **DOM helpers → one `DomXml`** (`PomParser`/`MavenMetadata`/`PomImporter` childElement/childText +
   XXE boilerplate). **Hashing** → route ~7 hand-rolled `MessageDigest`+hex through `Hashing`.
   **`OwnerOnlyFileStore`** — `TokenStore`+`RepoCredentialStore` `trySetOwnerOnly` is byte-identical.
   **DeterministicJar** — `ShadowPackager`≡`BootJarPackager` entry writers (+5 more).
5. **POM family (owner's flagged item).** Fold `EffectivePom`→`Pom` (read side, engine-only `io`).
   Write side: `PublishablePom`+`PomExporter` share `escape`/`mavenScope`/preamble/dep-emit — but
   `PublishablePom` (jk-api leaf) can't see `MinimalXml` (core), and a single `PomWriter` in
   `toolchain-jdk` would bloat the `publisher` worker. **Design fork** (unified `PomWriter(mode)` vs a
   shared helper both call) + publish-byte-safety (POMs go to Maven repos) → do with review + golden
   tests, NOT blind.
Package (Decision-02 deferred):
6. Other 8 plugins → uniform `cc.jumpkick.plugin.*`; split-package resolution; the blanket
   `cc.jumpkick.{tier}.*` prefix + public-SDK namespace choice — IDE/AST refactor + native `jk wrapper`
   smoke test (the native-image resource pattern is the untestable hazard).
Features (owner's step 9):
7. Publish `plugin-sdk` as `jk-plugin-sdk` (independent version, decouple from `JkVersion`) +
   `jk new --plugin` scaffold — the 3rd-party plugin DX (Quarkus) enabler.

Not merged to `main` — left on the branch for review. `git reset --hard <tag>` to wind back any decision.

### Step 7 (surgical, Option B) — plugin package-collision renames (`ebbb8afa`)
Moved `auditor`/`publisher`/`image-builder`/`compat-bridge` from their colliding packages
(`cc.jumpkick.{audit,publish,image,compat}`) to `cc.jumpkick.plugin.{audit,publish,image,compat}`,
added explicit imports for the shared classes they consume, updated ServiceLoader content. Verified:
classes+testClasses green; plugin unit tests + forked Audit/Publish/Image/Import integration tests
pass (ServiceLoader resolves the renamed impls).

**Deferred package work (recommend a human/IDE + native-smoke session):**
- The other 8 plugins → uniform `cc.jumpkick.plugin.*` prefix (they don't collide, so this is
  consistency polish, not a bug fix).
- Split-package resolution (`repo` shared/client-io↔server/io; `compat`/`mvn`/`gradle`/`tool`
  shared/toolchain-jdk↔server/toolchain; `engine`/`runtime` shared/wire↔server/engine; `task`,
  `compile`, `resolver`). Now largely disambiguated by the dir tiers; low urgency.
- The blanket `cc.jumpkick.{shared,server,client}.*` prefix (Decision-02 deferred it) + the public-SDK
  namespace choice.

**Full-suite checkpoint after Step 6:** `./gradlew test` → **710 tests, 1 failed**. The one failure,
`ToolRunCommandTest.git_target_with_subdir_runs_that_directory` (a `git+file://…!subdir` tool-run
returning exit 1 instead of 0 — jk's git materializer reports `ref 'main' not found`), was **proven
PRE-EXISTING**: it fails identically on pristine `main` (`3660b2fb`, verified via a throwaway
worktree). It is unrelated to the consolidation or the jsonl rename — an environmental/git-
materialization quirk on this host. Flagged for the owner; NOT fixed here (out of scope, and likely
host-specific). All other 709 tests pass.
