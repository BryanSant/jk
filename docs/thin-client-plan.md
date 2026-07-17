# Thin client — the engine owns every byte of TOML reasoning

**Status:** COMPLETE (2026-07-11). **Milestone A LANDED** (commits da02cf14..5be926f9):
the protocol (§2), the exec family (run/dev/install/aot-cache — zero client parsing, all
verified live), the build/native/clean/format/sync peeks, EDIT_REQUEST (add/remove/new
edits engine-side, verified live), and the new/add/explain parent peeks. Two documented
deliberate exceptions: PublishCommand (inline credentials must resolve client-side and
never ride the wire) and JkEnv (the shell hook runs on every prompt — engine round trips
are wrong there; it gets a line scanner in Milestone C).
**Workspace threading LANDED** (fb8fc976): entryBuild never crossed the wire — the engine
re-parses — so the client parse now happens only on the in-process test seam; Native's
workspace cascade reads per-module ProjectInfo summaries (GraalVM pre-resolve stays
client-side). Verified live: workspace scaffold + build + module redirect.
**Deny LANDED** (23ecf7e1): `jk deny` checks engine-side (DENY_CHECK_REQUEST/ACK →
DenyReport) — the one config read where a fail-soft client scan would be dangerous
(misread exotic TOML = silently permissive policy); DenyPolicyParser + LockfileReader
drop off the client path. Verified live: clean / parse-error / violations.
**Worker-JVM tuning LANDED** (0181bdda) — and fixed a real bug: the client resolved
cli > env > `[jvm]` and installed it on its OWN session; it never crossed the wire, so
hosted builds silently dropped all worker tuning. Now the client resolves only its
flag/env layers (PluginTunings.resolveClient), they ride build/test/single-build/native/
install requests (EngineProtocol.withJvmTuning/jvmTuning), and the engine overlays the
project's `[jvm]` table itself at worker-fork time (JvmOptions.tuning → overlayProject).
**Milestone B COMPLETE** (41a480bd..b3a7831b): tree/why run engine-side with marker-tag
styling (Styling.markers() emits flat ⟦kind…⟧ tags; the client substitutes its Theme via
DependencyTree.applyStyling — no node-model refactor needed, DependencyTree untouched);
export maven/gradle via GENERATE_REQUEST → GeneratedFiles payloads (client guards/writes/
prints); verify via per-module ProjectInfo (grew pathDeps + sourcesJarPath/javadocJarPath);
ide via IDE_MODEL_REQUEST → IdeWireModel (model math engine-side in IdeOps; generators
stay client-side consuming IdeModule facts instead of JkBuild).
**Milestone C progress**: TomlScan powers the shell hook, GlobalConfig.nerdfont,
JkConfigLoader's [config] layer, JkEngineConfig (engine spawn opts), JkCacheConfig,
GlobalDefaultJdk's config fallback; TrustedSources and ForgeAuthConfig got dedicated
line scanners (their shapes have open-ended keys). GraalVM reachability is method-level,
so engine-only methods of shared classes may keep tomlj (GlobalConfig.repositories,
PluginTunings.fromToml/overlayProject; JkHttp/JkHistory/ImageConfig/RepositoryToml are
engine-only files).
**Milestone C COMPLETE**: LibraryCatalog.parseTable is a strict line scanner (the catalog
files are jk-owned `name = "group:artifact"` flat scalars; parseLibrariesTable(TomlTable)
stays tomlj for the engine's jk.toml path); PublishCommand reads project facts from
ProjectInfo (new pathDeps field) and the inline repository credential via RepositoriesScan
(line scanner, strict ${ENV} interpolation — secrets never ride the wire). Plus jk deny
engine-side (DENY_CHECK → DenyReport; a fail-soft client scan of user-authored [deny]
would degrade silently PERMISSIVE) and the worker-JVM tuning split (a real bug fix:
hosted builds dropped [jvm]/--jvm-arg entirely — now the client ships flag/env layers on
the request and the engine overlays the project [jvm] at fork time).
**EXIT ASSERT PASSED (2026-07-11)**: the native image carries ZERO org.tomlj.* and zero
ANTLR classes (`strings jk` shows only the bundled catalog's `tomlj = "org.tomlj:tomlj"`
data line); the ANTLR init workaround is deleted; binary shrank 28,511,304 → 27,462,728
bytes (−1.0 MB). Final leaks fixed on the way: WorkspaceLocator's client callers
(PathDisplay/Clean/Add) moved to WorkspaceScan (TomlScan grew string-array support);
NativeCommand's test-seam WorkspaceLoader call routed behind
InProcessEngine.loadWorkspaceModules (static-reachability trap). Verified on the native
binary itself: hook-env with the engine stopped, tree/why/deny over the wire.
**THE THIN CLIENT IS COMPLETE.** Next: [build-plugins-plan.md](./build-plugins-plan.md).
Key reachability fact (handled): the in-process test seams parse behind the
reflectively-loaded InProcessEngine interface (`parseBuild(Path)`), keeping tomlj out of
the native image's static reachable set.
**Companions:** [engine.md](./engine.md) (process model), [build-plugins-plan.md](./build-plugins-plan.md)
(depends on this landing first — plugin-owned tables make client-side parsing impossible to do
correctly).

## 1. Why

The native `jk` client today links tomlj + an ANTLR runtime (with a native-image classpath
workaround already needed in `cli/build.gradle.kts`) and parses `jk.toml`/`jk.lock` in **18
command files** to make decisions: run classpaths, install gates, native eligibility, workspace
redirects. Every one of those branches is a copy of engine reasoning that goes stale the moment
`~/.jk/lib/jk-engine-*.jar` updates independently of the installed binary — a bug class we hit
repeatedly in practice (stale native gate, stale run exec assembly, stale install validation).

Target: **`jk` = JDK installer + engine launcher + TTY owner + thin protocol client.** The
engine is the only component that ever interprets `jk.toml`, `jk.lock`, or catalogs. What
legitimately stays in the client:

- Engine lifecycle (discover/spawn/handshake) and JDK provisioning (jdks.json is already a
  line scanner, not TOML).
- TTY ownership: rendering, wizards, and **process exec** — app processes must be forked by
  the client so they inherit the terminal. The client executes; the engine decides.
- `~/.jk/config.toml` bootstrap reads (theme, engine spawn options) — needed before an engine
  exists; moves off tomlj onto a scanner for our own flat format (Milestone C).
- The URL trust gate (`trusted-sources.toml`) — deliberately evaluated client-side on the
  exact string the user typed, before anything is asked to fetch.
- **The `jk activate` shell hook — a hard latency contract.** `jk hook-env` runs on every
  prompt; engine calls (let alone spawning a not-yet-running engine) are an explicit
  anti-goal there. The hook resolves JAVA_HOME/PATH via `TomlScan` — an early-stopping
  line scanner reading only `[project] jdk/java`, `[native] graal`, and the lockfile's
  header `jdk` — zero engine round trips, zero tomlj parses. Verified: the hook emits
  correct env with the engine fully stopped, and does not start one. Remaining on this
  path: `GlobalDefaultJdk`'s config-file fallback (symlink fast path already avoids it
  per-prompt) converts with the rest of the config family in Milestone C.

