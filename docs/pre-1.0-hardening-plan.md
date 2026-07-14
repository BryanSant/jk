# Pre-1.0 Hardening Plan

Status: IN PROGRESS — H1 landed; see the Execution log at the end for decisions made
while implementing (several diverge from or extend the original plan text).

This is the last cheap window to break things. Once 1.0 ships, the wire protocol, the
lockfile format, the `jk-api`/plugin SPI, and the CLI surface all become contracts we
version or tiptoe around. This plan is the output of a full-codebase adversarial sweep
(five parallel audits: wire protocol, API/SPI, back-compat inventory, engine/cache
internals, CLI surface) judged against the project's own posture:

> Pre-1.0: breaking changes are welcome; no back-compat shims. Backwards compatibility
> is an anti-goal. (docs/architecture/re-foundation.md)

Every item below was verified against source at the time of writing; the highest-severity
bugs (P0.1, P0.2, P0.3, P0.4) were independently re-confirmed by hand. File:line refs
are as of commit b727dbaf.

Ground rules for the execution:

- **G1 — Delete, don't deprecate.** No `@Deprecated`, no grace windows, no dual reads.
  A shim removed is a shim that can't rot.
- **G2 — One idiom per concept.** One atomic-write helper, one recursive delete, one
  error message shape, one field name per concept on the wire.
- **G3 — The protocol must be self-describing.** A reader must be able to decode a
  message without remembering which request it sent.
- **G4 — Full `./gradlew test` gates every phase**; behavioral smoke (dist + install +
  real project build) gates the merge.
- **G5 — Don't touch** `kernel/engine/src/main/resources/www/index.html` and
  `www/style.css` (user WIP).

---

## Part 0 — Confirmed live bugs (fix before anything else)

### P0.1 — `--version` collision breaks `jk self update --version X` and `jk wrapper --version X`
`CommandDispatch.withGlobals` appends `GlobalOptions.globalOpts()` **after** the
command's own options, and `ArgParser.parse` indexes names into a `HashMap` where later
puts win. The global boolean `-V, --version` therefore silently replaces the
value-taking `--version` declared by `SelfCommand.UpdateSub` and `WrapperCommand` — and
the dispatcher short-circuits on `in.isSet("version")` and prints the version banner
before the command ever runs. The two commands whose entire point is `--version <v>`
are dead on arrival.

Fix (both layers):
1. `ArgParser`: a duplicate option name across command + globals is a **build-time
   error** (throw in `withGlobals`), not a silent overwrite. This kills the whole bug
   class, including future collisions.
2. Rename the value option on both commands to a positional `<version>` param
   (`jk self update 0.12.0`, `jk wrapper 0.12.0`) — reads better than `--version` and
   sidesteps the global flag forever. Update `jk.sh`/`jk.bat`? No — the wrapper
   templates never invoke `--version`; only docs and the springboard exec
   (`WrapperCommand.run`) change.
3. Dispatcher: only honor bare `-V`/`--version` when the command didn't declare a
   same-named option (moot after #2, but keep the guard).

### P0.2 — Engine→engine auth is wire-incompatible with the server on TCP (Windows)
`EngineServer.openClient` (engine→engine signalling: `helloProbe`, `drainDisplaced`)
writes the **raw token string** as line one on the loopback-TCP transport
(EngineServer.java:389), but `authenticate()` requires the `{"t":"auth","token":…}`
envelope (EngineServer.java:457-462) and closes without reply on anything else. On
Windows (TCP transport), same-version election and takeover drain silently fail.
Fix: `openClient` sends `EngineProtocol.auth(token)` — one encoding for one concept.
Add a TCP-transport round-trip test (force `EngineTransport.useLoopbackTcp()`).

### P0.3 — Delegation child's stderr is never drained → guaranteed hang
`EngineDelegate.runAsChild` starts the pinned child with
`redirectErrorStream(false)` and never reads `getErrorStream()`
(EngineDelegate.java:76). Once the child writes ~64 KB to stderr (a stack trace, a JVM
warning), it blocks and the relay deadlocks in `readLine()`. This is the rarely-run
version-skew path — it will hang in the field, not in CI.
Fix: `redirectError(Redirect.appendTo(engineLog))` — stderr must not interleave with
the child's NDJSON stdout, and discarding diagnostics from the path most likely to
fail is wrong. Also bound the client→child pump to the relay's lifetime (today it can
outlive the exchange and write to a closed stream).

### P0.4 — `install.sh` materializes versions without feeding the CAS → pruned local versions are unrecoverable
`install.sh` copies the engine jar into `versions/<v>/lib/` but never ingests it into
the CAS. `VersionStore.prune`'s contract ("a pruned pin re-materializes on demand")
and `materialize()` both require the CAS blob. A locally-installed (or air-gapped)
version that gets pruned is gone; `EngineDelegate` then dead-ends with "run its
wrapper". The shell script is a fourth, divergent materializer that violates the
invariant the Java writers maintain.
Fix: delete the bespoke shell materialization; add a hidden
`jk self materialize <client-bin> <engine-jar>` that reuses
`VersionStore.materializeFromFiles` (CAS-first by construction) and have `install.sh`
call it through the freshly-built client. One materializer, everywhere.

### P0.5 — "Repeatable" flags that silently drop values
`--variant` (VariantSelection + the BuildCommand inline copy) and `--with`
(ToolRunCommand, ToolInstallCommand) document "repeatable" but never call `.repeat()`;
`ArgParser` last-wins, so `--variant a=1 --variant b=2` silently drops `a=1`. Fix: add
`.repeat()` (keep the comma form too), and add a parser test asserting both spellings
produce both pairs.

### P0.6 — `RepoArtifactStore.materialize` is non-atomic and swallows all errors
`Files.copy(REPLACE_EXISTING)` (not atomic) + blanket
`catch (IOException | RuntimeException ignored)` (RepoArtifactStore.java:141-155): a
crash mid-copy can leave a torn artifact, and ENOSPC/permission failures vanish while
the caller proceeds to compile against garbage. The correct `.part` + `ATOMIC_MOVE`
idiom already exists 200 lines down in the same class (`writeToLocalStore`). Fix: use
it, and on failure delete the partial and propagate.

