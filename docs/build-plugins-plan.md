# Build Plugins — custom tables, framework integrations, and the plugin SPI

**Status:** P6 LANDED (2026-07-11) — the Android spike PASSED the gate. `plugins/android`
builds a hello-world APK (aapt2 R-gen → javac → d8 → assemble + v1/v2 debug-sign, apksig-
verified) **using only the public SPI** — the engine has zero Android-specific code
(AndroidSpikeTest drives the real declared pipeline; real tools fetched from Google
Maven/Central). The spike's primary deliverable, the SPI-gap findings — every one fixed
GENERICALLY (P6a, b12e609d):
- Before-COMPILE steps + `contributesSources` (the promised row-5 capability): source-gen
  steps become compile prerequisites; their contributed `.java` files join the compiler's
  source list (stamp + action key see them like any source); `In.classes()` on such a
  step is a declaration error. Kotlin contributed-source union deferred (R is Java; KSP
  is android-plan Phase 4).
- `In.projectFiles(rel)`: a codegen step's real inputs (`res/`, `AndroidManifest.xml`,
  `proto/`) — module-relative, fingerprinted recursively into the action key.
- `[[contribute.step-dependency]]`: tool artifacts (aapt2's per-OS binary jar, r8, a
  platform jar) fetched engine-side by coordinate — classifier-aware, `${host.os}` joins
  the closed interpolation set — keyed as inputs, handed to bodies as `exec.extra(name)`.
- `StepExec.tool(Path)`: fork a fetched native executable. `PackageIo` gains `javaHome()`
  + the same tool forks (keytool for the debug keystore); the engine now supplies
  java-home on package specs.
- `[packaging] artifact-extension`: a packager replaces the main artifact under its own
  extension (`target/lib/app-1.0.apk`); `exec-mode = "device"` names a non-host artifact —
  run/dev answer with the descriptor error pointing at the plugin's deploy verb.
- Worker-side finding (no SPI change): extension-judging tools (d8 `--lib`) need a
  `.jar`-named alias of the extension-less cached blob — plugin-side link/copy, the same
  quirk the Kotlin worker handles.

