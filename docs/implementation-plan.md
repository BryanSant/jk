# jk — Implementation Plan

**Status:** v0.9 shipped (self-hosting). Plan is now a living document — milestones in §5 reflect what's landed.
**Owner:** bryan.sant@proton.me
**Last updated:** 2026-05-22
**Companion to:** [requirements.md](./requirements.md)

---

## 1. Context & mission alignment

`jk` is the single-binary, opinionated build tool for Java and Kotlin specified by [requirements.md](./requirements.md). Its pitch — *"the fastest way to run your existing Maven or Gradle build, and it just happens to come with a better build tool you can opt into when you're ready"* — sets the design constraint that drives every choice below: jk must be a fast native binary, with reproducibility and supply-chain hygiene by default, and with adoption-first migration story for Maven/Gradle users.

This document is a **terse strategic implementation overview**, not a per-feature design spec. Its purpose: settle the things that have to be settled before code goes down — language, libraries, packages, milestone ordering — so individual subsystems can be designed in isolation later.

---

## 2. Implementation language & bootstrap stack

- **Language: pure Java 25** for jk's own sources during bootstrap. No Kotlin in jk-the-binary. Rationale: GraalVM native-image is most predictable on plain Java; the Kotlin compiler is a *tool jk consumes* for its users, not a build-time dependency of jk itself. Re-evaluate after v1.0 ships.
- **Build tool: Gradle 9.5.1** through v0.8, replaced by jk itself at v0.9 (PRD §4 self-hosting milestone).
- **Base package: `dev.jkbuild`.** All internal packages under `dev.jkbuild.*`.
- **License: Apache 2.0.** `LICENSE` + `NOTICE` at repo root; every source file carries `// SPDX-License-Identifier: Apache-2.0` as its first comment. `CONTRIBUTING.md` (added at v0.1 start) will spell out the header form for contributors.

---

## 3. Module map

**Multi-module Gradle build**, structured in three tiers: kernel (universal, no plugin deps), plugins (first-party, loaded at runtime), and the CLI host. Convention plugins live in `buildSrc/` (Java 25 toolchain, SPDX header check, Spotless, Checkstyle, JUnit 6). Library pins live in `gradle/libs.versions.toml` (Gradle version catalog). Inter-module dependencies are explicit in each `build.gradle.kts`; no cycles.

Listed in dependency order — foundations first, the native binary last.

### Kernel modules (`kernel/`)

