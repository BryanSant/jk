# Slim-client inventory (Stage 1)

Status: **factual inventory, 2026-07-07** — Stage 1 of [slim-client.md](./slim-client.md). This is
the cut list: what every CLI verb actually drives in-process today, what the `jdeps` evidence says
the `:cli` jar links, where the JDK/toolchain flow's real dependencies end, and the module moves
Stage 5 needs. No code changed for this document.

Method: every command class under `cli/src/main/java/dev/jkbuild/command/` was read and traced one
level into its helpers; `jdeps 25.0.3 -verbose:class` was run on `cli/build/libs/cli.jar` against
the kernel module jars (`./gradlew jar`, jars under `*/build/libs/`). Package→module key used
throughout: `dev.jkbuild.{model,run,util,credential,publish,image}` = **:model** ·
`{config,lock,layout,library,deny,audit}` = **:core** · `{http,cache,repo,forge}` = **:io** ·
`{resolver,resolver.pubgrub}` = **:resolver** · `{jdk,tool,compat,discovery,gradle,mvn,kotlin,script}` =
**:toolchain** · `{runtime,worker,task,test,compile,git,engine}` = **:engine**.

Classification vocabulary (end-state per slim-client.md):

- **CLIENT-ONLY** — stays in the thin client (build-file reader, JDK/toolchain flow, display,
  local file edits, credential files, process exec).
- **ENGINE-HOSTED** — needs wire-protocol vocabulary; the engine does the work in-process.
- **WORKER-DELEGATED** — the engine forks a plugin worker (the CLI forks it directly today).
- **ALREADY-HOSTED** — goes through `EngineClient` since the engine era (build/test/explain/verify).

## 1. Per-verb classification

42 top-level verbs registered in `CommandDispatch.COMMANDS`. Grouped by family; sizes are Stage-3
migration effort (S/M/L), `—` = no migration work.

### 1.1 Build family

