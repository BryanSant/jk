# Android on jk — gap analysis & plan

**Status:** PHASES 1–3 LANDED (2026-07-11, worktree-android-plugin; Phase-3 status below).
Phase 1 (0a0cee71, 823c3bd4,
654c5133 — on top of the P6 spike). `plugins/android` builds and deploys a hello-world
APK over the public SPI, zero Android-specific engine code:
- **SDK provisioning (§3.2)**: AndroidSdk root (reuses $ANDROID_HOME/Studio via symlink;
  else ~/.jk/android-sdk), AndroidRepoFeed (repository2 XML: components, per-OS archives,
  sha1, license texts; stable channel preferred), AndroidSdkInstaller (installed →
  short-circuit; else license-gated, checksum-verified download). licenses/ is the exact
  sdkmanager on-disk format — acceptance interops with Studio both ways. `jk android
  licenses [--yes]` + `jk android sdk` ship as plugin verbs.
- **Real platform jar**: compile/link/dex run against the provisioned
  platforms;android-<compile-sdk>/android.jar (the android-all stand-in is gone); javac
  sees it PROVIDED via the new [[contribute.provided-classpath]].
- **manifest-merger**: the android-manifest step (transitive step-dependency closure)
  merges the app manifest — package from [android] namespace, <uses-sdk> injected — and
  aapt2 links the merged manifest (the APK's binary manifest now carries uses-sdk).
- **jk run onto a device**: [packaging] deploy-verb — the exec plan carries it and the
  client dispatches the plugin's deploy verb (adb install -r + am start on the launcher
  activity; adb from provisioned platform-tools; --adb overrides).
Phase-1 honest gap (still open): the exit's "installs and launches" ran against a
scripted fake adb — a live `adb devices` run is the outstanding proof.

**PHASE 2 LANDED** (2026-07-11: 1702e086, 57286fa1, bb8e2ee5, c2549fb8). Real app shape:
- **AAR consumption, resolver-owned**: effective-POM `packaging` decides the fetch (the
  lock records the real file name; `Artifact.coordinate()` honors it everywhere);
  `ExplodedArchives` materializes content-addressed exploded views keyed by archive SHA;
  `ClasspathResolver.Entry` grew a `container` and substitutes `classes.jar` on
  classpaths. Workspace `[android] library` AARs ride the same shape (the AAR packager
  emits the conventional classes jar next to the `.aar`, one action key).
- **Non-transitive R end to end**: dep res dirs compile individually and link in
  classpath order under the app's overlay (`-R --auto-add-overlay`, app wins); the app
  regenerates each dep's `R` from its `R.txt` with the final link ids; libraries link
  `--non-final-ids`, ship `R.txt`, and exclude `R*.class` from `classes.jar` — AGP's
  exact contract. Proven against androidx.core from real Google Maven
  (AndroidRemoteAarTest) and a two-module workspace (AndroidWorkspaceTest).
- **Compose**: a config-gated `[[contribute.kotlin-plugin]]` (zero new SPI) adds the
  embeddable Compose compiler at `${kotlin.version}`. **BuildConfig**: explicit
  `build-config = true` codegen step. **`jk dev` redeploy**: dev plans carry the deploy
  verb + src/res/manifest watch roots; the client loop rebuilds and re-dispatches.
- **SDK revisions pinned**: `[[sdk]]` lock entries (lock-sdk phase; drift reported).
Phase-2 honest gaps: a live `@Composable` e2e compile (constituents proven; lands with
Phase 4's Kotlin work), app-module R narrowing (dep Rs are correctly scoped; the app's
own R still carries merged symbols).