### P0.7 — `resolveJkExe` substring heuristic is load-bearing and wrong
`CachePruneScheduler.resolveJkExe` decides "is this process the jk binary" via
`path.getFileName().contains("jk")` (CachePruneScheduler.java:114). A JVM-dist run
(`bin/java`) false-negatives (engine can't resolve its own binary without `JK_EXE`);
a path like `jkl` false-positives. Fix: `ProcessProperties.getExecutableName()` under
native-image, `/proc/self/exe` on Linux, `ProcessHandle.info().command()` otherwise,
`JK_EXE` as the explicit override — no substring test.

---

## Part 1 — Wire protocol v1 (the 1.0 freeze candidate)

The NDJSON protocol works but is three encoders, two spellings of "directory", ~10
error shapes, and one message type wearing eleven schemas. This is the part of the
sweep with a hard deadline: after 1.0 every one of these becomes a compat constraint.

### P1.1 — Split `goal-finish`'s eleven schemas
`GOAL_FINISH` is built by eleven builders with disjoint field sets (`goalFinish`,
`goalFinishLock/Sync/Format/Publish/GitFetch/Import/Image/Tool/Script/Cache`); the
client decodes it by remembering what it asked for (G3 violation), and any field drift
degrades silently to `-1`/null. Fix: add a mandatory `kind` discriminator field
emitted by every terminal builder and asserted by the adapters (unknown kind for the
verb in flight → typed protocol error). Full type-splitting (11 new `t` values) buys
little over the discriminator and churns every adapter switch; the discriminator is
the 90% fix at 10% cost.

### P1.2 — One error envelope
Today: `build-error` (misnamed — it's the catch-all for ~20 hosted verbs),
`explain-error`, `history-error`, `tree-ack.error`, `edit-ack.error`,
`lock-finish.errors[]`, `workspace-finish.errors[]`, `forecast-ack.errors[]`,
`provision-result.error`, per-report `error` fields. Replace the standalone error
types with one `{"t":"error","code":…,"message":…}` where `code` distinguishes
`config | protocol | version-skew | internal`. Keep the embedded `errors[]` arrays on
finish messages (those are result payloads, not transport errors). The version-skew
refusal in `maybeDelegate` (today a `build-error` with prose) becomes
`code=version-skew` — clients can act on it (suggest `jk self update`) instead of
pattern-matching message text.

### P1.3 — One spelling for "the project directory"
`entryDir` (build/test/lock/audit/image…) vs `dir` (every thin-client verb). Pick
**`dir`** everywhere (shorter, no information lost — every request's dir is the entry
dir). Fixes the downstream `handleAsyncGoalRequest` bug where non-`entryDir` requests
record the literal string `"null"` in history/dashboard. While there: `startedAt` vs
`startedAtMillis` — wire field and builder param agree on `startedAtMillis`.

### P1.4 — Kill string-surgery attachments; make JVM tuning universal
`withVariant`/`withJvmTuning` splice fields into an encoded line via
`substring(length-1)` + re-append `}` (EngineProtocol.java:2310, :2329). Fold
`variant`, `clientEnv`, and `jvmTuning` into the canonical request builders as real
parameters (they are session state — every hosted request carries them). This also
fixes the arbitrary coverage gap where `image/compile/sync/lock/publish/audit/format/
tool/script` silently drop `--jvm-arg`/`JK_JVM_*`.

### P1.5 — One map encoding
Three ad-hoc parallel-array pairs (`graalDirs/graalHomes`, `paramKeys/paramValues`,
`envNames/envValues`), each with its own silent-on-mismatch decode. Add
`Ndjson.strMap(key)` + a matching encoder (flat `k1 v1…` or a nested-object
mini-grammar — implementer's choice, but exactly one) and use it for all three.

### P1.6 — One null convention
`Ndjson.quote(null)` already emits `null`; delete the five hand-rolled
`(x == null ? "null" : quote(x))` re-implementations, and make `statusAck` stop
omitting `httpUrl`/`httpError` keys (emit `null` like everything else). Rule for 1.0:
**absent and null are the same thing, and the encoder always emits the key.** Replace
the `-1`/`""` sentinels (`testTotal`, `SINGLE_GOAL_DIR`) with `null` where the reader
can distinguish; keep `-1` only where a primitive field genuinely can't be null.

### P1.7 — Give `proto` teeth or delete it — give it teeth
`PROTOCOL = 1` is stamped into hello/helloAck/statusAck and read by nobody. Keep the
field (it's the 1.0 escape hatch) and wire the check both ways: server rejects a hello
with `proto > PROTOCOL` via `error code=version-skew` ("client is newer — engine
restarting" — client then takes over as it already does for version-skew); client
verifies `helloAck.proto == PROTOCOL` and treats mismatch as stale-engine (kill/
respawn path that already exists). Two small checks make protocol-zero real.

### P1.8 — Honest probes
`hello("takeover-probe")` / `hello("status-probe")` ship fake version strings because
the server ignores the client's `version` anyway. Add an optional `purpose` field to
hello (`probe | connect`) and send the real version. Cheap, and stops the field from
lying if the server ever starts reading it.

### P1.9 — Framing robustness
- **Line cap**: bound `readLine()` (64 MB hard cap) on both sides — a peer that never
  sends a newline currently OOMs the process. Wrap in one `BoundedLineReader` used by
  server and client.
- **No silent drops**: server currently `continue`s on unparseable lines and no-ops
  unknown types for *requests* (the initial line of an exchange). A garbled request
  must get `error code=protocol` back, not silence — silence wedges streaming clients
  forever (they have no read timeout).
- **Streaming idle timeout**: adapters (`EngineWorkerAdapter.stream`, resolve/build
  listeners) get a generous read deadline (default 30 min, env-tunable) so a dead
  engine surfaces as an error instead of a hung CLI. Keep unknown *event* types as
  no-ops (that's the legitimate forward-compat seam).
- **`shutdown-pending`**: currently dead on the client (falls into default→no-op, then
  surfaces as "engine crashed"). Wire it: adapters translate it into "the engine is
  shutting down — retry" with a clean retry-once on the fresh endpoint.

### P1.10 — Vocabulary mergers and hygiene
- Merge `plan-module/plan-phase/plan-done` and `explain-module/phase/edge/done` into
  one plan vocabulary (`explain` keeps its extra `edge`; same field names).
- `cache-prune-request`'s four ops with op-specific fields: keep one type but make the
  field set uniform (`op`, `dir`, `scope`) — no fields that only exist for one op.
- `requestKind` derived by string-munging thread names (`"jk-engine-1build-"` →
  `"build"`, EngineServer.java:738,806-809): carry the kind explicitly from the
  request instead of parsing it back out of a thread name.
- History/metrics responses are built inline in EngineServer with string-literal field
  names via `JsonOut` while requests live in `EngineProtocol`: move the response
  builders into `EngineProtocol` next to their requests. One schema home. (The
  `Report.encode()` family is fine — reports are versioned payloads, not envelope.)
- Constant-time token compare in `authenticate` (`MessageDigest.isEqual`), and reply
  `error code=auth` before closing on bad token so clients can distinguish "wrong
  token" from "crash".

---

## Part 2 — API/SPI surface (jk-api + plugin-api)

### P2.1 — Evict IO/runtime machinery from the jk-api leaf
`kernel/model` claims to be "the impl-free contract" but ships recursive file deletion
(`PathUtil`), tree hashing (`TreeFingerprint`, `Hashing`), `~/.m2` reads
(`credential/MavenSettings`), and — worst — `JkThreads`, which installs a JVM
**shutdown hook** and global thread pools on any classpath that touches the API jar.
Fix: split. `:jk-api` keeps pure types + the command/run SPI; a new `:kernel/support`
module takes `dev.jkbuild.util` (JkThreads, PathUtil, Hashing, TreeFingerprint,
ContextPropagating*, GitUrl, JkDirs), `credential/`, and `image/`+`publish/` config
records move to where their consumers live (core/engine). Mechanical but wide; do it
before 1.0 or never.

### P2.2 — Rename the `:model` module to `:jk-api`
Three names for one thing today (`:model`, "jk-api", "the public API surface"). The
Gradle project id should say what it is. Rename settings.gradle.kts entry + all
`project(":model")` references (search also for the `-jdk-home`-style worker deps).

### P2.3 — Two public `PluginManifest` types
`dev.jkbuild.plugin.PluginManifest` (plugin-api, 2-field worker identity, imported by
all 12 workers) vs `dev.jkbuild.plugin.manifest.PluginManifest` (core, 12-field parsed
jk-plugin.toml). Coin-flip auto-import for plugin authors. Rename the **core** one to
`PluginDescriptor` (3 importing files; the worker-facing one keeps the established
name that 12 workers already import).

### P2.4 — Delete the aspirational SPI
- `BuildPluginContext.run(RunShape)` / `.nativeImage(NativeShape)` throw
  `UnsupportedOperationException` unconditionally; `RunShape`/`NativeShape` have zero
  engine consumers. Delete all four until the feature exists — a published method that
  always throws is a lie in the contract.
- `plugins/android/jk-plugin.toml` `next-store-file`: schema'd, never read. Delete the
  key ("forward-compat" is the anti-goal).
- `PluginConfig`: document the fifth value shape (nested group vs string reference) on
  the record javadoc — the mechanism is real, tested, and invisible to a reader of the
  type; fix the stranded `/** An int with a call-site fallback. */` javadoc above
  `group()`.

### P2.5 — Constructor sprawl: one canonical ctor + builder per type, delete the rest
The sweep found ~30 "Back-compat" telescoping overloads. Cutover costs are known:
- **Zero-caller — delete today**: `JkBuild.Build` ×4, `PluginDeclaration` 4-arg,
  `Dependency(module, version, pinnedIgnored)` (a public ctor that *ignores an
  argument* — a trap, not a convenience).
- **Test-only** (~28 sites): `JkBuild` 2/3-arg → `JkBuild.builder()`.
- **One caller**: `Session` 9-arg (inline into `defaults()`).
- **Real but bounded**: `Lockfile` ×5 + `Artifact.pinnedBy` (→ builder; the reader
  already defaults unknown fields, so the overloads buy nothing), manifest
  `PluginManifest` + nested ×11 "P2…P6 shape" overloads (→ builder; produced by one
  parser), `CompileRequest`/`KotlincRequest`/`JarPackager`/`ShadowPackager`/
  `EffortWeights`/`PluginBuild`/`DependencyTree`/`WorkspaceClasspath` and the
  cli/toolchain/plugins stragglers from the inventory.
- **Actively harmful**: `BuildPipeline.Inputs` overloads that default `session` from
  `SessionContext.current()` — they reintroduce the ambient global the `Session`
  record exists to kill. Delete first.

### P2.6 — Finish the `Session` threading, delete `SessionContext` from the kernel
`SessionContext` is self-described "Transitional" — the ambient holder bridging ~30
legacy `SessionContext.current().config()` call sites. Thread `Session` explicitly
through the remaining engine call paths and delete the holder from kernel code (a
CLI-local holder for process-global flags like ANSI is acceptable). This is the
largest single item in the plan; it is also the one the codebase has been paying
interest on the longest.

### P2.7 — Contract naming pass
- `version` means `String` in `Coordinate`/`Project` but `VersionSelector` in
  `Dependency`; `ToolCoordSpec` calls the same thing `selector`; `Project.kotlin` is a
  selector named after a language. Rule: typed selector fields are `versionSelector`
  (`kotlin` → keep the name, it reads as "the kotlin version" in TOML-adjacent code —
  but type-name consistency comes from the other three).
- `sha` vs `sha256` vs `sha256Hex`: standardize on `sha256` for hex-string fields
  (`GitRefSpec.Rev.sha` → `sha256`... it's a git object id (SHA-1/SHA-256 by repo
  format), so rename to `objectId` instead — calling it `sha256` would be wrong).
- Engine boundary records align to the SPI's `moduleDir` spelling
  (`BuildPipeline.Inputs.dir` → `moduleDir`? No — `Inputs.dir` is the *entry* dir;
  align it with the wire rename in P1.3: `dir`).
- Fix the stale `VersionSelector` comment: leading `=` is the **live** pin syntax
  lockfiles emit today, not "back-compat with older lockfiles". (Verified — do NOT
  delete the branch; the audit's deletion suggestion was wrong.)

---

## Part 3 — Back-compat deletions (the pre-1.0 purge)

All verified DELETE-NOW; none has a consumer in a world where no jk has ever been
publicly released.

### P3.1 — Retire `~/.jk/lib` (the engine-jar transition)
Delete: the "Transitional" libDir copy in `EngineJarFetcher` (+ `deleteStaleVersions`
on libDir), the `lib` fallback branch in `EngineClient.resolveEngineArtifact`, the
`install.sh` lib/ install block, and the `~/.jk/lib` references in `build.gradle.kts`
and `Jk.java`. `versions/<v>/` + CAS is the only layout. This also collapses the
three-copies-of-one-jar write in the fetcher (CAS blob + versioned + flat) to
CAS + one materialization, and `docs/implementation-plan.md` ("lib/ retired") becomes
true instead of aspirational.

### P3.2 — Retire the legacy flat-socket pointer
Delete `EngineServer.legacyCompatPointer` + its retirement logic in `cleanup`, the
flat-socket fallback in `EnginePaths.activeSocket`, and the two `EngineTakeoverTest`
assertions that pin the legacy path. Endpoint-file resolution is the only connect
path. (Pre-generation clients do not exist.)

### P3.3 — CLI alias and flag purge
- `verify-build`→`verify`, `check`→`compile` alias map entries (Jk.java) + their rows
  in docs/aliases.md and docs/requirements.md.
- `--rerun`/`--refresh` flags + `JK_RERUN`/`JK_REFRESH` env aliases (Jk.java,
  JkConfig, JkConfigLoader) — half-wired anyway (the dispatcher rejects them as
  unknown options on ported commands while the prepass honors them).
- `--force-recompile` (ToolRunCommand) — its own comment says global `--force` covers
  it.
- `IdeaCommand` (hidden alias of `jk ide --idea`).
- The `jkx` function unsets in `zsh_deactivate.sh`/`bash_deactivate.sh` + ShellTest
  line (jkx has been a real binary for a while).
- `members = …` workspace synonym for `modules` (JkBuildEditor, JkBuildParser) —
  force-migrate; `modules` only.

### P3.4 — Legacy on-disk migration purge
`Calibration.migrateLegacy` (+ the docs/build-history.md sentence), the
`JkMavenLocalRepo` GC-only remnant + its seeded tests, `JdkListCommand`'s
identifier-only legacy config read, `JkEnv` absolute-path identifier support,
`CleanCommand`'s "legacy pre-layout directories" sweep, `ClasspathFingerprint`'s
`.test-stamp` exclusion.

### P3.5 — Build machinery
- Delete the `installLocalCas` Gradle task (deprecated delegate to `installLocal`).
- Scrub stale plan-label comments ("Phase 2 gap", "Phase 3", "pre-P4 posture",
  "P4/P5/P6 shape") — the behavior ships; the archaeology confuses.
- Relabel comments that mislabel live seams as legacy: `CachePruneScheduler` "Legacy
  path" (it's the live in-process test path), `JavacDriver` "kept for backward
  compatibility" (it's the live ServiceLoader facade).

### P3.6 — Dormant seams: finish or fold
- Incremental Java compile: `IncrementalCompiler`/`IncrementalCompilers.resolve()` is
  a dormant strategy seam with one impl (`FullRebuildCompiler`) and no ServiceLoader
  registration; docs/incremental-java-compile.md itself proposes retiring the
  abstraction. Fold the seam (keep the doc as the design record for when the real
  multi-pass compiler lands). Verdict: fold — an unused indirection is a cost today
  for a feature that will need a different shape anyway.
- `AzureBlobTransport` / virtual-host S3 addressing: remove from the docs transport
  matrix ("planned" tables are where scope hides); track as issues instead.

---

## Part 4 — Engine/cache correctness and waste

### P4.1 — One atomic-write helper, one recursive delete
Five temp-naming schemes (`.put-*.tmp`, `.part`, `.compact`, `.jk-new`,
`.{version}-*`), three move idioms (ATOMIC_MOVE bare / with fallback / plain
REPLACE_EXISTING copy), and **12** reimplementations of `deleteRecursively`. Add
`AtomicWrites.replace(target, bytes|writer)` in client-io (temp in target's parent +
ATOMIC_MOVE + REPLACE_EXISTING fallback) and route every writer through it; collapse
all recursive deletes onto `PathUtil` (post-P2.1 home: `:kernel/support`), keeping one
deliberately-throwing variant named `deleteRecursivelyOrThrow`.

### P4.2 — Stop hashing and reading the same bytes twice
- `EngineJarFetcher`/`SelfCommand.fetchAndMaterialize`: `Hashing.sha256Hex(jar)` then
  `cas.put(jar)` re-hashes the same array. Add `Cas.put(byte[] data, String knownHex)`.
- `JavaIncrementalCompile`: `analyzeOutputs` reads every `.class` file, then `store`
  re-walks and re-reads the same tree to hash it. Hash in `analyzeOutputs` and thread
  the `relPath→hex` map through.
- `Cas.putStream` creates its temp file at the CAS **root** (leaks `.put-*.tmp` into
  the most visible dir on crash); create it in `target.getParent()` like `put`/
  `putFile` (subsumed by P4.1).

### P4.3 — Truth in comments: the CAS copies now
`ActionCache`/`CasPrewriter`/`JavaIncrementalCompile` comments still say "hard-link,
storage cost is zero"; `putFile` copies by design since the inode-aliasing incident.
Fix the comments. Do **not** reintroduce links opportunistically — the restore
direction stays copy-only too; the 76-blob corruption is the memory that justifies the
I/O.

### P4.4 — VersionStore/ledger polish
- Per-version lockfile around `materialize` (two racing materializers can currently
  delete each other's just-completed dir; self-healing but nondeterministic).
- `AccessLedger.touchAll` javadoc claims PIPE_BUF atomicity for writes that can exceed
  PIPE_BUF; fix the comment (readers already tolerate torn lines) or chunk the batch.
- `EngineDelegate` launches the pinned child on the **daemon's** JDK; record the
  child's floor JDK in `versions/<v>/manifest.toml` at materialization time and prefer
  a matching installed JDK, falling back to the daemon's with a logged warning.
- Wrapper template (`jk.sh`): `find "$TMP" -type f ! -name jk.zip | head -1` breaks if
  a release zip ever ships two files — anchor to the expected binary name. (The
  templates are FROZEN — get this right now or live with it forever; regenerating
  wrappers is cheap pre-1.0, so fix the template and bump the emitted copies.)
- `aotCachePath` maps an unreadable jar to the constant `"unreadable-jar"` signature —
  two different broken jars share an AOT key; include the jar path hash.

### P4.5 — Left alone deliberately (reviewed, keep)
The generational election/takeover locking (verified: correctly serialized under the
startup mutex), `Cas.read`'s verify-on-read paranoia, unknown-**event** no-op
tolerance in the adapters, the per-request client watchdog thread (short-lived
process), and `CasPrewriter`'s benign final-pass overlap (idempotent atomic puts; add
a `shutdownNow()` + join for tidiness only if touched anyway).

---

## Part 5 — CLI surface

### P5.1 — One global-option parser
Global flags are parsed twice — `Jk.applyCliOverrides` (prepass, hand-rolled) and
`GlobalOptions.from` (post-dispatch) — with drifted vocabularies (`--no-ansi` only in
the prepass; `--rerun`/`--refresh` prepass-only and dispatcher-rejected). After the
P3.3 alias purge, make the prepass consume exactly `GlobalOptions.globalOpts()` via
the same ArgParser, and have `GlobalOptions.from` read every declared global
(including `no-ansi`). One list, one parser, enforced by the P0.1 duplicate-name
check.

### P5.2 — Exit-code and flag semantics normalization
- "No jk.toml here" returns `Exit.CONFIG` (2) everywhere; today `run`/`install`/
  `tool install` return `Exit.USAGE` (64) for the same condition.
- `--force` keeps exactly one meaning (bypass caches / redo). Rename: import/export
  overwrite → `--overwrite`; `engine stop --force` / `self update --force` →
  `--now` (abandon in-flight jobs immediately).
- `--parallel`/`--no-parallel` collapse to one negatable Opt (framework already
  supports `.negate()`).
- `--precise` (UpdateCommand) errors "not yet implemented" — delete the flag until the
  feature exists.

### P5.3 — Help and structure
- `renderHelp` passes `List.of()` as globals, so no command's `--help` shows
  `--quiet/--offline/-C/--jdk/…` or even `--help` itself. Pass
  `GlobalOptions.globalOpts()` through.
- Demote `RunCommand`/`InstallCommand` from `CliCommand` to plain helpers
  (`ProjectRunner`, `ProjectInstaller`): they are not registered; their
  `run(Invocation)`/`options()`/`parameters()` are dead surface that must be manually
  kept in sync with the registered `ToolRunCommand`/`ToolInstallCommand`. Delete the
  dead members, keep the delegate methods.
- `BuildCommand`'s inline copy of the variant Opts ("keep textually in sync" comment)
  → call `VariantSelection.options()`.
- Hoist the copy-pasted hidden test seams (`--cache-dir`, `--jdks-dir`; CacheCommand
  declares `--cache-dir` six times) into a shared provider attached uniformly;
  `CompileCommand` gains the missing `--jdks-dir`.
- `jk update` (deps) vs `jk self update` vs `jk jdk update`: **keep** — `jk update`
  updating dependencies matches cargo/npm convention; the grouped forms are already
  namespaced. No rename.

---

## Part 6 — Docs and drift

- `docs/implementation-plan.md` "lib/ retired" becomes true (P3.1); engine-versioning
  plan gets a status update for the protocol changes (P1.7 makes protocol-zero
  enforcement real).
- docs/aliases.md, docs/requirements.md, docs/build-history.md rows for deleted
  aliases/migrations (P3.3, P3.4).
- docs/incremental-java-compile.md marked as design-record (P3.6).
- Wrapper docs updated for positional version (P0.1).
- A new `docs/protocol.md` snapshotting the v1 envelope (types, `kind` values, the
  error codes, the null rule, the map encoding, the hello/proto gate) — the freeze
  needs a written contract, not just code.

---

## Execution order

| Phase | Contents | Size | Risk |
|---|---|---|---|
| H1 | P0.1–P0.7 (bugs) | M | Low — each has a focused test |
| H2 | Part 3 purge (P3.1–P3.5) + P4.1 helpers | M | Low — deletions with suite gate |
| H3 | Part 1 protocol v1 (P1.1–P1.10) + docs/protocol.md | L | Medium — touches every adapter; gate with an e2e exchange test per verb family |
| H4 | Part 2 API/SPI (P2.1–P2.5, P2.7) | L | Medium — wide mechanical renames |
| H5 | P2.6 SessionContext threading + P3.6 seam folds | L | Medium — deepest refactor, do last |
| H6 | Part 5 CLI normalization + Part 4 remainder + Part 6 docs | M | Low |

Every phase: full `./gradlew test`, commit, ff-merge. After H3 and H6: full dist +
install + wrapper/self-update/variant behavioral smoke (including a forced-TCP
transport run for P0.2).

Explicitly out of scope (tracked elsewhere): release-pipeline signing + baked key,
sigstore layer, `jk engine status` generation surfacing, Robolectric binary
resources, lint/baseline profiles, solver version interning (BOM soft pins).

---

## Execution log (decisions made while implementing)

### H1

- **P0.1** — `--version` became a **positional** on `jk self update <version>` and
  `jk wrapper <version|latest>` (not a renamed flag). `CommandDispatch.withGlobals`
  now throws on any command/global option-name collision, and a
  `CommandDispatchTest` walks every registered command + subcommand to catch new
  ones at test time.
- **The collision hard-error found six more silently-broken declarations** (each was
  fully shadowed by the global, i.e. already dead code): `explain --verbose/-v`,
  `gradle -C/--directory`, `mvn -C/--directory`, and `new --jdk` were deleted (the
  identically-named global provides the same canonical key, so behavior is
  unchanged); `import/export --force` (overwrite semantics) was renamed
  `--overwrite` and `engine stop --force` / `self update --force` (stop-now
  semantics) renamed `--now` — the P5.2 renames pulled forward into H1 because the
  hard error forced the choice.
- **P0.2 exposed a latent self-drain bug.** Fixing `openClient` to send the auth
  envelope made `EngineTcpTransportTest` (new) fail: at startup `drainDisplaced`
  targets the pre-bind `activeSocket`, which by then is the flat compat pointer
  naming the engine ITSELF — every engine has been sending itself
  `shutdown force=false` at startup (visible as "drained displaced engine" in live
  daemon logs). It survived only by accident: on TCP the raw-token auth failed; on
  Unix the drain connection is already closed so the `bye()` reply throws EPIPE
  *before* `shuttingDown = true` executes. Root fix: `previousActive` is captured
  pre-bind and nulled when nothing was live, plus a `namesSelf()` guard in
  `drainDisplaced` (Unix: realpath identity; TCP: port-content identity) for the
  crashed-generation-reclaim case. `EngineTransport` gained a
  `-Djk.engine.transport=tcp|unix` override so the TCP lane is testable off-Windows.
- **P0.3** — child stderr goes to the engine **log** (not DISCARD): the version-skew
  path is the one that most needs diagnostics preserved. The client→child pump is
  bounded to the relay's lifetime.
- **P0.4** — `jk self materialize <client-bin> <engine-jar>` (hidden) reuses
  `VersionStore.materializeFromFiles`; install.sh's hand-rolled shell materializer
  deleted in favor of calling it through the freshly-installed client.
- **P0.6** — `materialize` also *repairs* the sidecar-without-artifact state
  (re-materializes) instead of early-returning on the sidecar alone.
- **P0.7** — resolution order: `JK_EXE` → `/proc/self/exe` → `ProcessHandle.info()`,
  rejecting `java`/`java.exe`/`javaw.exe`; any other executable name IS jk (the
  running binary is by definition the jk executing the code).

### H2

- **`~/.jk/lib` retired for the engine jar** (fetcher, `resolveEngineArtifact`,
  install.sh, docs). `JkDirs.lib()` itself STAYS — it is also `jk install`'s live
  app-lib dir; only the engine-jar slot was legacy.
- **Flat-socket compat pointer retired.** All lifecycle commands and tests resolve
  via `EnginePaths.activeSocket` (endpoint file); `activeSocket`'s flat return is
  now a never-bound placeholder whose probes fail cleanly ("no engine"), not a
  legacy lane. The stale-socket test now simulates the modern kill-9 leftover (dead
  generation socket + endpoint naming it).
- **`rerun`/`refresh` did NOT blindly collapse into `force`.** The audit called them
  legacy aliases, but the wire doc records a real distinction: rerun = rebuild
  without re-fetching locked artifacts (`jk verify`'s offline-safe lane), force =
  rebuild + refresh. A blind collapse would have made `jk verify` hit the network.
  Landed shape: JkConfig keeps ONE user flag (`force`) plus an internal `rebuild`
  component (`rebuildOr` = force-or-rebuild) that verify sets; the `--rerun`/
  `--refresh`/`JK_RERUN`/`JK_REFRESH` aliases and TOML keys are gone. The wire
  `rerun` field maps to `rebuild` (rename lands with H3).
- **`jk clean` no longer deletes `build/` or `.jk/generated`** ("legacy pre-layout"
  sweep): jk cleans only what jk creates; a hybrid repo's Gradle `build/` is not
  jk's to remove.
- Deleted: `verify-build`/`check` aliases, `--force-recompile`, `IdeaCommand` (its
  320-line e2e test was retargeted at `jk ide --idea`, not deleted), jkx unsets in
  all four deactivate scripts, `members=` synonym, `Calibration.migrateLegacy`,
  `JkMavenLocalRepo` (GC-only remnant), JdkList identifier-only fallback, JkEnv
  absolute-path identifiers, `.test-stamp` exclusion, `installLocalCas`, stale
  picocli/phase-label comments.
- **Bonus consistency fix:** `CacheGc` and `LruEvictor` swept only the dead legacy
  m2 mirror while `CasSweep` also swept named repo stores — all three now share
  `RepoArtifactStore.removeShasFromAll`. Fixing that exposed a latent bug in
  `removeShas`: it deleted files and pruned directories under a still-lazy
  `Files.walk` iterator (NoSuchFileException from the stream) — now collects
  sidecars before deleting.
- `AtomicWrites` helper added (temp-sibling + ATOMIC_MOVE + REPLACE fallback);
  `Cas.putStream` no longer creates temp files at the CAS root (nothing sweeps the
  root; crashed streams littered it). Broad adoption of AtomicWrites across the
  remaining writers lands with H6's Part 4 remainder.
- `deleteRecursively` consolidated onto `PathUtil` (+ new `deleteRecursivelyOrThrow`)
  for kernel modules; `plugins/shrink` keeps a local copy BY DESIGN (workers stay
  dependency-free of kernel modules); `CleanCommand` keeps its stats-counting
  variant.

### H3

- Wire renames landed: `entryDir`→`dir` everywhere, `rerun`→`rebuild` (carrying H2's
  semantic split), cache-clear's `projectRoot`→`dir`. `goal-finish` carries a
  mandatory `kind` discriminator (build/lock/sync/format/git-fetch/publish/import/
  image/tool/script/cache) — the message is self-describing.
- ONE error envelope: `error{code,message}` with codes request-failed / protocol /
  version-skew / shutting-down / auth. `build-error`, `explain-error`,
  `history-error`, AND `shutdown-pending` are gone — the drain refusal rides
  `code=shutting-down`, which also fixes shutdown-pending being dead on the client
  (adapters have one error handler that surfaces every code's message). The
  pre-existing `t="error"` console-line EVENT was renamed `error-line` to free the
  name.
- `withVariant`/`withJvmTuning` string surgery replaced by ONE validated
  `withSession(request, variant, clientEnv, jvm)` attachment used by every hosted
  request — JVM tuning now applies to every verb that forks workers (it silently
  dropped on image/compile/sync/lock/publish/audit/format/tool/script before).
  DIVERGENCE from the plan text: session state attaches at the adapter chokepoints
  via withSession rather than as parameters on every builder — same self-description
  and universality, a fraction of the churn, and the splice is now validated.
- One flat-map encoding (`Ndjson.map`/`strMap`): `env`, `graalHomes`, `params`
  replaced their parallel-array pairs. One null convention (keys always emitted,
  null = not applicable); statusAck stopped omitting http keys.
- Protocol-zero has teeth: server rejects `proto >` its own with
  `error code=version-skew`; client treats a newer `proto` in hello-ack as
  unusable-engine (takeover path). hello gained `purpose` (connect|probe) and
  probes send their REAL version — the takeover-probe/status-probe sentinels are
  gone.
- Framing: `BoundedLineReader` (64 MB line cap) on both sides; client streams get a
  60-minute idle timeout (`JK_STREAM_IDLE_MINUTES`); malformed or unknown REQUESTS
  are answered `error code=protocol` instead of silently dropped (silent drops
  wedged streaming clients); unknown EVENTS remain no-ops (the forward-compat
  seam). TCP auth compares constant-time and refuses with `error code=auth` before
  closing.
- `requestKind` is passed explicitly from dispatch sites (the thread-name munge is
  gone) and the journal/dashboard dir falls back `dir`→`cache` instead of recording
  the literal string "null".
- docs/protocol.md snapshots the v1 contract, the evolution rules, and marks
  `plan-*`/`explain-*` + history/metrics response shapes as NON-CONTRACT
  vocabularies: their merge/relocation (plan P1.10's last two bullets) is
  DEFERRED — they are consumed only by jk's own client/dashboard, the doc
  explicitly exempts them from the freeze, and renderer churn at the tail of this
  phase was judged worse than carrying two internal event families.

### H4

- Core `PluginManifest` (the parsed jk-plugin.toml model) is now `PluginDescriptor`
  (+ `PluginDescriptors` parser, `PluginDescriptorStore`, `PluginDescriptorOps`);
  the plugin-api worker-identity `PluginManifest` — the type 12 workers import —
  keeps the established name. The coin-flip auto-import is gone.
- Deleted from the contract: `BuildPluginContext.run(RunShape)`/`nativeImage
  (NativeShape)` (always-throwing "reserved" hooks) + both shape records — the
  hooks return WITH the feature; `next-store-file` (schema'd, never read);
  `Dependency(module, version, pinnedIgnored)` (a public ctor that ignored its
  argument — ScriptHeaderParser was actually passing `!floating` into it,
  believing it did something); `PluginDeclaration` 4-arg; `JkBuild.Build`'s four
  telescoping ctors (one live 5-arg caller found in the parser, moved to
  canonical); ALL 11 "P2…P6 shape" overloads in PluginDescriptor (zero callers —
  pure archaeology); `Session`'s 9-arg pre-variant ctor.
- `BuildPipeline.Inputs` lost the three overloads that silently defaulted the
  session from `SessionContext.current()` — all 25 call sites now say the ambient
  read OUT LOUD (mostly tests, which legitimately run in-process).
- `PluginConfig`'s fifth value shape (group-vs-reference dual shape) is documented
  on the record; the stranded javadoc fixed. `VersionSelector`'s leading-`=`
  comment now says what it is (the LIVE pin syntax), not "back-compat".
- KEPT deliberately: `JkBuild(project, deps[, repos])` — already documented as
  explicit shortcuts delegating to canonical, used by ~28 tests; not back-compat.
- DEFERRED (H4-residue, most valuable first): evicting `dev.jkbuild.util`/
  `credential`/`image` from the jk-api leaf into a `:support` module (P2.1) and the
  `:model`→`:jk-api` Gradle rename (P2.2) — module-graph surgery touching every
  build file, parked to protect H5/H6's verification budget tonight; the
  `Lockfile`/`Artifact` builder consolidation (P2.5's "real but bounded" tail) and
  the `versionSelector`/`objectId`/`moduleDir` naming pass (P2.7).

### H5

- The dormant incremental-Java seam turned out to be ALREADY RETIRED in code —
  `JavaIncrementalCompile.run` (the doc's recommended option (c)) is live in
  BuildPipeline/TestSupport and the single-pass `IncrementalCompiler` classes no
  longer exist; docs/incremental-java-compile.md got a STATUS note making it a
  design record. Azure Blob is marked not-implemented in the transport matrix
  (the `azblob://` scheme is reserved and errors clearly).
- **The FULL SessionContext eviction was re-scoped to residue** after inventory:
  137 call sites, and the holder is ScopedValue-based — the engine binds a
  per-request scope (`SessionContext.where(session, …)`) so reads are correctly
  request-isolated; the refactor is architectural purity, not a correctness fix.
  A 137-site flag-day at the tail of an overnight run was judged the wrong risk;
  the H4 Inputs change already removed the hidden ambient reads from signatures.

### H6

- CLI: "no jk.toml" is `Exit.CONFIG` everywhere (run/install/tool-install were
  USAGE); `--precise` deleted until implemented; `--parallel`/`--no-parallel`
  collapsed to one negatable flag (build, explain); every command's `--help` now
  shows the Global options section; `GlobalOptions.from` reads `no-ansi` like
  every other declared global (it was prepass-only). Full prepass/dispatcher
  parser unification was NOT done: the prepass must tolerate unknown argv by
  design (it runs before verb resolution); the drift risk is now covered by the
  H1 collision hard-error + aligned reads.
- `RunCommand` and `InstallCommand` are plain delegates (no `CliCommand` surface):
  their dead name/options/parameters/run(Invocation) members — a manual-sync trap
  with the registered `ToolRunCommand`/`ToolInstallCommand` — are deleted, along
  with the unused `joinClasspath`.
- Part-4 remainder: `Cas.put(byte[], knownHex)` (fetch paths hand over the hash
  they computed for verification — no second full hash); every stale "hard-link
  into the CAS" comment now says COPY (the post-incident reality); AccessLedger's
  PIPE_BUF claim corrected (large batches can tear; readers tolerate it);
  `aotCachePath` keys unreadable jars by path (no shared "unreadable-jar" key);
  `VersionStore.materialize` is serialized by a per-version file lock; the
  wrapper template anchors the expected binary name instead of "first file in
  the zip". REJECTED after inspection: threading `analyzeOutputs`' hashes into
  `store` (audit item M1's compile half) — the re-hash inside `store` is
  load-bearing: `putFile(file, hex)` trusts the caller's hash, so hashing in one
  walk and copying in a later one would poison the CAS if an output mutated
  between them.
- Residue inventory (in rough value order): SessionContext threading (137 sites);
  `:support` module split + `:model`→`:jk-api` rename; Lockfile/Artifact builders;
  P2.7 naming pass; shared hidden-opt provider (--cache-dir/--jdks-dir ×25 +
  CompileCommand's missing --jdks-dir); plan-*/explain-* merge + history/metrics
  builders into EngineProtocol (both explicitly non-contract in docs/protocol.md);
  EngineDelegate picking the pinned version's floor JDK from its manifest;
  goal-finish kind assertions in adapter callers.
- **Smoke-found bug (fixed + regression-tested):** `VersionStore.materialize`
  short-circuited on "already materialized" by VERSION alone, so a same-version
  re-install with different bytes (every dev `-SNAPSHOT` rebuild) silently kept
  the stale tree — the smoke ran a NEW client against an OLD-protocol engine and
  wedged. It now compares the manifest's engine sha and REPLACES the tree on
  mismatch (release versions are immutable, so they still fast-path). This is
  also why the H1 self-drain fix initially seemed not to hold in the live log:
  the running engine was the stale jar. The fresh engine starts with zero
  "drained displaced" lines.
- Final smoke (installed dist): scaffold → build → test → run → `--release` →
  lock pins the correct sha → `jk wrapper` emit → build THROUGH the wrapper →
  help surfaces (globals section, `self update [version]` positional) — all
  green. Forced-TCP on the native binary needs the property on both sides of
  the engine spawn (client got it, spawned engine didn't) — the TCP lane stays
  covered by EngineTcpTransportTest/EngineServerTest JVM-side; noted as residue.

### Residue bite 1 — :support split + :jk-api rename (P2.1/P2.2)

- New `:support` module (kernel/support) takes the IO machinery out of the
  contract leaf: PathUtil, Hashing, TreeFingerprint, JkDirs, GitUrl, MinimalXml,
  AtomicWrites. The leaf keeps NO `util` junk-drawer at all: JkThreads +
  ContextPropagator/ContextPropagatingExecutorService moved to
  `dev.jkbuild.run` (they ARE the Goal scheduler's execution seam — lazily
  inert, the shutdown hook only installs when a build actually runs) and
  JkVersion to `dev.jkbuild.model`. MavenSettings (a ~/.m2 reader) moved to
  its only consumer's module, `dev.jkbuild.repo` in :client-io. No split
  packages anywhere. `:support` layers ON the leaf (implementation(:jk-api))
  for the executor seam; the leaf has no edge to support. Wiring mirrors the
  old visibility: :core api-exposes :support exactly as it api-exposes the
  leaf.
- DIVERGENCE from the audit: ImageConfig/PublishablePom STAY in the leaf —
  they are pure data records consumed by worker plugins across the wire
  boundary; parking data records in an IO-utility module would misplace them.
- `:model` is renamed `:jk-api` in Gradle (dir stays kernel/model, matching
  the repo's name≠path convention); all six build-file references updated.
- **Smoke-found latent bug (fixed + 2 regression tests): the cache prune's
  mark-and-sweep ate every freshly-installed worker jar.** `repos/local` is a
  PUBLISH DESTINATION (installLocal / local publish), but the sweep treated it
  as derived cache: a just-published jar is legitimately unreferenced by any
  action/sync manifest, so the first prune after the cadence expired swept its
  blob and the repo GC removed the jar with it (all 12 workers vanished
  mid-smoke). Fix: repos/local sidecars are sweep ROOTS in CacheRoots.collect;
  other repos/<name> stores remain sweepable re-fetchable mirrors.

### Residue bite 2 — cache-bypassing builds no longer write the action cache

Prompted by the user's question about `jk verify` cache waste: the scratch
rebuild's action keys hash absolute (random temp) paths, so every store it made
was a permanent orphan. `storePackaged` already skipped stores on `rebuild`;
the three compile-path stores (KotlinCompile, JavaIncrementalCompile's full
path, the run-tests marker) now follow the same convention — a bypassing run
(`--force`, verify) neither reads nor writes. JavaIncrementalCompile's
INCREMENTAL path keeps storing: it is reachable only via a prior cache record,
i.e. never on a bypassing run. VerifyBuildCommandTest pins "verify leaves the
action-key set untouched".
