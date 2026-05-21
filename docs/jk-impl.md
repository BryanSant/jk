# jk — Implementation Plan

**Status:** Draft v0.1
**Owner:** bryan.sant@proton.me
**Last updated:** 2026-05-21
**Companion to:** [jk-prd.md](./jk-prd.md)

---

## 1. Context & mission alignment

`jk` is the single-binary, opinionated build tool for Java and Kotlin specified by [jk-prd.md](./jk-prd.md). Its pitch — *"the fastest way to run your existing Maven or Gradle build, and it just happens to come with a better build tool you can opt into when you're ready"* — sets the design constraint that drives every choice below: jk must be a fast native binary, with reproducibility and supply-chain hygiene by default, and with adoption-first migration story for Maven/Gradle users.

This document is a **terse strategic implementation overview**, not a per-feature design spec. Its purpose: settle the things that have to be settled before code goes down — language, libraries, packages, milestone ordering — so individual subsystems can be designed in isolation later.

---

## 2. Implementation language & bootstrap stack

- **Language: pure Java 25** for jk's own sources during bootstrap. No Kotlin in jk-the-binary. Rationale: GraalVM native-image is most predictable on plain Java; the Kotlin compiler is a *tool jk consumes* for its users, not a build-time dependency of jk itself. Re-evaluate after v1.0 ships.
- **Build tool: Gradle 9.5.1** through v0.8, replaced by jk itself at v0.9 (PRD §4 self-hosting milestone).
- **Base package: `dev.buildjk`.** All internal packages under `dev.buildjk.*`.
- **License: Apache 2.0.** `LICENSE` + `NOTICE` at repo root; every source file carries `// SPDX-License-Identifier: Apache-2.0` as its first comment. `CONTRIBUTING.md` (added at v0.1 start) will spell out the header form for contributors.

---

## 3. Module map

**Multi-module Gradle build from day one**, but consolidated by area — not one module per package. **Nine modules** at the repo root, grouped by dependency tier and how often the contained packages change together. Packages remain granular inside each module: `:core` contains `dev.buildjk.util`, `dev.buildjk.model`, etc. Inter-module dependencies are explicit in each `build.gradle.kts`; no cycles. Convention plugins live in `buildSrc/` (Java 25 toolchain, SPDX header check, Spotless, Checkstyle, JUnit 5). Library pins live in `gradle/libs.versions.toml` (Gradle version catalog).

Listed in dependency order — foundations first, the native binary last.

