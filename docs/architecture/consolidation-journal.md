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
