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
`www/*`. Docs' `www/` paths updated.

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
for wind-back. **Outcome + rationale recorded below once the verdicts land.**