| Verb | Today | In-process kernel today | End-state | Size |
|---|---|---|---|---|
| `build` | Workspace or single-project build | `EngineClient.buildWorkspace` (BuildCommand.java:385,491); in-process `BuildService.buildWorkspace` only under `engineDisabledForTests`. Residue: `BuildService.ensureWorkspaceLockFresh` called in-process (BuildCommand.java:194) and `JvmOptions.planAndApply` (BuildCommand.java:213) | **ALREADY-HOSTED** | — |
| `test` | Single project's compile+test goal | `EngineClient.runTest` (TestCommand.java:160); in-process `BuildPipeline.coreBuilder` fallback for tests only | **ALREADY-HOSTED** | — |
| `explain` | Dry-run build forecast | `EngineClient.explain` (ExplainCommand.java:98) for the plan; **ETA still computed client-side** via `BuildPipeline.coreBuilder`/`appendDeclaredTails` (ExplainCommand.java:150–151) — the known gap from docs/engine.md | **ALREADY-HOSTED** (plan); ETA residue must move or the client keeps `EffortWeights`/`Calibration` | S |
| `verify-build` | Rebuild + compare artifact hashes | `EngineClient.buildWorkspace` (VerifyBuildCommand.java:259); local hash-diff (`JkDiff`) of outputs | **ALREADY-HOSTED** (build); the compare is client-side and fine | — |
| `compile` | Compile-only run of the shared pipeline | `BuildPipeline.coreBuilder` **in-process** (CompileCommand.java:79) — not engine-hosted today | **ENGINE-HOSTED** — the single-goal vocabulary (`jk test`'s) fits as-is | S |
| `run` | Build, then exec the artifact / run a script | Build part: `BuildPipeline.coreBuilder`+`appendDeclaredTails` in-process (RunCommand.java:126–139); exec: `ProcessBuilder.inheritIO` (RunCommand.java:180). Script mode (`ScriptRunner`): in-process `Cas`, `Http`, `RepoGroup` dep fetches + `CompileToolchain.resolveJavaHome/resolveKotlinHome` | Split: **ENGINE-HOSTED** build (single-goal events) + **CLIENT-ONLY** exec (foreground process, Ctrl-C, exit code). Script-dep resolution → engine | M |
| `install` | Build+install current project / Maven coord / git URL (clone, build, install to `~/.jk/bin`) | `BuildPipeline` in-process incl. `BuildPipeline.nativePhase`; `GitSource`/`GitRefSpec` materialization; `Cas`+`Http` fetches; `CompileToolchain.runningJavaHome` (InstallCommand.java:272–294,418–442) | **ENGINE-HOSTED** (build/clone/fetch); final `~/.jk/bin` link + launcher write can stay client. Git-source materialization is a named Stage-3 heavy verb | L |
| `native` | Full pipeline + native-image tail | `BuildPipeline.coreBuilder` + `BuildPipeline.nativePhase` in-process (NativeCommand.java:253–348); `NativeImageDriver` (:toolchain) execs `native-image` as a child process; `GraalResolver` (cli) | **ENGINE-HOSTED** (the native-image child process is forked engine-side, same as workers) | M |
| `clean` | Delete `target/` trees; `--cache` runs a cache GC | Local recursive delete (:core parsers for module list); `--cache`: `dev.jkbuild.task.CacheGc` (:engine) **in-process** | **CLIENT-ONLY** for `target/`; the `--cache` GC → **ENGINE-HOSTED** (fold into the cache-prune vocabulary) | S |
| `image` | OCI image build (tarball / daemon / registry push) | `BuildPipeline` in-process; `dev.jkbuild.task.ActionCache`+`ActionKey`+`ClasspathFingerprint` in-process; **forks `WorkerJar.IMAGE_BUILDER` via `new WorkerClient("##JKIM:")` (ImageCommand.java:362)** | **WORKER-DELEGATED** | M |
| `format` | Format Java/Kotlin sources | **Resolves formatter jars in-process**: `ToolResolver.mavenCentral(new Http(), cas)` → :resolver + :io (FormatCommand.java:159,198); **forks `WorkerJar.FORMATTER` via `new WorkerClient("##JKFMT:")` (FormatCommand.java:244,299)** | **WORKER-DELEGATED** (resolve+fork engine-side; file list + diff rendering client-side) | M |
| `publish` | Publish artifacts to a repository | `RepoCredentialResolver` (:io) reads local credentials; `CompileToolchain.runningJavaHome`; **forks `WorkerJar.PUBLISHER` via `new WorkerClient("##JKPU:")` (PublishCommand.java:237)**; `Ndjson` event relay | **WORKER-DELEGATED** | M |
| `audit` | Dependency vulnerability audit | Reads `jk.lock`; **forks `WorkerJar.AUDITOR` via `new WorkerClient("##JKAU:")` (AuditCommand.java:172)**, jar located via `Cas` | **WORKER-DELEGATED** (a named Stage-3 heavy verb) | S–M |
| `engine start/status/stop` | Engine lifecycle control | `EngineClient.handshake/ensureRunning/status/ping/stop` only | **CLIENT-ONLY** (must work engine-less by definition) | — |

### 1.2 Resolver family

| Verb | Today | In-process kernel today | End-state | Size |
|---|---|---|---|---|
| `lock` | PubGrub-resolve declared deps, write `jk.lock` (workspace cascade) | `LockOrchestrator.lock/lockWithSources` + `pubgrub.*` (:resolver); `GitSourceResolution.prepare/stamp`, `RepoGroupBuilder`, `CompileToolchain.resolveJavaHome` (:engine); `RepoGroup.tryFetchArtifact/availableVersions`, `LibraryRegistryClient`+`Http` catalog refresh, `Cas` writes (:io) | **ENGINE-HOSTED** — the flagship migration | L |
| `sync` | Bring CAS + toolchain in line with `jk.lock` | `CacheSync.sync/syncSources` (:resolver) with `Http`+`Cas`; `LockFlow`/`AutoLock` in-process re-resolve when stale (:engine); `JdkEnsure.ensure` (JDK flow!); `JkWorkerSync.ensureInCas`; `SyncManifest`; spawns detached `jk cache prune --background` via `CachePruneScheduler` | **ENGINE-HOSTED** — with the `ensure-jdk` phase split out to the client (JDK flow stays client-side per directive); the prune spawn becomes an engine-internal idle job | L |
| `update` | Re-resolve fresh, overwrite `jk.lock` (`--git` splice) | Same pipeline as `lock` (`LockOrchestrator`, `GitSourceResolution`, `Cas`) | **ENGINE-HOSTED** — rides `lock`'s vocabulary + `gitOnly`/`precise` request fields | M (S after lock) |
| `add` | Edit `jk.toml` to add a dep; `--ping` checks availability | `JkBuildEditor` (:core, text edit); `LibraryCatalog` (:core, local); file-dep: `Hashing.sha256Hex` + `Cas.putByLink` (:io) + `JarManifest` (:toolchain); `--ping`: one `Http.get` | **CLIENT-ONLY** (flag: `Cas.putByLink` is a client-side CAS write — benign, content-addressed, but noted) | S |
| `remove` | Remove a dep from `jk.toml` | `JkBuildEditor` (:core) only | **CLIENT-ONLY** | — |
| `tree` | Render resolved dep tree, offline | `DependencyTree.render` (**:resolver** — offline lockfile walk); `ClasspathResolver.RUNTIME` constant (**:engine**); `JkBuildParser`+`LockfileReader` (:core) | **CLIENT-ONLY** — needs `DependencyTree` + the scope constant extracted out of :resolver/:engine | S |
| `why` | Print dependency paths to an artifact, offline | `Provenance.pathsTo` (**:resolver**, offline); :core parsers | **CLIENT-ONLY** — needs `Provenance` extracted | S |
| `deny` | Apply license/source/yanked policy to `jk.lock` | `DenyPolicyParser` + `deny.PolicyChecker` (:core), offline | **CLIENT-ONLY** | — |
| `library list/search/update` | Short-name catalog: print / search / refresh | `LibraryCatalog` (:core); search also scans `RepoArtifactStore.allModules` (:io) + `Versions.compare` (:resolver); update does one conditional `Http` GET via `LibraryRegistryClient` (:io) | **CLIENT-ONLY** — `Versions` + `RepoArtifactStore` need rehoming into the client slice | S |
| `cache dir/info/search` | Print cache dir / disk usage / scan cached modules | Local fs walks; `JkDirs`; info reads `CachePruneScheduler.LAST_PRUNED_FILE` (:engine constant); search: `RepoArtifactStore` + `Versions` | **CLIENT-ONLY** (rehome the stamp-file constant; note: client reads engine-owned cache dirs, read-only) | S |
| `cache prune` | Expire action cache, GC logs/stamps/tmp, CAS sweep + LRU eviction; `--background` self-spawn | `dev.jkbuild.task.{RunLogGc,FormatStampGc,TmpGc,CasSweep,CacheRoots,LruEvictor,AccessLedger}`, `runtime.PhaseTimings` (:engine) in-process; `Cas` (:io) | **ENGINE-HOSTED** — mutates caches the engine owns; must coordinate with in-flight builds. `--background` becomes an engine idle job | M |
| `cache purge` | Confirm, then delete the whole cache | `Confirm` prompt (client) + recursive delete | **ENGINE-HOSTED** delete, client-side confirm (or client deletes after `engine stop`) — flagged ambiguous | S |
| `auth login/logout/status/token` | Forge (GitHub/GitLab) token management; login = OAuth device flow | `ForgeAuth`/`TokenStore`/`DeviceFlow`/`GitForgeDetector`/`CliTokenProbe` (:io forge pkg) + `Http`; terminal + browser interaction | **CLIENT-ONLY** (deliberately: stdin, browser, terminal) | — |
| `repo login/logout` | Store/delete repository credentials | `RepoCredentialStore` (:io), `RepoCredential` (:model); no network | **CLIENT-ONLY** | — |

### 1.3 Toolchain family (the client's keep, per directive)

| Verb | Today | In-process kernel today | End-state | Size |
|---|---|---|---|---|
| `jdk default/graal/pin/home/update-shell` | Pointer-file + rc-file edits | `JdkRegistry`, `GlobalDefaultJdk`, `JdkSelector`, `JdkResolver`, `JkDirs` | **CLIENT-ONLY** | — |
| `jdk list` | List installed (`--all` available) JDKs | `JdkRegistry`, `ActiveJavac`, `IntellijJdkDir`; `--all`: `JdkCatalogClient` (`Http` feed fetch); one `Versions.compare` call (**:resolver**, JdkListCommand.java:256) | **CLIENT-ONLY** — rehome `Versions.compare` | S |
| `jdk install` | Wizard + non-interactive JDK install | **`dev.jkbuild.runtime.JdkService` + `JdkInstallListener` — physically in :engine** — driving `JdkCatalogClient`, `JdkSelector`, `JdkInstaller` (download, SHA-256, extract), `JdkRegistry` | **CLIENT-ONLY** — `JdkService` must be rehomed out of :engine | M |
| `jdk ensure/update/uninstall` | Resolve-or-install / feed update / remove | `JdkRegistry`, `JdkSelector`, `JdkCatalogClient`+`Http`, `JdkInstaller`, `StableJdkPointer`, `JdkGarbage`, `JdkToolUninstaller` | **CLIENT-ONLY** | — |
| `activate`/`deactivate`/`shell`/`hook-env` | Shell integration; per-`cd` env sync | `GlobalConfig` (:core); `JkEnv` → `JdkRegistry`, `GlobalDefaultJdk`, **`LockfileReader` (:core)** (reads `jk.lock`'s jdk id, fail-soft); shell emitters are cli-local | **CLIENT-ONLY** (latency-critical; must work engine-less) | — |
| `tool dir/list/uninstall` | Tool-shim root / list / delete | `JkDirs` file ops | **CLIENT-ONLY** | — |
| `tool install/run` | Install/run a CLI tool from a Maven coordinate | **`ToolResolver` → `NaiveResolver`/`Resolver`/`Resolution` (:resolver), `MavenRepo`/`RepoGroup`/`EffectivePomBuilder` (:io), `Cas` writes, `Http`**; `CompileToolchain.runningJavaHome` (:engine); `ToolLauncher`/`ToolEnv` shim + exec | Split: **ENGINE-HOSTED** resolve+fetch; **CLIENT-ONLY** shim write + foreground exec. The one jdk/tool-family flow that is *not* plausibly client-only as coded | M |
| `doctor` | Diagnose/repair discovered mvn/gradle/kotlin installs | `compat.BuildTool`/`InstalledTool`, `discovery.SymlinkProvisioner` (:toolchain compat slice) | **CLIENT-ONLY** defensible (must diagnose engine-down states) — but the only jdk-family verb dragging `compat/`+`discovery/` | S |

### 1.4 Import/export/scaffold family

| Verb | Today | In-process kernel today | End-state | Size |
|---|---|---|---|---|
| `import` | Convert a Maven/Gradle build to `jk.toml` | **Forks compat-bridge directly**: `WorkerJar.COMPAT_BRIDGE.locate(new Cas(cache))` (ImportCommand.java:114), `new WorkerClient("##JKCMP:")` (ImportCommand.java:126); `CompileToolchain.runningJavaHome` + `JvmOptions.javaCommand` (:engine); `Ndjson` relay. `GradleImporter`/`PomImporter` run inside the worker, not the CLI | **WORKER-DELEGATED** | S–M |
| `mvn` / `gradle` | Passthrough to a provisioned Maven/Gradle | Provision: same `WorkerJar.COMPAT_BRIDGE` fork (MvnCommand.java:103,124; GradleCommand.java:70 delegates); then `ProcessBuilder.inheritIO` exec of `bin/mvn` with `JdkResolver.forProject` + `PassthroughEnv` (:toolchain) setting `JAVA_HOME` | Split: **WORKER-DELEGATED** provisioning (one-shot request → `{bin, version, source}` result, no event stream) + **CLIENT-ONLY** exec | S |
| `export gradle` / `export maven` | Translate `jk.toml`+`jk.lock` into Gradle/Maven build files | `GradleExporter` / `PomExporter` (:toolchain compat slice); `ExportSupport.load` → :core parsers + `CompileSupport.isSimpleLayout` (**:engine**, ExportSupport.java:64). Offline, no fetches | **ENGINE-HOSTED** (client-only *possible* but drags the compat exporters onto the client — flagged ambiguous; wrote/note burst fits) | S |
| `export idea` | Delegate to `jk idea` | `private final IdeaCommand delegate` | see `ide` | — |
| `ide` / `idea` / `vscode` | Generate IntelliJ/VS Code project files | **Hidden heavy hitter**: `IdeSupport.build` runs `new CacheSync(cas, new Http()).sync(lock, …)` (IdeSupport.java:274 — a `jk sync` equivalent), `RepoArtifactResolver.locateOrMaterialize` (IdeSupport.java:294,302), `WorkspaceClasspath` (:core), `JdkRegistry`/`StableJdkPointer`/`IntellijJdkTable` (:toolchain jdk slice) | Split: **ENGINE-HOSTED** model computation (sync + classpath → serialized `IdeModel` burst); **CLIENT-ONLY** file emission + `IntellijSdkRegistrar` (writes user-home IDE config; depends on the client-resident JDK flow) | L |
| `new` / `init` | Scaffold a project/module (wizard); `init` = `new` pinned to `.` | `JdkCatalogClient().fetch()` (NewCommand.java:792, best-effort feed GET); `JdkInstaller` when the wizard installs a JDK (NewCommand.java:833–846); `JdkRegistry` discovery; `LibraryCatalog` (bundled resource); `JkBuildEditor`. No resolver, no CAS, no worker | **CLIENT-ONLY** — the existence proof of the client slice: HTTP + JSON + TOML + JDK installer + TUI | — |

### 1.5 Direct plugin-worker forks from the CLI today (the `dev.jkbuild.worker.*` cut)

Yes — six verbs fork workers directly from the client process:

| Verb | Worker jar | Call site |
|---|---|---|
| `image` | `WorkerJar.IMAGE_BUILDER` | `new WorkerClient("##JKIM:")` ImageCommand.java:362 |
| `format` | `WorkerJar.FORMATTER` | `new WorkerClient("##JKFMT:")` FormatCommand.java:244,299 |
| `publish` | `WorkerJar.PUBLISHER` | `new WorkerClient("##JKPU:")` PublishCommand.java:237 |
| `audit` | `WorkerJar.AUDITOR` | `new WorkerClient("##JKAU:")` AuditCommand.java:172 |
| `import` | `WorkerJar.COMPAT_BRIDGE` | `new WorkerClient("##JKCMP:")` ImportCommand.java:126 |
| `mvn`/`gradle` | `WorkerJar.COMPAT_BRIDGE` | `new WorkerClient("##JKCMP:")` MvnCommand.java:124 |

Moving these forks engine-side removes `dev.jkbuild.worker.WorkerClient`/`WorkerJar`/`JvmOptions`
(and the `CompileToolchain.runningJavaHome` JVM-location helper) from the client. The
`WorkerJarNotFoundException` handling in `CommandDispatch` (CommandDispatch.java:201) becomes a
structured engine error. The workers' NDJSON event streams relay 1:1 over the engine protocol —
the same discriminated-envelope style, so this is vocabulary plumbing, not redesign.

> **Status: landed (Wave 2, 2026-07-07).** All six forks now happen engine-side, through shared
> `dev.jkbuild.runtime.{Audit,Format,Publish,Image,Compat}Goals` factories that the commands'
> test-only in-process path (`jk.test.noEngine`) also builds — so the table above now describes the
> in-process *test* path only. The `mvn`/`gradle` split resolved exactly as classified: the
> compat-bridge *provisioning* fork is hosted (one-shot `provision-request` →
> `{bin, version, source}` result, no event stream), while the passthrough *exec* of the
> provisioned tool stays client-side with inherited stdio — hosting a foreign build's interactive
> TTY run would be wrong; hosting its download is not. `jk publish`'s env/keychain credential +
> GPG-passphrase resolution stays client-side and rides the request (see `docs/engine.md`).
> `CommandDispatch`'s `WorkerJarNotFoundException` rendering is retained for the in-process path;
> hosted, a missing worker jar surfaces as the engine's structured error text (same side-load
> instructions).

> **Status: Wave 3 landed (2026-07-08).** The in-process `BuildPipeline` stragglers are hosted:
> `compile` (single-goal shape, shared `CompileGoals`), `run`'s build half (rides the existing
> `single-build-request`; the exec stays client-side), `native` (the whole serial cascade — one
> `native-request` speaking `build-request`'s workspace vocabulary, `native-image` forked
> engine-side, exit codes engine-computed via `NativeGoals`; GraalVM resolution/consent pre-flights
> client-side and rides the request), and `install`'s project/git modes (`InstallGoals`: build +
> cache-install hosted, plus a `git-fetch-request` for the clone; the `~/.jk/bin`/`libexec`
> "make install" half stays client-side). `install`'s Maven-coord mode still resolves via
> `ToolResolver` in-process — deferred to Wave 4 with `tool install/run`, whose stack it is. The
> §1.1 `explain` ETA residue is closed: `BuildService.estimateEtaMillis` runs engine-side, fed by
> eta fields on `explain-request`, emitted as an `eta` event. `cache prune/purge` stay in Wave 4:
> hosting the foreground mutation alone wouldn't be the correctness fix — CAS/ActionCache have no
> cross-process locks at all (only prunes mutually exclude via `.prune.lock`, and only on the
> `--background` path), and the engine runs pipelines concurrently, so a hosted prune would still
> race in-flight builds; the real fix is the Wave-4 engine-internal idle job that quiesces
> pipelines first.