| Module | Packages | Responsibility |
|---|---|---|
| `:model` | `dev.jkbuild.{model, run, util, credential, image, publish}` | Domain types: coordinate model, `jk.toml` data model, `GoalKey` / `Phase` / `PhaseKind`, path utilities, credential types, image and publish metadata. Zero network, zero process spawning. |
| `:core` | `dev.jkbuild.{config, lock, layout, library, audit, deny}` | TOML parser wrapper (line-precise diagnostics), `jk.toml` parse + validation, `jk.lock` read/write/merge, `BuildLayout` path conventions, library catalog lookup, audit and deny policy parsers. |
| `:io` | `dev.jkbuild.{http, cache, repo, forge}` | All fetches and on-disk artifact storage. HTTP/2 client wrapper, CAS + action cache + `~/.m2` view, Maven repo client (sparse-index, mirror, auth, normalization), forge credential helpers. |
| `:resolver` | `dev.jkbuild.resolver` | PubGrub solver, version selectors, prose conflict diagnostics. Depends on `:io` for POM fetch, `:core` for types. |
| `:toolchain` | `dev.jkbuild.{jdk, script, tool, discovery, compat, gradle, mvn, kotlin}` | Anything that manages a JVM, kotlinc, mvn, or gradle on disk. JDK manager (JetBrains feed client, install/default/graal/pin, the `jk activate` shell hook + `jk shell`, unified resolution shared with the build), JBang-compatible single-file Java 25 scripts, `jk tool install` / `jk tool run` tool envs with LRU eviction, and the good-neighbor `discovery` package (probes for jk/IntelliJ/Gradle/SDKMAN/JBang/mise/asdf/jenv/Homebrew/system — used for JDK discovery and for Maven/Gradle/Kotlin tools). Also owns the Maven/Gradle migration layer (`jk import`, `jk export`, passthroughs). |
| `:engine` | `dev.jkbuild.{compile, runtime, task, test, git, worker}` | The compile/test pipeline. Action graph engine + parallel worker pool (`BuildPipeline`), subprocess-driven javac and kotlinc strategies behind a pluggable `CompileStrategy` SPI, JUnit Platform launcher with Surefire-XML/SARIF/JaCoCo output, progress-bar weight prediction (`EffortWeights` + `PhaseTimings`). (Named `:engine`, not `:build`, because `build/` at the repo root would collide with Gradle's default output directory.) |

### Plugin modules (`plugins/`)

First-party plugins loaded at runtime by the engine via the plugin API. Each is an isolated module with no inbound deps from `:cli`.

| Module | Responsibility |
|---|---|
| `:java-compiler` | subprocess javac strategy |
| `:kotlin-compiler` | subprocess kotlinc strategy |
| `:test-runner` | JUnit Platform launcher |
| `:auditor` | OSV vulnerability scanner (`jk audit`) |
| `:publisher` | Maven Central publish + GPG + Sigstore + SLSA |
| `:image-builder` | Jib-core OCI image builder (`jk image`) |
| `:compat-bridge` | Maven/Gradle passthrough runners |
| `:git-client` | JGit resolver for git-source dependencies |
| `:formatter` | Spotless-wrapped Java/Kotlin formatter (`jk format`) |

### Plugin API (`plugin-api/`)

The SPI that kernel `:engine` and all plugin modules share. Plugins declare their `CompileStrategy`, `TestStrategy`, etc. against this API; the engine loads them at startup.

### CLI host (`cli/`)

| Module | Packages | Responsibility |
|---|---|
| `:cli` | `dev.jkbuild.{cli, command}` | Verb dispatch, arg parsing (`ArgParser`), ANSI/TUI rendering, exit codes. Depends on every kernel module. Also the application entrypoint: applies `application` + `org.graalvm.buildtools.native` plugins, so `./gradlew :cli:installDist` produces the JVM distribution and `./gradlew :cli:nativeCompile` the native binary. |

---

## 4. Library picks (one per slot, committed)

| Slot | Pick | Rationale |
|---|---|---|
| TOML parser | **tomlj** (`org.tomlj:tomlj`) | Pure Java, TOML 1.0 conformant, GraalVM-friendly, no transitive Jackson. Wrapped in `dev.jkbuild.config` with line-precise error rendering. (Lightbend Config was the original pick; tomlj was substituted for its lighter footprint and GraalVM compatibility.) |
| CLI parsing | **Hand-rolled `ArgParser`** (`dev.jkbuild.cli.args`) | Zero reflection, no native-image hints needed, gives full control over alias rewriting and help rendering. picocli was the original pick; it was phased out as commands moved to the ported dispatch layer. |
| HTTP/2 client | **`java.net.http.HttpClient`** (JDK built-in) | Zero added dep, HTTP/2 native, virtual-thread friendly, GraalVM-safe. Avoids Netty's binary-size cost. |
| Git client | **JGit** (`org.eclipse.jgit`) | Pure Java, no native libs, well-tested under GraalVM. libgit2 via FFI is rejected for native-image complexity. |
| Bytecode / ABI | **ASM 9.10** | PRD-specified. Pulled in at v0.2 for jar manifest assembly; v1.5 ABI fingerprinting reuses it. |
| GPG signatures | **BouncyCastle** (`bcpg-jdk18on`) | Pure Java, no `gpg` binary dependency. System `gpg` is the documented fallback per PRD §21.2. |
| Sigstore / cosign | **sigstore-java** (`dev.sigstore:sigstore-java`) | Official client, keyless OIDC, Rekor verification built in. |
| Kotlin compiler driver | **`kotlinc` subprocess** (the SDKMAN-style distribution) | Dropped the `kotlin-compiler-embeddable` in-process driver at v0.9 — embedding it ballooned the native-image and bound jk to one Kotlin version. The subprocess strategy lives behind the `CompileStrategy` SPI; an in-process strategy can be plugged in later (e.g. by an IDE integration). |
| CycloneDX SBOM | **cyclonedx-core-java** | Official; emits CycloneDX 1.6. |
| SPDX SBOM | **java-spdx-library** (`org.spdx`) | Official; emits SPDX 2.3. |
| OCI image build | **jib-core** (`com.google.cloud.tools:jib-core`) | Daemonless, distroless-aware, multi-arch manifests, embeddable. Exactly the "Jib-style" the PRD calls for. |
| JDK metadata | **Roll-our-own** client over the JetBrains JDK feed (`jdks.json.xz`) | One small file (~600 KB compressed) holding the full catalog — the same source IntelliJ uses. xz decompression via Apache Commons Compress + `org.tukaani:xz`. |
| OSV client | **Roll-our-own** over OSV REST | Same reasoning. Schema version pinned per release. |
| In-toto / SLSA | **Hand-rolled JSON** to the `slsa.dev/v1` predicate schema | No production-grade Java client; predicate is a small, frozen schema. |
| Test framework | **JUnit 6 + AssertJ** | PRD §18 blesses JUnit Jupiter; jk's own tests dogfood the same. (JUnit 6 unifies Jupiter/Platform/Launcher under a single version.) |
| JSON (internal) | **Jackson Databind** | Restricted to event log + REST clients (JetBrains JDK feed, OSV, Rekor). GraalVM-stable with the standard `META-INF/native-image/` config files. |
| PubGrub | **Hand-port from the `pub` Dart reference** | No production-grade Java port exists. Algorithm core ≈ 1500 LoC; the differentiating effort is the diagnostics layer. |
| Logging | **`java.lang.System.Logger`** routed to a jk-internal JSONL appender | Avoids SLF4J + bridge proliferation in the native binary. |

---

## 5. Milestone roadmap

Logical sequencing, dependency-driven, no calendar estimates. Each milestone is a tagged release.

- **v0.1 — Resolver MVP.** ✅ Shipped. TOML `jk.toml` parse, `jk.lock` read/write, Maven Central HTTP fetch, PubGrub solver with prose diagnostics. Commands: `jk init`, `jk add`, `jk remove`, `jk lock`, `jk sync`, `jk update`, `jk tree`, `jk why`.
- **v0.2 — Builder.** ✅ Shipped. javac driver, action graph + CAS + action cache, per-file incremental, JUnit Platform tests. Commands: `jk check`, `jk build`, `jk test`, `jk clean`, `jk explain`, `jk why-rebuilt`.
- **v0.3 — Kotlin & workspaces.** ✅ Shipped. kotlinc subprocess strategy, KSP, user-facing multi-module workspaces (in `jk.toml`), features, profiles.
- **v0.4 — Toolchain.** ✅ Shipped. JDK manager installs from the JetBrains JDK feed and discovers JDKs from all common locations (jk/IntelliJ/Gradle/SDKMAN/mise/asdf/jenv/Homebrew/system) via the probe chain; foojay/Disco usage retired. Unified resolution order shared by the build and the `jk activate` shell hook (manages `JAVA_HOME`/`GRAALVM_HOME`); `jk shell`; project-level JDK pinning + a global default / default-GraalVM. See [jdk-resolution.md](./jdk-resolution.md).
- **v0.5 — Migration.** ✅ Shipped. `jk mvn` / `jk gradle` passthroughs, three-tier `jk import pom.xml` (single + multi-module), best-effort `jk import build.gradle(.kts)`, `jk export pom.xml`.
- **v0.6 — Publishing & scripting.** ✅ Shipped. `jk publish` (GPG + Sigstore + SLSA + CycloneDX/SPDX SBOMs). `jk tool run script.java` (JBang-compat). `jk tool install` / `jk tool run` for tool envs with LRU eviction; `jkx` is a shell alias (installed by `jk activate`) for `jk tool run`.
- **v0.7 — Git deps.** ✅ Shipped. JGit resolver, TOML git-source syntax, GitFetcher + GitSource + GitUrl.
- **v0.8 — Container & supply chain.** ✅ Shipped. `jk image` (Jib-core), `jk audit` (OSV), `jk deny`, CycloneDX + SPDX, `jk native` (GraalVM native-image driver).
- **v0.9 — Self-hosting.** ✅ Shipped. `jk verify` (reproducibility check), candidate self-hosting `jk.tomls`, jk builds itself end-to-end. Subprocess compile strategies behind a pluggable SPI (kotlin-compiler-embeddable removed). Native-image config consolidated; binary trimmed 187 MB → 138 MB. Good-neighbor tool discovery layered on after the milestone.
- **v1.0 — GA.** Next. IntelliJ plugin (synthetic-POM bridge mode), VS Code extension shell, docs site, benchmark dashboard, `setup-jk` GitHub Action, signed v1.0.0 release.

---

## 6. Native-image strategy

- Build with **GraalVM 25 CE** (bootstrap JDK) via the `org.graalvm.buildtools.native` Gradle plugin through v0.8. From v0.9 onward, jk builds itself; the Gradle build is retained as bootstrap.
- Each subsystem owns `META-INF/native-image/dev.jkbuild/<subsystem>/` config (reflection, resource, JNI, proxy). Aggregated at link time.
- Binary-size knobs applied at v0.9: no-op GC (`-R:+UseSerialGC` with low-throughput tuning), `-Os` size-priority codegen, stripped debug symbols, `--initialize-at-build-time` for the static-init-heavy parsers. Result: 138 MB on linux-x86_64 (down from 187 MB).
- CI matrix per release: `linux-x86_64-musl-static`, `linux-aarch64-musl-static`, `macos-aarch64-dynamic`, `macos-x86_64-dynamic`, `windows-x86_64-dynamic`.
- Performance gate before merging to `main`: `jk --help` cold start ≤ 50 ms on a 2024 M-series Mac. requirements.md §27 sets the broader budget; the help-cold-start gate is the cheapest proxy for it.

---

## 7. Self-hosting transition (v0.8 → v0.9) — done

1. ✅ Every feature the jk-build itself depends on shipped in a tagged release before the flip.
2. ✅ Candidate `jk.tomls` files committed in v0.9 slice A; multi-module workspace derived from the existing Gradle build.
3. ✅ Parity via `jk verify` (byte-identical jar across boxes).
4. ✅ jk builds itself end-to-end as of commit `88c2fdf`. Gradle build retained at the repo root as the bootstrap path through at least v1.1.

---

## 8. Testing strategy

- **Unit:** JUnit 6 + AssertJ per subsystem.
- **Resolver fixtures:** snapshot real Maven POMs (Spring Boot 3.4, Quarkus 3.x, Micronaut 4, Kotlin stdlib, Jackson, Netty) into `src/test/resources/fixtures/`. Serve via an in-memory HTTP/2 stub. PubGrub regressions caught by golden-output diffs.
- **End-to-end:** shell-level scripts under `src/test/e2e/`, run via JUnit + `ProcessBuilder` first against the JVM build, then against the native binary in CI.
- **Reproducibility:** every release tag runs `jk verify` in a clean workspace and on at least one second OS/arch combination.

---

## 9. CI strategy

- GitHub Actions. Matrix per native-image target (see §6).
- Jobs: `lint` (Spotless + Checkstyle), `unit`, `e2e-jvm`, `e2e-native`, `bench` (nightly; results pushed to the public dashboard).
- Builds run with `--frozen --offline` once the cache is populated, to dogfood the CI defaults from PRD §26.

---

## 10. Top risks

1. **GraalVM compat of JGit & Lightbend Config.** *Mitigation:* pin versions known to work, vendor upstream `META-INF/native-image/` config, run a nightly native-binary smoke job against a corpus of real repos. (Resolved through v0.9; carry-forward for v1.0.)
2. **PubGrub on Maven's wild west.** *Mitigation:* fixture corpus + a defensive normalization layer in `dev.jkbuild.repo.normalize` before constraints reach the solver.
3. **TOML diagnostics.** *Mitigation:* the `dev.jkbuild.config` wrapper maps `com.typesafe.config.ConfigException` positions to source lines and renders Rust-style carets. Required to close requirements.md §31 #3.
4. **Self-hosting chicken-and-egg.** *Mitigation:* the Gradle bootstrap build is kept at the repo root through v1.1 as an escape hatch; parity CI runs per §7.
5. **IntelliJ plugin staffing (requirements.md §31 #1).** Out of scope for this document; flagged as a v1.0 blocker that requires a separate effort and is not on the critical path of the binary itself.
6. **Build-tool discovery-link rot.** *Mitigation:* `jk doctor` prunes broken mvn/gradle/kotlin symlinks under `$JK_CACHE_DIR/tools/`. (JDKs are installed directly into IntelliJ's directory now — no symlink layer, no rot.) See [tool-discovery-plan.md](./tool-discovery-plan.md).

---

## 11. Repo layout (current)

```
jk/
├── LICENSE                          # Apache 2.0 verbatim
├── NOTICE                           # short attribution
├── .gitignore
├── .sdkmanrc                        # java=25.0.3-graal, gradle=9.5.1
├── settings.gradle.kts              # include(":model", ":core", ":io", ...)
├── build.gradle.kts                 # root: applies convention plugins to subprojects
├── jk.toml                          # self-hosting build (jk builds itself since v0.9)
├── jk.lock                          # locked dependency graph for the jk build itself
├── buildSrc/                        # Gradle convention plugins
│   └── src/main/kotlin/             # jk.java-conventions.gradle.kts, jk.spdx-header.gradle.kts, ...
├── gradle/
│   └── libs.versions.toml           # version catalog (single source of truth for deps)
├── docs/                            # design documents
├── kernel/                          # universal kernel modules (no plugin-api deps)
│   ├── model/    src/{main,test}/java/dev/jkbuild/{model,run,util,credential,image,publish}/
│   ├── core/     src/{main,test}/java/dev/jkbuild/{config,lock,layout,library,audit,deny}/
│   ├── io/       src/{main,test}/java/dev/jkbuild/{http,cache,repo,forge}/
│   ├── resolver/ src/{main,test}/java/dev/jkbuild/resolver/
│   ├── toolchain/ src/{main,test}/java/dev/jkbuild/{jdk,script,tool,discovery,compat,gradle,mvn,kotlin}/
│   └── engine/   src/{main,test}/java/dev/jkbuild/{compile,runtime,task,test,git,worker}/
├── plugin-api/  src/{main,test}/java/                # CompileStrategy / TestStrategy SPI
├── plugins/                         # first-party plugins (loaded at runtime)
│   ├── java-compiler/
│   ├── kotlin-compiler/
│   ├── test-runner/
│   ├── auditor/
│   ├── publisher/
│   ├── image-builder/
│   ├── compat-bridge/
│   ├── git-client/
│   └── formatter/
└── cli/        src/{main,test}/java/dev/jkbuild/{cli,command}/  # verb dispatch + native-image entrypoint
```

---

*End of document. Per-feature design specs live alongside this file under `docs/` (e.g. [`tool-discovery-plan.md`](./tool-discovery-plan.md)) as subsystem work lands.*