| Module | Packages | Responsibility |
|---|---|---|
| `:core` | `dev.buildjk.{util, event, model, hocon, lock}` | Foundations. Hashing/paths/ANSI/env-scrubbing, JSONL event log + chrome://tracing emitter, `build.jk` model + coordinate types, HOCON parser wrapper (line-precise diagnostics for PRD §31 #3), `jk.lock` read/write/merge. Zero network, zero process spawning. |
| `:io` | `dev.buildjk.{http, cache, git, repo}` | All fetches and on-disk artifact storage. HTTP/2 client wrapper, CAS + action cache + `~/.m2` view, JGit-backed git resolver (sparse checkout, URL canonicalization, tag-rewrite detection), Maven repo client (sparse-index, mirror, auth, normalization). |
| `:resolver` | `dev.buildjk.resolver` | PubGrub solver, version selectors, prose conflict diagnostics. Depends on `:io` for POM fetch, `:core` for types. |
| `:toolchain` | `dev.buildjk.{jdk, script, tool}` | Anything that manages a JVM on disk. JDK manager (Disco API, install/use/pin, `.sdkmanrc` interop, `jk shell`/`jk env`), JBang-compatible single-file Java 25 scripts, `jk install`/`jkx` tool envs with LRU eviction. |
| `:engine` | `dev.buildjk.{task, compile, test}` | The compile/test pipeline. Action graph engine + parallel worker pool, javac + `kotlin-compiler-embeddable` driver + KSP, JUnit Platform launcher with Surefire-XML/SARIF/JaCoCo output. (Named `:engine`, not `:build`, because `build/` at the repo root would collide with Gradle's default output directory.) |
| `:supply-chain` | `dev.buildjk.{audit, sbom, deny, publish}` | Everything that gates or attests a release. `jk audit` (OSV), CycloneDX + SPDX SBOMs, `jk deny` license/source/yanked policy gate, `jk publish` (POM export, GPG + Sigstore signing, SLSA in-toto attestation). |
| `:image` | `dev.buildjk.image` | Jib-core OCI builder for `jk image`. Kept isolated because jib-core is a heavyweight dependency that most builds never load. |
| `:compat` | `dev.buildjk.{mvn, gradle}` | Maven/Gradle migration layer. `jk mvn` / `jk gradle` passthroughs, three-tier `jk import pom.xml`, best-effort `jk import build.gradle(.kts)`, `jk export pom.xml`. |
| `:cli` | `dev.buildjk.cli` | picocli verb dispatch, flag parsing, exit codes, ANSI rendering. Depends on every module above. Also the application entrypoint: applies `application` + `org.graalvm.buildtools.native` plugins, so `./gradlew :cli:run` and `./gradlew :cli:nativeCompile` produce the JVM and native binaries respectively. |

---

## 4. Library picks (one per slot, committed)

| Slot | Pick | Rationale |
|---|---|---|
| HOCON parser | **Lightbend Config** (`com.typesafe:config`) | Canonical reference impl; PRD §5.1 specifies. Wrapped with a line-precise diagnostic decorator to fix PRD §31 #3. |
| TOML parser | **tomlj** | Pure Java, TOML 1.0 conformant, GraalVM-friendly, no transitive Jackson. |
| CLI parsing | **picocli** | GraalVM-first design (used by Quarkus). Minimal reflection footprint, completion-script generation, mature subcommand support. |
| HTTP/2 client | **`java.net.http.HttpClient`** (JDK built-in) | Zero added dep, HTTP/2 native, virtual-thread friendly, GraalVM-safe. Avoids Netty's binary-size cost. |
| Git client | **JGit** (`org.eclipse.jgit`) | Pure Java, no native libs, well-tested under GraalVM. libgit2 via FFI is rejected for native-image complexity. |
| Bytecode / ABI | **ASM 9.x** | PRD-specified. Pulled in at v0.2 for jar manifest assembly; v1.5 ABI fingerprinting reuses it. |
| GPG signatures | **BouncyCastle** (`bcpg-jdk18on`) | Pure Java, no `gpg` binary dependency. System `gpg` is the documented fallback per PRD §21.2. |
| Sigstore / cosign | **sigstore-java** (`dev.sigstore:sigstore-java`) | Official client, keyless OIDC, Rekor verification built in. |
| CycloneDX SBOM | **cyclonedx-core-java** | Official; emits CycloneDX 1.6. |
| SPDX SBOM | **java-spdx-library** (`org.spdx`) | Official; emits SPDX 2.3. |
| OCI image build | **jib-core** (`com.google.cloud.tools:jib-core`) | Daemonless, distroless-aware, multi-arch manifests, embeddable. Exactly the "Jib-style" the PRD calls for. |
| JDK metadata | **Roll-our-own** Disco API client over the JDK HttpClient | foojay's API is small; no library worth pulling. |
| OSV client | **Roll-our-own** over OSV REST | Same reasoning. Schema version pinned per release. |
| In-toto / SLSA | **Hand-rolled JSON** to the `slsa.dev/v1` predicate schema | No production-grade Java client; predicate is a small, frozen schema. |
| Test framework | **JUnit Platform 1.11 + AssertJ** | PRD §18 blesses JUnit 5; jk's own tests dogfood the same. |
| JSON (internal) | **Jackson Databind** | Restricted to event log + REST clients (Disco, OSV, Rekor). GraalVM-stable with the standard `META-INF/native-image/` config files. |
| PubGrub | **Hand-port from the `pub` Dart reference** | No production-grade Java port exists. Algorithm core ≈ 1500 LoC; the differentiating effort is the diagnostics layer. |
| Logging | **`java.lang.System.Logger`** routed to a jk-internal JSONL appender | Avoids SLF4J + bridge proliferation in the native binary. |

---

## 5. Milestone roadmap

Logical sequencing, dependency-driven, no calendar estimates. Each milestone is a tagged release.

- **v0.1 — Resolver MVP.** HOCON `build.jk` parse, `jk.lock` read/write, Maven Central HTTP fetch, PubGrub solver with prose diagnostics. Commands: `jk init`, `jk add`, `jk remove`, `jk lock`, `jk sync`, `jk update`, `jk tree`, `jk why`, `jk fetch`. No compile yet.
- **v0.2 — Builder.** javac driver, action graph + CAS + action cache, per-file incremental, JUnit Platform tests. Commands: `jk check`, `jk build`, `jk test`, `jk clean`, `jk explain`, `jk why-rebuilt`.
- **v0.3 — Kotlin & workspaces.** Embedded `kotlin-compiler-embeddable`, KSP, user-facing multi-module workspaces (in `build.jk`), features, profiles.
- **v0.4 — Toolchain.** JDK manager (Disco), `.sdkmanrc` interop, `jk shell` / `jk env`, project-level JDK pinning.
- **v0.5 — Migration.** `jk mvn` / `jk gradle` passthroughs, three-tier `jk import pom.xml`, best-effort `jk import build.gradle(.kts)`, `jk export pom.xml`.
- **v0.6 — Publishing & scripting.** `jk publish` (GPG + Sigstore + SLSA + SBOM). `jk run script.java` (JBang-compat). `jk install` / `jkx`.
- **v0.7 — Git deps.** JGit resolver, sparse checkouts, tag-rewrite detection, commit/tag signature verification.
- **v0.8 — Container & supply chain.** `jk image` (Jib-core), `jk audit` (OSV), `jk deny`, CycloneDX + SPDX on every build, `jk native-image`.
- **v0.9 — Self-hosting.** jk builds itself. Native-image config consolidated. Performance dashboard online. Gradle build retained behind `bootstrap/` for one release cycle.
- **v1.0 — GA.** IntelliJ plugin (synthetic-POM bridge mode), VS Code extension shell, docs site, benchmark dashboard, `setup-jk` GitHub Action, signed v1.0.0 release.

---

## 6. Native-image strategy

- Build with **GraalVM 21 CE** via the `org.graalvm.buildtools.native` Gradle plugin, through v0.8. From v0.9 onward, jk builds itself.
- Each subsystem owns `META-INF/native-image/dev.buildjk/<subsystem>/` config (reflection, resource, JNI, proxy). Aggregated at link time.
- CI matrix per release: `linux-x86_64-musl-static`, `linux-aarch64-musl-static`, `macos-aarch64-dynamic`, `macos-x86_64-dynamic`, `windows-x86_64-dynamic`.
- Performance gate before merging to `main`: `jk --help` cold start ≤ 50 ms on a 2024 M-series Mac. PRD §27 sets the broader budget; the help-cold-start gate is the cheapest proxy for it.

---

## 7. Self-hosting transition (v0.8 → v0.9)

1. While on Gradle, ensure every jk feature the jk-build itself depends on has shipped in a tagged release.
2. Generate a candidate `build.jk` from the v0.8 `build.gradle.kts` via `jk import build.gradle.kts`. Hand-finish any Tier-2/Tier-3 items in the import report.
3. Add a CI job that builds with both Gradle and jk; require artifact parity via `jk verify-reproducible` (byte-identical jar on Linux and macOS).
4. Flip the default at `v0.9-rc1`. Keep `bootstrap/build.gradle.kts` available through at least v1.1 as an escape hatch.

---

## 8. Testing strategy

- **Unit:** JUnit 5 + AssertJ per subsystem.
- **Resolver fixtures:** snapshot real Maven POMs (Spring Boot 3.4, Quarkus 3.x, Micronaut 4, Kotlin stdlib, Jackson, Netty) into `src/test/resources/fixtures/`. Serve via an in-memory HTTP/2 stub. PubGrub regressions caught by golden-output diffs.
- **End-to-end:** shell-level scripts under `src/test/e2e/`, run via JUnit + `ProcessBuilder` first against the JVM build, then against the native binary in CI.
- **Reproducibility:** every release tag runs `jk verify-reproducible` in a clean workspace and on at least one second OS/arch combination.

---

## 9. CI strategy

- GitHub Actions. Matrix per native-image target (see §6).
- Jobs: `lint` (Spotless + Checkstyle), `unit`, `e2e-jvm`, `e2e-native`, `bench` (nightly; results pushed to the public dashboard).
- Builds run with `--frozen --offline` once the cache is populated, to dogfood the CI defaults from PRD §26.

---

## 10. Top risks

1. **GraalVM compat of JGit & Lightbend Config.** *Mitigation:* pin versions known to work, vendor upstream `META-INF/native-image/` config, run a nightly native-binary smoke job against a corpus of real repos.
2. **PubGrub on Maven's wild west.** *Mitigation:* fixture corpus + a defensive normalization layer in `dev.buildjk.repo.normalize` before constraints reach the solver.
3. **HOCON diagnostics.** *Mitigation:* the `dev.buildjk.hocon` wrapper maps `com.typesafe.config.ConfigException` positions to source lines and renders Rust-style carets. Required to close PRD §31 #3.
4. **Self-hosting chicken-and-egg.** *Mitigation:* keep the Gradle bootstrap build alive in `bootstrap/` through v1.1; require parity CI per §7.
5. **IntelliJ plugin staffing (PRD §31 #1).** Out of scope for this document; flagged as a v1.0 blocker that requires a separate effort and is not on the critical path of the binary itself.

---

## 11. Initial repo layout (post-execution)

```
jk/
├── LICENSE                          # Apache 2.0 verbatim
├── NOTICE                           # short attribution
├── .gitignore
├── .sdkmanrc                        # java=25.0.3-tem, gradle=9.5.1
├── settings.gradle.kts              # `include(":core", ":io", ":resolver", ...)`
├── build.gradle.kts                 # root: applies convention plugins to subprojects
├── buildSrc/                        # Gradle convention plugins
│   └── src/main/kotlin/             # jk.java-conventions.gradle.kts, jk.spdx-header.gradle.kts, ...
├── gradle/
│   └── libs.versions.toml           # version catalog (single source of truth for deps)
├── docs/
│   ├── jk-prd.md
│   └── jk-impl.md                   # this document
├── core/
│   ├── build.gradle.kts
│   └── src/{main,test}/java/dev/buildjk/{util,event,model,hocon,lock}/
├── io/
│   └── src/{main,test}/java/dev/buildjk/{http,cache,git,repo}/
├── resolver/
├── toolchain/
│   └── src/{main,test}/java/dev/buildjk/{jdk,script,tool}/
├── engine/                          # renamed from :build to avoid collision with Gradle's build/ dir
│   └── src/{main,test}/java/dev/buildjk/{task,compile,test}/
├── supply-chain/
│   └── src/{main,test}/java/dev/buildjk/{audit,sbom,deny,publish}/
├── image/
├── compat/
│   └── src/{main,test}/java/dev/buildjk/{mvn,gradle}/
└── cli/                             # entrypoint; applies application + native-image plugins
```

---

*End of document. Per-feature design specs follow as individual files under `docs/design/` once subsystem work begins.*