## 2. jdeps evidence

`jdeps -verbose:class -cp <model,core,io,resolver,toolchain,engine,plugin-api jars> cli/build/libs/cli.jar`
(jdeps 25.0.3). Raw per-package tables in Appendix A.

Module-level summary — distinct kernel classes reached directly from `:cli` classes (vs the
module's total class count, inner classes included):

| Module jar | Distinct classes reached | Total in jar | CLI source classes touching it |
|---|---|---|---|
| engine.jar | 89 | 181 | 34 |
| model.jar | 58 | 89 | 104 |
| toolchain.jar | 54 | 102 | 39 |
| core.jar | 35 | 50 | 56 |
| io.jar | 32 | 65 | 32 |
| resolver.jar | 19 | 55 | 10 |
| plugin-api.jar | 1 (`Ndjson`) | 5 | 10 |

Readings:

- **`:model` and `:core` are the client's true currency** — 104 and 56 CLI classes touch them;
  the heaviest single packages are `dev.jkbuild.model.command` (the CliCommand model itself) and
  `dev.jkbuild.run` (`Goal`/`Phase`/`GoalListener` — the rendering event vocabulary). These stay.
- **`:engine` fan-in is broad but shallow**: 89 classes across `runtime` (42 — `BuildPipeline`,
  `BuildService`, `CompileToolchain`, `JdkService`…), `task` (20 — `ActionCache`, cache-GC suite),
  `worker` (6), `compile` (11 — `ClasspathResolver` and friends). Almost all of it disappears with
  the verb migrations in §1; the survivors needing rehoming are `JdkService`/`JdkInstallListener`
  and the `CompileToolchain.runningJavaHome/resolveJavaHome` statics.
- **`:resolver` is already narrow**: only 10 CLI classes touch it, 19 classes reached — `LockOrchestrator`
  and PubGrub types (lock/sync/update — leaving), `ToolResolver`'s stack (tool install/format —
  leaving), and the offline walkers `DependencyTree`/`Provenance`/`Versions` (staying, so they must
  move out of :resolver).
