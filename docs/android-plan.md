# Android on jk — gap analysis & plan

**Status:** Phase-1 SPIKE LANDED (2026-07-11, via build-plugins P6): `plugins/android`
builds a hello-world APK over the public build-plugin SPI — aapt2 compile+link with R
generation (before-compile contributed sources), javac against a platform jar (PROVIDED
scope; Phase-1 stand-in: Maven-published android-all — SDK provisioning per §3.2 still
open), d8 dex, APK assembly + v1/v2 debug signing (bundled apksig), apksig-verified.
Zero Android-specific engine code. Remaining for a full Phase 1: SDK provisioning +
licenses, manifest-merger, `jk run` onto a device (deploy verb). Companion research:
[android-gradle.md](./android-gradle.md).
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