## 2. The two new protocol shapes

Both are synchronous inline request/ack pairs (the `FORECAST_REQUEST` pattern — flat scalars +
string lists, Jsonl discipline).

### 2.1 `PROJECT_INFO_REQUEST` → `PROJECT_INFO_ACK`

`{dir}` in; one parsed-project summary out. Every client-side "peek" consumes this instead of
`JkBuildParser.parse`:

```
exists, error                       — no jk.toml / parse failure (message ready to print)
group, name, version, coord        — display + target strings
jdk, javaRelease, kotlin, kotlinVersion, layoutSimple
isWorkspaceRoot, workspaceRootDir  — "" when standalone; set when dir is/belongs to a workspace
moduleDirs[]                       — when workspace root
isApplication, mainClassDeclared, shadowJar
nativeMode                         — DISABLED | SUPPORTED | ALWAYS
springBoot, springBootVersion      — (post-plugin: execMode/capabilities instead)
formatStyle, formatJava, formatKotlin, formatOptimizeImports
hasLock, lockJdk                   — jk env / IDE flows
```

### 2.2 `EXEC_PLAN_REQUEST` → `EXEC_PLAN_ACK`

`{dir, kind, mainOverride?, binName?, appArgs?}` in; a complete execution plan out. The client
never assembles a classpath again:

- `kind=run`: `argv[]` (absolute java, flags, `-cp`, main — or `-jar`, or the native binary),
  `workingDir`, `display` (the chip string). Engine picks the artifact preference
  (native > shadow > boot/classes > jar) — the logic that lives in `RunCommand` today.