- **`:io` splits cleanly**: the client-retained slice is `http` (3 classes), `forge` (auth), and
  the credential/catalog readers; the `repo`+`cache` machinery (18 + CAS) leaves with the verbs
  that drive it.

Chokepoints — CLI classes with the widest kernel fan-out (distinct kernel classes referenced;
full list in Appendix A.3):

```
67 dev.jkbuild.command.SyncCommand        45 dev.jkbuild.command.ImageCommand
66 dev.jkbuild.command.LockCommand        39 dev.jkbuild.command.NewCommand
63 dev.jkbuild.command.InstallCommand     37 dev.jkbuild.command.VerifyBuildCommand
62 dev.jkbuild.command.BuildCommand       37 dev.jkbuild.command.UpdateCommand
59 dev.jkbuild.command.CacheCommand       37 dev.jkbuild.command.JdkInstallCommand
55 dev.jkbuild.command.ScriptRunner       36 dev.jkbuild.command.PublishCommand
```

The top of the list *is* the Stage-3 migration list: sync/lock/install/cache — plus `BuildCommand`,
whose fan-out is mostly the already-hosted path's residue (`BuildService` statics, `JvmOptions`,
listener types) and will collapse to the wire-adapter types.

## 3. The JDK/toolchain-flow reality check

`kernel/toolchain`'s `jk.toml` declares `jk-core`, `jk-io`, `jk-resolver`. The genuinely-used
subset, per package (import-level trace of the classes the JDK flow executes):