**PHASE 3 LANDED** (2026-07-11: 3edaa240, 1a39f733 + acceptance/docs). Release:
- **Variants as computed parameterizations** (§3.1): generic manifest machinery
  (sub-schemas, variant axes, dimensioned flavors — build-plugins §3.1) folds the
  selected overlays into ONE flat effective config at parse time; `jk build --release`
  (+ `--build-type`/`--flavor`) rides requests as a compact selector. Precedence
  base < flavors < build-type (AGP's); `build-type`/`flavor` inject for
  `[[packaging.variant]]` conditions. Variant-separated caching falls out of config
  keying.
- **R8 full mode** replaces d8 when minifying (release default ON): whole-program
  against the platform, keep rules = plugin baseline + aapt2 `--proguard` (the res link
  always emits them now) + AAR consumer rules + declared `proguard-files` (each a keyed
  project-file input); `mapping/seeds/usage` → `target/r8/`. Stripping + keeps proven in
  AndroidReleaseTest (usage lists the dead class; seeds carry the activity + rule-kept).
- **Signing configs** (§3.1): `[android.signing.<name>]` with `env:` indirection;
  passwords are `secret = true` sub-schema keys riding the secrets side channel (client
  shell env resolves them — ProjectInfo.envRefs names what to resolve; never in tokens,
  describe payloads, or logs; action keys carry a digest). APK: apksig v1+v2+v3
  release / v1+v2 debug. AAB: jarsigner (apksig schemes are APK-only).
- **AAB via bundletool** (§3.3): release links twice (binary + `--proto-format`); the
  aab packager lays out base/ (proto manifest, resources.pb, res, dex, merged assets,
  AAR jni → lib/) and forks `build-bundle` over the transitive closure;
  `bundletool validate` + `build-apks --mode=universal` both accept the result
  (AndroidReleaseTest, real tools). The deploy verb detects `.aab` and installs the
  universal APK (own aapt2 + debug keystore).
- **AAR assets/jni fold** into APK and AAB (module assets win; `.so` STORED — apksig
  page-aligns).
- **docs/android-studio-plan.md** written (the §5 gate): ASwB-modeled project-system
  plugin over the engine's HTTP surface, phases S0–S4.
Phase-3 honest gaps: **optimized resource shrinking is NOT wired** — r8 8.5.35 ships
only the deprecated programmatic `ResourceShrinker` (no CLI; the real shrinker CLI is
AGP-internal). Wire it when jk moves to an r8 line with `--android-resources` support,
or adopt the standalone shrinker artifact. Flavor artifacts share the debug/release
file naming (same extension → a flavor switch overwrites; per-variant output dirs are
Phase-4 polish). `jk run`/`jk dev` of a release build deploy via the verb but the
run/dev EXEC PLAN paths stay debug-shaped. v3 key rotation schema'd, not wired.
**PHASE 4 LANDED (with two recorded gaps)** (2026-07-11, worktree-android-plugin):
- **KSP2 (§3.5)**: an engine `ksp` phase forks `KSPJvmMain` (KSP2's own CLI) — processors
  detected by their registered `SymbolProcessorProvider` service (KSP wins dual-service
  jars; javac keeps the rest on -processorpath); KspResolver picks the newest stable
  STANDALONE KSP2 release (it embeds its own analysis compiler; serves a Kotlin range) and
  CAS-caches the closure. Generated .kt/.java are ordinary sources: unioned into both
  compilers, re-published to the goal keys so freshness stamps match, kotlinc's
  -Xjava-source-roots carries the generated-Java dir, and a Kotlin module with processor
  deps routes through the mixed pipeline (Hilt components are Java).
- **The Room + Hilt gate PASSES** (KspRoomHiltTest, real tools): Room's database impl and
  Hilt's components generate via KSP2, compile, dex, and package. Hilt runs its documented
  plugin-less mode (@AndroidEntryPoint(Base::class) + extends Hilt_*) — the Gradle
  plugin's bytecode transform is NOT required for correctness; an automatic-superclass
  transform step is a candidate generic SPI capability if unmodified-source parity is ever
  wanted.
- **JUnit 4 (§3.6)**: proven — junit:junit + junit-vintage-engine in [test-dependencies]
  run @org.junit.Test through jk's runner unchanged (engines load from the test
  classpath). The provided platform jar rides the test runtime classpath LAST (AGP's
  throw-on-call stub posture).
- **Robolectric (§3.6) — 90%, gap recorded**: new generic `contributesTestClasspath` SPI;
  the android plugin's test-config step writes AGP-shaped test_config.properties (merged
  manifest, raw res, linked ap_, custom package). Proven: vintage runs the Robolectric
  runner, the config is discovered, android-all self-provisions. OPEN: resource lookup
  throws NotFoundException for a correctly-linked id — Robolectric's binary-resources
  mode appears to need AGP's exact unit-test resource apk (a dedicated link of the merged
  table) rather than the plain app ap_; @Disabled repro in RobolectricUnitTest.