Android Phase 1 (worktree-android-plugin) grew the SPI further, still generically:
- `[[contribute.step-dependency]]` sources: `sdk-component`/`sdk-path` (a provisioned SDK
  component in sdkmanager spelling; pseudo-component `root` = the SDK root) and
  `transitive = true` (a JVM tool's runtime closure materialized as a lib dir).
- `[[contribute.provided-classpath]]` — a declared step-dependency joins the COMPILE
  classpaths only (platform-jar posture: compiler sees it, runtime/packaging never do).
- Step chaining: `In.stepOutput` on a StepSpec orders two plugin steps inside one anchor
  window and hands the chained output to the body (StepExec.stepOutput).
- Verbs receive the declared step-dependency tool artifacts (VerbExec.extra) and the
  built main artifact under its packager extension (VerbExec.mainArtifact).
- `[packaging] deploy-verb` (device exec-mode): `jk run` on a device artifact dispatches
  the plugin's deploy verb over the plugin-verb protocol — nothing execs on the host.
Phase-1 platform stand-in: the Maven-published android-all jar (Robolectric's AOSP build)
serves as android.jar for aapt2 -I / javac (PROVIDED scope) / d8 --lib — real SDK
provisioning (repository2 feed + licenses) is android-plan §3.2's own later phase. Deploy
verbs (`jk run` onto a device) and manifest-merger are android-plan Phase 1 leftovers the
spike deliberately excludes.

**Previous status — P5 LANDED (2026-07-11, 465c0309..4be7331a) — third-party resolution + trust.
`[plugins]` declarations now complete the loop the earlier lock/sync groundwork started:
lock-plugins fetches + SHA-pins (existing) AND materializes each jar's `jk-plugin.toml`
into `<module>/target/plugin-manifests/<sha>.jk-plugin.toml` (PluginManifestOps writes,
PluginManifestStore is the parser-readable file side — the parser never touches the CAS).
`PluginTableRegistry.manifestsFor(dir, decls)` layers materialized third-party manifests
onto the built-ins (id/table collisions error); BuildPipeline + PluginVerbs run a CAS-only
pre-flight + `JkBuildParser.reparse` so a plugin's table validates and its contributions
apply on the very first build after `jk sync`. The §3.4 unowned-table gate is live
(suppressed only while a declaration is unresolved). Third-party code hooks fork the
lock-pinned CAS jar itself (`[code] worker` is first-party-only now) behind the consent
gate: `TrustedPlugins` (`~/.jk/state/trusted-plugins.toml`, exact coordinate or `group:`
prefix; `jk trust plugin` manages it) — the refusal names the remedy; first-party plugins
are implicitly trusted (Posture A). Acceptance: ThirdPartyPluginTest publishes a
hello-world plugin to a file:// repo and drives publish→declare→lock→extract→validate→
contribute→refuse-untrusted→trust→verb end to end. `docs/authoring-plugins.md` is the
blueprint walkthrough. P5 residue: the P4 `--spring` static-flag listing still stands
(dynamic listing of installed plugins' scaffold flags is now unblocked but not wired);
capability declaration/audit (Posture B) stays deferred per plugin-refactor.md.

**Previous status — P4 LANDED** (2026-07-11, f1479623..) — scaffold + import + verbs from plugins.
The manifest grows `[scaffold]` (flag, jk.toml fragment appends, sample templates — pure
data with the closed `lang` predicate; templates bake in next to the manifest and render
engine-side via GENERATE params, so `jk new --spring` ships ZERO framework content in the
client) and `[[import.gradle-plugin]]` rules (GradleImporter maps plugin ids → owned-table
config generically; compat-bridge's renderer emits every plugin table from its schema with
default-omission — renderSpringBoot deleted). VerbSpec lands in the SPI + harness
(`op=verb`, `verb-out` streaming, body exit code), the engine routes PLUGIN_VERB_REQUEST
through the describe declarations, and the CLI falls back to plugin verbs on unknown
commands (never spawning an engine for a typo: live socket or test seam only). Boot
declares no verbs — the machinery is fixture-tested (harness + declarations decode).
Deliberate P4 residue: the `--spring` flag itself and its input derivations (executable/
traditional-layout/Application main) stay client-side until P5's dynamic plugin listing;
scaffold/import stayed manifest data — no ScaffoldSpec/ImportRule code hooks were needed.

**Previous — P3** (ff5cd9f9..3ed652c4) — the steps/packagers SPI.
StepSpec/PackagerSpec/BuildPluginContext + the BuildPluginHarness worker driver live in
plugin-api (depends on :model alone); the engine learns registrations over a file-cached
`describe` worker fork (key: jk+plugin versions, config, registration-visible facts — a
fully-cached rebuild forks nothing) and drives `run-step`/`package` executions with action
keys fingerprinting exactly the DECLARED inputs, restoring scratches/artifacts on hits so
step bodies never learn the cache exists. `[packaging]` in the manifest is the packager's
static artifact descriptor (exec-mode/self-contained/classes-run/main-scan/layered-image):
run/install/image/aot-cache consult it with zero plugin code — every isSpringBoot() decision
branch in BuildPipeline/ImageGoals/ExecPlans is a descriptor check now. SpringAotRunner +
BootJarPackager bodies moved into the plugins/spring-boot worker; the engine fetches
`[[contribute.packager-dependency]]` artifacts (loader, jarmode-tools) and renders the SBOM.
Deliberate residue: jk dev's DevTools tier-2 injection stays hard-coded until RunShape (P7,
§5); ProjectInfo's springBoot wire fields still read table presence (client wire compat);
before-COMPILE / contributesSources steps refuse loudly until P6 wires source contribution.
Next: P4 (scaffold/import/verbs).

**P2 (5a36d2ca)** — declarative contributions. The manifest carries
`[[contribute.platform-dependency]]` / `[[contribute.compiler-args]]` / `[[contribute.kotlin-plugin]]`
with `${config.<key>}`/`${kotlin.version}`/`${project.*}` interpolation (unknown variable =
manifest-load error) and the closed `when` predicate set (`classpath-has`, `config`/`equals`,
`native-declared`, `kotlin-project`; exactly one per entry; `classpath-has` is rejected on
platform-dependencies — they inject before resolution). `PluginContributions` evaluates
engine-side: the parser's BOM injection (`withPlatformContributions`, user-declared module
wins), JavacLint's contributed-args lane (BuildPipeline + BuildPlanForecast against the same
lock, so forecast keys match), and the Kotlin block (allopen unconditional, noarg gated on
jakarta.persistence via classpath-has, `${kotlin.version}` lockstep with the compiler — the
worker's .jar-suffix link quirk untouched downstream). `plugins/spring-boot/jk-plugin.toml`
is the single source of truth; kernel/core processResources bakes it in as the registry
resource. Boot builds green through the full suite.
P1 LANDED (2026-07-11, worktree branch `worktree-build-plugins`) — table routing
+ schema. The parser carries zero framework-specific tables: plugin-owned tables validate
against their manifest's `[schema]` (`PluginTableRegistry` + built-in
`spring-boot.jk-plugin.toml` resource in kernel/core) into generic `PluginConfig` values on
`JkBuild.pluginConfigs` (`JkBuild.SpringBoot` deleted; typed reads via `SpringBootFacts`
engine-side, which P3 dissolves into the plugin worker). `isSpringBoot()` remains as a
presence convenience until P3's capability checks. Unknown tables stay ignored in P1 — the
"unowned table is an error" UX arrives with P5, when the installed set becomes user-visible.
Required-key diagnostics keep hand-written quality via schema `example`/`hint` metadata.
P2–P6 below remain.
**Companions:** [plugin-refactor.md](./plugin-refactor.md) (the worker/process SPI — phases 0–6
done), [spring-boot-plan.md](./spring-boot-plan.md) (the capability inventory this SPI must
carry), [android-plan.md](./android-plan.md) (the stress-test consumer).

