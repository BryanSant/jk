# jk extension/plugin remodel — Extension · Plugin · Phase-DAG · capability interfaces

## Context

The plugin model accreted inconsistent vocabulary and conflates two distinct ideas.
Today there is a `Plugin` SPI (forked worker, JSONL) and a `BuildPlugin` sub-SPI
(`register()` with step/packaging/command hooks), plus three *unrelated* in-engine
seams — `GitBackend`, `JavaCompileStrategy`, `LocalToolProbe` — that are conceptually
"engine extensions" but share no supertype, no discovery mechanism, and carry
`Runner`/`Worker`/`Backend`/`Probe` suffixes. Phase participation is expressed as
`StepSpec` after/before *anchor windows*, and the pipeline is treated as a fixed linear
sequence. We want a coherent model:

- **`Extension`** — anything that extends the core engine; may run *in* the engine JVM.
  `GitBackend`, `JavaCompileStrategy`, `LocalToolProbe` are Extensions.
- **`Plugin extends Extension`** — an Extension that runs *outside* the engine JVM (forked
  worker, JSONL, managed + sandboxed). The 12 worker plugins are Plugins. **Clients (CLI,
  Web-UI) are not Extensions** even though they speak JSONL (`InProcessEngine` stays a
  client↔engine seam, untouched).
- **Phases form a flexible DAG**, not a line: `resolve → compile → test → package →
  (run | image | publish)`, with `image → publish` and shortcuts allowed (incremental
  compile without resolve; image straight from compile without a jar). Strong defaults,
  overridable.
- **Consistent class names** — no `Runner`/`Worker`/`Backend`/`Probe`: `JavaCompilerWorker→
  JavaCompilerPlugin`, `JkRunner→TestRunnerPlugin`, `PublishRunner→PublishPlugin`,
  `GitCliBackend→GitCliExtension`, `MiseProbe→MiseExtension`, etc.

## Decisions (settled with the user)

1. **Phase participation = behavioral capability interfaces** (`BuildExtension`,
   `TestExtension`, …) each with an overridable method + a **rich phase-scoped context**.
   The context *is* the contribution surface (subsuming `StepSpec`/`PackagerSpec`): common
   plugins call it once; complex plugins (Android) call it many times. `BuildPlugin.register`
   / `StepSpec` survive as the low-level substrate the harness/wire uses. Common things easy,
   hard things possible.
2. **Phase enum:** keep `COMPILE`; `RUN_PREPARE → RUN`; **add `IMAGE`, `PUBLISH`** as terminal
   nodes. Final set: `RESOLVE, COMPILE, TEST, PACKAGE, RUN, IMAGE, PUBLISH`.
3. **DAG is flexible with defaults** — phases/goals declare what they *consume*; the engine
   wires the minimal graph for the invoked target from available producers. Terminal goals
   `run`/`publish` are mutually exclusive; `image` may chain into `publish` or terminate.
4. **compile & test stay engine-driven** — the java/kotlin compilers and test runner remain
   engine-invoked forked workers (renamed `*Plugin`, tagged `COMPILE`/`TEST`); they are *not*
   reworked into the capability SPI. Capability interfaces are for *contributors*
   (protobuf/spring-boot/android/shrink) and *terminal goals* (image/publish/run).
5. **Domain extensions** (`GitBackend`/`JavaCompileStrategy`/`LocalToolProbe`) keep their own
   behavioral interfaces, become `Extension` subtypes, and declare their phase as **metadata/
   tag** (git→resolve, compile-strategy→compile, probe→resolve) — they are invoked by the
   engine's resolver/compiler/toolchain, not via a phase callback.
6. **Standalone commands** (audit/format/compat) are **zero-phase** Plugins exposing a
   `jk <name>` command (existing `PluginCommandSpec` path).

## Target model