| toolchain pkg | :io pulls | :core pulls | :model pulls | :resolver pulls |
|---|---|---|---|---|
| `jdk/` | **`http.Http` only** (`JdkCatalogClient` feed fetch, `JdkInstaller` archive download; `OfflineException` is same-package in :io) | `config.TomlValues` (`GlobalDefaultJdk` pointer file) | `util.Hashing` (SHA-256 verify), `JkDirs`, `JkThreads`, `PathUtil` | **none** |
| `tool/` | `cache.Cas`, `http.Http`, `repo.MavenRepo`/`RepoGroup`/`EffectivePomBuilder` — **all via `ToolResolver` only** | none | `Coordinate`/`Dependency`/`VersionSelector` | **`NaiveResolver`/`Resolver`/`Resolution` — via `ToolResolver` only** |
| `compat/` | `http.Http` (tool-dist downloads) | none | `Hashing`, `PathUtil` | none |
| `discovery/`, `gradle/`, `mvn/`, `kotlin/`, `script/` | `repo.Pom`/`PomParser` (mvn only) | none | model types | none |

Facts that make the client slice small:

- **Archive extraction is self-contained**: `MinimalTar` lives in `jdk/` itself; zip/gzip is
  `java.util.zip`. Nothing from :io.
- **Feed JSON parsing is self-contained**: `JdkCatalogClient.parse` hand-parses the JetBrains
  `jdks.json`. No shared JSON dependency.
