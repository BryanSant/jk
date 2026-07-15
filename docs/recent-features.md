# Recent features — 2026-07-11 → 2026-07-13

Everything that landed in the last two days, culminating in the Android north star:
**jk builds Now in Android** — all 27 modules of Google's reference app, a demo-debug
APK and a signed R8-full-mode release AAB, with no Gradle and no AGP. Twenty product
findings from that conformance run are folded in below. Live scenarios validating all
of this live in the companion repo `~/src/oss/jk-examples`.

## Core (jk.toml + build model)

- **`[[kotlin-plugins]]`** — project-declared Kotlin compiler plugins:
  `coordinate = "group:artifact[:version]"` (version defaults to the project's Kotlin
  version — the org.jetbrains.kotlin convention), optional `id` and `options`. Rides the
  same typed lane as manifest-contributed plugins, so it action-caches identically.
  kotlinx-serialization is the flagship consumer; allopen/noarg/power-assert ride free.
- **`[build] ksp-options`** — project-declared KSP processor options as `key=value`
  strings (Room's `room.schemaLocation` for committed schemas/auto-migrations is the
  canonical use). Appended after plugin-contributed options, so the project wins.
- **Workspace fidelity fixes** (both bit real builds):
  - `WorkspaceMerge.applyToModule` now carries the module's **plugin tables and
    `[build]` block** through the merge — previously an `[android]` workspace module
    locked as `standard-jvm` (wrong KMP variants) and silently lost its
    ksp-options/kotlin-plugins/order-after at lock time.
  - Sibling externals now propagate **transitively** (MAIN + EXPORT of every reachable
    sibling fold into the consumer's main scope). Maven's compile scope is transitive,
    and a self-contained artifact hard-requires the closure — an APK was missing theme
    resources three AAR hops away.

## Resolver

- **KMP/Gradle-Module-Metadata variant selection** — marker-POM roots resolve
  Gradle-style: the `.module` sidecar picks the runtime variant for the build's
  `org.gradle.jvm.environment` (`android` | `standard-jvm`, cross-fallback), dep edges
  substitute the GMM target, and the root locks as a POM-only alias. Refined by three
  findings: variants with an **absent** `jvm.environment` attribute count as
  standard-jvm (kotlinx omits it; androidx declares it); documentation-category variants
  are skipped; redirect targets pin **exact** (`available-at` is an exact reference — a
  lower bound once floated kotlinx-datetime's `-jvm` onto the incompatible
  `0.8.0-0.6.x-compat` line).
- **Global KMP variant exclusion** — a platform artifact's own POM can name a
  non-selected sibling concretely (`datastore-core-okio-jvm` → `datastore-core-jvm`),
  double-defining classes at dex. Non-selected siblings of redirected roots now leave
  the resolution whenever the selected sibling is present.
- **Half-published releases no longer fail the solve** — when metadata advertises a
  version whose POM 404s everywhere (a publisher mid-propagation; observed live with
  junit 6.1.2), the solver excludes exactly that version and retreats to the next
  candidate. Genuine network errors stay fatal.
- **Propagation watch index** — `PubGrubSolver.propagate` visits only incompatibilities
  watching the changed package instead of rescanning the full list per round.
- **Parked, decided, documented (finding 13)** — BOM pins are currently hard;
  Gradle's `platform()` is a recommendation that stricter floors lift past. The
  pin-first candidate semantics is decided and tested in-the-small, but wide candidate
  lists on the androidx compose graph drown the solver in `VersionSet.Union`
  intersections; blocked on per-package version interning.

## Kotlin / JVM compile pipeline

- **kotlinc gets `-module-name`** (the project name, lockstep with the KSP round) —
  internal-member mangling (`member$module_name`) is baked into call sites KSP-generated
  Java emits (Hilt factories calling `internal` providers); the mismatch broke any
  internal `@Provides`.
- **Incremental-state hygiene** (two related correctness fixes): the BTA incremental
  working dir is keyed by a hash of the compile config (module name, args, plugins, jvm
  target) so a config change starts fresh IC state; and IC state resets whenever the
  output dir carries no classes — surviving cache-side state plus a cleaned `target/`
  used to produce *successful, near-empty* compiles, including an empty library jar
  that "passed". A zero-output success for a non-empty source set is also never
  action-cached.
- **Mixed-pipeline routing for contributed sources** — a Kotlin-only module whose
  plugin steps contribute sources routes through the mixed pipeline (javac compiles
  contributed Java; kotlinc reads it from source via `-Xjava-source-roots`), and the
  **KSP round now sees plugin-contributed source dirs** (roots + freshness stamp +
  ordering after source-gen steps). A contributed `@Module` or protoc output is
  processor input like any hand-written file.

## Plugin SPI (all plugins)

- **`StepSpec.transformsClasses(dir)`** — a step's output **replaces** the module's
  classes dir downstream (packaging, later steps' `In.classes()`, the native tail).
  One transform per build; must run in the COMPILE→PACKAGE window and consume
  `In.classes()`. The build-time-weaving primitive: Hilt's superclass rewrite today;
  OTel weaving/entity enhancement/offline coverage tomorrow.
- **`[[contribute.compiler-args]] ksp = [...]`** — manifest-contributed KSP processor
  options (condition-gated like all contributions).
- **`${host.os-arch}` interpolation** — protoc-style native-binary classifiers
  (`linux-x86_64`, `osx-aarch_64`, …); pairs with `@type` coordinates (e.g. `@exe`) for
  bare-binary Maven artifacts.
- **`PackageIo.moduleDir()`** — packagers can resolve project-relative inputs without
  deriving layout from `artifactPath()`.
- **Plugin worker jars join step/packager action keys** — the step's *code* is an
  input; a plugin upgrade (or first-party plugin development) used to silently restore
  outputs produced by the old code, surviving full cleans.

## New plugins

- **`protobuf`** — owns `[protobuf]`: per-OS protoc provisioned from Maven
  (`${host.os-arch}` + `@exe`, staged executable), runs over `src` (default `proto/`),
  Java and Kotlin-DSL codegen (`kotlin = true`), `lite` mode for the
  javalite/datastore posture. Deliberately ecosystem-neutral — the second real consumer
  validating the before-compile codegen SPI.
- **`shrink`** — owns `[shrink]`: R8 `--classfile` full mode over the app + runtime
  closure, packaged as one slim executable jar (a NiA-scale fat jar collapses ~85%).
  Shrink-only by default (`-dontobfuscate`); entry point kept automatically; `keep` /
  `keep-files` add rules as declared inputs; `obfuscate = true` writes a `mapping.txt`.
  Zero new engine surface — it validated `replacingMainArtifact` on a second packager.

## Android plugin

Phases 1–4 (landed 2026-07-11): SDK provisioning from Google's `repository2` feed with
sdkmanager-compatible license acceptance and `[[sdk]]` lockfile pins; manifest-merger;
aapt2 compile/link with non-transitive R end to end; AAR consume/produce (resolver-owned
exploded containers); Compose via a config-gated contributed compiler plugin;
BuildConfig codegen; variants (build types × flavor dimensions) as config overlays with
a secrets side channel for signing; R8 full mode with the whole keep-rule chain; APK
(apksig v1+v2+v3) and AAB (bundletool) packaging; deploy/instrument/avd verbs; KSP2
rounds passing the Room + Hilt gate; JUnit4/vintage and Robolectric wiring.

The north-star run then added (2026-07-12/13):

- **Hilt unmodified-source parity** — `[android] hilt = true` contributes the
  processor's superclass-validation toggle and registers an ASM transform (AGP's exact
  contract) over the generic classes-transform SPI, including the BroadcastReceiver
  `onReceive` super-call injection.
- **AGP `src/main/` layout** — AndroidManifest.xml/res/assets resolve at the module
  root (jk's convention, wins) or `src/main/` (AGP's).
- **`build-config-fields`** — custom BuildConfig constants, verbatim
  `TYPE NAME = VALUE` declarations, per-variant via the overlay sub-schema.
- **`extra-src` per variant** — `[android.flavors.<f>] extra-src = ["src/demo/kotlin"]`
  joins variant source dirs to the compilers and KSP via a contributed-sources step;
  the explicit opt-in that keeps variants config overlays rather than a source-set
  object model.
- **Dependency resource merging** — the AAR closure folds into ONE merged tree
  (resource-granular, values merged element-by-element, earlier classpath entry wins)
  before a single aapt2 link; the module's own res stays the app-wins `-R` overlay.
  Forced by compose-ui and compose-foundation both shipping `string/autofill`.
- **Library fidelity set** — libraries regenerate dependency R classes (non-final
  ids); library manifests merge in manifest-merger's LIBRARY mode (placeholders stay
  literal for the one real app merge); manifest-less libraries get a synthesized
  minimal manifest (AGP-7 posture); app merges strip consumed `tools:` declarations.

