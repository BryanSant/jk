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

## 3. Module map (under `dev.buildjk.*`)

Single Gradle module for v0.1–v0.2. Logical packages below; first Gradle-module split happens at v0.3 (extract `:core` and `:cli`).

| Package | Responsibility |
|---|---|
| `dev.buildjk.cli` | Verb dispatch, flag parsing, exit codes, ANSI rendering. |
| `dev.buildjk.model` | `build.jk`/`build.toml` data classes, coordinate types, scope enums. |
| `dev.buildjk.hocon` | HOCON load, JSON-Schema validation, line-precise error rendering wrapper over Lightbend Config (PRD §31 open question #3). |
| `dev.buildjk.lock` | `jk.lock` read/write, deterministic serialization, merge driver. |
| `dev.buildjk.resolver` | PubGrub solver, constraint set, version selectors, conflict diagnostics. |
| `dev.buildjk.repo` | Maven repo HTTP layout, sparse-index, mirror, auth, normalization. |
| `dev.buildjk.cache` | CAS, action cache, `~/.m2` interop view. |
| `dev.buildjk.http` | HTTP/2 client wrapper, retry/backoff, ETag / If-Modified-Since. |
| `dev.buildjk.git` | JGit-backed git resolver, sparse checkout, URL canonicalization, tag-rewrite detection. |
| `dev.buildjk.jdk` | Disco-API client, install/use/pin, `.sdkmanrc` interop, `jk shell` / `jk env`. |
| `dev.buildjk.compile` | javac + kotlinc-embeddable driver, KSP, source set glue, per-file incremental. |
| `dev.buildjk.task` | Action graph engine, hashing, action cache lookup, parallel worker pool. |
| `dev.buildjk.test` | JUnit Platform launcher, fork-per-N, Surefire-XML emit, SARIF, JaCoCo. |
| `dev.buildjk.script` | JBang-compatible header parsing, single-file Java 25 execution. |
| `dev.buildjk.tool` | `jk install` / `jkx`, tool envs, LRU eviction. |
| `dev.buildjk.publish` | POM export, signing, Sigstore, SLSA in-toto. |
| `dev.buildjk.image` | Jib-core-based OCI builder, multi-arch, reproducible layers. |
| `dev.buildjk.audit` | OSV client, SARIF emit. |
| `dev.buildjk.sbom` | CycloneDX + SPDX emission. |
| `dev.buildjk.deny` | License / source / yanked policy gate. |
| `dev.buildjk.mvn` | `jk mvn` passthrough + `jk import pom.xml` (three-tier). |
| `dev.buildjk.gradle` | `jk gradle` passthrough + `jk import build.gradle(.kts)`. |
| `dev.buildjk.event` | JSONL event log, chrome://tracing emitter, BEP-compatible streaming. |
| `dev.buildjk.util` | Hashing, paths, env scrubbing, ANSI. |

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
- **v0.3 — Kotlin & workspaces.** Embedded `kotlin-compiler-embeddable`, KSP, multi-module workspaces, features, profiles. *First Gradle-module split: `:core` and `:cli`.*
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
├── .gitignore                       # already present
├── build.gradle.kts                 # added at v0.1 start
├── settings.gradle.kts              # ditto
├── docs/
│   ├── jk-prd.md
│   └── jk-impl.md                   # this document
└── src/
    ├── main/java/dev/buildjk/...    # populated from v0.1 onward
    └── test/java/dev/buildjk/...
```

---

*End of document. Per-feature design specs follow as individual files under `docs/design/` once subsystem work begins.*