---

## 1. Purpose

`[spring-boot]` proved the model: one table in `jk.toml`, and the standard verbs change
behavior. But today that table is **hard-coded** — 19 `isSpringBoot()` branches across 9
files (parser, model, BuildPipeline, ImageGoals, Run/Dev/Install/AotCache commands,
importer, scaffolder). Repeat that for `[micronaut]`, `[quarkus]`, `[android]`,
`[protobuf]`, `[jooq]`, … and the core rots. Gradle answers this with plugins + scripting
(scripting is a jk anti-goal); Maven with plugins. jk's answer is **build plugins**:

- A plugin **owns a table** (`[spring-boot]`, `[android]`) — schema, validation, defaults.
- A plugin **shapes the build** through declarative contributions and, where code is
  unavoidable, isolated worker steps.
- The **spring-boot plugin ships with jk** (we maintain it) and is the blueprint third
  parties copy. Most of Boot's value already landed as *general* jk capabilities (dev
  scopes, BOMs, main-class scan, SBOM, layered images, `--aot-cache`); what remains is
  exactly the thin framework-specific layer a plugin should carry.
- **Android is the design stress test.** If the SPI can't express aapt2/R8/dexing/APK
  assembly/device deploy without leaking jk internals, it isn't generic enough. Nor is it
  only about frameworks: code generators (protobuf, jOOQ, OpenAPI), quality gates, and
  deploy targets are the same shape.

**Design bar:** a plugin author never learns the CAS, the action cache, freshness stamps,
or jk's directory layout. Easy things easy, hard things possible.

---

## 2. What a build plugin must be able to do (evidence, not speculation)

Every row is something `[spring-boot]` actually required, with the Android analogue —
this is the SPI's required surface, derived from shipped code rather than guesswork:

| # | Integration point | Boot used it for | Android will use it for |
|---|---|---|---|
| 1 | Own a config table | `[spring-boot] version/aot/build-info/...` | `[android] compile-sdk/min-sdk/...` |
| 2 | Inject dependencies | auto-import the BOM (platform scope) | core-library desugaring, test orchestrator |
| 3 | Compiler args | javac `-parameters`, kotlinc `-java-parameters` | `-source 8` desugar constraints, KSP flags |
| 4 | Kotlin compiler plugins | all-open (spring), no-arg (jpa, conditional on classpath) | Compose compiler plugin |
| 5 | Generated-source steps | Spring AOT processor → compile generated sources | aapt2 + R-class gen, KSP, view binding |
| 6 | Packaging ownership | boot-jar layout (loader, layers.idx, STORED libs) | APK/AAB assembly, signing |
| 7 | Run/dev semantics | classes-dir run; DevTools detect/inject | install-to-device, logcat attach |
| 8 | Native/image hooks | AOT dirs on native classpath; image layer mapping | n/a (validates optionality) |
| 9 | Scaffold templates | `jk new --spring` (Java + Kotlin) | `jk new --android` |
| 10 | Import translation | Gradle Boot plugin → `[spring-boot]` | AGP → `[android]` |
| 11 | Extra verbs (rare) | — | `jk devices`, `jk logcat` |
| 12 | Post-build artifacts | build-info, SBOM placement | mapping.txt, split APKs |

Two observations drive the whole design:

- Rows 1–4 and 9–10 are **pure data** — a function of the table's config, no code needed.
- Rows 5–8 and 11–12 are **code** — but every one fits "declared inputs → tool run →
  declared outputs," which is exactly what jk's action-cache machinery already
  generalizes over. The plugin declares *what*; jk owns *when* (incrementality) and
  *whether it can be skipped* (caching).

---

## 3. Design

### 3.1 Two layers: declarative manifest + worker code

A build plugin is one jar containing:

```
jk-plugin.toml                      ← the declarative layer (parsed by jk, no plugin code runs)
META-INF/services/dev.jkbuild.plugin.BuildPlugin   ← the code layer (runs in a worker JVM)
```

**The declarative layer covers the easy 90%** and executes *zero plugin code in the
engine* — deterministic, auditable, cacheable, and safe to evaluate even for untrusted
plugins:

```toml
# jk-plugin.toml (shipped inside the spring-boot plugin jar)
[plugin]
id          = "spring-boot"
table       = "spring-boot"          # the jk.toml table this plugin owns
version     = "1.0.0"
jk-compat   = ">=0.10"

[schema]                             # table schema: typed keys, defaults, required
version       = { type = "string", required = true }
aot           = { type = "bool" }    # tri-state: absent = auto
build-info    = { type = "bool", default = false }
include-tools = { type = "bool", default = true }
aot-args      = { type = "string-list", default = [] }

[[contribute.platform-dependency]]   # row 2: BOM auto-import
coordinate = "org.springframework.boot:spring-boot-dependencies:${config.version}"

[[contribute.compiler-args]]         # row 3
javac  = ["-parameters"]
kotlin = ["-java-parameters"]

[[contribute.kotlin-plugin]]         # row 4
id         = "org.jetbrains.kotlin.allopen"
coordinate = "org.jetbrains.kotlin:kotlin-allopen-compiler-plugin-embeddable:${kotlin.version}"
options    = ["preset=spring"]

[[contribute.kotlin-plugin]]
id         = "org.jetbrains.kotlin.noarg"
coordinate = "org.jetbrains.kotlin:kotlin-noarg-compiler-plugin-embeddable:${kotlin.version}"
options    = ["preset=jpa"]
when       = { classpath-has = "jakarta.persistence:jakarta.persistence-api" }
```

Interpolation is `${config.<key>}` plus a handful of well-known values
(`${kotlin.version}`, `${project.group}` …). Conditions are a **closed predicate set**,
not an expression language: `classpath-has`, `config`, `native-declared`,
`kotlin-project`. Anything conditional beyond these belongs in code — a tiny DSL that
grows expressions is how build systems rot.

**The code layer covers the hard 10%** and always runs in the plugin's forked worker JVM
(the process SPI that already exists — `Plugin`/`PluginWorkerMain`/NDJSON protocol):

