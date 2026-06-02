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

**Multi-module Gradle build from day one**, but consolidated by area — not one module per package. **Nine modules** at the repo root, grouped by dependency tier and how often the contained packages change together. Packages remain granular inside each module: `:core` contains `dev.jkbuild.util`, `dev.jkbuild.model`, etc. Inter-module dependencies are explicit in each `build.gradle.kts`; no cycles. Convention plugins live in `buildSrc/` (Java 25 toolchain, SPDX header check, Spotless, Checkstyle, JUnit 6). Library pins live in `gradle/libs.versions.toml` (Gradle version catalog).

Listed in dependency order — foundations first, the native binary last.

| Module | Packages | Responsibility |
|---|---|---|
| `:core` | `dev.jkbuild.{util, event, model, config, lock}` | Foundations. Hashing/paths/ANSI/env-scrubbing, JSONL event log + chrome://tracing emitter, `jk.toml` model + coordinate types, TOML parser wrapper (line-precise diagnostics for PRD §31 #3), `jk.lock` read/write/merge. Zero network, zero process spawning. |
| `:io` | `dev.jkbuild.{http, cache, git, repo}` | All fetches and on-disk artifact storage. HTTP/2 client wrapper, CAS + action cache + `~/.m2` view, JGit-backed git resolver (sparse checkout, URL canonicalization, tag-rewrite detection), Maven repo client (sparse-index, mirror, auth, normalization). |
| `:resolver` | `dev.jkbuild.resolver` | PubGrub solver, version selectors, prose conflict diagnostics. Depends on `:io` for POM fetch, `:core` for types. |
| `:toolchain` | `dev.jkbuild.{jdk, script, tool, discovery}` | Anything that manages a JVM, kotlinc, mvn, or gradle on disk. JDK manager (JetBrains feed client, install/use/pin, `jk shell`/`jk env`, shared with IntelliJ's JDK directory), JBang-compatible single-file Java 25 scripts, `jk tool install` / `jk exec` tool envs with LRU eviction, and the good-neighbor `discovery` package (probes for SDKMAN/JBang/asdf/jenv/Homebrew + symlink provisioner — used now only for Maven/Gradle/Kotlin, not JDKs). |
| `:engine` | `dev.jkbuild.{task, compile, test}` | The compile/test pipeline. Action graph engine + parallel worker pool, subprocess-driven javac and kotlinc strategies behind a pluggable SPI, JUnit Platform launcher with Surefire-XML/SARIF/JaCoCo output. (Named `:engine`, not `:build`, because `build/` at the repo root would collide with Gradle's default output directory.) |
| `:supply-chain` | `dev.jkbuild.{audit, sbom, deny, publish}` | Everything that gates or attests a release. `jk audit` (OSV), CycloneDX + SPDX SBOMs, `jk deny` license/source/yanked policy gate, `jk publish` (POM export, GPG + Sigstore signing, SLSA in-toto attestation). |
| `:image` | `dev.jkbuild.image` | Jib-core OCI builder for `jk image`. Kept isolated because jib-core is a heavyweight dependency that most builds never load. |
| `:compat` | `dev.jkbuild.{mvn, gradle}` | Maven/Gradle migration layer. `jk mvn` / `jk gradle` passthroughs (wired through the discovery probes), three-tier `jk import pom.xml`, best-effort `jk import build.gradle(.kts)`, `jk export pom.xml`. Depends on `:toolchain` for `ToolProvisioning`. |
| `:cli` | `dev.jkbuild.cli` | picocli verb dispatch, flag parsing, exit codes, ANSI rendering. Depends on every module above. Also the application entrypoint: applies `application` + `org.graalvm.buildtools.native` plugins, so `./gradlew :cli:run` and `./gradlew :cli:nativeCompile` produce the JVM and native binaries respectively. |

---

## 4. Library picks (one per slot, committed)

| Slot | Pick | Rationale |
|---|---|---|
| TOML parser | **Lightbend Config** (`com.typesafe:config`) | Canonical reference impl; PRD §5.1 specifies. Wrapped with a line-precise diagnostic decorator to fix PRD §31 #3. |
| TOML parser | **tomlj** | Pure Java, TOML 1.0 conformant, GraalVM-friendly, no transitive Jackson. |
| CLI parsing | **picocli** | GraalVM-first design (used by Quarkus). Minimal reflection footprint, completion-script generation, mature subcommand support. |
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
- **v0.4 — Toolchain.** ✅ Shipped (and later retargeted). JDK manager now reads the JetBrains JDK feed and shares IntelliJ's JDK directory; `.sdkmanrc` interop and foojay/Disco usage retired. `jk shell` / `jk env`, project-level JDK pinning.
- **v0.5 — Migration.** ✅ Shipped. `jk mvn` / `jk gradle` passthroughs, three-tier `jk import pom.xml` (single + multi-module), best-effort `jk import build.gradle(.kts)`, `jk export pom.xml`.
- **v0.6 — Publishing & scripting.** ✅ Shipped. `jk publish` (GPG + Sigstore + SLSA + CycloneDX/SPDX SBOMs). `jk tool run script.java` (JBang-compat). `jk tool install` / `jk exec` (was `jk install` / `jkx` pre-Option-1; `jkx` is kept as an alias for `jk exec`).
- **v0.7 — Git deps.** ✅ Shipped. JGit resolver, TOML git-source syntax, GitFetcher + GitSource + GitUrl.
- **v0.8 — Container & supply chain.** ✅ Shipped. `jk image` (Jib-core), `jk audit` (OSV), `jk deny`, CycloneDX + SPDX, `jk native` (GraalVM native-image driver).
- **v0.9 — Self-hosting.** ✅ Shipped. `jk verify-build`, candidate self-hosting `jk.tomls`, jk builds itself end-to-end. Subprocess compile strategies behind a pluggable SPI (kotlin-compiler-embeddable removed). Native-image config consolidated; binary trimmed 187 MB → 138 MB. Good-neighbor tool discovery layered on after the milestone.
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
3. ✅ Parity via `jk verify-build` (byte-identical jar across boxes).
4. ✅ jk builds itself end-to-end as of commit `88c2fdf`. Gradle build retained at the repo root as the bootstrap path through at least v1.1.

---

## 8. Testing strategy

- **Unit:** JUnit 6 + AssertJ per subsystem.
- **Resolver fixtures:** snapshot real Maven POMs (Spring Boot 3.4, Quarkus 3.x, Micronaut 4, Kotlin stdlib, Jackson, Netty) into `src/test/resources/fixtures/`. Serve via an in-memory HTTP/2 stub. PubGrub regressions caught by golden-output diffs.
- **End-to-end:** shell-level scripts under `src/test/e2e/`, run via JUnit + `ProcessBuilder` first against the JVM build, then against the native binary in CI.
- **Reproducibility:** every release tag runs `jk verify-build` in a clean workspace and on at least one second OS/arch combination.

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
6. **Build-tool discovery-link rot.** *Mitigation:* `jk doctor` prunes broken mvn/gradle/kotlin symlinks under `$JK_CACHE_DIR/tools/`. (JDKs are installed directly into IntelliJ's directory now — no symlink layer, no rot. `jk jdk reconcile` walks the directory and reports orphaned entries.) See [tool-discovery-plan.md](./tool-discovery-plan.md).

---

## 11. Repo layout (current)

```
jk/
├── LICENSE                          # Apache 2.0 verbatim
├── NOTICE                           # short attribution
├── .gitignore
├── .sdkmanrc                        # java=25.0.3-graal, gradle=9.5.1
├── settings.gradle.kts              # include(":core", ":io", ":resolver", ...)
├── build.gradle.kts                 # root: applies convention plugins to subprojects
├── jk.toml                         # candidate self-hosting build (v0.9 — jk builds itself)
├── buildSrc/                        # Gradle convention plugins
│   └── src/main/kotlin/             # jk.java-conventions.gradle.kts, jk.spdx-header.gradle.kts, ...
├── gradle/
│   └── libs.versions.toml           # version catalog (single source of truth for deps)
├── docs/
│   ├── requirements.md              # PRD
│   ├── implementation-plan.md       # this document
│   ├── comparison.md                # jk vs uv / Cargo / Gradle / Maven
│   └── tool-discovery-plan.md       # good-neighbor symlink discovery (shipped)
├── core/   src/{main,test}/java/dev/jkbuild/{util,event,model,config,lock}/
├── io/     src/{main,test}/java/dev/jkbuild/{http,cache,git,repo}/
├── resolver/   src/{main,test}/java/dev/jkbuild/resolver/
├── toolchain/  src/{main,test}/java/dev/jkbuild/{jdk,script,tool,discovery}/
├── engine/     src/{main,test}/java/dev/jkbuild/{task,compile,test}/        # subprocess CompileStrategy SPI
├── supply-chain/ src/{main,test}/java/dev/jkbuild/{audit,sbom,deny,publish}/
├── image/      src/{main,test}/java/dev/jkbuild/image/                      # Jib-core
├── compat/     src/{main,test}/java/dev/jkbuild/{mvn,gradle}/
└── cli/        src/{main,test}/java/dev/jkbuild/cli/                        # picocli; application + native-image
```

---

*End of document. Per-feature design specs live alongside this file under `docs/` (e.g. [`tool-discovery-plan.md`](./tool-discovery-plan.md)) as subsystem work lands.*