## Engine & CLI

- **Self-healing, invisible AOT cache** on a pinned HotSpot JDK — the engine trains and
  maps its own AOT cache automatically; `EnginePrewarm` renders the one-time
  "optimizing…" wedge (animated chip, "took Xs" suffix) before a command's own TUI
  takes over; the cache is eagerly assembled before being reported optimized.
- **Engine HTTP hardening** — the HTTP token persists across restarts;
  `jk engine rotate-token` rotates it on demand.
- **Engine lifecycle & status UX** — graceful drain for `jk engine stop` (with
  `--force`), idle self-termination removed, `jk engine start` reports "already
  running", and `jk engine status` gained the PipelineWedge layout with uptime, bulleted
  detail, a 50-cell memory bar, and theme-consistent styling.
- **Git in-process** — jk runs git via the git CLI when present, falling back to
  embedded JGit; the separate git-client worker plugin is gone.
- **Protocol fix** — `Ndjson.strArray` no longer truncates elements containing `]`.

## Known parked items

- Finding 13: BOM-as-recommendation (solver version-interning prerequisite).
- Unified-scope resolution (finding 15): processor-scope constraints can influence
  main-scope picks — the guava/listenablefuture `9999.0-empty` dance is the canonical
  detector; per-scope graphs are the eventual answer.
