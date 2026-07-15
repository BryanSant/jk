# Android Studio on jk — the project-system plan

**Status:** proposed (2026-07-11; the plan android-plan.md §5 requires before Phase 3 completes).
**Companions:** [android-plan.md](./android-plan.md) (the build), [http.md](./http.md) (the
engine's HTTP surface — the sync backbone), [thin-client-plan.md](./thin-client-plan.md) (the
protocol discipline the sync endpoint inherits).

## 1. The problem, stated plainly

Android Studio's sync, editing assistance, run/debug, and preview pipelines speak Gradle's
Tooling API to an AGP project. jk can already build, test, sign, bundle, and deploy an Android
app headlessly — but without Studio-grade integration, adoption is CLI/CI-first and stops at the
teams who live in Studio. This is android-plan §5's "the IDE is the moat" risk, and it deserves
its own delivery plan rather than a hand-wave.

Two facts make it tractable:

1. **Studio's project system is pluggable.** `com.android.tools.idea.projectsystem` is an
   extension point; Google's own Blaze/Bazel plugin (ASwB, "Android Studio with Bazel") is a
   shipping third-party project system — the existence proof that sync/run/test/debug can be
   served by a non-Gradle model. ASwB is open source; its `BlazeProjectSystem`,
   `BlazeAndroidModel`, and apk-provider seams are the reference implementation to mirror.
2. **jk already has an IDE-neutral model and a wire protocol.** The thin-client work moved every
   project fact behind engine requests (`PROJECT_INFO`, `IDE_MODEL` — the IntelliJ/VS Code
   generators already consume a resolved per-module view: classpaths, generated-source roots,
   JDKs). Studio sync is "the same answers, served live over HTTP instead of written to disk."

## 2. Architecture

One new artifact: **`jk-studio` — an Android Studio / IntelliJ plugin implementing an
`AndroidProjectSystem`** backed by the jk engine's HTTP endpoint (docs/http.md), not the Gradle
Tooling API.

```
Android Studio ──(project system SPI)── jk-studio plugin ──(HTTP + NDJSON events)── jk engine
                                                                 │
                                                                 └── the same ExecPlans/IdeOps/
                                                                     BuildPipelines every CLI verb uses
```

- **Sync** = `GET /ide/model?dir=…` (a JSON rendering of the existing `IdeWireModel`, extended
  with the Android facts Studio needs: namespace, min/compile SDK, res/asset roots, generated
  `R`/BuildConfig roots, variant list + the selected variant, AAR dependency views, the SDK
  root). No configuration phase exists, so "sync" is a read — the whole class of Gradle sync
  slowness disappears by construction.
- **Builds/runs** = `POST /build` (streaming NDJSON progress events, mapped onto Studio's build
  window), `POST /run` returning the deploy plan; the plugin drives adb itself via Studio's
  own device machinery (`ApkProvider` hands Studio the built APK path / universal APK for AABs,
  exactly like ASwB's `BlazeApkProvider`).
- **File watching**: the engine already watches for `jk dev`; Studio triggers re-sync off
  `jk.toml`/`jk.lock` VFS events — cheap because sync is a read.

### 2.1 What Studio needs, mapped

| Studio capability | Source in jk | Notes |
|---|---|---|
| Module structure + classpaths | `IDE_MODEL` (exists) | plus AAR exploded-container views (exist since A2) |
| Generated sources (R, BuildConfig) | plugin step outputs (exist) | `contributesSources` dirs are already in the IDE model path |
| SDK / platform | `AndroidSdk` root (exists, A1) | Studio wants an SDK path + API level — both known |
| Variant selector | Variants machinery (A3) | expose declared axes + selection; re-sync on switch |
| Manifest index | merged manifest step output | Studio's own merged-manifest view can read the step's output |
| Run configurations | packaging descriptor + deploy verb | `ApkProvider`/`LaunchTask` over the built artifact |
| Debugger attach | plain JDWP on the device | nothing Gradle-specific; Studio owns it once the APK installs |
| Instrumented tests | A4's adb worker | surfaces later, with A4 |
| Compose preview | **the hard one** | preview compiles through Studio's own fast-compile pipeline; ASwB supports it — mirror its `FastPreviewManager` wiring; budget a full phase |

### 2.2 What we deliberately do NOT build

- No Gradle Tooling API shim (a fake Gradle that answers Studio: brittle, chases AGP internals).
- No fork of Studio. The plugin targets stock Android Studio releases; ASwB proves Google keeps
  the SPI viable.
- No bespoke editor support for non-Studio IDEs beyond the existing `jk ide` generators (VS Code
  already works via the Eclipse JDT path).

## 3. Phases

1. **S0 — HTTP IDE endpoint** (engine): `/ide/model` serving the IdeWireModel + Android facts;
   `/build` streaming the existing workspace event vocabulary. Pure re-plumbing of existing
   requests onto docs/http.md. *Exit: `curl` returns the full model for an Android workspace.*
2. **S1 — read-only project system**: open a jk Android project in Studio; modules, classpaths,
   SDK, generated sources resolve; editing/completion/navigation work; no build integration yet
   (builds still CLI). *Exit: Now-in-Android-shaped code edits with full resolution.*
3. **S2 — build + run + debug**: build window wired to `/build` events; run configs install via
   the deploy plan (universal APK for AABs); debugger attaches. Variant selector switches the
   `--release`/flavor selection and re-syncs. *Exit: edit → run → breakpoint on a device from
   inside Studio.*
4. **S3 — Compose preview + fast iteration**: FastPreview/Live-Edit wiring per ASwB's approach;
   `jk dev`'s watch loop as the deploy refresher. *Exit: Compose preview renders and updates.*
5. **S4 — polish + distribution**: JetBrains Marketplace packaging, Studio-version compat
   matrix (the SPI moves between majors — pin per-release like ASwB does), telemetry-free
   defaults.

## 4. Risks

- **SPI churn between Studio majors** — ASwB absorbs this with per-version source branches;
  budget the same. The plugin must declare compat ranges and CI against Studio EAPs.
- **Compose preview internals** are the least-documented seam; S3 is the highest-variance
  phase. Fallback posture: S2 alone (build/run/debug without preview) is already a usable
  daily driver; preview lands when it lands.
- **Two sources of truth for the SDK** (Studio's own SDK manager vs jk's provisioned root) —
  solved by A1's symlink reuse: point Studio at the same root jk manages.
- **Team scale**: this is a product-sized plugin, not a weekend seam. The build side (this
  plan's dependency) is done; the plugin can proceed independently of android-plan Phases 4–5.