- **The JDK flow's entire :io surface is the 3-file `http` package** (`Http`, `OfflineException`,
  `HostRateLimiter`) — plain `java.net.http`, no third-party code.
- **The JDK flow touches :resolver nowhere inside :toolchain.** The single family-wide touch is
  CLI-side: `JdkListCommand` → `Versions.compare` (JdkListCommand.java:256).
- `hook-env` (the every-`cd` path) reads `jk.lock`'s jdk id via `LockfileReader` (:core),
  fail-soft — so the client keeps :core's lock *reader*, consistent with its build-file-reader role.
- **One rehome debt in the flow itself**: `jk jdk install`'s non-interactive core is
  `dev.jkbuild.runtime.JdkService` + `JdkInstallListener` — physically in **:engine** today. It
  drives only jdk-slice classes; it must move out of :engine for the client to drop that classpath.

The slice line inside `kernel/toolchain` is clean: **client keeps** `jdk/*` +
`tool/{ToolLauncher,ToolEnv,AppLauncher,JarManifest}` (+ `NativeImageDriver` only if `jk install`'s
client half needs it — it doesn't; it goes engine-side with the build). **Engine keeps**
`ToolResolver` (with its :resolver/:io stack), `compat/`, `discovery/`, `gradle/`, `mvn/`,
`kotlin/`, `script/` — the import/compat machinery used only by `import`/`mvn`/`gradle`/`doctor`.
After the split, the jdk slice's true deps are: :model utils + :core's `config`/`lock`-reader
subset + the 3-class `http` package. The `jk-resolver` dependency disappears entirely.

Client-side capability set this implies (matches the "HTTP, JSON, TOML, SHA256 and a few other
essentials" directive) — confirmed independently by `jk new`, which already lives entirely on it:
`Http` · `JdkCatalogClient`/`JdkInstaller`/`JdkRegistry`/`StableJdkPointer`/`MinimalTar` ·
`Hashing` · `JkBuildParser`/`JkBuildEditor`/`TomlValues`/`GlobalConfig` · `LockfileReader` ·
`LibraryCatalog` · jline/TUI.

## 4. Proposed target module graph (Stage 5)

Client artifact classpath:

```
jk (client binary, -Os)
├── :cli            presentation, arg parsing, renderers, EngineClient adapter
├── :model          domain currency + Goal/Phase event types + CliCommand model
├── :core           jk.toml/jk.lock parsing+editing, GlobalConfig, LibraryCatalog,
│                   deny PolicyChecker (all client-only verbs read it; 50 classes, keep whole)
├── :client-io      thin extraction: dev.jkbuild.http (3 classes) + dev.jkbuild.forge (auth)
│                   + RepoCredentialStore + LibraryRegistryClient (+ Cas.putByLink for add --file)
└── :toolchain-jdk  the jdk/ slice + tool launch shims (JdkService rehomed here)
```

Engine-side (unchanged layering): `:io` (repo/cache machinery), `:resolver`, `:toolchain` (compat/
import/gradle/mvn remainder), `:engine`, plugin workers. The wire seam is `EngineClient` + the
protocol types — the future thin `jk-api`.

Concrete moves/extractions, in dependency order (what must move first):

1. **Rehome `JdkService` + `JdkInstallListener`** out of `:engine` into the jdk slice
   (`dev.jkbuild.jdk`). Blocks everything: `jk jdk install` is a must-work-engine-less verb and is
   the only jdk-flow code physically in :engine. Low risk — it drives only jdk-slice classes.
2. **Small-class rehomes**: `Versions.compare` (:resolver → :model or :core), `DependencyTree` +
   `Provenance` (:resolver → :core's lock package — they are offline lockfile walkers),
   `ClasspathResolver.RUNTIME` scope-set constant (:engine → :model), `CompileSupport.isSimpleLayout`
   and `CompileToolchain.runningJavaHome/resolveJavaHome` statics (:engine → :core or the jdk
   slice), `CachePruneScheduler.LAST_PRUNED_FILE` constant. All mechanical.
3. **Split `:io`** into the client slice (`http`, `forge`, credential store, `LibraryRegistryClient`)
   and the engine remainder (`repo`, `cache`). Alternative: extract `:client-io` and have :io depend
   on it. **Risky/ambiguous**: `Cas` — `add --file` does a client-side `Cas.putByLink` and
   `cache info/search` read CAS dirs; either bless a tiny read-side + `putByLink` client surface or
   route those through the engine.
4. **Split `:toolchain`** at the documented slice line (jdk+launchers vs compat machinery). Depends
   on move 3 (the jdk slice needs `:client-io`'s `Http`). **Risky/ambiguous**: `doctor` uses
   `compat`/`discovery` client-side — either the client carries that small slice or doctor's repair
   goes engine-side with only diagnosis staying client.
5. **Cut `:cli`'s Gradle deps** to `:model` + `:core` + `:client-io` + `:toolchain-jdk` (+ the
   protocol/`jk-api` types) and let the compiler enforce it — only possible after every §1
   ENGINE-HOSTED/WORKER-DELEGATED verb has migrated (Stage 3) and the `explain`-ETA residue moved.

Flagged risky/ambiguous overall: the `export gradle/maven` classification (client-only possible,
engine-hosted recommended); `cache purge`'s delete-vs-engine coordination; the client-side CAS
touches (move 3); `SyncCommand`'s `ensure-jdk` phase, which entangles an engine-bound goal with
the client-retained JDK flow and must be split at migration time (client ensures JDK first, then
sends the sync request); and `LockCommand`'s injection of a TUI `pubgrub.Diagnostics.Palette` into
the resolver — hosted diagnostics must cross the wire structured, not pre-themed.

## 5. Migration order recommendation (Stage 3)

No work needed (already client-only or already hosted): `build`, `test`, `verify-build`, `engine *`,
`remove`, `deny`, `auth *`, `repo *`, `jdk *` (except the `JdkService` rehome), `activate`,
`deactivate`, `shell`, `hook-env`, `tool dir/list/uninstall`, `new`, `init`, `clean` (target/ part).
Near-free after small rehomes: `tree`, `why`, `add`, `library *`, `cache dir/info/search`,
`jdk list`, `doctor`.

Migration waves — heavy first, per slim-client.md §3, each wave = protocol vocabulary + engine
handler + client renderer:

| Wave | Verbs | Size | Rationale / protocol shape |
|---|---|---|---|
| 1 | `lock`, `sync`, `update` | L, L, M | The memory/CPU-heavy resolution+fetch family the ceilings exist for; includes git-source materialization. New lock/sync request+event vocabulary (single-goal shape fits: progress + `goal-diagnostic`* + `goal-finish`; workspace cascade = `plan-module` burst). `update` rides `lock`'s vocabulary |
| 2 | `import`, `mvn`/`gradle` provisioning, `audit`, `publish`, `format`, `image` | S–M each | The direct worker forks (§1.5) — moving them engine-side deletes `dev.jkbuild.worker.*` from the client in one tranche. Workers' NDJSON streams relay 1:1; `mvn`/`gradle` is a one-shot request → `{bin,version,source}` result |
| 3 | `compile`, `run` (build part), `native`, `install` | S, M, M, L | The in-process `BuildPipeline` stragglers; `compile` reuses `jk test`'s single-goal vocabulary as-is; `install`'s git mode overlaps Wave 1's git-source work |
| 4 | `ide`/`idea`/`vscode` (model part), `cache prune/purge`, `clean --cache`, `export gradle/maven`, `tool install/run` (resolve part), `explain` ETA rehome | L, M, S, S, M, S | Long tail; `ide` is the largest new wire vocabulary (serialized `IdeModel` burst + sync progress) but reuses Wave 1's sync machinery engine-side |

Wave 2 can proceed in parallel with Wave 1 (disjoint vocabulary); Waves 3–4 want Wave 1 landed
(they reuse its resolution/sync/git handlers). The artifact split (Stage 4) is unblocked the moment
Waves 1–3 land and the §4 move list through step 4 is done.

## Appendix A — raw jdeps tables

### A.1 Per-package dependency counts (class-level edges from cli.jar into kernel jars)

| Edges | Target package | Jar | Distinct classes |
|---|---|---|---|
| 348 | dev.jkbuild.model.command | model.jar | 9 |
| 262 | dev.jkbuild.run | model.jar | 14 |
| 170 | dev.jkbuild.jdk | toolchain.jar | 29 |
| 149 | dev.jkbuild.config | core.jar | 20 |
| 120 | dev.jkbuild.runtime | engine.jar | 42 |
| 111 | dev.jkbuild.model | model.jar | 21 |
| 48 | dev.jkbuild.util | model.jar | 7 |
| 45 | dev.jkbuild.lock | core.jar | 6 |
| 38 | dev.jkbuild.repo | io.jar | 18 |
| 32 | dev.jkbuild.resolver | resolver.jar | 15 |
| 27 | dev.jkbuild.worker | engine.jar | 6 |
| 25 | dev.jkbuild.task | engine.jar | 20 |
| 25 | dev.jkbuild.forge | io.jar | 11 |
| 23 | dev.jkbuild.engine | engine.jar | 4 |
| 19 | dev.jkbuild.cache | io.jar | 2 |
| 17 | dev.jkbuild.tool | toolchain.jar | 7 |
| 17 | dev.jkbuild.test | engine.jar | 3 |
| 17 | dev.jkbuild.library | core.jar | 3 |
| 16 | dev.jkbuild.http | io.jar | 1 |
| 13 | dev.jkbuild.compile | engine.jar | 11 |
| 12 | dev.jkbuild.compat | toolchain.jar | 7 |
| 10 | dev.jkbuild.plugin.protocol | plugin-api.jar | 1 |
| 9 | dev.jkbuild.layout | core.jar | 1 |
| 6 | dev.jkbuild.credential | model.jar | 3 |
| 4 | dev.jkbuild.resolver.pubgrub | resolver.jar | 4 |
| 4 | dev.jkbuild.mvn | toolchain.jar | 4 |
| 3 | dev.jkbuild.publish | model.jar | 3 |
| 3 | dev.jkbuild.audit | core.jar | 3 |
| 2 | dev.jkbuild.script | toolchain.jar | 2 |
| 2 | dev.jkbuild.gradle | toolchain.jar | 2 |
| 2 | dev.jkbuild.git | engine.jar | 2 |
| 2 | dev.jkbuild.engine.protocol | engine.jar | 1 |
| 2 | dev.jkbuild.discovery | toolchain.jar | 2 |
| 2 | dev.jkbuild.deny | core.jar | 2 |
| 1 | dev.jkbuild.kotlin | toolchain.jar | 1 |
| 1 | dev.jkbuild.image | model.jar | 1 |

(External, for completeness: `org.jline.*` is jline-terminal-ffm — client TUI, stays;
`org.graalvm.nativeimage` is compile-only for `EngineDetachFeature`.)

### A.2 Per-command module fan-out (distinct kernel classes per jar; migration-relevant rows)

| Command class | model | core | io | resolver | toolchain | engine | plugin-api |
|---|---|---|---|---|---|---|---|
| LockCommand | 27 | 9 | 9 | 8 | — | 4 | — |
| SyncCommand | 16 | 10 | 4 | 4 | — | 11 | — |
| CacheCommand | 14 | 2 | 3 | 1 | — | 16 | — |
| ImageCommand | 19 | 10 | 1 | — | 1 | 13 | 1 |
| PublishCommand | 26 | 2 | 2 | — | 1 | 4 | 1 |
| AuditCommand | 14 | 5 | 1 | — | 1 | 4 | 1 |
| FormatCommand | 8 | 1 | 2 | — | 3 | 4 | 1 |
| ImportCommand | 7 | — | 1 | — | 1 | 4 | 1 |
| MvnCommand | 6 | — | 1 | — | 4 | 4 | 1 |
| NewCommand | 20 | 3 | 1 | — | 13 | — | — |
| AddCommand | 13 | 5 | 3 | — | 1 | — | — |
| TreeCommand | 6 | 4 | — | 2 | — | 1 | — |
| WhyCommand | 6 | 4 | — | 3 | — | — | — |
| AuthLoginCommand | 6 | 1 | 6 | — | — | — | — |
| ExportGradleCommand | 5 | — | — | — | 3 | — | — |
| ExportMavenCommand | 6 | — | — | — | 6 | — | — |
| ExportSupport | 3 | 5 | — | — | 3 | 1 | — |

### A.3 Chokepoint CLI classes (distinct kernel classes referenced)

```
67 SyncCommand      59 CacheCommand    39 NewCommand           29 RunCommand
66 LockCommand      55 ScriptRunner    37 VerifyBuildCommand   29 ide.IdeSupport
63 InstallCommand   45 ImageCommand    37 UpdateCommand        28 NativeCommand
62 BuildCommand                        37 JdkInstallCommand    28 JdkUpdateCommand
36 PublishCommand   35 cli.engine.EngineBuildListenerAdapter   26 JdkUninstallCommand
26 AuditCommand     25 ToolInstallCommand  25 ExplainCommand   24 JdkEnsureCommand
```