```java
public interface BuildPlugin {
    /** Steps, packagers, scaffolds, importers, verbs — registered against typed hooks. */
    void register(BuildPluginContext ctx);
}

public interface BuildPluginContext {
    Config config();                 // the parsed, schema-validated table
    ProjectView project();           // read-only: coords, scopes, resolved RUNTIME entries
                                     //   (real file paths WITH real artifact names), layout
                                     //   handles (classesDir(), targetDir()) — no CAS paths,
                                     //   no ~/.jk knowledge
    void step(StepSpec spec);        // row 5 — see 3.2
    void packaging(PackagerSpec s);  // row 6 — see 3.3
    void run(RunShape shape);        // row 7: classpath entries, JVM/system props, exec mode
    void nativeImage(NativeShape s); // row 8: extra classpath dirs, image args
    void scaffold(ScaffoldSpec s);   // row 9: templates for `jk new --<id>`
    void importer(ImportRule rule);  // row 10: foreign-build construct → table/config
    void verb(VerbSpec spec);        // row 11: a new command, worker-executed
}
```

### 3.2 Steps: the one abstraction that hides the internals

Everything incremental in jk reduces to: *inputs → action → outputs*. The SPI makes that
the plugin author's entire mental model:

```java
ctx.step(StepSpec.named("spring-aot")
    .after(Anchor.COMPILE)                    // anchors, not phase-name coupling
    .before(Anchor.PACKAGE)
    .inputs(In.classes(), In.runtimeClasspath(), In.config())   // declared → jk keys the cache
    .outputs(Out.dir("aot/classes"), Out.dir("aot/resources"))  // relative to a jk-owned scratch
    .contributesClasses("aot/classes")        // folded into packaging + native classpath
    .contributesResources("aot/resources")
    .run(exec -> exec.java()                  // runs IN THE PLUGIN WORKER
        .classpath(exec.in().runtimeClasspath())
        .mainClass("org.springframework.boot.SpringApplicationAotProcessor")
        .args(...)));
```

What the author **never sees**: action keys (jk fingerprints the declared inputs),
cache restore (jk materializes prior outputs on a hit and skips `run`), freshness stamps,
CAS paths, `target/` conventions. What the author **gets for free**: incrementality,
`--rerun` semantics, progress reporting (`exec.label(...)`), failure surfacing, and the
step's outputs showing up in packaging/native/run automatically via the
`contributes*` declarations.

Anchors (`RESOLVE`, `COMPILE`, `TEST`, `PACKAGE`, `RUN-PREPARE`) map onto the existing
`Phase.requires` graph. Android's chain — `aapt2-compile → merge-manifest → gen-R (before
COMPILE, contributesSources) → ksp → dex/r8 (after COMPILE) → assemble-apk (PACKAGE)` — is
just several steps with `contributesSources` feeding the compiler, which is why row 5
must support *before*-compile source generation, not only after-compile processing.

### 3.3 Packagers: replace the artifact, keep the caching

```java
ctx.packaging(PackagerSpec.replacingMainArtifact("boot-jar")
    .inputs(In.classes(), In.runtimeEntries(), In.stepOutput("spring-aot"), In.config())
    .produce((in, out) -> { /* zip assembly against in.runtimeEntries() —
                               coordinate-named paths, manifest helpers, STORED support */ }));
