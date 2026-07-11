# Spring Boot 4+ on jk — gap analysis & plan

**Status:** proposed (2026-07-10). Companion research: [spring-boot-4-gradle.md](./spring-boot-4-gradle.md).
**Goal:** a Spring Boot 4 team deletes `build.gradle.kts` and the Boot/dep-management
plugins entirely; jk covers the whole lifecycle — dev loop, packaging, AOT/native, OCI —
natively (delegating to Gradle is an anti-goal), and does several things better.

## 1. What the Boot Gradle plugin actually is

Strip the ceremony and the plugin is five capabilities (research §2–§10):

1. **BOM-governed dependencies** — versionless starters pinned by `spring-boot-dependencies`.
2. **Boot-layout executable jar** — `BOOT-INF/classes` + `BOOT-INF/lib` + loader +
   `layers.idx`/`classpath.idx`, plus the jarmode tools.
3. **Dev loop** — `bootRun`, DevTools restart, `developmentOnly`/`testAndDevelopmentOnly`
   scopes, `bootTestRun` (Testcontainers dev services).
4. **AOT** — `processAot`/`processTestAot` generated source/resource/class sets, feeding
   JVM-AOT mode, CDS/AOT-cache training, and GraalVM native.
5. **OCI packaging** — `bootBuildImage` via buildpacks.

Everything else (build-info, main-class scan, SBOM hook, protobuf alignment) is small.

## 2. Gap table — jk today vs. required

| Capability | jk today | Gap |
|---|---|---|
| BOM import | `[platform-dependencies]` (PRD §7.6) + versionless catalog deps | Verify BOM-managed versionless `[dependencies]` end-to-end against `spring-boot-dependencies:4.x` (70+ module split, Jackson 3 `tools.jackson`); property-style override ergonomics |
| Executable jar | shadow jar (merged) | **No Boot layout.** Shaded Boot apps break on `META-INF/spring/*.imports` merging; need a real `boot-jar` packager + layers |
| Dev scopes | main/provided/runtime/test/processor/export/platform | **No `dev` scope** (`developmentOnly`), no `test-dev`, no production-runtime-classpath subtraction |
| Run loop | `jk run` (build+exec, engine-hosted) | No restart-on-change loop; DevTools works if on the run classpath, but nothing recompiles continuously |
| Test-main run | — | No "run a main from the test source set on the test classpath" (`bootTestRun` → Testcontainers dev services) |
| AOT processing | — | No generated-source-set phase; no `SpringApplicationAotProcessor` invocation |
| Native image | `jk native` (engine-managed GraalVM 25, `[native]`) | Needs AOT outputs on the image classpath + `reachability-metadata.json` + metadata-repository consumption |
| CDS/AOT cache | jk trains its **own** engine AOT cache at install | Not exposed as an app-packaging feature (extract layout + training run) |
| OCI image | `[image]` via jib worker (daemonless) | Not layer-aware (deps/loader/snapshot/app), no Boot env conventions; buildpacks unsupported (deliberately — see §5) |
| build-info | build stamping exists internally | No `META-INF/build-info.properties` emission |
| Main-class detection | `[application] main` required | No bytecode scan fallback (`resolveMainClass`) |
| Starter ergonomics | library catalog (short-name → g:a) | No Spring entries; no `jk new` Spring template; no starter-rename migration hints |

Non-gaps worth stating: Java 17–25 toolchains, Kotlin 2.2+, JUnit 6, lockfile, workspaces,
annotation processors, publishing/SBOM/Sigstore — jk already covers or exceeds these.
Boot's plugin needs Gradle 8.14/9; jk replaces that axis entirely.

## 3. Design

### 3.1 `[spring-boot]` — one table, reactive like the plugin

```toml
[project]
group = "com.example"; name = "shop"; version = "1.0.0"; jdk = 25; java = 25

[spring-boot]
version = "4.0.0"          # imports spring-boot-dependencies:4.0.0 as a platform,
                           # pins the AOT processor + loader + jarmode-tools versions
# aot = true               # enable processAot equivalents (auto-true when [native] present)
# build-info = true

[application]
main = "com.example.ShopApplication"   # optional once main-scan lands

[dependencies]
starter-webmvc = { group = "org.springframework.boot", name = "spring-boot-starter-webmvc" }  # versionless (BOM)
# or, with catalog entries (§3.7): starter-webmvc = "boot"

[dev-dependencies]
devtools = { group = "org.springframework.boot", name = "spring-boot-devtools" }
docker-compose = { group = "org.springframework.boot", name = "spring-boot-docker-compose" }
```

`[spring-boot]` mirrors the plugin's reactive nature: its presence switches `jk build`'s
packaging to the Boot layout, wires AOT phases when enabled, and layer-maps `[image]`.
No task names to learn — the standard verbs (`jk build/run/test/native/image`) change
behavior, which is exactly how the Gradle plugin behaves (reacting to `java`/`war`/NBT).