```
Extension                         (base: id() + declared Phase tag(s); may run in-engine)
 ├─ GitBackend, JavaCompileStrategy, LocalToolProbe   (in-engine; own methods; phase = tag)
 └─ Plugin extends Extension      (forked worker, JSONL, managed + sandboxed)
      ├─ capability ifaces: ResolveExtension/BuildExtension/TestExtension/PackageExtension/
      │                     RunExtension/ImageExtension/PublishExtension
      │                     (behavioral method + rich phase context; the authoring SPI)
      ├─ engine-driven built-ins: JavaCompilerPlugin/KotlinCompilerPlugin (COMPILE),
      │                           TestRunnerPlugin (TEST)  — tagged, engine-invoked
      └─ command plugins: AuditPlugin/FormatPlugin/CompatPlugin  (zero phase, jk <name>)

Phase DAG (defaults; flexible):  resolve → compile → test → package → {run | image | publish},  image → publish
```

## Implementation streams (ordered safe → structural)

### Stream 1 — Phase enum + DAG (`plugin-api` + `kernel/engine`)
- `plugin-api/.../plugin/build/Phase.java`: `RUN_PREPARE → RUN`; add `IMAGE`, `PUBLISH`. Update
  `wireName`/`fromWire`, javadoc. Sweep `RUN_PREPARE` refs.
- Model phase dependency **defaults + flexibility**: extend the phase concept with declared
  upstream deps (default edges) but keep the real execution DAG emergent from the existing
  step graph (`Step.requires` + typed `In`/contributions in `BuildPipelines`). A terminal goal
  declares what it *consumes* (e.g. image needs the runtime closure) and the engine satisfies
  it from the best available producer (package jar, else compile classes). Represent the
  default target→phase mapping so `jk build/run/image/publish/test` each resolve their minimal
  phase set. Key file: `kernel/engine/src/main/java/build/jumpkick/runtime/BuildPipelines.java`
  (`beforePhase()`/`packageRequires`/`pluginStepStep` weaving) + the `*Pipelines` factories.

### Stream 2 — `Extension` base + `Plugin extends Extension`
- New `Extension` interface (likely `plugin-api/.../plugin/Extension.java`): `id()` (reuse
  `PluginManifest.id`) + declared phase tag(s) metadata + docs of "may run in-engine".
- `Plugin` (`plugin-api/.../plugin/Plugin.java`) `extends Extension`; keep `manifest()` +
  `run(args, out)` (the forked-worker contract). Document: sandboxed forked JVM (see
  `PluginLoader`/`WorkerProcess`/`JvmOptions`).
- Do NOT model `InProcessEngine` as an Extension.

### Stream 3 — Capability phase interfaces + rich context (`plugin-api`)
- Add `ResolveExtension`/`BuildExtension`/`TestExtension`/`PackageExtension`/`RunExtension`/
  `ImageExtension`/`PublishExtension` (in `plugin-api/.../plugin/build/`), each a behavioral
  interface with one overridable method taking a phase context.
- Design the contexts as the contribution surface, reusing today's role-specific facades:
  `BuildContext` ⊇ `StepExec` (read `classesDir`/`runtimeClasspath`/`config`/`project`/`scratch`
  + declare `contributeSources`/`contributeClasses`/`transformClasses` + `step(name, body)` for
  multi-step), `PackageContext` ⊇ `PackageIo` (`artifactPath`/`secret`), `PublishContext`/
  `ImageContext`/`RunContext` consume `mainArtifact()`/runtime closure. Keep `StepSpec`/
  `PackagerSpec`/`PluginCommandSpec` as the internal wire/harness representation
  (`BuildPluginHarness` translates capability-impl → describe/run ops). `BuildPlugin.register`
  becomes the low-level escape hatch (Android may keep using it directly).

### Stream 4 — Rename the 12 plugin classes → `*Plugin` (`plugins/*`)
- `JavaCompilerWorker→JavaCompilerPlugin`, `KotlinCompilerWorker→KotlinCompilerPlugin`,
  `JkRunner→TestRunnerPlugin`, `PublishRunner→PublishPlugin`, `ImageRunner→ImagePlugin`,
  `AuditRunner→AuditPlugin`, `CompatRunner→CompatPlugin`; `FormatPlugin`/`AndroidPlugin`/
  `SpringBootPlugin`/`ProtobufPlugin`/`ShrinkPlugin` already correct. `git mv` each + fix the
  `META-INF/services/build.jumpkick.plugin.Plugin` file contents + normalize packages (drop the
  stray `.runner` leaf, e.g. `build.jumpkick.publish.runner → build.jumpkick.publish`). Update
  `WorkerJar` enum, any `-Djk.plugin.class` refs, tests.