- Workspace variant propagation: an app's flavor selection reaching sibling AAR builds
  automatically (the harness selects per-module today).
- KSP freshness stamps ignore bare processor-option flips (consistent with existing
  config-stamp posture).

## Core variants (2026-07-13, post-inventory)

The variant mechanism moved from plugin-manifest axes to a core `[variants]` section
(docs/variants.md is the reference; docs/variants-plan.md the rationale):

- `[variants.<dim>.<value>]` declares dimensions/values in core; overlays carry `extra-src`,
  dependency-scope tables, and schema-validated plugin-config tables. `build-type` is the
  built-in dimension (`debug`/`release` built in, default `debug`, `--release` sugar).
- Selection: `--variant <dim>=<value>` (repeatable / comma-join; bare value with one custom
  dimension). `--flavor` and `--build-type` are gone. Wire encoding unchanged
  (`release|contentType=demo`); injected config keys are now `build-type` + `variant.<dim>`.
- `[build] extra-src` is a general core feature (extra source roots for compiler + KSP);
  variant overlays append to it. The android plugin's `extra-src` schema key and
  `VariantSourcesStep` are gone.
- Lock semantics: lock scopes resolve the UNION of every value's dependency overlays (folded
  in `WorkspaceMerge.applyToModule`/`merge` and the standalone lock paths) — one lockfile
  covers every variant; the build folds only the selected value's deps.
- Plugin manifests: `variant-axis`/`dimensioned`/`built-in`/`default` sub-table attributes
  removed; sub-tables are named definition groups only (android keeps `[sub-tables.signing]`).
  A schema key may share a group's name — the TOML value shape (string reference vs table of
  definitions) disambiguates.
- `build.jumpkick.model.Variants` (declaration + `Selection` + `unionDependencies`) and
  `build.jumpkick.plugin.manifest.VariantApply` (fold engine) replace the old
  `plugin.manifest.Variants`.