### 3.2 Scopes: `dev` and `test-dev`

Two new top-level dep tables, matching the plugin's configurations 1:1:

- `[dev-dependencies]` (= `developmentOnly`): on `jk run`/`jk dev`'s runtime classpath,
  **never** in any artifact (boot jar, image, native) — the packager consumes a
  production classpath computed as runtime − dev − test-dev.
- `[test-dev-dependencies]` (= `testAndDevelopmentOnly`): additionally reaches the test
  source set.

Both are general-purpose (not Boot-specific) — they also serve Quarkus/Micronaut later.
Lockfile records them under their own scope so production resolution is unchanged.

### 3.3 Boot-layout packaging (`boot-jar` phase in the build pipeline)

A new packager alongside `JarPackager`/`ShadowPackager` (pure zip assembly, no Boot code
runs at build time):

- `BOOT-INF/classes/` (project classes+resources), `BOOT-INF/lib/` (production runtime
  classpath, CAS hard-links), loader classes extracted from
  `spring-boot-loader:<version>` (fetched via the platform), manifest
  `Main-Class: org.springframework.boot.loader.launch.JarLauncher` +
  `Start-Class: <main>`.
- `BOOT-INF/layers.idx` + `classpath.idx`: default layers `dependencies` /
  `spring-boot-loader` / `snapshot-dependencies` / `application` (research §6) —
  jk computes SNAPSHOT-ness and project-dep-ness from the lockfile, which it owns.
  Custom layering config deferred (defaults cover ~all real apps).
- Bundle `spring-boot-jarmode-tools` (opt-out `include-tools = false`), honor
  `Spring-Boot-Jar-Type: development-tool` exclusions and the 4.0 "optional deps
  excluded" rule.
- War support: **not in v1** (Servlet-container deploys are a shrinking niche; revisit
  on demand).

`jk verify`'s reproducibility check applies to the boot jar like any artifact — something
Gradle does not give Boot users out of the box.

### 3.4 AOT phases

`[spring-boot] aot = true` (implied by `[native]`) adds pipeline phases after `compile`:

1. **`spring-aot`** — fork `java -cp <app runtime cp> org.springframework.boot.SpringApplicationAotProcessor
   <main> <gen-sources> <gen-resources> <gen-classes> <group> <artifact> [app args]`
   (this is all `ProcessAot` does; it's a plain JavaExec). Profiles baked at build time
   via `[spring-boot] aot-args = ["--spring.profiles.active=prod"]`.
2. **`compile-aot`** — compile generated sources against the app classpath into an `aot`
   output folded into packaging (and the native classpath), plus the generated resources
   (GraalVM hints) and pre-generated CGLIB classes.
3. Test AOT (`processTestAot`) deferred to phase 3 — `nativeTest` is explicitly a
   "reserve for native-specific behavior" tool even in the Gradle world.

Incrementality: the AOT phase re-runs when app classes/classpath change — engine-hosted,
cached by the same action-key machinery as compilation.

### 3.5 Native + the efficiency ladder

- `jk native` on a `[spring-boot]` project: run AOT phases, add gen-classes/resources to
  the image classpath, set `-Dspring.aot.enabled=true` conventions, require GraalVM 25+
  (jk's GraalResolver already provisions it), and consume the **GraalVM Reachability
  Metadata Repository** (a Maven-fetchable artifact set — natural fit for jk's resolver;
  this also benefits non-Spring native users).
- **`jk package --aot-cache`** (works for any JVM app, Boot-optimized): produce the
  extracted directory layout (`lib/` + thin app jar), run the training run
  (`-Dspring.context.exit=onRefresh` when Boot is detected, `-XX:AOTCacheOutput`), ship
  layout + `app.aot`. jk already has exactly this machinery for its own engine
  (`install.sh` warms the engine AOT cache) — productize it. On Java ≤24 fall back to
  classic CDS flags. This is a **better-than-Gradle** story: Boot documents the recipe as
  manual Dockerfile steps; jk makes it one flag, locally and in `[image]`.

### 3.6 OCI images: layered jib, not buildpacks

Keep the jib worker and make it Boot-layer-aware: map `dependencies` / `loader` /
`snapshot-dependencies` / `application` to separate image layers (the exact benefit the
Paketo buildpack extracts from `layers.idx`), default a sane base image, set
`BPL`-equivalent JVM env, and support `--aot-cache` training inside the image build.
Rationale for **not** implementing CNB: buildpacks' value is Dockerfile-less builds and
layer reuse — jib already gives jk both **without a Docker daemon**, more reproducibly,
and engine-cached. Document the trade (`bootBuildImage` parity table); revisit CNB only
if users need Paketo-specific behaviors (spring-cloud-bindings can be added as a layer
directly).

### 3.7 Dev loop: `jk dev`

New verb (general, Boot-tuned): watch sources → engine incremental compile → the running
app restarts. Two tiers:

- **Tier 1 (ships first):** run with `[dev-dependencies]` on the classpath; when DevTools
  is present, jk's recompile-on-change *is* the restart trigger (DevTools watches the
  classes dir — its two-classloader restart just works). Static-resource live reload via
  `sourceResources`-equivalent (serve resources from `src/main/resources`).
- **Tier 2:** `jk dev --test-main` = `bootTestRun` (main from the test source set on the
  test classpath) → the canonical Testcontainers dev-services flow. Docker Compose
  support is runtime-side (the `spring-boot-docker-compose` dep) — jk only needs the
  `dev` scope for it.

Better-than-Gradle: the resident engine + incremental compiler means the change→restart
latency is bounded by javac on the delta, with no Gradle configuration phase in the loop.

### 3.8 Small parity items

- **Main-class scan** — bytecode scan of output classes for `public static void main`
  when `[application] main` is absent (reuses the detection posture jk's script runner
  already has; Kotlin `…Kt` convention included).
- **build-info** — `[spring-boot] build-info = true` emits
  `META-INF/build-info.properties` (group/artifact/version/name + extras; `time` excluded
  by default for reproducibility — jk's default posture anyway).
- **SBOM** — jk's publisher already emits CycloneDX; teach the boot-jar packager to embed
  it at `META-INF/sbom/application.cdx.json` + manifest keys, matching the plugin.
- **`-parameters`** — default for `[spring-boot]` projects (javac + `-java-parameters`
  for Kotlin), as the plugin does.

### 3.9 Ecosystem on-ramp

- **Catalog:** ship `spring-boot-starter-*` short names (post-4.0 names; deprecated
  aliases map with a rename hint — jk can *diagnose* `starter-web` → `starter-webmvc`
  and the "raw dep needs an explicit starter now" traps (flyway-core → starter-flyway…)
  at resolve time, which Gradle cannot).
- **`jk new --spring`** — start.spring.io-equivalent template: `[spring-boot]`, webmvc +
  test starters, Testcontainers test-main, devtools in `[dev-dependencies]`.
- **`jk import`** — the compat-bridge importer learns the Boot plugin: translate
  `bootJar`/`springBoot{}`/dep-management blocks into `[spring-boot]` + platform +
  scopes.

## 4. Phasing (each ships alone)

1. **Foundations** — `dev`/`test-dev` scopes + production-classpath subtraction; BOM
   verification suite against `spring-boot-dependencies:4.x` (managed versionless deps,
   `tools.jackson`, override ergonomics); main-class scan; Spring catalog entries.
   *Exit test: a Boot 4 webmvc app resolves, builds, `jk run`s, `jk test`s.*
   **DONE (2026-07-09).**
2. **Packaging** — boot-jar layout + layers + jarmode-tools + build-info + SBOM embed;
   layered jib `[image]`. *Exit: `java -jar` runs it; `-Djarmode=tools list-layers`
   matches Gradle's output byte-for-layer; image layers cache independently.*
   **IN PROGRESS:** `[spring-boot]` table (§3.1) parses and auto-imports the
   `spring-boot-dependencies` BOM; `-parameters` / `-java-parameters` default live
   (§3.8). Verified end-to-end 2026-07-10: a versionless-starter webmvc app locks,
   builds, and serves with parameter-name binding. (En route, fixed a resolver
   correctness bug: POM dependency identity is `group:artifact:type:classifier`, so a
   managed `test-jar` row no longer shadows the real jar — logback-core used to vanish
   from every Boot classpath.) Boot-layout packaging ships (2026-07-10):
   `[spring-boot]` switches the main jar to the executable layout — loader exploded at
   the root, STORED nested libs with original `artifact-version.jar` names,
   `classpath.idx` + `layers.idx`, jarmode-tools bundled unless
   `include-tools = false`. `jk run` uses the classes dir + RUN classpath so dev-scope
   deps (DevTools) ride locally while staying out of the jar. Verified: `java -jar`
   boots and serves; `-Djarmode=tools list-layers` prints the four default layers;
   devtools absent from `BOOT-INF/lib`.
   **DONE (2026-07-10).** Completed: `build-info = true` emits
   `BOOT-INF/classes/META-INF/build-info.properties` (no `build.time` —
   reproducibility); a CycloneDX SBOM generated from the lockfile is always embedded at
   `BOOT-INF/classes/META-INF/sbom/application.cdx.json` (+ `Sbom-*` manifest headers);
   `jk install` links the self-contained boot jar and launches with `-jar`; `jk image`
   maps Boot layers to OCI layers (release deps / snapshot deps / exploded classes,
   entrypoint `java -cp /app/classes:/app/libs/*`) — a code change churns a KB-scale
   layer instead of the whole fat jar. Verified in podman. Also verified against Boot
   **4.1.0** (spring-core 7.0.8 line): lock, build, `java -jar`, jarmode list-layers +
   extract.
3. **AOT & native** — spring-aot/compile-aot phases; `jk native` integration +
   reachability-metadata repo; `jk package --aot-cache` ladder. *Exit: native webmvc app
   builds and serves; AOT-cache startup ≈ Boot's documented ~1.5×.*
   **CORE DONE (2026-07-10).** `aot = true` (auto with `[native]`) forks
   `SpringApplicationAotProcessor` against the production classpath during boot
   packaging, compiles the generated sources, and embeds classes + GraalVM hints in the
   jar (JVM runs opt in with `-Dspring.aot.enabled=true` — verified "Starting
   AOT-processed", 0.64s). `jk native` on Boot uses exploded classes + AOT output on the
   image classpath with the Start-Class scan; verified: native webmvc binary builds
   (1m03s) and serves, startup **0.022s**.
   **Deferred items completed 2026-07-11:** AOT output is action-cached (2.5s cold →
   0.1s warm restore); `aot-args` bakes profiles at build time; `jk build --aot-cache`
   ships the JEP 514 ladder for any app (jarmode-extract layout + onRefresh training
   for Boot, self-assembled layout for plain apps, AppCDS fallback on JDK < 25 —
   measured ~1.5×). Still open: reachability-metadata repo consumption (design sketch
   in [boot-on-jk.md](./boot-on-jk.md) "Known gaps"), test AOT.
4. **Dev loop** — `jk dev` tiers 1–2; `jk new --spring`; `jk import` Boot translation;
   migration doc ("Boot on jk" with the parity table).
   **CORE DONE (2026-07-10).** `jk dev` ships general-purpose: watch → engine
   incremental recompile → DevTools hot-restart when devtools is on the RUN classpath,
   plain process restart otherwise (both verified live). `jk new --spring` scaffolds a
   runnable Boot app (BOM table, versionless starter, devtools, controller + test) —
   new → build → run → serves. `jk import` translates Boot Gradle builds: plugin
   version → `[spring-boot]`, versionless coords → platform-managed, developmentOnly /
   testAndDevelopmentOnly → dev / test-dev scopes; an imported webmvc+data-jdbc build
   locks, builds, and serves.
   **Deferred items completed 2026-07-11:** `jk dev` tier 2 auto-injects devtools
   (Central fetch into the CAS, session-only, offline degrades to process restart);
   Kotlin `[spring-boot]` projects compile with the all-open (kotlin-spring) plugin and
   `jk new --spring --lang kotlin` scaffolds a runnable app; every application jar
   (plain/shadow/boot) embeds the lockfile CycloneDX SBOM; the migration guide +
   parity table live at [boot-on-jk.md](./boot-on-jk.md).
   (The import exercise flushed out and fixed: a PubGrub infinite-derive loop on
   NoVersions conflicts, exact-declaration-beats-BOM semantics, and the jk.lock
   round-trip of hyphenated scope names.)

## 5. Risks & open questions

- **Loader/jarmode version coupling** — the boot-jar layout is an implementation detail
  Boot can move (it did in 3.2/4.0). Mitigation: pin loader/tools artifacts from the
  imported BOM version and integration-test `java -jar` + `-Djarmode=tools` against each
  supported Boot minor; scope support to **4.x only** (matching the research doc).
- **AOT processor CLI stability** — `SpringApplicationAotProcessor`'s argv is internal-ish;
  same mitigation (per-minor integration tests). Fallback: invoke via a thin jk-owned
  adapter jar published once.
- **Buildpack parity expectations** — some teams standardize on Paketo. Position layered
  jib + `--aot-cache` as the default answer; leave CNB as a documented non-goal until
  demanded.
- **War / Undertow / JUnit-4-era projects** — out of scope; Boot 4 dropped or deprecated
  them anyway.
- Verify the research doc's §12 open items (starter-rename diagnostics list) against the
  published BOM before wiring resolve-time hints.

## 6. Why jk ends up better, not just equal

No configuration phase (TOML + lockfile beats plugin-reactive Groovy/KTS for cold builds
and IDE sync); resident-engine dev loop with DevTools; reproducible boot jars verified by
`jk verify`; daemonless layered OCI; one-flag AOT-cache packaging (Gradle: a manual
Dockerfile recipe); resolve-time migration diagnostics for the 4.0 module split; and the
whole supply chain (lockfile, Sigstore, SBOM, OSV audit) that Boot users currently
assemble from third-party plugins.