- `kind=dev`: run-shaped `argv[]` for the classes-dir + RUN classpath, plus `hotReload`
  (devtools present or auto-injected), `devtoolsInjected`, `watchRoots[]`.
- `kind=install`: `links[]` (`src → dest` pairs into `~/.jk/lib`), `launcherPath`,
  `launcherScript` (full content), or `nativeBin` for ALWAYS-native projects. Validation
  errors come back as `error` — the gates live engine-side.
- `kind=aot-cache`: `isBoot`, `mainJar`, `tier` (aot|cds), `mainClass`, `libs[]`
  (`name → path`), `trainingArgs[]` — feeds `AotCachePackage`'s layout/training without any
  client-side lock/manifest reasoning.

Plans are computed fresh per request (they read the post-build artifacts), so a client of any
age gets current-engine behavior — the skew class dies by construction.

## 3. Migration inventory (all 18 files + the config family)

**Milestone A — decisions out of the client (the skew killers):**

| File | Today | Becomes |
|---|---|---|
| RunCommand | parses project + lock, assembles exec | `EXEC_PLAN(run)`; client forks argv |
| DevCommand | project + lock + devtools fetch | `EXEC_PLAN(dev)` per (re)start; watch loop unchanged |
| InstallCommand (app path) | gates + layout + lock classpath | `EXEC_PLAN(install)`; client applies links/launcher |
| AotCachePackage | project + lock + layout | `EXEC_PLAN(aot-cache)`; extract/train stays client (forks) |
| BuildCommand | workspace peek + target string | `PROJECT_INFO` |
| NativeCommand | eligibility gates, module walk | `PROJECT_INFO` |
| CleanCommand, SyncCommand, PublishCommand, FormatCommand | pre-flight peeks | `PROJECT_INFO` |
| AddCommand | parse + **edit** jk.toml + catalog | engine-hosted `ADD_REQUEST` (edit happens engine-side) |
| NewCommand | parent-info peek + root **edit** (module registration) | `PROJECT_INFO` + `REGISTER_MODULE_REQUEST` |

**Milestone B — diagnostics/generators (need response vocabularies, lower risk):**

| File | Notes |
|---|---|
| TreeCommand / WhyCommand | engine returns the rendered graph lines (or a typed node list) |
| ExplainCommand | `EXPLAIN_REQUEST` already exists — remove the client-parse fallback path |
| JkEnv | folds into `PROJECT_INFO` (`lockJdk`) |
| ExportSupport / IdeSupport | full-model generators — engine-hosted requests returning generated file payloads |
| VerifyBuildCommand | engine-hosted (it already rebuilds via the engine; comparison moves too) |
| ToolTargets catalog names | classification folds into `TOOL_RESOLVE_REQUEST` (URL trust gate stays client) |
| NewJkBuildRenderer catalog lookups | `LIBRARY_LOOKUP` request or render explicit coords |

**Milestone C — tomlj eviction from the native image:**

- The config family (`GlobalConfig`, `JkConfigLoader`, `JkEngineConfig`, `JkHttpConfig`,
  `JkCacheConfig`, `JkHistoryConfig`, `PluginTunings`, `TrustedSources`, `RepositoryToml`,
  `ForgeAuthConfig`, `DenyPolicyParser`) moves onto a small hand-rolled `ConfigToml` scanner
  (sections, scalars, string arrays — formats we own; anything richer belongs engine-side).
  The engine keeps tomlj for `jk.toml`/`jk.lock`/catalogs (user-authored full TOML).
- Then: assert no native-reachable path touches `org.tomlj`, drop the ANTLR native-image
  workaround, and record the binary-size delta.
- Exit test: `strings jk | grep -c tomlj == 0` (and the image builds without the workaround).

## 4. Compatibility & risks

- **In-process/test paths**: `jk.test.noEngine` and the JVM dist link everything; only the
  native image slims. `InProcessEngine` keeps working — the same ProjectInfo/ExecPlan
  facades run in-process there.
- **Engine-down UX** becomes the single failure point for every verb; the engine-start error
  path must stay excellent (it already prints the log location + training notice).
- **Protocol growth**: acks are flat + forward-compatible (unknown fields ignored), matching
  the existing wire discipline.
- **Ordering with plugins**: this lands first; build-plugins P1 (table routing) then changes
  *engine* internals only — the client never sees plugin tables at all.