### Variant selection on every command (same day, follow-up)

`--release` / `--variant <dim>=<value>` moved from jk build-only to every artifact-producing
command: run, dev, test, compile, image, native, publish, install. The selection + client-
resolved `env:` values ride the `Session` (`Session.withVariant`); `BuildPipelines.Inputs`
defaults from it, so every pipeline factory — engine-hosted or in-process — is parameterized
without per-pipeline threading. Engine-side it decodes once in `resolveSession`; client-side it
attaches once in `EnginePluginAdapter.stream` (plus the test/single-build/exec-plan/verb
writers). Exec plans and plugin verbs apply the selection LENIENTLY
(`VariantApply.applyLenient`): unselected mandatory dimensions are skipped, so
`jk android licenses` still answers pre-selection while `jk run --release` deploys the AAB.

### Gap closures + CAS aliasing sweep (same day)

- The CAS never shares inodes with anything, both directions: Cas.putFile (copy,
  temp+atomic) replaced putByLink everywhere (compile-output stores, the CAS prewriter,
  worker-jar sync, repo materialize, file-dep adds), ActionCache.restore copies class
  trees, and jk install copies artifacts instead of linking the build tree. Root cause:
  compilers/packagers rewrite outputs IN PLACE, so any shared inode silently mutated
  "immutable" blobs (76 poisoned blobs found and purged).
- Variant switches are fully safe in place: FreshnessStamp.hasRemovedSources detects a
  shrunken source set and compile-kotlin restarts the merged classes tree clean (javac
  already pruned via JavaIncrementalCompile). The documented stale-class gap is closed —
  its main symptom was actually the CAS aliasing bug.
- Union-lock resolve failures now name each variant value's contributed deps
  (LockFlow.variantUnionHint) with a pointer to docs/variants.md → Locking.

### JDK 17 project floor restored (same day)

requirements.md promises a JDK 17+ project floor; a `jdk = 21`-pinned Kotlin project
couldn't build because workers (class-file 69) forked on the PROJECT's JDK. The rule is
now explicit and enforced: a worker's host JVM is jk's own runtime, the project JDK is an
input — kotlinc worker gets `-jdk-home` (KotlincDriver), KSP keeps `-jdk-home=` but hosts
on jk's runtime, the java-compiler AP worker matches the in-process javac posture — unless
the process IS the user's program (the forked test JVM), where everything that rides it is
compiled at `--release 17`: plugin-api is now the dependency-free bottom of the contract
leaf (it owns PluginConfig; model api-depends on IT, staying at the engine's language
level) and jk-test-runner pins 17 with it. JdkFloorTest is the regression: a jdk=17-pinned
Kotlin project builds and its JUnit test asserts java.specification.version == "17".

Follow-up FIXED: JAVA_HOME is published by ensure-jdk from its own install outcome
(parse-build no longer snapshots it before the install exists), so the FIRST build against
a never-installed pinned JDK already compiles and tests on that JDK. FirstBuildJdkTest
reproduces the first-run shape with an empty jdksDir override.

## Engine versioning v0.11 (2026-07-13, P1-P6)

docs/engine-versioning-plan.md implemented end to end: side-by-side versions in
~/.jk/versions/<v>/ materialized by copy from the CAS (VersionStore; install.sh +
self-fetch + self-update + wrapper are four writers of one layout); frozen protocol-zero
(hello/hello-ack + proto int + graceful shutdown) with generational sockets and an
atomically-replaced endpoint pointer — upgrades TAKE OVER instead of killing (lame-duck
drain, displacement watchdog, same-version election); jk.lock carries the toolchain pin
(one grep-able line) and older pins delegate downward as one-shot --job stdio children;
releases are Ed25519-signed (signature-then-hash before materialization, trusted-keys
rotation) with `jk self update [--force]` riding the takeover; `jk wrapper` writes frozen
./jk + jk.bat scripts whose updates springboard through the pinned client; unused
versions are LRU-pruned via the AccessLedger with their per-version AOT state.