- **Instrumented tests (§3.6)**: the `instrument` verb installs app+test APKs and parses
  `am instrument -r -w` raw protocol into per-test results (streamed, failure stacks,
  exit-code contract) — scripted-fake tested; Test Orchestrator (per-test instrumentation,
  clearPackageData) and androidTest-APK assembly are recorded follow-ups.
- **jk avd (§3.6)**: create/list/boot — the AVD definition is avdmanager's on-disk format
  written directly under the managed SDK root (ANDROID_AVD_HOME); boot forks a headless
  emulator, KVM-gated, refusing gracefully without the emulator component. Live
  system-image provisioning + a real boot remain environment-gated (heavy downloads).

**Now-in-Android :core:* inventory (Phase-4 exit attempted honestly — blockers → Phase 5):**
cloned android/nowinandroid; its ~20 modules reduce to convention plugins jk already
covers ([android] library ✓, Hilt ✓ via KSP, flavors ✓ demo/prod, workspace graph ✓) plus
these BLOCKERS, in dependency order: (1) **androidx KMP dual-artifact resolution** — the
Maven POM view of androidx's KMP splits (compose runtime-annotation root AAR + -jvm
variant both resolve; navigationevent drags it via androidx.activity ≥1.10) double-defines
classes at dex; Gradle avoids it via module metadata — jk's resolver needs GMM awareness
or a KMP-redirect rule; (2) **kotlinx-serialization**: a user-declared kotlin compiler
plugin surface in jk.toml (plugins can contribute them; projects can't yet); (3)
**protobuf/datastore-proto**: a protoc codegen step (generic source-gen machinery exists —
needs a protoc tool step, natural as a jk plugin); (4) **Hilt unmodified-source parity**
(the transform, above); (5) Robolectric completion (Roborazzi rides it); (6) lint v1 +
baseline profiles (already Phase 5). None of these is architectural — the SPI held.

**PHASE 5 IN PROGRESS** (2026-07-12, worktree-android-plugin) — burning down the blocker
list in order:
- **A5a — KMP/GMM resolution (blocker 1) LANDED** (fc70f871): marker-POM KMP roots
  resolve Gradle-style — the .module sidecar picks the runtime variant for the build's
  `org.gradle.jvm.environment` (android | standard-jvm, cross-fallback), dep edges
  substitute the GMM target, the root locks as a POM-only alias. Plugins declare their
  environment via `[contribute.resolution] jvm-environment` (android sets it). Fail-soft:
  no marker / no .module = plain-Maven view. Proven against real Google Maven both ways.
  Generic resolver win — server-side Kotlin (coroutines/ktor) gets correct variants too.
- **A5b — project-declared Kotlin compiler plugins (blocker 2) LANDED**: `[[kotlin-plugins]]`
  in jk.toml (`coordinate = "group:artifact[:version]"`, version defaults to the project's
  Kotlin version — the org.jetbrains.kotlin plugin convention; optional `id`, `options`)
  rides the same KotlincRequest.Plugin lane as manifest-contributed plugins, so it action-
  caches identically. Deliberately core, not Android-specific — kotlinx-serialization is
  the NiA driver, allopen/noarg/powerassert ride free. Acceptance: KotlinSerializationTest
  compiles a `@Serializable` class against real Central and asserts the generated
  `$serializer` class exists (the codegen only happens when the plugin actually loaded).
- **A5c — protobuf plugin (blocker 3) LANDED**: `plugins/protobuf`, deliberately
  ecosystem-neutral (any gRPC/protobuf JVM service; datastore-proto is just the NiA
  consumer) and the second real consumer of the before-compile codegen SPI. One generic
  core addition: `${host.os-arch}` manifest interpolation (protoc's classifier vocabulary
  — linux-x86_64, osx-aarch_64; the native-Maven-binary convention), and `@exe` packaging
  flowed through the coordinate machinery untouched. Acceptance: ProtobufPluginTest — a
  plain Java project's message generates, compiles, and packages against real Central.
- **A5d — Hilt unmodified-source parity (blocker 4) LANDED**, riding two new generic
  capabilities: `StepSpec.transformsClasses` (a step's output REPLACES the classes dir
  downstream — the build-time-weaving primitive; one per build, COMPILE→PACKAGE window,
  conflicts are errors) and `[[contribute.compiler-args]] ksp` (manifest-contributed KSP
  processor options). `[android] hilt = true` contributes Hilt's superclass-validation
  toggle and registers `android-hilt-transform` — an ASM superclass rewrite to the
  generated `Hilt_*` bases, AGP's exact transform; dex consumes the transformed dir.
  Plugin-less spelling keeps working. Recorded gap: `@AndroidEntryPoint` BroadcastReceivers
  (AGP injects an `onReceive` super-call) keep the plugin-less spelling until demanded.
  Acceptance: HiltTransformTest — unmodified AGP-style sources build to an APK, the
  rewritten superclass asserted byte-for-byte.
- **Sibling harvest (same session): `plugins/shrink`** — R8 `--classfile` full mode over a
  plain JVM app + runtime closure, packaged as one slim executable jar (shrink-only
  default, `-dontobfuscate`; keep rules from config + entry-point). Not an Android
  feature — the generic-JVM payoff of the R8 keep-rule plumbing, sharing the r8 artifact.
  Acceptance: ShrinkPluginTest — the shrunk jar RUNS and dead library code is verifiably
  absent. Remaining Phase-5 blockers: (5) Robolectric binary-resources completion,
  (6) lint v1 + baseline profiles — then the full NiA build.
- **A5e — the live NiA :core:* build attempt (2026-07-12, uncommitted sweep harness;
  clone + hand-written per-module jk.tomls in the session scratchpad).** TEN of twelve
  :core modules build green: model, common, datastore-proto, network, database,
  datastore, notifications, analytics, data (7-sibling AAR aggregator), domain —
  covering GMM/KMP variant selection, [[kotlin-plugins]] serialization, KSP2 Room
  (auto-migrations via [build] ksp-options) + Hilt, the protobuf plugin's Kotlin DSL,
  workspace AAR composition with non-transitive R, the androidx Compose BOM
  ([platform-dependencies]) and a live @Composable compile (closing the Phase-2 recorded
  gap), and custom BuildConfig fields. Eight product findings fixed en route (GMM
  attribute-less variants; protobuf --kotlin_out + contributed-source mixed routing; AGP
  src/main layout; build-config-fields; WorkspaceMerge dropping pluginConfigs/[build];
  [build] ksp-options; kotlinc -module-name + IC-state config keying + the zero-output
  cache guard; library manifests merging only themselves). Recorded deviations: Kotlin
  ^2.4.0 (jk's BTA floor; NiA pins 2.3.0), compile-sdk 34 (warm test SDK; NiA pins 36).
  **COMPLETE (2026-07-12, a0537358): all TWELVE :core modules build green from a full
  clean** — findings 9-12 closed it: (9) AGP-style dependency resource merging
  (ResourceMerger — the AAR closure folds into one tree, values merged
  element-by-element, before a single aapt2 link; compose-ui vs compose-foundation
  string/autofill was the forcing conflict); (10) libraries regenerate dependency R
  classes too (non-final ids — ui references designsystem.R); (11) KMP redirect targets
  pin EXACT (available-at is an exact reference; the lower bound floated
  kotlinx-datetime's -jvm onto the 0.8.0-0.6.x-compat line); (12) kotlinc IC state
  resets when the output dir is empty (surviving cache-side IC state + a cleaned
  target/ produced successful near-empty compiles — including an empty library jar
  that "passed"). designsystem carries the full Material 3 stack; ui consumes sibling
  Rs. Next: feature/* (mechanical), then :app — flavors, full manifest merge, release
  R8/AAB.
Companion research: [android-gradle.md](./android-gradle.md).
**Goal:** build, test, and ship a modern Android app (Compose-first, AGP-9-era baselines:
compileSdk 37, minSdk 24+, Kotlin 2.3+, R8 full mode, AAB) with **no Gradle and no AGP**.
North star acceptance: **jk builds Now in Android** (`android/nowinandroid`) — Google's
own reference app and the de-facto conformance suite for the recommended stack.

Honesty up front: this is the largest single feature jk could take on — AGP is ~15 years
of orchestration. The saving grace, and the reason this is feasible at all: **AGP is
orchestration around separable tools that Google ships independently** (aapt2, d8/r8,
manifest-merger, bundletool, apksigner, lint, KSP, the Compose compiler are all Maven
artifacts or build-tools binaries). jk's architecture — resident engine, forked workers,
CAS, tool provisioning — is exactly the right shape to drive them directly.

## 1. What AGP actually does (decomposed)

| AGP responsibility | Underlying tool | Distribution |
|---|---|---|
| SDK management | `sdkmanager` (cmdline-tools), licenses | Google `repository2` XML feed + zips |
| Resource compile/link, R generation | **aapt2** | `com.android.tools.build:aapt2` (per-OS classifier — jk's native-classifier machinery already handles this pattern) |
| Manifest merging | `manifest-merger` | `com.android.tools.build:manifest-merger` (plain JVM library) |
| Dex + desugaring | **D8** | `com.android.tools:r8` jar (+ `desugar_jdk_libs` for core-lib desugaring) |
| Shrink/obfuscate/optimize | **R8** (full mode) | same `com.android.tools:r8` jar |
| Kotlin (built-in since AGP 9) | KGP/K2 | jk's kotlin-compiler worker (BTA) already |
| Compose compiler | Kotlin compiler plugin | `org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable`, version == Kotlin |
| KSP | KSP2 | `com.google.devtools.ksp:symbol-processing-*` (K2-aligned, embeddable API) |
| APK packaging/align/sign | zipflinger/apksig | `apksigner`+`zipalign` (build-tools) or `com.android.tools.build:apksig` library |
| AAB | **bundletool** | `com.android.tools.build:bundletool` jar |
| AAR produce/consume | zip format | plain packaging + resolver work |
| Lint | lint CLI/library | `com.android.tools.lint:lint` |
| Instrumented tests / emulators | adb, avdmanager, emulator, system-images | SDK components |
| Baseline/startup profiles | Macrobenchmark (on-device) + `profgen` | androidx libs + `com.android.tools:profgen` |

Variants, the `android{}` DSL, convention plugins, configuration cache, isolated
projects — the other half of AGP — are **Gradle-problem solutions** jk replaces with its
own declarative model rather than reimplements (§3.1, §6).

## 2. Gap table — jk today vs. required

| Capability | jk today | Gap |
|---|---|---|
| Android SDK provisioning | JDK/tool provisioning (`ToolProvisioner`, probe chain, license-free feeds) | **New feed**: cmdline-tools + `sdkmanager`-equivalent (platforms, build-tools, platform-tools, emulator, system-images) + license acceptance UX |
| Compile against `android.jar` | javac/kotlinc workers with configurable classpath | bootclasspath/`-Xandroid`-style wiring; `core-lambda-stubs`; API-level selection |
| Resources (aapt2, R) | — | compile/link phases, non-transitive per-module R, resource merging across modules/AARs |
| Manifest merge | — | worker around manifest-merger; placeholders, `dist:` namespace for feature modules |
| Dexing (D8) + desugaring | — | worker around r8 jar in D8 mode; incremental per-module dex; `desugar_jdk_libs` |
| R8 release pipeline | — | keep-rule collection (app + consumer rules from AARs + generated), full-mode defaults, mapping.txt outputs, optimized resource shrinking |
| AAR consume | resolver handles jars only | AAR unpack in resolver/CAS (classes.jar, res, manifest, consumer-rules, api.jar); AAR **produce** for `[android] library` |
| Variants | profiles + features (flavor-less) | **debug/release build types + flavor dimensions** as a first-class axis (§3.1) |
| Signing | GPG/Sigstore for Maven publish | apksig v2/v3(+rotation)/v4, keystore config, Play upload-key posture |
| AAB / bundletool | — | worker: `build-bundle`, local `build-apks`/`install-apks`, universal APK |
| Compose | kotlinc worker passes extraArgs | compiler-plugin jar wiring (version == Kotlin), `composeCompiler`-equivalent flags, stability config |
| KSP | annotation `processor` scope (javac) | KSP2 worker; per-variant generated sources (Room/Hilt/Moshi are the gate to real apps) |
| Unit tests | jk test (JUnit 6, forked runner) | Android unit-test flavor: JUnit4 runner path, `android.jar` stubs / Robolectric resource wiring |
| Instrumented tests | — | adb worker (install, `am instrument`, result parsing); managed-device equivalent (avdmanager + headless emulator, ATD images) |
| Lint | jk audit (OSV), format | lint worker (release-gating checks); scope-limited v1 |
| Baseline/startup profiles | — | orchestrate macrobenchmark on device → `baseline-prof.txt` → profgen `.prof` at package time; DEX layout optimization is R8-driven |
| Play Feature/Asset Delivery | — | out of scope until core ships (§5) |
| IDE story | — | **critical external dependency**: Studio sync speaks Gradle; see §5 risk |

## 3. Design

### 3.1 The model: `[android]` + variants without the DSL

```toml
[project]
group = "com.example"; name = "app"; version = "1.0"; kotlin = 21   # Kotlin project, JDK 17+ toolchain

[android]
namespace   = "com.example.app"
compile-sdk = 37
min-sdk     = 24
# target-sdk defaults to compile-sdk (AGP 9 posture)
compose     = true

[android.build-types.debug]                # debug/release built in, like AGP
application-id-suffix = ".debug"

[android.build-types.release]
minify = true                              # R8 full mode + optimized resource shrinking (defaults ON)
proguard-files = ["proguard-rules.pro"]    # -android-optimize defaults included
signing = "release"

[android.flavors.tier]                     # optional dimension → variants = types × flavors
free = { application-id-suffix = ".free" }
paid = { application-id-suffix = ".paid" }

[android.signing.release]
store-file = "env:KEYSTORE_FILE"           # env-indirection, never secrets in TOML
store-password = "env:KEYSTORE_PASSWORD"
key-alias = "env:KEY_ALIAS"
key-password = "env:KEY_PASSWORD"

[dependencies]
core-ktx  = "1.16.0"                       # catalog ships androidx short names
compose-bom = { group = "androidx.compose", name = "compose-bom", version = "2026.06.00", platform = true }
material3 = { group = "androidx.compose.material3", name = "material3" }   # versionless via BOM

[processor-dependencies]                   # KSP processors ride the existing scope
room-compiler = "2.7.0"
```

Library modules: `[android] library = true` (no `application-id`); workspace modules
compose exactly as today (`:app` → `:feature:*` → `:core:*` — jk workspaces already
model this, with per-module builds, parallelism, and caching).

Deliberate cuts vs. the AGP DSL: no `buildFeatures` grab-bag (aidl/renderscript/shaders
are legacy-off in AGP 9 anyway; BuildConfig becomes an explicit small codegen toggle);
**no KAPT ever** (KSP only — AGP is deprecating it too); **no DataBinding** (ViewBinding
later if demanded; Compose-first); no density splits (removed in AGP 9); per-variant
overrides use the same table-scoping pattern instead of a Groovy DSL. AGP 9's default
flips (non-transitive R, target=compile, full-mode R8) are jk's *only* mode — no legacy
flags to migrate.

Variant mechanics: variants are computed goal parameterizations, not configured objects —
there is no configuration phase to explode, so "variant explosion" costs nothing until a
variant is actually built. The `androidComponents`/convention-plugin machinery (research
§3–4) exists to fight Gradle's own model; jk's TOML + workspace inheritance replaces it
outright (Now in Android's 16 convention plugins ≈ a dozen TOML lines).

### 3.2 SDK provisioning — extend, don't invent

Extend the JDK-style provisioning to an `AndroidSdkInstaller`: parse Google's
`repository2` feed, install `cmdline-tools`, `platforms;android-<N>`,
`build-tools;<v>`, `platform-tools` under `$JK_JDKS_DIR`-style roots with the existing
probe chain (**reuse an existing `$ANDROID_HOME`/Studio SDK via symlink** — the
tool-discovery pattern, so Studio users never download twice). License acceptance is an
explicit `jk android licenses` prompt (recorded hashes, exactly like `sdkmanager
--licenses`); CI uses `--yes`. Versions pinned from `[android]` and recorded in
`jk.lock` — **hermetic, lockfile-pinned SDK components**, which Gradle does not give you.

### 3.3 The build pipeline (app module, debug)

New engine phases (all forked workers, all CAS/action-key cached):

```
merge-manifest → aapt2-compile (per res dir, incremental) → aapt2-link (→ R.txt, R.jar, proto/binary res)
→ compile (kotlinc [+compose plugin] + javac against android.jar + R) → ksp (before compile, per round)
→ dex (D8 per-module, desugared, core-lib desugaring optional) → package-apk (zipflinger) → sign (apksig)
```

Release inserts `r8` replacing per-module dex (whole-program: classes + consumer rules +
generated rules from aapt2 + mapping outputs + optimized resource shrinking) and
`bundle` (bundletool build-bundle) as the default artifact; `jk run` on a device uses
bundletool `build-apks --local-testing` + adb install. AAR consumption lands in the
resolver: an AAR materializes as classes.jar (classpath) + res (linked) + manifest
(merged) + consumer rules (R8) — cached per-AAR in the CAS.

### 3.4 Verbs

- `jk build` → debug APK (app) / AAR (library); `jk build --release` → AAB + mapping.
- `jk run` → build, install to the selected device/emulator, launch main activity —
  the universal-runner model extends: an Android project directory target "runs" onto a
  device. `jk devices` lists/provisions (managed-device equivalent: avdmanager +
  headless emulator with `aosp-atd` images, provisioned like JDKs).
- `jk test` → local unit tests; `jk test --device` → instrumented (adb + orchestrator
  semantics: per-test instrumentation, clearPackageData).
- `jk dev` → install + incremental redeploy loop (Compose-first; full Apply-Changes
  parity is explicitly out of scope — restart-based deploy first).
- `jk android licenses|sdk|avd` — provisioning surface.

### 3.5 Compose & KSP

- Compose: kotlinc worker gains compiler-plugin support (`-Xplugin=` the embeddable
  compose plugin matched to the Kotlin version — the version-matching dance is gone by
  construction, same as Kotlin 2.x Gradle). `[android] compose = true` adds the plugin +
  default flags; metrics/stability-config as `[android.compose]` keys.
- KSP2 worker: drive the `symbol-processing-aa` (standalone/embeddable K2 API) per
  module/variant; `[processor-dependencies]` entries that are KSP processors are detected
  by artifact convention. Gate: **Room + Hilt green** on a sample app before calling it
  done — they are the ecosystem's real acceptance test.

### 3.6 Testing

- **Local unit**: jk's test-runner adds a JUnit 4 path (Android's default; the existing
  JUnit-6 runner covers `de.mannodermaus`-style suites later) with `android.jar`
  default-values stubs; Robolectric works when its deps + merged resources are wired
  (`isIncludeAndroidResources` equivalent on by default for modules with res).
  Roborazzi screenshot tests then come free (it's Robolectric-based).
- **Instrumented**: adb worker — install app+test APKs, `am instrument -w` with
  orchestrator, parse the proto/status output into jk's test reporting.
- **Managed devices**: `jk avd` provisions system images + AVDs (lockfile-pinned),
  boots headless emulators, runs shards. Same provisioning DNA as JDKs.
- **Baseline profiles** (later phase): a `[android] baseline-profile` producer module
  runs Macrobenchmark on a managed device, emits `baseline-prof.txt`; the packager runs
  profgen → `.prof` into the artifact. Startup-profile DEX layout feeds R8.

### 3.7 Migration & catalog

- Catalog ships androidx short names (`core-ktx`, `compose-bom`, `room-*`, `hilt-*`) —
  jk's layered catalog replaces `libs.versions.toml` outright.
- `jk import` (compat-bridge) learns AGP: translate `android{}` DSL, flavors, signing
  configs, catalog, and convention-plugin conventions into `[android]` TOML. This is the
  adoption wedge — Now in Android importing cleanly is the benchmark.

## 4. Phasing (long haul — each phase independently useful)

1. **Hello, APK** — SDK provisioning + licenses; single-module app: manifest merge,
   aapt2, kotlinc/javac vs android.jar, D8, package, debug-sign, `jk run` onto a
   connected device. *Exit: a Compose-less hello-world APK installs and launches.*
2. **Real app shape** — AAR consumption, multi-module workspaces with non-transitive R,
   Compose compiler, BuildConfig, `jk dev` redeploy loop. *Exit: a small Compose app
   with androidx deps builds and runs.*
3. **Release** — R8 full mode + keep-rule plumbing + mapping/retrace outputs, optimized
   resource shrinking, signing configs, AAB via bundletool (+ universal APK), variants
   (build types × one flavor dim). *Exit: `jk build --release` produces a
   Play-uploadable AAB; bundletool-verified.*
4. **Processors & tests** — KSP2 (Room+Hilt gate), local unit tests + Robolectric,
   instrumented tests over adb, `jk avd` managed devices. *Exit: Now in Android's
   `:core:*` modules build; its unit-test suite runs.*
5. **North star & polish** — full Now in Android build (app + feature modules +
   Roborazzi), lint v1, baseline profiles, `jk import` for AGP projects, migration doc.
6. **Deferred until demanded** — dynamic features/asset packs, NDK/AGP-prefab, Wear/TV
   form-factor specifics, Privacy Sandbox, KMP Android targets, ViewBinding.

## 5. Risks — stated plainly

- **The IDE is the moat.** Android Studio's sync, preview, and debugging pipelines speak
  Gradle's Tooling API. jk can build/test/deploy headlessly and via any editor with a
  JVM language server, but **Studio-grade integration needs its own project-system
  plugin** (Studio's project system is pluggable — Bazel's ASwB proves third-party
  project systems are viable). Without it, adoption is CLI/CI-first. This is the single
  largest risk and deserves its own plan doc before phase 3 completes; the engine's
  planned HTTP surface (docs/http.md) is a natural sync endpoint.
- **Tool-flag fidelity.** aapt2/R8/bundletool flags AGP passes are semi-documented;
  behavior conformance needs golden tests comparing jk vs AGP outputs (APK diff, dex
  diff, mapping equivalence) on reference apps. Budget for a conformance harness early.
- **Churn cadence.** AGP majors are quarterly-ish; but jk depends on the **tools**, not
  AGP — tool CLIs/jars (aapt2, r8, bundletool) are far more stable than AGP's DSL. Pin
  per-release in the lockfile; track the AGP release notes for default-flip guidance.
- **Ecosystem plugins** (Crashlytics upload, google-services.json, Play publishing):
  each is a small artifact-transform or upload step jk can add as verbs/keys
  (`google-services.json` codegen is a tiny generator; Play publishing via the Play
  Developer API), but the long tail is real — prioritize by demand.
- **KAPT-only processors** — declared unsupported; the modern set (Room, Hilt, Moshi,
  Glide) is KSP-native.

## 6. Why jk ends up better, not just equal

The entire §4/§8 of the research doc — Kotlin DSL, version catalogs, convention plugins,
configuration cache, isolated projects, build-cache fix plugins, `gradle.properties`
tuning — is **accidental complexity of Gradle's model**, and jk deletes it: TOML has no
configuration phase to cache, workspace inheritance replaces convention plugins,
per-module goals parallelize and cache by construction, and the catalog is built in.
Add hermetic lockfile-pinned SDK/emulator provisioning (Gradle: "install Studio first"),
supply-chain (SBOM/Sigstore/OSV) for APK/AAB artifacts, reproducible-build verification
of release artifacts, one native binary instead of wrapper+daemon JVMs, and a
resident-engine deploy loop. The honest counterweight: AGP's breadth and Studio
integration took Google a decade — jk wins by being radically simpler on the modern 90%
(Compose, KSP, R8-full, AAB), not by cloning all of AGP.