- Re-target the 4 build plugins + terminal-goal plugins onto the capability interfaces
  (Stream 3): protobuf→`BuildExtension` (contributes sources), shrink→`PackageExtension`,
  spring-boot→`BuildExtension`+`PackageExtension`, android→`register()` escape (multi-step) +
  tags; publish→`PublishExtension`, image→`ImageExtension`. Compilers/test-runner: tag only.

### Stream 5 — Rename domain extensions → `*Extension` (`kernel/engine`, `kernel/toolchain-jdk`)
- `GitCliBackend→GitCliExtension`, `JGitBackend→JGitExtension` (`kernel/engine/.../git/`);
  `GitBackend` interface `extends Extension` + phase tag RESOLVE; selection stays
  `GitFetcher.select` + `JK_GIT_BACKEND` (env-factory, unchanged — unifying discovery is a
  non-goal here).
- `SubprocessJavacStrategy→SubprocessJavacExtension` (+ `JavaCompileStrategy extends Extension`,
  tag COMPILE; selection stays `JavaCompileStrategies`/ServiceLoader).
- The 11 `*Probe→*Extension` (`kernel/toolchain-jdk/.../discovery/`) + `LocalToolProbe extends
  Extension`, tag RESOLVE; selection stays `Probes.defaultChain()`.

### Stream 6 — Terminal goals + command plugins wiring
- Make `image`/`publish` first-class terminal phases (Stream 1) whose work is a
  `ImageExtension`/`PublishExtension` plugin; `jk image`/`jk publish` target those DAG nodes.
- Keep audit/format/compat as zero-phase command plugins (`jk <name>` via `PluginCommandSpec`).

### Stream 7 — Wire/docs/Web-UI + verification
- If the phase set is on the wire (it is: describe `after`/`before`, step `phase` field), extend
  wire + CLI adapters + Web-UI for the new `IMAGE`/`PUBLISH` phases and `run`(was run-prepare).
- Docs: `docs/authoring-plugins.md`, `build-plugins-plan.md`, `plugin-refactor.md`, `engine.md`.

## Verification

- `./gradlew compileJava compileTestJava --offline --continue` green across all modules.
- Unit tests: `PluginBuildDeclarationsTest`, `ProtobufPluginTest`, `ShrinkPluginTest`,
  `SpringBootPluginTest`, `ThirdPartyPluginTest` (SPI contract), `EngineProtocolTest`,
  `GitBackend*Test`, `JavaCompile*`/probe tests, `WebClientFoldTest` + node `fold.test.mjs`.
- E2e (mirrors prior proof): `./gradlew nativeCompile :cli-engine:shadowJar installLocal`;
  stage + `install.sh`; then in `../bjs/simple`+`../bjs/multi`: `jk build`, `jk test`, and a
  plugin-backed build (protobuf/spring-boot) to exercise the capability-interface path; drive
  a terminal goal (`jk image` / `jk publish` dry-run) and a command plugin (`jk audit`/`jk
  format`); confirm `jk <plugin-command>` dispatch and phase tags in `jk explain`/`--output json`.

## Risks / sequencing notes

- **Stream 3 (capability interfaces + context) is the deep one** — it reshapes the plugin
  authoring API. Land Streams 1–2 + 4–5 (mechanical renames + Extension base) first and verify;
  do Stream 3 as its own verified pass, keeping `register()`/`StepSpec` working throughout so
  nothing breaks mid-flight.
- The flexible **phase DAG** (Stream 1) changes engine wiring; introduce default edges +
  target→phase resolution without removing the existing step-graph behavior, and verify
  `jk build/test/run` unchanged before adding `image`/`publish` targets.
- Keep the `Plugin` JSONL wire and `META-INF/services/build.jumpkick.plugin.Plugin` discovery
  intact (string-keyed; silent-failure risk) — validate via the plugin-build + third-party tests.