```

jk keys the artifact cache on the declared inputs (exactly what `packageBootJar` does by
hand today) and owns where the artifact lives. `jk install`, `jk run`, and `jk image`
consult the packager's **artifact descriptor** (`selfContained`, `execMode: jar|classpath|
binary`) instead of `isSpringBoot()` branches — that's how the Run/Install/AotCache
special cases generalize (an APK descriptor would declare `execMode: device`).

### 3.4 Loading, trust, and lifecycle

- **Discovery.** First-party plugins ship in the jk distribution (a `plugins/` dir next to
  the engine jar, or baked resources); third-party come from `[plugins]` in `jk.toml`,
  resolved like any dependency, SHA-pinned in `jk.lock` (= plugin-refactor Phase 7).
- **Engine parses only `jk-plugin.toml`** — never loads plugin classes. Declarative
  contributions apply in-engine. A table with no owning plugin is an error naming the
  known tables ("`[micronaut]` is not owned by any installed plugin — add it under
  `[plugins]`").
- **Code hooks execute in the plugin's worker JVM** over the existing spec-file/NDJSON
  protocol (steps and packagers each get a spec: resolved input paths in, output paths +
  structured events out). The engine never classloads third-party code — same isolation
  and the same trust posture as today's workers, plus the `jk trust` model for
  third-party plugin coordinates.
- **Caching of the plugin itself:** manifest contributions are keyed by (plugin id,
  version, config) — they participate in action keys automatically, so bumping a plugin
  version correctly invalidates.

### 3.5 What stays core (deliberately NOT plugin surface)

The general capabilities harvested from Boot stay in jk for everyone: dev/test-dev
scopes, BOM/platform machinery, main-class scan, SBOM embedding, layered images,
`--aot-cache`, `jk dev`'s watch loop, reachability metadata. Plugins *use* these; they do
not reimplement them. The core also keeps compilers, test running, resolution, and
publishing — a build plugin decorates the pipeline, it does not replace the kernel.

---

## 4. Refactor + build-out plan (each phase ships alone, self-hosting stays green)

1. **P1 — Table routing + schema.** `PluginTableRegistry` in the parser: known tables
   come from installed plugins' `jk-plugin.toml` schemas; `[spring-boot]`'s schema moves
   to a (still-built-in) manifest resource. `JkBuild.SpringBoot` becomes generic
   `JkBuild.pluginConfig("spring-boot") → Config`. The 19 `isSpringBoot()` call sites
   switch to capability checks fed by contributions, not table presence.
   *Exit: parser has zero framework-specific tables; behavior identical.*
2. **P2 — Declarative contributions engine-side.** BOM injection, compiler args, kotlin
   plugins, and the condition predicates evaluate from the manifest (deleting the
   hard-coded versions in JkBuildParser/BuildPipeline). The noarg classpath condition and
   `${config.version}` interpolation are the acceptance tests.
   *Exit: `plugins/spring-boot/` exists holding only `jk-plugin.toml`; Boot builds byte-identical.*
3. **P3 — Steps + packagers.** `StepSpec`/`PackagerSpec` in `plugin-api`; worker-side
   `BuildPluginContext` runtime; anchors wired into `BuildPipeline`; artifact descriptors
   replace the Run/Install/Image/AotCache branches. Move `SpringAotRunner` and
   `BootJarPackager` bodies into the spring-boot plugin worker.
   *Exit: engine has no Boot-specific code; the boot jar is produced by the plugin;
   caching behavior unchanged (same rebuild triggers as today).*
4. **P4 — Scaffold + import + verbs.** `jk new --<plugin-id>` templates and importer
   rules come from plugins (Boot's `NewScaffolder`/`GradleImporter` chunks move);
   `VerbSpec` for plugin commands.
   *Exit: `jk new --spring`, `jk import` of a Boot build work purely via the plugin.*
5. **P5 — Third-party resolution + trust** (= plugin-refactor Phase 7). `[plugins]`
   coordinates → resolve → SHA in `jk.lock` → worker-only execution + `jk trust` gating.
   Authoring guide: `docs/authoring-plugins.md` (the blueprint walkthrough of
   `plugins/spring-boot`).
   *Exit: a third-party hello-world table plugin runs from a published coordinate.*
6. **P6 — Android spike (validation gate).** Implement android-plan.md Phase 1 (aapt2 +
   R-gen + dex + APK for a hello-world app) **using only the public SPI** — no engine
   edits allowed. Every place the spike needs a private API is an SPI bug to fix before
   the SPI is declared stable.
   *Exit: `jk build` on a minimal Android app produces an installable APK via
   `plugins/android`, and the SPI needed zero engine changes to get there.*

### Android Phase-2 SPI findings (all generic, none Android-specific)

- **Container artifacts are resolver-owned** (android-plan §2): the lock records a
  non-jar packaging's file name (`path`); `ExplodedArchives` materializes
  content-addressed exploded views; `ClasspathResolver` entries carry a `container` dir
  and substitute `classes.jar`. Plugins receive containers as runtime entries —
  `PackageIo.RuntimeEntry.container` / `StepExec.runtimeEntries()` — never CAS paths.
- **`[[packaging.variant]]`** with `when = { config, equals }`: one manifest, per-config
  artifact descriptors (an app's APK vs a library's AAR); `exec-mode = "none"` names a
  non-executable artifact. Config predicates only — packaging resolves before any
  classpath exists.
- **A container packager may emit the conventional classes jar** next to its main
  artifact; both cache under the packager's one action key — workspace consumers keep the
  plain-jar contract with zero engine special-casing.
- **`ExecPlan.deployVerb` extends to `jk dev`**: device artifacts get a rebuild → redeploy
  loop (the client re-dispatches the plugin verb per change; watch roots ride the plan).
- **`[[sdk]]` lock entries** pin provisioned-SDK component revisions (lock-sdk phase);
  step-dependency resolution verifies pins and reports drift.

Android Phase 3 (release) additions — all generic, none Android-special-cased:

- **Sub-schemas + sub-tables** (`[sub-schema.<name>]`, `[sub-tables.<t>]`): typed nested
  table groups on a plugin's own table, schema-validated into `PluginConfig` nested maps.
- **Variant axes** (build-plugins §3.1 realized): a sub-table group may declare
  `variant-axis` (+ `built-in`, `default`, `dimensioned` for flavor dimensions);
  `Variants.apply` folds the selection into ONE flat effective config at parse time, so
  describe keys, contribution predicates, step/packager action keys,
  `[[packaging.variant]]`, and worker specs are all variant-correct with zero variant
  awareness. The selection rides requests as a compact selector
  (`EngineProtocol.withVariant` — the jvm-tuning suffix pattern); `jk build --release`.
- **Secrets side channel**: `secret = true` sub-schema keys + `env:` indirection resolve
  client-side (`ProjectInfo.envRefs` names them; the shell env rides the request — the
  publish posture) and travel only into PACKAGE specs (`PackageIo.secret(key)`); action
  keys carry a digest, never plaintext; describe/step specs and logs never see them.
- **Packagers receive step-dependency tools** (the same artifacts verbs get) — an AAB
  packager forks bundletool exactly like a step forks aapt2; packager-dependencies win
  name collisions.
- **Packager action keys fingerprint container content and `project:` inputs** — an
  assets-only AAR bump or a module `assets/` edit re-packages; before this, only entry
  JARS were keyed.

## 5. Risks & open questions

- **Manifest DSL creep.** The closed predicate set will attract feature requests
  ("just one more condition"). The answer is always "write a code hook" — the manifest
  stays boring on purpose.
- **Worker round-trips for steps.** An Android build chains 5–6 steps; per-step JVM forks
  could hurt. Mitigation: one worker JVM per plugin per build (steps multiplex over the
  protocol), which the process SPI already supports in spirit.
- **Two-plugin composition** (spring-boot + jib-extras both touching packaging): first
  version rules — one packager may replace the main artifact; contributions otherwise
  merge; conflicts are errors, not priorities.
- **`jk dev` hooks** (DevTools detection is currently hard-coded): fold into `RunShape`
  (`devClasspathExtra`, `hotReloadCapable`) in P3, or defer to a P7 if it drags.
- **Schema evolution:** `jk-compat` ranges + manifest-format version field from day one.

**Android Phase-4 SPI findings (2026-07-11):** `contributesTestClasspath` on StepSpec — a
step output dir joining the module's test runtime classpath (Robolectric's
test_config.properties; any test-harness classpath wiring). Engine-side (not SPI, but
generic): the ksp phase + processor-service detection live in the Kotlin pipeline, not
the android plugin — KSP serves any Kotlin module; provided-classpath jars now ride the
test runtime classpath last (stub posture). Candidate future capability: a post-compile
classes-transform step (Hilt unmodified-source parity; bytecode rewriting as a declared
inputs→outputs step).
