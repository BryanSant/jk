# Android + Gradle: Ecosystem Analysis

> **Purpose.** A deep reference on how modern Android development is built with Gradle —
> the Android Gradle Plugin (AGP), the recommended project setup, R8/optimization,
> build-performance tooling, packaging/signing/distribution, and the testing + Compose
> toolchain. Scoped strictly to the **absolute-latest, currently-recommended** state of
> the art (mid-2026); legacy tools appear only to mark what replaced them. Compiled from
> `developer.android.com`, the Android Developers blog, `docs.gradle.org`, the Kotlin
> docs, and Google's Now in Android (`android/nowinandroid`) reference app (URLs per
> section).

## Table of contents

1. [Baselines & the toolchain matrix](#1-baselines--the-toolchain-matrix)
2. [The Android Gradle Plugin & `android { }` DSL](#2-the-android-gradle-plugin--android---dsl)
3. [The Variant API (`androidComponents`)](#3-the-variant-api-androidcomponents)
4. [Recommended project setup: KTS, catalogs, convention plugins](#4-recommended-project-setup-kts-catalogs-convention-plugins)
5. [Multi-module architecture & KSP](#5-multi-module-architecture--ksp)
6. [R8: shrinking, obfuscation, optimization](#6-r8-shrinking-obfuscation-optimization)
7. [Baseline & Startup Profiles + Macrobenchmark](#7-baseline--startup-profiles--macrobenchmark)
8. [Build performance & caching](#8-build-performance--caching)
9. [Packaging, signing & distribution](#9-packaging-signing--distribution)
10. [Compose compiler & BOM](#10-compose-compiler--bom)
11. [Testing](#11-testing)
12. [Recommended `gradle.properties` & catalog](#12-recommended-gradleproperties--catalog)
13. [Source index](#13-source-index)

---

## 1. Baselines & the toolchain matrix

**AGP 9.2.x is the current stable line** (9.2.0, April 2026; 9.2.x patches). AGP **9.0**
(January 2026) was the big breaking major — it required Gradle 9, dropped JDK 11, deleted
the legacy variant/DSL APIs, and turned on built-in Kotlin. 9.1 and 9.2 are quarterly
quality releases. Each AGP version is supported ~3 years; the **AGP 10.0** roadmap (late
2026) deletes the remaining old DSL/Variant interfaces and the `builtInKotlin`/`newDsl`
opt-outs.

| Component | AGP 9.2 requires | Notes |
|---|---|---|
| **Gradle** | **9.4.1** | AGP 9.0 was the first to *require* Gradle 9 (9.1.0). |
| **JDK (to run the build)** | **17** (min = default) | JDK 11 support removed in AGP 9.0. |
| **SDK Build Tools** | **36.0.0** | |
| **Kotlin (KGP)** | **2.3.10** bundled | AGP 9.0 bundled KGP 2.2.10. Kotlin 2.4.0 is the latest stable Kotlin. |
| **compile/target API** | up to **37** (AGP 9.1+) | API 37 = Android 17, stable June 2026. Android now uses minor API levels (e.g. 36.1). |
| **NDK** | 28.2.13676358 default | |

**Version reconciliation.** Pin Kotlin to whatever KGP the AGP line bundles (2.3.x for
AGP 9.2) unless you deliberately move ahead; Kotlin 2.4.0 is the newest stable and works
with AGP 8.5.2+/R8 9.1.29+. K2 is the only Kotlin front end now. The Compose compiler
tracks the Kotlin version exactly (see §10).

**Recommended posture for greenfield (mid-2026):** AGP 9.2.x · Gradle 9.4.1 · JDK 17
toolchain · Kotlin 2.3.x (or 2.4.0) · `compileSdk`/`targetSdk` = **37**.

**Google Play gates:** new apps and all updates must target **API 36 (Android 16)** as of
**31 Aug 2026** (extension to 1 Nov 2026); existing apps must target ≥ API 35 to stay
installable on Android 16/17. Targeting the newest (37) is the recommended stance.

Sources: `developer.android.com/build/releases/about-agp`,
`.../agp-9-0-0-release-notes`, `.../agp-9-2-0-release-notes`,
`.../gradle-plugin-roadmap`, `.../build/kotlin-support`,
`developer.android.com/google/play/requirements/target-sdk`.

---

## 2. The Android Gradle Plugin & `android { }` DSL

Plugins are applied via the plugins DSL + a version catalog (§4). Root/`settings` declare
versions with `apply false`; modules apply.

| Plugin id | Applied in | Purpose |
|---|---|---|
| `com.android.application` | app module | Buildable/installable app (APK/AAB). |
| `com.android.library` | library module | Reusable AAR. |
| `com.android.test` | test module | Standalone test (e.g. Baseline Profile / Macrobenchmark producer). |
| `com.android.dynamic-feature` | feature module | Play Feature Delivery module. |
| `com.android.asset-pack` | asset-pack module | Play Asset Delivery pack. |
| `com.android.kotlin.multiplatform.library` | KMP module | New simplified KMP library plugin. |
| `com.android.settings` | **`settings.gradle.kts`** | Centralizes `compileSdk`/`minSdk`/`targetSdk` build-wide (AGP 8.7+). |

> **AGP 9 headline — built-in Kotlin.** AGP 9.0 bundles Kotlin support
> (`android.builtInKotlin=true` by default), so **you no longer apply
> `org.jetbrains.kotlin.android`** in Android modules. Configure Kotlin via the top-level
> `kotlin { }` block (not `kotlinOptions`). KAPT moved from `kotlin-kapt` to
> `com.android.legacy-kapt` (prefer KSP). The `builtInKotlin`/`newDsl` opt-outs are
> removed in AGP 10.0.

### Essential DSL (recommended shape)

```kotlin
android {
    namespace = "com.example.myapp"   // REQUIRED since AGP 8.0; manifest package= is retired
    compileSdk = 37                    // integer form; block form: compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "com.example.myapp"   // library modules omit this
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    flavorDimensions += "tier"
    productFlavors {
        create("free") { dimension = "tier"; applicationIdSuffix = ".free" }
        create("paid") { dimension = "tier"; applicationIdSuffix = ".paid" }
    }

    buildFeatures {
        compose = true
        buildConfig = true   // several buildFeatures default OFF in AGP 9 — see below
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Kotlin configured OUTSIDE android { } — kotlinOptions is deprecated/error
kotlin {
    jvmToolchain(17)   // pins the JDK for compilation (Kotlin + Java)
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
```

- **`namespace`** is required (AGP 8.0+); build variants = product flavors × build types
  (`freeDebug`, `paidRelease`, …).
- **`compileSdk`/`targetSdk` block form** — `compileSdk { version = release(36) { minorApiLevel = 1 } }` supports minor API levels.
- **AGP 9 default flips worth knowing:** `targetSdk` now defaults to `compileSdk` (was
  `minSdk`); `android.nonTransitiveRClass`, `android.useAndroidx`,
  `android.enableAppCompileTimeRClass`, `android.r8.optimizedResourceShrinking`,
  `android.r8.strictFullModeForKeepRules` are **default-ON**; `buildFeatures` `resValues`,
  `shaders`, `aidl`, `renderscript` are **default-OFF** (enable per module).
- **Removed in AGP 9.0:** legacy variant API, `variantFilter`, `dexOptions`,
  `deviceProvider`/`testServer` (→ Gradle Managed Devices), density-split APKs,
  embedded Wear support, `sdkDirectory`/`ndkDirectory` (→ `androidComponents.sdkComponents`).

Sources: `developer.android.com/build/migrate-to-built-in-kotlin`,
`.../android-settings-plugin`, `.../agp-9-0-0-release-notes`.

---

## 3. The Variant API (`androidComponents`)

The old `applicationVariants`/`libraryVariants`/`variantFilter`/`BaseExtension` API is
**removed in AGP 9.0**. Everything goes through the `androidComponents` extension (backed
by the stable `gradle-api` artifact), with three lifecycle callbacks:

```kotlin
androidComponents {
    // 1) finalizeDsl — last chance to mutate the DSL before variants lock
    finalizeDsl { ext -> ext.buildTypes.create("staging") }

    // 2) beforeVariants — enable/disable variants, set build-time-only values
    beforeVariants(selector().withBuildType("release")) { vb ->
        vb.minSdk = 24
        vb.enable = true                 // was enabled()
    }

    // 3) onVariants — read/modify resolved variant properties (lazy Providers), wire tasks
    onVariants(selector().withBuildType("release")) { variant ->
        variant.minSdk                   // was minSdkVersion()
        val out = variant.outputs.single { it.outputType == OutputType.SINGLE }
        out.versionCode.set(provider { gitCommitCount })   // e.g. git-derived versioning
        variant.sources.java?.addStaticSourceDirectory("custom/src/${variant.name}")
    }
}
```

Selectors: `selector().withName(...) / .withBuildType(...) / .withProductFlavor(...)`. The
**Artifacts API** reads/transforms/replaces/appends intermediate & final artifacts —
`variant.artifacts.use(task).wiredWithFiles(...).toTransform(SingleArtifact.MERGED_MANIFEST)`,
and `variant.artifacts.get(SingleArtifact.BUNDLE / APK)` to hook the AAB/APK for custom
upload tasks. Recipes: `github.com/android/gradle-recipes`.

AGP 9 method renames: `minSdkVersion()→minSdk()`, `targetSdkVersion()→targetSdk()`,
`ComponentBuilder.enabled()→enable()`, `transformClassesWith()` moved to `Instrumentation`.

Source: `developer.android.com/build/extend-agp`.

---

## 4. Recommended project setup: KTS, catalogs, convention plugins

The Google-recommended shape (canonical reference: `android/nowinandroid`) is: **Kotlin
DSL everywhere**, **a version catalog as the single source of dependency truth**, an
included **`build-logic` build of convention plugins**, centralized repository/plugin
management in `settings.gradle.kts`, and **KSP over KAPT**.

### Kotlin DSL (`build.gradle.kts`) is the default

Default for `gradle init` and new Android Studio projects since Studio Giraffe / Gradle
8.0; Google "strongly recommends" it. Wins: type-safe accessors, IDE autocompletion/refactoring,
compile-time-checked build scripts, one language across build & app. Keep *logic* out of
build files — put it in plugins so it's testable.

### Version catalog — `gradle/libs.versions.toml`

Gradle auto-discovers `gradle/libs.versions.toml` with no config; AGP/Studio scaffold it.
Four tables — `[versions]`, `[libraries]`, `[bundles]`, `[plugins]`:

```toml
[versions]
agp = "9.2.1"
kotlin = "2.3.10"
ksp = "2.3.10-2.0.4"          # tracks the Kotlin version
composeBom = "2026.06.00"
hilt = "2.59"
room = "2.7.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
compose-bom       = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-material3 = { module = "androidx.compose.material3:material3" }   # versionless → from BOM
room-runtime      = { module = "androidx.room:room-runtime", version.ref = "room" }
room-compiler     = { module = "androidx.room:room-compiler", version.ref = "room" }

[bundles]
compose-ui-test = ["compose-ui-test-junit4", "compose-ui-test-manifest"]

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library     = { id = "com.android.library", version.ref = "agp" }
kotlin-android      = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }  # optional under AGP 9 built-in Kotlin
compose-compiler    = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp                 = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt                = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
# project-local convention plugins declared here too (versionless):
myapp-android-library = { id = "myapp.android.library" }
```

Consume via type-safe accessors (kebab-case → dotted): `libs.androidx.core.ktx`,
`libs.bundles.compose.ui.test`, `alias(libs.plugins.android.application)`,
`libs.versions.kotlin.get()`. Gradle recommends **kebab-case with dashes** for best
completion.

### Convention plugins / `build-logic`

The recommended way to share config across modules (not `buildSrc`, which invalidates on
every change). `build-logic` is a **separate included build** with a `convention` module
registering small, composable, single-responsibility plugins:

```
build-logic/
  settings.gradle.kts
  convention/
    build.gradle.kts
    src/main/kotlin/
      AndroidApplicationConventionPlugin.kt
      AndroidLibraryConventionPlugin.kt
      AndroidLibraryComposeConventionPlugin.kt
      HiltConventionPlugin.kt
      AndroidRoomConventionPlugin.kt
```

`build-logic/convention/build.gradle.kts` applies `kotlin-dsl`, targets JDK 17, depends on
AGP/Kotlin/KSP plugins as `compileOnly`, and registers each plugin with an id from the
catalog:

```kotlin
plugins { `kotlin-dsl` }
dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
}
gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = libs.plugins.myapp.android.library.get().pluginId
            implementationClass = "AndroidLibraryConventionPlugin"
        }
    }
}
```

A `Plugin<Project>` convention plugin:

```kotlin
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        apply(plugin = "com.android.library")
        extensions.configure<LibraryExtension> {
            configureKotlinAndroid(this)     // shared helper: compileSdk, jvmToolchain, etc.
            testOptions.targetSdk = 37
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        dependencies {
            "testImplementation"(libs.findLibrary("junit").get())
        }
    }
}
```

Inside plugin code the catalog is reached via `libs.findLibrary("x").get()` (generated
`libs.*` accessors aren't available there). Modules then apply in one line:
`plugins { alias(libs.plugins.myapp.android.library) }` — eliminating duplicated
`android { }` blocks across dozens of modules. This is also the recommended enabler for
Gradle **Isolated Projects** (side-effect-free, no cross-project state at configuration
time). Now in Android registers ~16 such plugins.

### `settings.gradle.kts`

```kotlin
pluginManagement {
    includeBuild("build-logic")          // wire in the convention plugins
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)   // no per-module repos
    repositories {
        google { /* same content filters */ }
        mavenCentral()
    }
}
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")   // projects.core.data
rootProject.name = "myapp"
include(":app", ":core:data", ":core:designsystem", ":feature:home")
```

Declaring repos in `settings` (not `allprojects`) is current best practice —
`FAIL_ON_PROJECT_REPOS` enforces it; content filters speed resolution and reduce
supply-chain surprises.

Sources: `developer.android.com/build`, `.../build/migrate-to-kotlin-dsl`,
`.../build/migrate-to-catalogs`, `github.com/android/nowinandroid`,
`docs.gradle.org/current/userguide/{version_catalogs,isolated_projects}.html`,
`android-developers.googleblog.com/2023/04/kotlin-dsl-is-now-default-for-new-gradle-builds.html`.

---

## 5. Multi-module architecture & KSP

### Layering

```
:app                (entry point; DI wiring, navigation)
  └─▶ :feature:*     (self-contained UI features)
        └─▶ :core:*  (data, database, network, datastore, designsystem, ui, model, common, testing)
```

Rule: **lower layers never depend on higher layers.** Principles: low coupling / high
cohesion, `internal` visibility to prevent leakage, `api`/`impl` splits for stricter
decoupling. Why it matters: only changed modules rebuild, independent modules compile **in
parallel**, and the build cache reuses per-module artifacts. Pitfall Google warns about:
too fine-grained (boilerplate/overhead) vs too coarse (loses the benefit); a `:core`
everything depends on serializes the graph and kills parallelism — aim for a **shallow,
wide** dependency graph and prefer `implementation` over `api` to limit recompilation
propagation.

### KSP replaces KAPT

**KAPT is in maintenance mode** — it generates Java stubs (30–50% of build time on medium
projects). **KSP analyzes Kotlin directly**, up to ~2× faster; **KSP2 is default since KSP
2.0.0**, aligned with K2. Room, Hilt/Dagger, Moshi, Glide all ship KSP processors.
Migration is usually just swapping the configuration:

```kotlin
plugins {
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
dependencies {
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)        // was kapt(...)
    ksp(libs.hilt.compiler)
}
```

Applied cleanly via convention plugins (a `HiltConventionPlugin`, `AndroidRoomConventionPlugin`).
Drop the `kotlin-kapt`/`com.android.legacy-kapt` plugin once a module is fully on KSP.

Sources: `developer.android.com/topic/modularization`, `.../build/migrate-to-ksp`,
`kotlinlang.org/docs/ksp-why-ksp.html`.

---

## 6. R8: shrinking, obfuscation, optimization

R8 replaced ProGuard entirely — it does **shrinking (tree-shaking) + obfuscation +
optimization + dexing in a single pass**. ProGuard is no longer part of the build; even the
non-optimizing default rules file is being removed.

```kotlin
buildTypes {
    release {
        isMinifyEnabled = true       // code shrink + obfuscate + optimize
        isShrinkResources = true     // resource shrink (requires isMinifyEnabled)
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),  // NOT proguard-android.txt
            "proguard-rules.pro"
        )
    }
}
```

- R8 still consumes **ProGuard-format rules** (`-keep`, `-keepattributes`, `-dontwarn`).
- **Always use `proguard-android-optimize.txt`** — the plain `proguard-android.txt`
  contains `-dontoptimize` and its support is removed in AGP 9.0.
- **Libraries** ship keep rules to consumers via `consumerProguardFiles("consumer-rules.pro")`
  (packaged into the AAR, auto-applied when the app shrinks).

### R8 full mode (default since AGP 8.0)

More aggressive than the old compatibility mode: may change member visibility to enable
inlining; does **not** implicitly keep default constructors; retains `-keepattributes`/
annotations/`Signature` **only** for explicitly-kept classes; aggressive class merging.
Recommended state: no `android.enableR8.fullMode=false` line (remove it if inherited).
Reflection/generics/serialization commonly need explicit keep rules under full mode:

```proguard
-keepattributes Signature, RuntimeVisibleAnnotations
-keep class kotlin.Metadata { *; }                 # Kotlin reflection
-keep class com.example.ReflectivelyMade { <init>(); }
```

### Resource shrinking + optimized resource shrinking

`isShrinkResources` requires `isMinifyEnabled`. Manual keep/discard in `res/raw/keep.xml`
(`tools:keep`, `tools:discard`, `tools:shrinkMode="strict"`). **Optimized resource
shrinking** folds resource references into R8's code graph so resources reachable only
from dead code are removed — **opt-in in AGP 8.12** (`android.r8.optimizedResourceShrinking=true`),
**default in AGP 9.0+**. Reported >50% size cuts for multi-form-factor apps.

### Obfuscation & mapping (retrace)

Mapping written to `app/build/outputs/mapping/<variant>/mapping.txt` (+ `seeds.txt`,
`usage.txt`, `configuration.txt`). It's **auto-bundled into the AAB**, so Play Console
deobfuscates crashes/ANRs; upload per-release to Crashlytics too. Manual:
`retrace mapping.txt stacktrace.txt`. `-dontobfuscate` keeps shrink+optimize without
renaming. **AGP 9.0 auto-deobfuscates R8 builds in Android Studio Logcat.** Google also
ships an **R8 Configuration Analyzer** to flag overly-broad keep rules.

### D8 core-library desugaring

Use newer Java APIs (`java.time`, streams) on old API levels:

```kotlin
android { compileOptions { isCoreLibraryDesugaringEnabled = true } }
dependencies { coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.x") }
```

Sources: `developer.android.com/topic/performance/app-optimization/{enable-app-optimization,full-mode}`,
`.../studio/write/java8-support`,
`android-developers.googleblog.com/2025/{09/improve-app-performance-with-optimized-resource-shrinking,11/use-r8-to-shrink-optimize-and-fast}.html`.

---

## 7. Baseline & Startup Profiles + Macrobenchmark

**Baseline Profiles** list hot classes/methods (Critical User Journeys) that ART
**AOT-compiles at install** instead of JIT-ing later — big startup/jank wins. Uses the
**`androidx.baselineprofile` Gradle plugin** + **Macrobenchmark** to generate. Three roles:
consumer (`:app`), app-target (usually `:app`), producer (`:baseline-profile`, a
`com.android.test` module).

**Producer** `baseline-profile/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}
android {
    namespace = "com.example.app.baselineprofile"
    targetProjectPath = ":app"
    testOptions.managedDevices.localDevices {
        create("pixel6Api34") { device = "Pixel 6"; apiLevel = 34; systemImageSource = "aosp" }
    }
}
baselineProfile {
    managedDevices += "pixel6Api34"
    useConnectedDevices = false
}
```

**Producer test** (also emits a Startup Profile):

```kotlin
@get:Rule val rule = BaselineProfileRule()
@Test fun startup() = rule.collect(
    packageName = "com.example.app",
    includeInStartupProfile = true,     // → Startup Profile
    profileBlock = { startActivityAndWait(); /* exercise CUJs */ }
)
```

**Consumer** `app/build.gradle.kts`:

```kotlin
plugins { id("androidx.baselineprofile") }
dependencies {
    baselineProfile(project(":baseline-profile"))
    implementation("androidx.profileinstaller:profileinstaller:1.4.x")
}
```

Generate: `./gradlew :app:generateBaselineProfile` → human-readable
`src/<variant>/generated/baselineProfiles/baseline-prof.txt`; `ProfileInstaller` compiles
it to the binary `.prof` in the APK/AAB (or Play delivers cloud profiles).

**Startup Profiles** (a subset flagged `includeInStartupProfile = true`) additionally drive
**DEX layout optimization** — startup classes packed into the primary `classes.dex` — for
~15–30% extra startup improvement on top of baseline profiles. Requires an obfuscated,
full-R8 release build.

**Macrobenchmark** (`androidx.benchmark:benchmark-macro-junit4`) both generates
(`BaselineProfileRule`) and verifies (`MacrobenchmarkRule`) profiles, measuring in a
separate process:

```kotlin
@get:Rule val rule = MacrobenchmarkRule()
@Test fun startup() = rule.measureRepeated(
    packageName = "com.example.app",
    metrics = listOf(StartupTimingMetric()),
    iterations = 10,
    startupMode = StartupMode.COLD,
    compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
) { pressHome(); startActivityAndWait() }
```

Compare `CompilationMode.None()` vs `Partial(Require)` to quantify the profile win. Run on
**Gradle Managed Devices** with **`aosp`/`aosp-atd`** (rooted) images for profiling.

Recommended workflow order: enable R8 (minify+shrink+optimize, full mode) → fix keep rules
with the Config Analyzer → add baseline + startup profiles → measure before/after with
Macrobenchmark on a GMD.

Sources: `developer.android.com/topic/performance/baselineprofiles/create-baselineprofile`,
`.../startupprofiles/dex-layout-optimizations`,
`.../benchmarking/macrobenchmark-overview`.

---

## 8. Build performance & caching

### Configuration cache — the headline

Stores the result of Gradle's **configuration phase** (task graph, resolved deps) and
skips it on reuse; also enables parallel execution. Enable:

```properties
org.gradle.configuration-cache=true
org.gradle.configuration-cache.problems=warn   # report, don't fail, during adoption
```

**Status:** the **core is stable in Gradle 9** and is the *preferred* mode — Gradle 9
prints a CLI recommendation to enable it, `gradle init` enables it for new projects, and
the goal is **on-by-default in Gradle 10**. Gradle 9 added **graceful degradation** (core
tasks downgrade instead of failing), cache **encryption**, **dependency verification**,
string dedup, and parallel store/load. Not yet on by default. AGP 8.1+ supports it (a
spurious-invalidation bug was fixed in 8.2+); the AGP 9 line is built around it. Remaining
incompatibilities come from third-party plugins and build logic that reads system state at
execution time or captures `Project`/`Task` in task actions. This **supersedes
configuration-on-demand** (`org.gradle.configureondemand`) — do **not** add that to new
builds.

### Build cache

Distinct from the config cache — stores **task outputs** keyed by inputs:

```properties
org.gradle.caching=true
```

Local `DirectoryBuildCache` by default; a **remote HTTP cache** shares across
developers/CI (declared in `settings.gradle.kts` `buildCache { remote<HttpBuildCache> { … } }`,
typically `isPush = System.getenv("CI") != null`). Most AGP tasks are cacheable; the
`gradle/android-cache-fix-gradle-plugin` patches the few that aren't relocatable — still
commonly applied in 2026. **Develocity** (ex-Gradle Enterprise) is the productized remote
cache + Build Scans platform (plugin 4.0+ for Isolated Projects).

### Isolated Projects

The next frontier: prevents cross-project state access at configuration time → **parallel
configuration** + per-project config caching, and enables cached IDE sync. **Graduated to
incubating in Gradle 9.7 (June 2026)**; property renamed to **`org.gradle.isolated-projects=true`**
(old `org.gradle.unsafe.isolated-projects` is a deprecated alias). IDE support is good in
Studio/IntelliJ 2025.3+; full AGP build-time support is still maturing (tracker
issuetracker.google.com/401234700) — treat as opt-in/experimental for Android. Convention
plugins are the recommended enabler.

### Other levers

- **Non-transitive R classes** (`android.nonTransitiveRClass`, default since AGP 8.0):
  each module's `R` holds only its own resources → compilation avoidance and better cache
  hits (a leaf resource edit no longer ripples up).
- **`android.enableJetifier`** — **removed/unsupported in AGP 9.1+; delete the line.**
- **KSP over KAPT** (§5) — up to ~2× faster.
- **Modularization** (§5) — parallel compile + per-module caching.
- **JVM toolchains** (`jvmToolchain(17)` / `java { toolchain { … } }`) — reproducible
  builds and stable remote-cache keys regardless of the launching JDK; Gradle 9 adds a
  daemon toolchain.
- **Gradle daemon** on (default); **file-system watching** on; Gradle 9 keeps **Java
  compiler daemons alive** (~30% faster Java compile) and reuses Kotlin incremental state.
- **Build Scans** (`--scan`), `--profile`, and Android Studio **Build Analyzer** for
  diagnosing GC pressure, config-cache incompatibilities, and non-cacheable tasks.

Sources: `docs.gradle.org/current/userguide/{configuration_cache,build_cache,isolated_projects}.html`,
`gradle.org/whats-new/gradle-9/`, `developer.android.com/build/optimize-your-build`,
`.../build/build-analyzer`.

---

## 9. Packaging, signing & distribution

### AAB is the format; APK is the artifact

Google Play requires the **Android App Bundle (`.aab`)** — the raw universal APK upload is
dead for Play. The AAB isn't installed; Play's servers generate **optimized split APKs**
per device (ABI + density + language only) and re-sign them. ~15% smaller downloads on
average. APKs still matter **off Play** (sideload, F-Droid, Amazon, enterprise/MDM) — there
you ship a single **universal APK** (a partial split set fails to install on Android 10+).

```bash
./gradlew bundleRelease   # → app/build/outputs/bundle/release/app-release.aab
./gradlew assembleRelease # → APK
```

The `bundle { }` block controls which dimensions Play may split on (all default `true`):

```kotlin
android {
    bundle {
        language { enableSplit = true }   // set false if you switch locale in-app
        density  { enableSplit = true }
        abi      { enableSplit = true }
    }
}
```

> Gotcha: for AAB builds Gradle **ignores the legacy `splits { }` block** entirely —
> split behavior comes only from `bundle { }`. `splits { }` applies to APK builds only.

**`bundletool`** (the same engine Play uses) tests AABs locally:
`bundletool build-apks --bundle=…aab --output=app.apks [--ks=…]`, then
`bundletool install-apks --apks=app.apks`. `--mode=universal` emits the single installable
APK for off-Play channels; `--connected-device`/`--device-spec` build device-matched sets;
`--local-testing` exercises on-demand feature modules.

### Signing

```kotlin
// Load secrets from a git-ignored keystore.properties (or CI env vars)
val props = java.util.Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
android {
    signingConfigs {
        create("release") {
            storeFile     = file(System.getenv("KEYSTORE_FILE") ?: props["storeFile"] as String)
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: props["storePassword"] as String
            keyAlias      = System.getenv("KEY_ALIAS") ?: props["keyAlias"] as String
            keyPassword   = System.getenv("KEY_PASSWORD") ?: props["keyPassword"] as String
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("release") } }
}
```

`.gitignore` must exclude `keystore.properties`, `*.jks`, `*.keystore`; in CI, base64-decode
the keystore onto the runner and read passwords from secrets.

**Play App Signing** (mandatory for new apps): you hold the **upload key** (resettable if
leaked) and sign the AAB with it; **Google holds the app signing key** and re-signs the
delivered APKs. Key rotation / signing lineage via v3 (`apksigner rotate`); on Android 13+
the new key signs, pre-13 keeps the old.

**Signature schemes:** v1 (legacy JAR, only for API < 24), v2 (whole-APK baseline), v3
(v2 + rotation), v4 (streaming, sidecar `.apk.idsig`, applied by Play automatically). AGP
exposes `enableV1Signing`…`enableV4Signing`; **leave them null** — AGP picks defaults by
`minSdk` (skips slow v1 on `minSdk ≥ 24`, signs v2+v3). `apksigner verify --verbose`
reports schemes.

### Play Feature Delivery & Asset Delivery

- **`com.android.dynamic-feature`** modules (declared in the app via `android.dynamicFeatures += …`)
  with delivery mode set in the manifest `dist:` namespace: install-time (default),
  on-demand (fetched at runtime via `SplitInstallManager` from
  `com.google.android.play:feature-delivery`), or conditional (min-sdk / device-feature /
  country). **Recommendation:** they add real complexity — reach for them only for
  genuinely large or optional slices; automatic config splits already deliver the size win
  with zero code.
- **`com.android.asset-pack`** modules for large assets (no code): install-time /
  fast-follow / on-demand via `assetPack { dynamicDelivery { deliveryType = … } }`,
  runtime access via `com.google.android.play:asset-delivery` (`AssetPackManager`), with
  Texture Compression Format Targeting.

### Versioning

One `versionCode`/`versionName` in `defaultConfig`; **per-ABI version codes are obsolete
under AAB** (one artifact, one code, Play generates the splits). Automate from Git via the
`androidComponents.onVariants { … }` API or a plugin like ReactiveCircus `app-versioning`.

### Adjacent (runtime, but signing-linked)

**Play Integrity API** (replaced SafetyNet), **in-app updates** (`AppUpdateManager`) —
both runtime libraries tied to Play App Signing. The monolithic `com.google.android.play:core`
is gone; use the split libraries (`feature-delivery`, `asset-delivery`, `app-update`,
`integrity`), each with a `-ktx` companion (their `Task` type is now
`com.google.android.gms.tasks.*`). **Privacy Sandbox / SDK Runtime** packaging
(`com.android.privacy-sandbox-sdk`-style ASB) is still niche/rollout-stage.

Sources: `developer.android.com/guide/app-bundle`, `.../tools/bundletool`,
`.../studio/publish/app-signing`, `.../guide/playcore/{feature-delivery,asset-delivery}`,
`.../google/play/requirements/target-sdk`,
`support.google.com/googleplay/android-developer/answer/9842756`.

---

## 10. Compose compiler & BOM

### Compose compiler moved into Kotlin

Since **Kotlin 2.0.0** the Compose compiler ships inside the Kotlin repo, **version-matched
to Kotlin**, applied via the **`org.jetbrains.kotlin.plugin.compose`** Gradle plugin. This
replaces the old `composeOptions { kotlinCompilerExtensionVersion }` and the
Compose↔Kotlin compatibility-table dance.

```kotlin
// module with @Composable code
plugins {
    id("com.android.application")
    alias(libs.plugins.compose.compiler)   // id = org.jetbrains.kotlin.plugin.compose, version == Kotlin
}
android { buildFeatures { compose = true } }   // still needed — AGP wiring/preview support
```

Both are required: `buildFeatures.compose = true` is the AGP switch; the Kotlin plugin
registers the compiler with K2. The `composeCompiler { }` DSL controls diagnostics & flags:

```kotlin
composeCompiler {
    metricsDestination = layout.buildDirectory.dir("compose_metrics")   // stability/skippability reports
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_stability.conf"))
    featureFlags = setOf(
        ComposeFeatureFlag.OptimizeNonSkippingGroups,
        // ComposeFeatureFlag.StrongSkipping.disabled(),   // strong skipping is ON by default (Kotlin 2.0.20+)
    )
}
```

**Strong skipping** (default since Kotlin 2.0.20) lets composables with unstable params be
skipped and auto-remembers lambdas (~20% recomposition improvement). Feature flags replaced
the old ad-hoc `-P plugin:` args.

### Compose BOM

Align Compose artifacts via the BOM; declare libraries **versionless**:

```kotlin
val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
implementation(composeBom)
androidTestImplementation(composeBom)
implementation("androidx.compose.material3:material3")       // no version
implementation("androidx.compose.ui:ui-tooling-preview")
debugImplementation("androidx.compose.ui:ui-tooling")
```

BOM `2026.06.00` maps to Compose core `1.11`. The BOM (runtime/UI artifacts) is independent
of the compiler-plugin version (which tracks Kotlin).

### Kotlin 2.x / K2 / `compilerOptions`

K2 is the only front end. **`kotlinOptions` is deprecated → error in Kotlin 2.2.0**; use
the typed `compilerOptions { }` on the `kotlin { }` extension with enum values
(`JvmTarget.JVM_17`, not `"17"`) and `jvmToolchain(17)` to fix the compile JDK.

Sources: `developer.android.com/develop/ui/compose/{compiler,bom}`,
`kotlinlang.org/docs/{compose-compiler-options,gradle-compiler-options}.html`,
`developer.android.com/develop/ui/compose/performance/stability/strongskipping`.

---

## 11. Testing

### Unit (local / JVM) — `src/test/`

```kotlin
android {
    testOptions.unitTests {
        isIncludeAndroidResources = true    // Robolectric / Compose-on-Robolectric
        isReturnDefaultValues = true
    }
}
dependencies {
    testImplementation("junit:junit:4.13.2")            // JUnit4 = Android default
    testImplementation("androidx.test:core:1.6.x")
    testImplementation("org.robolectric:robolectric:4.x")
    testImplementation("io.mockk:mockk:1.x")            // MockK = idiomatic Kotlin mocker
}
```

JUnit 4 is the first-class default; JUnit 5/6 on-device needs the third-party
`de.mannodermaus.android-junit5` plugin (rebranded "android-junit-framework" in 2026,
JUnit 5+6 compatible). **Robolectric** touches the Android framework from the JVM without an
emulator. **Emerging best practice: fakes over mocks** — Now in Android bans mocking
frameworks in favor of hand-written fakes wired via Hilt test APIs.

### Instrumented — `src/androidTest/`

```kotlin
android { defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" } }
dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.x")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.x")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")   // for createComposeRule()
}
```

**Compose UI test** (semantics-tree based, auto-synchronized):

```kotlin
@get:Rule val rule = createComposeRule()       // or createAndroidComposeRule<MainActivity>()
@Test fun flow() {
    rule.setContent { MyAppTheme { MainScreen(fakeState) } }
    rule.onNodeWithText("Continue").performClick()
    rule.onNodeWithTag("welcome").assertIsDisplayed()
}
```

> **April '26: Compose v2 test APIs are the default; v1 is deprecated.** v2 drives the test
> clock with `StandardTestDispatcher` — coroutines are queued until you advance the virtual
> clock, mirroring production scheduling and cutting flakiness. Expect to advance/idle the
> clock explicitly.

### Gradle Managed Devices

AGP provisions/runs/tears down emulators for reproducible CI:

```kotlin
android.testOptions.managedDevices.localDevices {
    create("pixel8api34") { device = "Pixel 8"; apiLevel = 34; systemImageSource = "aosp-atd" }
}
// groups { create("phones") { targetDevices.add(devices["pixel8api34"]) } }
```

Run: `./gradlew pixel8api34DebugAndroidTest` (or `phonesGroupDebugAndroidTest`). **ATD**
(`*-atd`) images are headless, stripped-down, CI-optimized (but no HW-rendering screenshot
tests).

### Screenshot testing

- **`com.android.compose.screenshot`** (first-party, LayoutLib, host-side) — still
  **alpha** (`0.0.1-alpha15`); `android.experimental.enableScreenshotTest=true`, tests in
  `src/screenshotTest/` annotated `@PreviewTest @Preview`, tasks
  `updateDebugScreenshotTest` / `validateDebugScreenshotTest`.
- **Roborazzi** (Robolectric-based, JVM) — the production-proven path Now in Android uses;
  `recordRoborazzi<Variant>` / `verifyRoborazzi<Variant>`, auto-discovers `@Preview`s via
  ComposablePreviewScanner.

### Orchestration & sharding

**Android Test Orchestrator** runs each test in its own `Instrumentation` (crash isolation,
state wipe): `testOptions.execution = "ANDROIDX_TEST_ORCHESTRATOR"` +
`testInstrumentationRunnerArguments["clearPackageData"] = "true"` + `androidTestUtil("androidx.test:orchestrator:…")`.
**Sharding** across managed devices via `android.experimental.androidTest.numManagedDeviceShards=N`
(or Firebase Test Lab's `numUniformShards`/`targetedShardDurationMinutes`).

Sources: `developer.android.com/training/testing/local-tests`,
`.../develop/ui/compose/testing`, `.../studio/test/managed-devices`,
`.../studio/preview/compose-screenshot-testing`,
`.../blog/posts/whats-new-in-the-jetpack-compose-april-26-release`,
`github.com/android/nowinandroid/wiki/Testing-strategy-and-how-to-test`.

---

## 12. Recommended `gradle.properties` & catalog

```properties
# --- JVM / memory ---
org.gradle.jvmargs=-Xmx6g -XX:MaxMetaspaceSize=1g -XX:+UseParallelGC -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8

# --- Core performance ---
org.gradle.caching=true
org.gradle.configuration-cache=true
org.gradle.configuration-cache.problems=warn
org.gradle.parallel=true
# org.gradle.configureondemand  -> OMIT (superseded by configuration cache / isolated projects)

# --- Kotlin ---
kotlin.incremental=true

# --- AndroidX / R classes ---
android.useAndroidX=true
# android.nonTransitiveRClass   -> default true (AGP 8.0+); only set on legacy projects
# android.enableJetifier        -> DELETE (unsupported in AGP 9.1+)

# --- Optional, large builds (opt-in, still maturing for Android) ---
# org.gradle.isolated-projects=true
# org.gradle.configuration-cache.parallel=true
```

The version catalog and `settings.gradle.kts` are in §4; the convention-plugin layout in
§4 is the recommended way to keep these consistent across modules.

---

## 13. Source index

**Official — Android developer docs**
- AGP releases / matrix / roadmap: `developer.android.com/build/releases/{about-agp,agp-9-0-0-release-notes,agp-9-1-0-release-notes,agp-9-2-0-release-notes,gradle-plugin-roadmap}`
- Build configuration & migration: `.../build`, `.../build/{gradle-build-overview,migrate-to-kotlin-dsl,migrate-to-catalogs,migrate-to-ksp,migrate-to-built-in-kotlin,android-settings-plugin,extend-agp,optimize-your-build,build-analyzer,kotlin-support}`
- Modularization: `.../topic/modularization`
- R8 / optimization: `.../topic/performance/app-optimization/{enable-app-optimization,full-mode}`, `.../tools/retrace`, `.../studio/write/java8-support`
- Profiles / benchmark: `.../topic/performance/baselineprofiles/create-baselineprofile`, `.../startupprofiles/dex-layout-optimizations`, `.../benchmarking/macrobenchmark-overview`
- Packaging/signing/distribution: `.../guide/app-bundle`, `.../tools/bundletool`, `.../studio/publish/app-signing`, `.../guide/playcore/{feature-delivery,asset-delivery,in-app-updates}`, `.../google/play/{requirements/target-sdk,integrity}`
- Testing: `.../training/testing/local-tests`, `.../develop/ui/compose/testing`, `.../studio/test/managed-devices`, `.../studio/preview/compose-screenshot-testing`
- Compose: `.../develop/ui/compose/{compiler,bom}`, `.../compose/performance/stability/strongskipping`

**Official — Android Developers blog**
- `android-developers.googleblog.com/2023/04/kotlin-dsl-is-now-default-for-new-gradle-builds.html`
- `.../2025/09/improve-app-performance-with-optimized-resource-shrinking.html`
- `.../2025/11/use-r8-to-shrink-optimize-and-fast.html`
- `.../2026/06/Android-17.html`
- `developer.android.com/blog/posts/whats-new-in-the-jetpack-compose-april-26-release`

**Gradle & Kotlin**
- `docs.gradle.org/current/userguide/{configuration_cache,build_cache,isolated_projects,version_catalogs,best_practices_dependencies}.html`
- `gradle.org/whats-new/gradle-9/`, `blog.gradle.org/{road-to-configuration-cache,best-practices-naming-version-catalog-entries}`
- `kotlinlang.org/docs/{releases,whatsnew24,ksp-why-ksp,compose-compiler-options,gradle-compiler-options,compose-compiler-migration-guide}.html`

**Ecosystem / reference**
- Now in Android: `github.com/android/nowinandroid` (+ `build-logic/README.md`, testing wiki)
- `github.com/gradle/android-cache-fix-gradle-plugin`, `github.com/google/ksp`
- `github.com/takahirom/roborazzi`, `github.com/mannodermaus/android-junit5`
- `github.com/ReactiveCircus/app-versioning`, `github.com/android/gradle-recipes`

---

*Compiled 2026-07-10 from six parallel research passes against the current mid-2026 stack
(AGP 9.2.x, Gradle 9.4.1, Kotlin 2.3.x/2.4.0, Compose BOM 2026.06.00). Library patch
versions shift frequently — take exact pins from the AndroidX/AGP release pages.*
