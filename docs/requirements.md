# jk — Product Requirements Document

**Status:** Living spec. v0.1–v0.9 milestones implemented per [implementation-plan.md §5](./implementation-plan.md#5-milestone-roadmap); v1.0 (GA) is next.
**Owner:** bryan.sant@proton.me
**Last updated:** 2026-05-22

---

## 1. Vision

`jk` is a single-binary, opinionated build tool for Java and Kotlin that replaces Maven and Gradle. It is spiritually descended from `uv` (Python's modern package/project manager), with the dependency-and-lockfile discipline of Cargo, the workspace ergonomics of go-mod, and full back-compat with the Maven-Central artifact ecosystem.

It exists because Maven is too verbose and non-reproducible, Gradle is too programmable and opaque, and the JVM ecosystem has watched Cargo and uv solve problems we've been talking around for fifteen years.

### One-line pitch

> *jk is the fastest way to run your existing Maven or Gradle build, and it just happens to come with a better build tool you can opt into when you're ready.*

### Design tenets

1. **Declarative core, code at the edges.** `jk.toml` is data. Custom logic lives in single-file Java 25 task scripts with declared inputs and outputs.
2. **Native binary, no daemon.** Sub-50ms startup, parallel HTTP/2 everything, no JVM warmup tax.
3. **Reproducibility is a default, not a flag.** Locked deps, locked toolchain, scrubbed env, sorted jar entries, deterministic timestamps. Bit-identical artifacts on every box.
4. **Diagnostics are a product, not infrastructure.** PubGrub-style English error messages, `jk why`, `jk explain`, `jk why-rebuilt`, all offline.
5. **Adoption first.** `jk mvn` and `jk gradle` passthroughs from day one. Best-effort `pom.xml`/`build.gradle(.kts)` import. Round-trip POM export for publishing.
6. **Resist plugin sprawl.** No plugin API at v1.0. First-party-only feature bundles (OCI build, native-image, audit). Cargo waited five years; so will jk.
7. **Be a good Maven Central citizen.** GPG signatures, checksums, BOM imports, scope semantics, `~/.m2`-compatible cache layout — semantics must be indistinguishable from Maven even where syntax is radically different.

---

## 2. Goals and Non-Goals

### v1.0 Goals

- Build, test, run, package, and publish Java and Kotlin projects (single-module and multi-module workspaces).
- Manage JDK installations (install/list/use/uninstall), compatible with `.sdkmanrc` and the SDKMAN candidate scheme (`21.0.5-tem`).
- Resolve dependencies from Maven-Central-style repositories and from git URLs (GitHub, GitLab, BitKeeper, Gitea).
- Produce and consume `jk.lock` with full transitive closure, checksums, and source provenance.
- Best-effort import of `pom.xml` (Tier 1 lossless, Tier 2 best-effort, Tier 3 stub-with-diagnostic).
- Generate `pom.xml` on publish so jk-built jars are first-class on Maven Central.
- Subsume JBang's single-file scripting role (`jk run script.java` with header-comment deps).
- `jk tool install` / `jkx` for installing and ephemerally running JVM CLIs as tools.
- Built-in `jk audit` (OSV database) and `jk image` (Jib-style daemonless OCI images, distroless, multi-arch).
- SLSA L3 provenance and Sigstore keyless signing by default for `jk publish` from CI.
- SBOM emission (CycloneDX + SPDX) on every build.
- Local content-addressed build cache with action-cache semantics.
- Cargo-style command surface, PubGrub-style resolver diagnostics, machine-readable JSON output on every command.

### v1.0 Non-Goals (documented future phases — see §30)

- Remote build cache and Bazel Remote Execution API client.
- Plugin SDK / third-party plugin marketplace.
- Android build support (AAR, R8/D8, resources).
- Kotlin Multiplatform (KMP) for JS/Native/iOS/Wasm. Compose Multiplatform iOS.
- `kapt` annotation processing (KSP only).
- Buildpacks-based image emission.
- Best-effort Gradle build script *generation* (round-trip out). Import is in scope; export is v1.1+.
- Remote execution (RBE) — not even read-only client.
- GraalVM native-image *as a project artifact* via a dedicated profile is in scope; advanced AOT tuning surfaces are not.

### Non-goals, period

- Replacing IDE build systems. jk produces metadata IntelliJ/Eclipse/VS Code consume; it doesn't replace them.
- Replacing CI runners. jk integrates with GitHub Actions, GitLab CI, Jenkins, etc., but doesn't ship a CI runner.
- A new artifact registry. jk speaks Maven Central's protocol; it does not invent a new one.

---

## 3. Personas

- **Maven veteran (Mira).** Tenured backend engineer, large enterprise. Has `~/.m2/settings.xml` with corporate mirror. Cares about CVE patches, lockable builds, Central publishing fidelity, and not being told to rewrite her POMs by Tuesday. Migration cost is everything. **jk wins her by:** `jk mvn` passthrough, `jk import pom.xml`, round-trip POM export, and a 10x faster developer-machine experience without her giving up Maven.

- **Gradle/Kotlin developer (Greg).** Ships a Spring Boot service in Kotlin. Loves the Kotlin DSL but resents the daemon, configuration-cache breakage, and uninspectable resolution. **jk wins him by:** TOML with schema validation in IntelliJ, `jk why-rebuilt :foo:compile`, native binary speed, and first-class Kotlin (K2 + KSP + Spring/JPA compiler plugins).

- **Polyglot adopter (Pria).** Heavy `uv` and `cargo` user, occasional Java contributor to a backend service. Wants the JVM to feel like the rest of her toolchain. **jk wins her by:** `jk init`, `jk add`, `jk sync`, `jk lock`, `jk tool install`, `jkx`, `jk audit`, and a 5-second `cargo new`-equivalent.

- **Library author (Liam).** Publishes an OSS Java library to Maven Central. Needs sources/javadoc jars, GPG signatures, valid POM with licenses/SCM/developers, Sigstore attestations, SLSA provenance. **jk wins him by:** one-command `jk publish` from CI with all of the above, no `nexus-staging-maven-plugin` rituals.

- **Platform engineer (Pat).** Owns the org's build platform. Wants reproducible CI builds, dependency confusion defense, supply-chain attestations, and an audit/policy gate. **jk wins her by:** mandatory checksum verification, repo-pinning per package, `jk audit`, `jk deny` (license/source/version policy), and SLSA L3 provenance on every published artifact.

---

## 4. Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│  jk (single static-linked GraalVM native binary)            │
│  (~140 MB at v0.9 — trimming to ~30 MB before v1.0)         │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────┐    │
│  │ CLI/TUI  │ │ Resolver │ │  Cache   │ │ Action graph │    │
│  │ (ANSI)   │ │ (PubGrub)│ │ (CAS+AC) │ │ (DAG runner) │    │
│  └──────────┘ └──────────┘ └──────────┘ └──────────────┘    │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────┐    │
│  │ HTTP/2   │ │ Git      │ │ JDK mgr  │ │ Compiler glue│    │
│  │ client   │ │ client   │ │ (Disco)  │ │ (javac/kotlinc)│  │
│  └──────────┘ └──────────┘ └──────────┘ └──────────────┘    │
└─────────────────────────────────────────────────────────────┘
        │            │            │              │
        │            │            │              │
        ▼            ▼            ▼              ▼
 ~/.jk/cache  ~/.jk/git     ~/.jk/jdks    ./target ./.jk
 (content-addr) (git CAS)   (vendor/ver)   (build out)
```

### Implementation language

- **Bootstrap:** Java 25.0.3, built with Gradle 9.5.1.
- **Production binary:** GraalVM native-image. Static linking on Linux (`musl` static), dynamic on macOS/Windows where required.
- **Self-hosting milestone:** v0.9 (jk builds itself). v1.0 ships as a jk-built binary.

### Filesystem layout

```
$HOME/.jk/
  bin/                          # user-PATH entry; tool launchers and native-installed binaries
  libexec/                      # actual jars/classpaths backing the launchers in bin/
  jdks/                         # installed JDKs, content-addressed by (vendor, version, arch, os)
    21.0.5-tem-aarch64-darwin/
    graalvm-jdk-21-aarch64-darwin/
  cache/
    sha256/                     # content-addressed blob store; jars, source jars, javadoc jars, metadata
      ab/cd/ef.../artifact.jar
    actions/                    # action cache: hash(action) -> hash(outputs)
    metadata/                   # cached maven-metadata.xml, repo index entries
    jkx/                        # ephemeral tool environments (LRU-evicted)
  git/
    db/<sha256(url)>/           # bare repo per canonical URL
    co/<sha256(url)>/<sha>/     # checkout per resolved SHA
    sparse/<sha256(url)>/<sha>/<path-hash>/   # sparse checkouts for monorepo sub-paths
  tools/
    bin -> ../bin
    envs/                       # one resolved env per installed tool
  credentials.toml              # per-host secrets (0600), or delegated to keychain/.netrc
  config.toml                   # user-global config (default vendor, parallelism, etc.)
  projects.toml                 # registry of known projects for `jk jdk gc`

<project>/
  jk.toml                       # canonical manifest, TOML
  jk.lock                       # TOML, sorted, committed
  .jk-version                   # optional, single-line JDK pin (sdkman scheme)
  .sdkmanrc                     # honored; jk reads it, may write it
  .jk/                          # generated, gitignored
    classpath.txt
    jdk -> ~/.jk/jdks/...
    generated/                  # KSP/annotation-processor outputs
    sync.json                   # last-sync state
  target/                       # generated, gitignored
    classes/                    # compiled main
    test-classes/               # compiled test
    jk-reports/
      junit/*.xml
      sarif/*.sarif
      sbom/cyclonedx.json
      sbom/spdx.json
      provenance/in-toto.json
    jk-events/<ts>.jsonl        # build event log
    *.jar                       # final artifacts
```

### Process model

- **No daemon.** Each `jk` invocation is a fresh process. GraalVM native-image makes this affordable.
- **In-process parallelism.** Worker pool sized to `min(cores, 4)` in CI (auto-detected via env), `cores` on developer machines, override via `--jobs`.
- **Out-of-process forks** only for: javac/kotlinc invocations that need a specific JDK, test JVMs, custom task scripts, native-image builds.

---

## 5. File Formats

### 5.1 `jk.toml` (canonical)

TOML. The single source of truth for project manifests. Reasons:

- Familiar to anyone who's edited a `Cargo.toml`, `pyproject.toml`, or `uv.lock`.
- Strict, declarative, no substitution / no includes / no surprises — the manifest is data.
- Dependabot, Renovate, GitHub's dependency graph all speak TOML fluently.
- Already in the toolchain — `jk.lock` uses TOML, so there's no second parser to maintain.

Conventions:

- All top-level tables are optional. A `jk.toml` with only `[project]` (`group`, `artifact`, `version`) is a valid project.
- A JSON Schema is published alongside the binary; IntelliJ/VS Code/Helix can autocomplete and validate.
- Dependencies are arrays of `"group:artifact:version"` strings per scope under `[dependencies]`. Non-registry overrides (git, path, URL) live in a parallel `[sources]` table keyed by `"group:artifact"`.

### 5.2 `jk.lock`

TOML. Sorted, deterministic (LF, terminal newline, two-space indent), no comments. Always committed to VCS. See §9.

### 5.3 `.jk-version`

Single line. SDKMAN candidate identifier (e.g., `21.0.5-tem`). Read on every `jk` invocation. If `jk.toml`'s `[project].jdk` is set, it takes precedence and `jk sync` regenerates `.jk-version`.

### 5.4 `.sdkmanrc`

Read, honored, optionally written. If both `.sdkmanrc` and `.jk-version` exist and disagree, `.jk-version` wins; jk warns once and offers `jk jdk sync-sdkmanrc` to reconcile.

---

## 6. Command Surface

A small, stable, Cargo-style verb set. No verbs are pluggable in v1.

| Command | Purpose |
|---|---|
| `jk new <name> [--lib\|--bin]` | Create a new project from a template. |
| `jk init` | Initialize jk in an existing directory (with optional `--from pom.xml` / `--from build.gradle`). |
| `jk add <coord> [--test] [--processor] [--runtime] [--provided] [--features=...]` | Add a dependency. |
| `jk remove <coord>` | Remove a dependency. |
| `jk lock` | Re-resolve and write `jk.lock`. |
| `jk sync [--locked\|--frozen]` | Reconcile cache/`.jk/` to the lockfile. |
| `jk update [--precise <coord>@<ver>]` | Re-resolve deps, updating `jk.lock`. |
| `jk build [-p <member>] [--profile=...] [--features=...]` | Compile and package. |
| `jk test [-p <member>] [--filter=...]` | Run tests. |
| `jk run [<bin>] [--with <coord>] [-- args...]` | Run a binary artifact or an inline script. |
| `jk check` | Type-check without producing artifacts (Kotlin/Java compile to in-memory). |
| `jk fmt` | Format `jk.toml`/`build.toml`/`jk.lock` and (optionally) source via plugged-in formatter. |
| `jk lint` | Run blessed linters (Checkstyle/ktlint/Spotless) emitting SARIF. |
| `jk tree [-i <coord>] [-e features] [--duplicates] [--depth N]` | Print resolved dependency tree. |
| `jk why <coord>` | Explain why a dependency is in the graph. |
| `jk explain [<target>]` | Print build plan with cache status. |
| `jk why-rebuilt <target>` | Diff cached-key vs current-key inputs for a task. |
| `jk graph [--format=dot\|html]` | Emit project graph. |
| `jk audit` | OSV vulnerability scan, SARIF output. |
| `jk deny` | License / source / version policy enforcement. |
| `jk publish [--repo <name>] [--dry-run]` | Sign, package, upload to a Maven-style repository. |
| `jk image [--registry <url>] [--push]` | Build an OCI image (Jib-style). |
| `jk native` | Build a GraalVM native binary from a `--bin` artifact. (Verb is `native`, not `native-image`, to keep it distinct from `jk image` which builds OCI container images.) |
| `jk tool install --git ... --bin ...` or `jk tool install <coord> --bin <name>` | Install a JVM CLI as a tool. (`jk install <coord>` is a hidden alias.) |
| `jkx <coord>[@ver] [-- args...]` | Ephemeral tool execution. |
| `jk tool {list,update,uninstall,run}` | Manage installed tools. |
| `jk jdk {install,list,use,uninstall,pin,gc,import-sdkman}` | JDK management. |
| `jk shell` / `jk env` | Spawn subshell or print env exports for the project's JDK. |
| `jk mvn ...` | Passthrough to Maven (jk downloads/manages Maven). |
| `jk gradle ...` | Passthrough to Gradle (jk downloads/manages Gradle). |
| `jk import {pom.xml\|build.gradle\|build.gradle.kts}` | Best-effort convert to `jk.toml`. |
| `jk export {pom.xml}` | Emit a publishable POM (Gradle export is v1.1+). |
| `jk scan` | Write a local HTML/JSON build scan report. |
| `jk fetch [--offline-prepare]` | Download all dependencies without building (CI-friendly). |
| `jk verify-build` | Rebuild in a clean directory and diff outputs. |

Common flags:

- `--output {pretty,json,jsonl}` — every command supports machine-readable JSON.
- `--profile <name>` — selects from `profiles.*` in `jk.toml`. `ci` is auto-selected when `CI=true`.
- `--offline` — fail rather than touch the network.
- `--locked` — fail if `jk.lock` would change.
- `--frozen` — `--locked` + `--offline`.
- `-p <member>` — scope to a workspace member.
- `--jobs N` — worker parallelism.
- `--color {auto,always,never}` — ANSI control.

Exit codes follow `sysexits.h` conventions:

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Build failure |
| 2 | Configuration error (invalid `jk.toml`) |
| 3 | Resolution failure |
| 4 | Test failure |
| 5 | Lock drift (`--locked` violation) |
| 6 | Network / repository error |
| 7 | Authentication error |
| 64+ | Usage / CLI errors |

---

## 7. Dependency Model

### 7.1 Coordinate format

Gradle convention: `groupId:artifactId:version` (e.g., `com.fasterxml.jackson.core:jackson-databind:2.18.2`). With optional classifier: `groupId:artifactId:version:classifier@type` (e.g., `io.netty:netty-transport-native-epoll:4.1.115:linux-x86_64@jar`).

### 7.2 Scopes (Maven names retained)

| Scope | `jk.toml` section | Compile classpath | Runtime classpath | Test classpath | Published `<scope>` |
|---|---|---|---|---|---|
| Main | `dependencies.main` | yes | yes | yes | `compile` |
| Provided | `dependencies.provided` | yes | no | yes | `provided` |
| Runtime | `dependencies.runtime` | no | yes | yes | `runtime` |
| Test | `dependencies.test` | no | no | yes (compile+runtime) | `test` |
| Processor | `dependencies.processor` | no (only `-processorpath`) | no | no | (omitted from POM) |
| Platform (BOM) | `dependencies.platform` | (constraints only) | (constraints only) | (constraints only) | `<dependencyManagement>` import |

`system` scope is rejected on import with a Tier-3 diagnostic. Use git deps or a local repo.

### 7.3 Version selectors

**Caret-by-default semantics**, like Cargo:

| Declaration | Meaning |
|---|---|
| `"2.18.2"` | `^2.18.2` — `>=2.18.2, <3.0.0` |
| `"=2.18.2"` | exactly `2.18.2` |
| `"~2.18.2"` | `>=2.18.2, <2.19.0` (tilde, patch-only) |
| `">=2.18, <3"` | range |
| `"latest"` | latest GA (never resolves SNAPSHOT) |

The lockfile pins the *resolved* exact version. Declarations are intent; the lock is fact.

**Important divergence from Maven:** Maven treats `2.18.2` as an exact requirement. jk treats it as a caret range. Imported POMs are translated to exact pins (`=2.18.2`) to preserve original semantics; new declarations the user writes use caret. The `jk import` report calls this out explicitly.

### 7.4 Conflict resolution

**Highest-version-wins** across the transitive graph (Gradle/Cargo behavior). Maven's nearest-wins is rejected as a footgun.

Conflicts are resolved by:

1. Strict requirements (`=` exact) always win and conflict with overlapping ranges.
2. Among compatible ranges, the highest version satisfying all is selected.
3. If no version satisfies all constraints, the resolver fails with a PubGrub-style explanation (see §8.2).
4. User overrides via `[patch]` or `forceVersion` in `jk.toml` are honored last and noted in `jk.lock` with `source = "user-override"`.

### 7.5 Repository pinning per package (dependency confusion defense)

By default, a coordinate is resolved by walking declared repositories in declared order; the first hit wins. **A package can be pinned to a specific repository** with `from = "internal"`:

```toml
[dependencies]
main = ["com.acme:internal-lib:1.0"]

[sources]
"com.acme:internal-lib" = { from = "internal" }
```

When pinned, jk will *refuse* to fetch the package from any other repo even at a higher version. This closes the dependency-confusion attack class.

### 7.6 Platform / BOM imports

```toml
[dependencies]
platform = ["org.springframework.boot:spring-boot-dependencies:3.4.0"]
```

Imported `<dependencyManagement>` constraints apply to all other scopes in the project (and, in a workspace root, to all members).

### 7.7 Target-conditional dependencies

```toml
[dependencies]
main = [
  "io.netty:netty-transport-native-epoll:4.1.115",
  "io.netty:netty-transport-native-kqueue:4.1.115",
]

[sources."io.netty:netty-transport-native-epoll"]
classifier = "linux-x86_64"
target     = "os(linux)"

[sources."io.netty:netty-transport-native-kqueue"]
classifier = "osx-aarch64"
target     = "os(darwin) && arch(aarch64)"
```

Supported target predicates in v1: `os(linux|darwin|windows)`, `arch(x86_64|aarch64)`, `jdk(>=N)`. User-defined attributes are not in scope (avoiding Gradle's variant-attribute complexity).

### 7.8 Features

Named, additive dependency sets. Solve real JVM problems (driver/parser/logger selection) without conditional source compilation.

```toml
[features]
default = ["postgres", "jackson"]

[features.postgres]
deps = ["org.postgresql:postgresql:42.7.4"]

[features.mysql]
deps = ["com.mysql:mysql-connector-j:9.0.0"]

[features.jackson]
deps = ["com.fasterxml.jackson.core:jackson-databind:2.18.2"]

[features.gson]
deps = ["com.google.code.gson:gson:2.11.0"]

[features.metrics]
deps = ["io.micrometer:micrometer-core:1.13.6"]

[features.full]
features = ["postgres", "jackson", "metrics"]
```

Consumer side — request specific features from a dependency via the `[sources]` override:

```toml
[dependencies]
main = ["com.example:widget:0.3.1"]

[sources."com.example:widget"]
features         = ["mysql", "gson"]
default-features = false
```

Features differ from profiles (see §14): features change *what* is compiled; profiles change *how*.

### 7.9 Patches and substitutions

```toml
[patch.central]
"org.example:flaky-lib" = { version = "1.2.3-fork.1", from = "internal" }

[patch."git:github.com/foo/bar"]
"com.foo:bar" = { path = "../local-bar" }

[workspace.substitute]
"com.acme:auth-client" = "projects.libs.auth"
```

`[patch]` overrides resolution transitively. `[workspace.substitute]` swaps a binary dep for a local source path (composite-build semantics). Path substitutions outside the workspace require `allow-path-deps = true` in `[project]` and are rejected on `jk publish`.

---

## 8. Resolution Algorithm

### 8.1 Resolver

PubGrub (the algorithm behind `pub`, `uv`, and modern Cargo). Chosen for two reasons: fast enough for the JVM ecosystem's coord graph, and capable of producing intelligible failure explanations.

The resolver:

1. Builds the constraint set from declared dependencies (including `[patch]`, BOMs, features).
2. Walks the graph, fetching `maven-metadata.xml` and POMs (or jk-native indexes when advertised) over HTTP/2 in parallel.
3. Performs unit propagation and decision-making per PubGrub.
4. On conflict, derives a minimal incompatibility set and renders it in English.
5. Writes the resolved graph + checksums to `jk.lock`.

### 8.2 Failure diagnostics

Maven says "Could not resolve dependencies." Gradle prints a 400-line tree. jk says:

```
× No version of com.foo:bar is compatible with your project.

  Because spring-boot-starter:3.2.5 depends on spring-core:6.1.*
      (via org.springframework.boot:spring-boot:3.2.5),
  and com.foo:bar:2.0 depends on spring-core:6.2.*,
  com.foo:bar:2.0 cannot be added to a project that uses Spring Boot 3.2.

  Other versions of com.foo:bar were considered:
    com.foo:bar:1.9.0 requires spring-core:6.1.* — compatible, but you asked for 2.0
    com.foo:bar:2.1.0 requires spring-core:6.2.* — same conflict
    com.foo:bar:1.x   compatible with Spring Boot 3.2

  Suggestions:
    1. Pin an older version:        jk add com.foo:bar:1.9.0
    2. Upgrade Spring Boot:         jk add org.springframework.boot:spring-boot-starter:3.4.0
                                    (this will upgrade spring-core to 6.2.x)
    3. Override the conflict:       jk add com.foo:bar:2.0 --override spring-core=6.2.0
                                    (not recommended — may break Spring Boot runtime)

  Run `jk why spring-core` to see the full constraint graph.
```

This is the single most differentiating UX feature in v1.0.

### 8.3 Networking

- HTTP/2 client with connection pooling.
- Parallel POM/metadata fetches across all candidate coords up to `--jobs` simultaneous in-flight.
- ETag / If-Modified-Since for metadata caching.
- Exponential backoff with jitter: 100ms, 200ms, 400ms, 800ms, 1.6s; max 5 retries; only on 5xx and network errors. No retry on 4xx.
- Range requests for resumable large-artifact downloads.
- Negative caching (5 min) for 404s.

### 8.4 SNAPSHOT handling

SNAPSHOT versions (`*-SNAPSHOT`) are tolerated on input for back-compat with corporate Maven workflows. The resolver records the timestamped variant in the lockfile (`1.0-20260520.123456-7`), giving reproducibility across re-syncs. `jk publish` refuses SNAPSHOT versions by default; `--allow-snapshot` is required.

---

## 9. Lockfile (`jk.lock`)

### 9.1 Properties

- **TOML.** Sorted, deterministic, LF, no comments.
- **One file at workspace root.** Always. Even in multi-module workspaces.
- **Committed to VCS.** For applications and libraries. The lockfile describes the development environment, not what consumers see at runtime.
- **`--locked` and `--frozen`** CI flags refuse to mutate it.
- **Schema-versioned** so jk upgrades can change the layout without silently breaking older lockfiles.

### 9.2 Schema

```toml
version = 4
generated-by = "jk 1.0.0"
resolution-algorithm = "pubgrub-v1"

[[package]]
name     = "com.fasterxml.jackson.core:jackson-databind"
version  = "2.18.2"
source   = "central+https://repo.maven.apache.org/maven2/"
checksum = "sha256:7b3a3d8c4f2e..."
deps = [
  "com.fasterxml.jackson.core:jackson-annotations@2.18.2",
  "com.fasterxml.jackson.core:jackson-core@2.18.2",
]

[[package]]
name     = "com.squareup.okhttp3:okhttp"
version  = "5.0.0"
source   = "git+https://github.com/square/okhttp?tag=parent-5.0.0#a1b2c3d4e5f6..."
path     = "okhttp"
checksum = "sha256:..."
deps = [ "com.squareup.okio:okio@3.9.0" ]

[metadata]
lockfile-checksum = "sha256:..."   # of everything above
```

`source` formats:

- `central+<https-url>` — declared central-like repo
- `repo:<name>+<https-url>` — named alternate repo
- `git+<url>?<ref>#<sha>` — git dep
- `path+file:///<absolute-path>` — local path substitution

### 9.3 Merge conflict resolution

`jk lock` is idempotent: running it on a conflicted merge result re-resolves to a valid state. `jk init` registers a `.gitattributes` merge driver (`jk.lock merge=jk-lock`) wired to `jk lock-merge` so most conflicts auto-resolve.

---

## 10. Repositories

### 10.1 Defaults

Maven Central (`https://repo.maven.apache.org/maven2/`) is enabled implicitly. Override with an explicit `central = { ... }` entry under `[repositories]` in `jk.toml` (e.g., to point at a corporate mirror).

### 10.2 Configuration

```toml
[repositories]
central  = { url = "https://repo.maven.apache.org/maven2/", default = true }
sonatype = { url = "https://s01.oss.sonatype.org/content/repositories/snapshots/", snapshots = true }
internal = { url = "https://nexus.example/repository/maven-releases/", auth = "env:NEXUS_TOKEN" }
gh-pkgs  = { url = "https://maven.pkg.github.com/acme/", auth = "gh-token" }
```

### 10.3 Authentication

Resolved in this order:

1. `auth = "env:NAME"` — environment variable bearer token.
2. `auth = "gh-token"` — uses `gh auth token` when present (developer machines).
3. `~/.jk/credentials.toml` (chmod 600).
4. `.netrc` for HTTP basic.
5. `~/.m2/settings.xml` `<servers>` for back-compat.
6. macOS Keychain / freedesktop secret service / Windows Credential Manager.

Credentials are *never* in `jk.toml`.

### 10.4 Mirror / replacement

```toml
repositories.central.mirror = "https://repo.corp.example.com/central"
```

A clean replacement for Maven's `<mirrorOf>` machinery.

### 10.5 Protocols

- HTTPS only by default; HTTP requires explicit `insecure = true` per repo (loud warning emitted).
- HTTP→HTTPS redirects allowed; HTTPS→HTTP refused unless `insecure = true`.
- HTTP/2 preferred where supported.
- Sparse-index optimization: if a repo advertises `/.well-known/jk-index/`, jk uses it; otherwise walks Maven layout.

### 10.6 Cache layout interoperability

jk's cache (`~/.jk/cache/sha256/...`) is content-addressed. A *view* is maintained at `~/.jk/cache/m2/` mirroring the Maven `groupId/artifactId/version/` layout (via hardlinks or copies, OS-dependent), so existing Maven and Gradle invocations can use the cache. jk also reads `~/.m2/repository` as a fallback source on cache miss, with full SHA-256 verification.

---

## 11. Git Dependencies

The headline new feature. Inspired by Cargo, with JVM-shaped extensions.

### 11.1 Declaration

A git dep appears as a bare `"group:artifact"` (no version) in the `[dependencies]` array, and the override lives in `[sources]` — the uv pattern:

```toml
[dependencies]
main = [
  "com.foo:bar",
  "com.foo:baz",
  "com.foo:qux",
]

[sources]
# Long form
"com.foo:bar" = { git = "https://github.com/foo/bar", tag = "v1.2.3", path = "modules/bar", submodules = true }
# Host shorthands (gh, gl, bb, sr — for github, gitlab, bitbucket, sourcehut)
"com.foo:baz" = { git = "gh:foo/baz", tag = "v0.5.0" }
# SSH form
"com.foo:qux" = { git = "git@github.com:foo/qux.git", branch = "main" }
```

The ref selector is exactly one of `tag`, `branch`, or `rev`. `submodules` defaults to `true`; set `false` to opt out per dep.

### 11.2 Pinning model

- Declaration carries *intent* (`tag`/`branch`/`rev`).
- `jk.lock` pins the resolved 40-char SHA.
- `jk update` re-resolves intent → SHA.
- `jk update --precise <coord>@<sha>` overrides the lock without touching declaration.

### 11.3 The trust model — be honest

A git tag is mutable. A Maven Central artifact, by repo policy, is immutable. jk closes this gap as far as it can:

- Always pin the SHA in the lockfile.
- On every fetch, verify that the requested ref still resolves to the locked SHA. If the tag moved, fail loudly with both SHAs; opt-in to accept via `jk update` or `--allow-tag-rewrites`.
- Optional commit-or-tag signature verification: `verify-signed = true` requires the commit/tag to be signed by a key in a project-local `keys.jk` trust store.
- Sigstore/cosign verification of git artifacts is a v1.1 addition.

**Documentation must state plainly: git deps are weaker immutability than Maven Central. For OSS libraries publishing to Central, prefer Central.**

### 11.4 Transitive resolution from a git dep

The git repo (at the requested path/ref) must contain one of:

1. `jk.toml` — preferred. Resolved with the same rules as a local project.
2. `pom.xml` — Tier-1 best-effort import in-flight, with warnings.
3. Neither — jk refuses to resolve (no inference from raw `src/main/java`).

### 11.5 Caching and sparse checkout

`~/.jk/git/` is content-addressed:

- `db/<sha256(canonical-url)>/` — bare clone per URL, fetched once.
- `co/<sha256(canonical-url)>/<sha>/` — checked-out working tree per resolved SHA.
- `sparse/<sha256(canonical-url)>/<sha>/<path-hash>/` — sparse checkout for monorepo subpaths.

URL canonicalization (lowercase host, strip `.git`, drop default port) prevents double-cloning.

### 11.6 Authentication

- HTTPS basic: `~/.jk/credentials.toml`, `git credential` helper protocol.
- SSH: `ssh-agent` + `~/.ssh/config`. jk never touches keys directly.
- GitHub developer: `gh auth token` reused transparently.
- CI: `GH_APP_INSTALLATION_TOKEN`, `CI_JOB_TOKEN`, generic `JK_GIT_TOKEN_<HOST>`.

---

## 12. JDK and Toolchain Management

### 12.1 Goals

End the SDKMAN/jenv/asdf/jabba/Homebrew/sdkman/manual-tarball sprawl. Make `jk` the obvious single tool that installs JDKs, manages per-project pinning, and exports `JAVA_HOME` to child processes.

### 12.2 Vendor support (v1)

Temurin (Adoptium), GraalVM CE, GraalVM Oracle, Liberica, Azul Zulu, Amazon Corretto, Oracle JDK, IBM Semeru, SapMachine, Microsoft OpenJDK.

Upstream metadata: the **Disco API** (foojay.io). SDKMAN uses the same source.

### 12.3 Version grammar

```
jk jdk install 21                  # latest LTS 21.x from default vendor (Temurin)
jk jdk install 21-tem              # explicit vendor suffix (sdkman-compatible)
jk jdk install 21.0.5-tem          # exact
jk jdk install lts                 # latest LTS overall
jk jdk install latest              # latest GA
jk jdk install '>=21,<25'          # range
jk jdk install graalvm-21          # GraalVM CE 21
jk jdk install graalvm-jdk-21      # GraalVM Oracle
```

### 12.4 `.sdkmanrc` compatibility

Read-and-write interop with the SDKMAN candidate identifier scheme. When `jk jdk pin 21.0.5-tem` is run, jk writes both `.jk-version` and `.sdkmanrc` (unless `--no-sdkman-compat`).

### 12.5 Install location

`~/.jk/jdks/<vendor>-<version>-<arch>-<os>/`. jk owns this tree.

`jk jdk import-sdkman` scans `~/.sdkman/candidates/java/` and symlinks pre-installed JDKs into `~/.jk/jdks/`. The user opts in explicitly; jk does not auto-trust SDKMAN's installs (no integrity record).

### 12.6 Activation

- **`jk shell`** — spawn a subshell with `JAVA_HOME`, `PATH`, and `KOTLIN_HOME` configured for the project. Exit returns to normal.
- **`jk env`** — print `export` lines for `eval "$(jk env)"`. Primary mechanism on systems where subshells are awkward.
- For child processes spawned by jk itself (javac, kotlinc, tests, scripts), jk sets `JAVA_HOME` directly and unsets `JDK_HOME` to avoid the common footgun.

### 12.7 Kotlin compiler

The Kotlin compiler is a *tool*, not a JDK. `jk.toml` declares `project.kotlin = "2.3.21"` and jk provisions the `kotlinc` distribution on demand: first via the good-neighbor probes (SDKMAN, JBang, asdf, jenv, Homebrew, `KOTLIN_HOME`), then by downloading into `~/.jk/tools/kotlin/<version>/` if no local install matches. Invocations go through a subprocess `CompileStrategy` so jk's own native binary doesn't embed kotlinc.

### 12.8 GraalVM as a capability

GraalVM distributions advertise the `native-image` capability. `jk native` requires a GraalVM-capable JDK; if missing, jk offers to install one (`jk jdk install graalvm-21`).

### 12.9 Garbage collection

`jk jdk gc` removes JDKs not referenced by any project in `~/.jk/projects.toml` (a registry jk maintains as projects are discovered). Skips JDKs marked `--keep`.

---

## 13. Workspaces

### 13.1 Model

Cargo + Nx hybrid. One root `jk.toml` declares members. Members are themselves valid `jk.toml` projects.

```toml
# workspace root jk.toml
[workspace]
members         = ["libs/*", "services/*"]
exclude         = ["libs/legacy-shim"]
default-members = ["services/api", "services/web"]
resolver        = 2                              # one resolver only; this field exists for future-proofing

[toolchain]
jdk    = "21.0.5-tem"
kotlin = "2.1.0"

[repositories]
# shared

[workspace.dependencies]
"com.fasterxml.jackson.core:jackson-databind" = "2.18.2"
"io.projectreactor:reactor-core"              = "3.6.10"
```

Member `services/api/jk.toml`:

```toml
[project]
group    = "com.example"
artifact = "api"
version  = "$workspace"

[dependencies]
main = [
  "com.fasterxml.jackson.core:jackson-databind:$workspace",
  "com.example:widget-core",
]

[sources]
"com.example:widget-core" = { path = "../../libs/core" }
```

### 13.2 Properties

- **One `jk.lock` at workspace root.** Always.
- **Shared `target/`** under workspace root. Incremental compilation reuses across members.
- **Workspace-level dep version pinning** (`dependencies.workspace.*`) — members reference via `$workspace`.
- **`jk -p <member> <cmd>`** scopes a command.
- **Virtual workspaces** supported — a root `jk.toml` with `workspace { }` but no `project { }` is valid.
- **Glob members** (`libs/*`) to avoid hand-listing.
- **Affected-by-change**: `jk build --affected-since=origin/main` computes the changed set via git + reverse dep graph.

### 13.3 Composite-build semantics

`[workspace.substitute]` lets a workspace member replace any binary dep (including a git dep) at build time:

```toml
[workspace.substitute]
"com.acme:auth-client" = "projects.libs.auth"
```

This unifies three Gradle features (classpath BOMs, composite builds, git deps) into one substitution mechanism.

---

## 14. Build Profiles

Cargo-style. Profiles change *how* code is compiled, not *what*.

```toml
[profiles.dev]
javac    = ["-g", "-parameters"]
jvm-args = ["-Xshare:auto"]
optimize = false

[profiles.release]
javac    = ["-g:none", "-parameters"]
jvm-args = ["-XX:+UseZGC", "-XX:+ZGenerational"]
optimize = true
native   = { enabled = false }

[profiles.bench]
javac    = ["-g:none", "-parameters"]
optimize = true

[profiles.ci]
inherits    = "dev"
parallelism = "min(cores, 4)"
tui         = false                 # plain text output
daemon      = false                 # always
```

`ci` is auto-selected when the standard CI env vars are present (`CI`, `GITHUB_ACTIONS`, `GITLAB_CI`, etc.). Explicit `--profile=...` overrides.

---

## 15. Features (see §7.8)

Already detailed in §7.8 — repeated here as a placeholder for cross-reference. Note: features differ from profiles. Features select *which* dependencies enter the graph (driver, parser, logger). Profiles select *how* the code is compiled.

---

## 16. Tasks and Lifecycle

### 16.1 Lifecycle phases (as user-facing verbs)

Maven's phases are useful *as mental anchors* even if the underlying execution model is a DAG:

| Verb | What runs |
|---|---|
| `jk check` | Resolve, compile (in-memory only), no artifacts. |
| `jk build` | Resolve, compile, process annotations/KSP, package jars, run linters. |
| `jk test` | `build` + execute tests, emit JUnit XML + SARIF + JaCoCo coverage. |
| `jk publish` | `build` + `test` + sign + upload. |
| `jk image` | `build` + emit OCI image. |
| `jk native` | `build` + GraalVM native compile. |
| `jk clean` | Delete `target/` and `.jk/generated/`. |

### 16.2 Task DAG

Internally, each verb expands to a graph of tasks. Tasks declare typed inputs and outputs. The engine:

1. Hashes inputs (file content + parameters + toolchain + jk version).
2. Looks up the input hash in the action cache; on hit, restores outputs.
3. On miss, executes the task, writes outputs to CAS, records the action mapping.
4. Schedules tasks in topological order with worker-pool parallelism.

### 16.3 Custom tasks (the escape hatch)

No plugin API in v1. The escape hatch is a **single-file Java 25 task script** referenced by path:

```toml
[tasks.generate-sql-models]
script      = "scripts/GenerateSqlModels.java"
inputs      = ["src/main/resources/schema.sql"]
outputs     = ["build/generated/sql-models"]
cacheable   = true
runs-before = ["compile-main"]
```

The script is a normal Java 25 file (unnamed-class style supported) with declared `main`. jk invokes it in a forked JVM with `JK_INPUTS`/`JK_OUTPUTS` env vars pointing at staged directories. Inputs/outputs are declared in TOML (data), so caching, parallelism, and `jk why-rebuilt` all work.

This satisfies extensibility for known v1 needs (codegen, custom packaging steps) without inventing a plugin SDK.

---

## 17. Incremental Compilation and Caching

### 17.1 Local cache

Two stores, both content-addressed:

- **CAS** (`~/.jk/cache/sha256/`) — blobs (jars, .class files, generated sources).
- **Action cache** (`~/.jk/cache/actions/`) — `hash(action) → hash(outputs)`. The action hash is `SHA-256(task_type + sorted_input_hashes + classpath_abi + parameters + toolchain + jk_version + os/arch_if_relevant)`.

### 17.2 Incremental compilation

- **v1.0 MVP:** file-content-hash based per-file recompile. Javac is invoked with the changed file set; Kotlin's `-Xincremental-compilation` is delegated to.
- **v1.5:** ABI-fingerprint compile avoidance. Extract ABI digests from each `.class` (ASM), recompile downstream only when ABI changes. Gradle does this; it's not magic.
- **v2.0+:** per-symbol dependency tracking (Buck/Bazel/Pants tier).

### 17.3 Cache poisoning prevention

- Verify checksums on every cache hit (cheap because keys are content addresses).
- Refuse writes from non-hermetic actions (env-tainted, network-using).
- Only the local user can write; multi-user shared caches require a server.

### 17.4 Remote cache (v1.1, future)

See §30. Wire format: Bazel REAPI for read-only client + simple HTTP/2 CAS for self-hosting. Skipped in v1.0 by user decision; planned as the next-up feature post-GA.

---

## 18. Testing

### 18.1 Default framework

JUnit Platform (JUnit 5). Auto-detected; no configuration. Other frameworks (TestNG, Spock, Kotest) are supported via standard JVM args / test engine SPI but are not the blessed default.

### 18.2 Outputs

- `target/jk-reports/junit/*.xml` (Surefire-compatible layout) — every CI consumer eats this.
- `target/jk-reports/jacoco/jacoco.xml` — coverage.
- `target/jk-reports/sarif/*.sarif` — when linters/security checks run.
- Live output during execution: failing test names printed immediately, summary at end, links to detailed HTML. Cargo-style.

### 18.3 Source sets

`src/test/{java,kotlin,resources}` is the default test source set. Custom sets:

```toml
[test-sets.integration]
path = "src/integrationTest"
deps = ["org.testcontainers:testcontainers:1.20.4"]    # appended to dependencies.test

[test-sets.smoke]
path = "src/smoke"
```

`jk test --set integration` runs only that set.

### 18.4 Parallelism

- Default test parallelism: 1 fork × N threads (`min(cores, 4)`).
- `test.fork.every = 100` runs each fork for at most 100 tests before recycling the JVM (Surefire's idiom).
- `test.jvm.args = [ ... ]` exposes JVM tuning declaratively.

---

## 19. Scripts: Subsuming JBang

jk owns single-file JVM scripting.

### 19.1 Header format

```java
///usr/bin/env jk run "$0" "$@"; exit $?

//jk jdk 21
//jk dep com.squareup.okhttp3:okhttp:4.12.0
//jk dep com.fasterxml.jackson.core:jackson-databind:2.18.2
//jk dep org.slf4j:slf4j-simple:2.0.16
//jk repo https://repo.maven.apache.org/maven2/
//jk feature postgres

import okhttp3.*;
// ...
void main() {
    System.out.println("hello");
}
```

### 19.2 JBang compatibility

`//DEPS`, `//JAVA`, `//SOURCES`, `//JAVAC_OPTIONS`, `//JAVA_OPTIONS`, `//FILES` directives are accepted. Existing JBang scripts run unmodified under `jk run`.

### 19.3 Execution model

- Header parsed; dependencies resolved (cached by SHA-256 of the header block).
- Source compiled to `.jk/script-cache/<header-hash>/`.
- Run with the project's (or `//jk jdk`-pinned) JDK.
- Second run with unchanged header is essentially-instant.

### 19.4 Languages

Java 25 (with unnamed-class / instance-main / JEP 512). Kotlin (`.kts` with jk-style header comments) is supported in v1.0 since the Kotlin compiler is already first-class.

---

## 20. Tools (`jk tool install` / `jkx`)

### 20.1 Install

```
jk tool install com.diffplug.spotless:spotless-cli:2.45.0 --bin spotless
jk tool install --git https://github.com/foo/bar --tag v1.2.0 --bin bar
jk tool install com.foo:bar --with com.baz:extra:1.0   # inject deps into env
```

### 20.2 Layout

```
~/.jk/tools/envs/<tool>/        # resolved env per tool, with pinned JDK
~/.jk/bin/<launcher>            # user-PATH entry, shell wrapper or native binary
```

`jk tool install` prints the `export PATH=...` line for `~/.jk/bin`; it does not mutate dotfiles. `jk tool update-shell` writes it with explicit user confirmation.

### 20.3 Ephemeral execution: `jkx`

```
jkx com.diffplug.spotless:spotless-cli:2.45.0 -- check
jkx --from com.foo:bar --bin baz -- arg1 arg2
```

Resolves, caches in `~/.jk/cache/jkx/`, executes. LRU-evicted. Subsequent runs of the same coord+version are near-instant.

### 20.4 Native-image tools

A GraalVM native binary published as a Maven artifact (the `<classifier>=native-x86_64-linux`-style convention) is preferred when available; jk picks the right one for the current OS/arch.

---

## 21. Publishing

### 21.1 Targets

```toml
[publish]
default-repo = "central"

[publish.repositories]
central = { type = "maven-central", staging = "ossrh" }
github  = { type = "maven", url = "https://maven.pkg.github.com/acme/internal", auth = "env:GITHUB_TOKEN" }
nexus   = { type = "maven", url = "https://nexus.example/repository/releases", auth = "env:NEXUS_TOKEN" }

[publish.pom]
licenses    = [{ name = "Apache-2.0", url = "https://www.apache.org/licenses/LICENSE-2.0" }]
developers  = [{ id = "bsant", name = "Bryan Sant", email = "bryan.sant@modmed.com" }]
description = "..."

[publish.pom.scm]
url = "https://github.com/modmed/widget"
```

### 21.2 What gets published

- `<artifactId>-<version>.jar` (main artifact).
- `<artifactId>-<version>-sources.jar`.
- `<artifactId>-<version>-javadoc.jar` (built unless `publish.javadoc = false`).
- `<artifactId>-<version>.pom` — generated; *no* `<repositories>`; no `<build>`; optional `<parent>`.
- `.md5`, `.sha1`, `.sha256`, `.sha512` per file.
- `.asc` GPG signatures (system `gpg` or bundled BouncyCastle).
- `*.intoto.jsonl` provenance and Sigstore signatures (when `--slsa-provenance=true`, default in CI).
- CycloneDX + SPDX SBOMs as additional artifacts.

### 21.3 POM export — fidelity rules

- Resolve all jk-specific concepts to Maven equivalents.
- **Git deps cannot appear** in the exported POM. If a published artifact transitively depends on a git dep, `jk publish` fails unless the git dep is also reachable through a Maven coord (which jk auto-detects when possible) or `--allow-git-deps-in-pom` is set (which writes a `provided`-scope marker — not valid for Central but acceptable internally).
- BOM imports flatten to `<dependencyManagement><dependency><scope>import</scope>`.
- `provided` scope round-trips.
- Features are stripped (they're consumer-side selectors; not a POM concept).

### 21.4 SNAPSHOT publishing

Refused unless `--allow-snapshot`. Recommend `-dev.N` / `-rc.N` qualifiers in `jk publish --help`.

---

## 22. Container Images

`jk image` ships in v1.0. Jib-style, daemonless, distroless, multi-arch.

### 22.1 Defaults

- Base image: `gcr.io/distroless/java21-debian12:nonroot` (or `:latest` for current LTS).
- Layered: dependency jars (rarely change), resources, application classes, project files.
- Multi-arch: `linux/amd64` and `linux/arm64` manifest list. Same JRE base; layers share when possible.
- Push directly to registry; no Docker daemon required.
- Reproducible: `SOURCE_DATE_EPOCH`, sorted entries, stable file modes.
- SBOM + provenance attached as OCI 1.1 referrers.

### 22.2 Config

```toml
[image]
base      = "gcr.io/distroless/java21-debian12:nonroot"
user      = "nonroot"
ports     = [8080]
env       = { JAVA_OPTS = "-XX:+UseZGC -XX:+ZGenerational" }
labels    = { "org.opencontainers.image.source" = "https://github.com/modmed/widget" }
registry  = "ghcr.io/modmed"
tag       = "${project.version}"
platforms = ["linux/amd64", "linux/arm64"]
```

Buildpacks-based emission is not in v1.0 (CNCF spec churn, large surface area).

---

## 23. Supply Chain Security

### 23.1 Mandatory checksum verification

Every downloaded artifact's SHA-256 must match the lockfile. No escape hatch. Missing checksums = build failure.

### 23.2 GPG signatures

Maven Central artifacts ship `.asc` signatures. jk verifies them by default; allow keyring pinning per `groupId`:

```toml
[verification.gpg]
required-for-groups = ["org.springframework.*", "com.fasterxml.jackson.*"]

[verification.gpg.allowed-keys]
"org.springframework" = ["0x...", "0x..."]
```

### 23.3 Sigstore / cosign

First-class. Verify cosign signatures and Rekor inclusion proofs when present. `jk publish` signs with keyless OIDC by default when run in CI (any CI with OIDC token issuer is auto-detected).

### 23.4 SLSA L3 provenance (default for CI)

`jk publish` in CI emits in-toto attestations with `slsa.dev/v1` predicate type:

- Hermetic build inputs (locked deps, locked toolchain).
- Non-falsifiable build platform identity via OIDC.
- Reproducible verification (`jk verify-build` rerun in clean dir).

Opt-out: `publish.slsa = false` for air-gapped environments. Default-on is by design.

### 23.5 `jk audit`

OSV is the primary data source (federated; aggregates GHSA, RustSec, PyPA, etc.; MIT-licensed). Optional NVD enrichment. SARIF output. Exit non-zero only on *applicable* vulns (matching the locked version range; reachability analysis is v1.5+).

```
jk audit                          # scan jk.lock
jk audit --fix                     # propose patched upgrade for each finding
jk audit --severity=high           # filter by severity
jk audit --output=sarif > out.sarif
```

### 23.6 `jk deny`

License / source / version policy gate:

```toml
[deny]
yanked = "deny"                                       # cargo-style yanked-version policy

[deny.licenses]
deny  = ["GPL-3.0", "AGPL-3.0"]
allow = ["Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "EPL-2.0"]

[deny.sources]
deny = ["jcenter.bintray.com"]
```

### 23.7 Hermeticity (Cargo-plus)

Default behavior:

- Scrub env: allowlist `LANG`, `LC_*`, `TERM`, project-relative paths. Strip `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, `KOTLIN_HOME`, `MAVEN_OPTS`, `GRADLE_OPTS`.
- Pin toolchain (JDK + Kotlin compiler).
- Lock deps.
- Verify checksums on every fetch and cache hit.
- Sorted jar entries; `SOURCE_DATE_EPOCH` from `HEAD` commit time (or workspace-pinned constant).
- Force 0644/0755 file modes; clear `Created-By` / `Build-Jdk` manifest pollution.

Opt-in:

- `jk build --hermetic` enables sandbox-exec (macOS) / bwrap (Linux) per-action sandboxing. Not the default in v1.0.
- `jk verify-build` rebuilds in a clean directory and diffs.

### 23.8 Dependency confusion defense

Repositories are queried *in declared order* with first-hit-wins. **A package pinned to a repo via `from = "..."` is refused from any other repo.** This closes the dependency-confusion attack class.

### 23.9 SBOM emission

Every build emits:

- `target/jk-reports/sbom/cyclonedx.json` (CycloneDX 1.6).
- `target/jk-reports/sbom/spdx.json` (SPDX 2.3).

Both, because GitHub's dependency graph prefers SPDX and most vuln scanners prefer CycloneDX. Generation is microseconds from an already-resolved lockfile.

---

## 24. Migration and Compatibility

### 24.1 `jk mvn` and `jk gradle` passthrough

```
jk mvn clean install
jk gradle build
```

jk downloads and runs the real Maven or Gradle (pinned via `.mvn/wrapper/maven-wrapper.properties` or `gradle/wrapper/gradle-wrapper.properties` if present, otherwise jk picks a sane version). Adds value via:

- Faster JDK switch via jk's toolchain manager.
- Shared `~/.jk/cache/` for downloads.
- Prettier output (ANSI, grouped sections in CI).
- `--offline` / `--locked` semantics layered on top.

This is the `uv pip` lesson applied to JVM tooling: don't pretend to be Maven, but get out of Maven's way.

### 24.2 `jk import pom.xml`

Three-tier fidelity:

**Tier 1 (lossless, auto):** dependencies (all scopes), dependencyManagement, properties, parent inheritance (flattened), basic build config (finalName, sourceDirectory, resources), maven-compiler-plugin (source/target/release, annotation processors), maven-surefire/failsafe (includes/excludes, jvmArgs), maven-jar-plugin manifest entries, repositories, distributionManagement, standard packaging (jar/war/pom).

**Tier 2 (best-effort, with warnings):** profiles with simple property/OS activation (file-existence activators warned and skipped), common plugins with well-known mappings (Spotless, JaCoCo, Shade/Assembly with caveats, Spring Boot, Quarkus, Kotlin Maven plugin), multi-module `<modules>` → workspace.

**Tier 3 (unsupported, emit diagnostic + stub):** maven-antrun-plugin, custom in-house plugins, profiles with non-trivial activation, `<extensions>`, `<distributionManagement><site>`, `system`-scope deps.

Output: a fresh `jk.toml` *and* a `jk-import-report.md` listing every unsupported construct with original line numbers. Never fail silently.

### 24.3 `jk import build.gradle(.kts)`

Best-effort for declarative-style Gradle scripts. Anything that *executes Groovy/Kotlin* at configuration time (most of Gradle's "power") is flagged as Tier 3 with a manual conversion stub. We're honest about this — fully converting arbitrary Gradle is impossible without writing a Gradle interpreter.

### 24.4 `jk export pom.xml`

Round-trip publishing — see §21. Generates a Maven-Central-grade POM from `jk.toml`.

`jk export build.gradle.kts` is v1.1+.

### 24.5 IDE support

IntelliJ plugin is critical and on the v1.0 timeline as a parallel effort. Strategy: in early versions, the plugin generates a synthetic `pom.xml` for IntelliJ's Maven importer to consume, so users get IDE features without IntelliJ needing native jk knowledge. Native jk support follows once we have install base.

VS Code support via a `vscode-jk` extension (JSON Schema for `jk.toml`, command palette wrappers, problem matchers).

---

## 25. Observability and Debugging

### 25.1 `jk explain`

```
build plan for widget v0.3.1 (profile: dev, features: [postgres, jackson]):
  1. compile-processor-path:  lombok 1.18.34, hibernate-jpamodelgen 7.0.0.Final
  2. compile-main:             12 sources, javac --release 21, -parameters
  3. package-main:             widget-0.3.1.jar
  4. compile-test:             8 sources
  5. test:                     47 jars on test classpath

cache status:
  compile-main:  HIT  (no source changes since 8 minutes ago)
  compile-test:  MISS (WidgetTest.java modified)
```

### 25.2 `jk why <coord>`

Shows the dependency chain and why a version was selected:

```
org.slf4j:slf4j-api v2.0.16 is pulled in by:
  widget v0.3.1
  └── ch.qos.logback:logback-classic v1.5.8 (main)
      └── org.slf4j:slf4j-api v2.0.16

resolved to v2.0.16 because:
  - logback-classic 1.5.8 requires slf4j-api >=2.0.16
  - selecting highest compatible: 2.0.16
```

### 25.3 `jk why-rebuilt <task>`

```
task `:compile-main` rebuilt because input hash changed.

inputs that changed since last cached run:
  src/main/java/com/example/Widget.java
    last:    sha256:ab12...
    current: sha256:cd34...

inputs unchanged: 11 sources, classpath ABI (47 entries), javac flags.
```

This is the single most-requested diagnostic in Gradle's bug tracker.

### 25.4 `jk tree`

Cargo-style. Flags: `-i <coord>` invert, `-e features` show features, `--duplicates`, `--depth N`, `--format pretty|machine`.

### 25.5 `jk graph`

DOT to stdout (`| dot -Tsvg`), `--format=html` for an embedded interactive viewer (~200KB D3-based).

### 25.6 Event log

`target/jk-events/<timestamp>.jsonl`. One JSON object per line, schema versioned. Compatible with `jq` out of the box. Includes: task start/end, cache hit/miss, resolution decisions, errors. Optional gRPC streaming compatible with Bazel BEP for tooling vendors.

### 25.7 `jk scan`

Writes a local HTML/JSON build scan report (no upload). Includes: timeline, cache hit rates, slowest tasks, dependency tree, resolution diagnostics. Privacy default: nothing leaves the machine.

### 25.8 Profile output

`--profile=trace.json` emits chrome://tracing format. Every Buck2/Bazel user knows the format.

### 25.9 Anonymous telemetry

**Off by default. No exceptions.** Users can opt in with `jk config set telemetry.enabled true` for aggregate usage data sent to a self-hostable endpoint. Cargo and uv got this right; jk follows.

---

## 26. CI/CD Integration

### 26.1 Setup actions / plugins

- `setup-jk` (GitHub Actions, 5-line adoption).
- CircleCI orb.
- GitLab CI template include.
- Tekton Task.
- Jenkins plugin.

### 26.2 CI defaults

When `CI=true` (or `GITHUB_ACTIONS`, `GITLAB_CI`, etc.):

- Parallelism = `min(cores, 4)`.
- Heap = `min(75% of cgroup limit, 4 GB)`.
- No fancy TUI; plain text, ANSI-coded section markers (`::group::`/`::endgroup::` on GHA, `section_start:` on GitLab).
- Verbose logging on failure (auto-rerun the failing task with `--verbose`).
- `--frozen` implied. Resolution offline; only declared cache fetches allowed.

### 26.3 Cache integration

`jk --print-cache-key` emits a stable hash of `(jk.lock + toolchain + jk version)`. CI cache plugins key off this without user effort.

### 26.4 Reports

- `target/jk-reports/junit/*.xml` — Surefire-compatible; consumed by GHA test reporter, GitLab `junit` artifact, Jenkins JUnit plugin.
- `target/jk-reports/sarif/*.sarif` — code scanning ingestion.
- `target/jk-reports/sbom/{cyclonedx,spdx}.json` — dependency graph ingestion.
- `target/jk-reports/provenance/in-toto.jsonl` — SLSA attestations.

### 26.5 CI examples (illustrative)

```yaml
# .github/workflows/build.yml
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: modmed/setup-jk@v1
        with: { version: '1.0' }
      - run: jk --frozen build test
      - uses: actions/upload-artifact@v4
        with:
          name: reports
          path: target/jk-reports/
```

---

## 27. Performance Targets

Published benchmarks with reproducible scripts and a public dashboard. Targets:

| Scenario | Target | Reference |
|---|---|---|
| Cold start, no cache, full Spring Boot app | ≤ 50% of Maven, ≤ 70% of Gradle | — |
| Warm cache no-op (`jk build` after no changes) | < 200ms | Maven ~3s; Gradle daemon ~500ms; Bazel ~150ms; uv ~50ms |
| Incremental, one-file change in 100-module repo | < 1s | — |
| Dependency resolution, fresh Spring Boot starter | < 2s | Maven ~8s; Gradle ~5s |
| Parallelism efficiency at 8 cores | > 0.7 | — |
| `jk` binary size | < 35 MB | uv ~25 MB; Cargo ~10 MB (sans rustc) |

The aspirational comparison is uv. uv is fast because it's Rust + opinionated + skips Python's traditional resolver dance. jk's native binary + PubGrub + opinionated defaults should land in the same neighborhood.

---

## 28. Defaults and Configuration

### 28.1 User-global config

`~/.jk/config.toml`:

```toml
[default]
vendor = "tem"               # default JDK vendor for `jk jdk install <ver>`
parallelism = "cores"

[telemetry]
enabled = false              # opt-in only

[colors]
mode = "auto"
```

### 28.2 Project-level overrides

Anything in `~/.jk/config.toml` is overridable in `jk.toml`'s top-level scope. Workspaces override per-member.

### 28.3 Environment variables

| Variable | Effect |
|---|---|
| `JK_CACHE_DIR` | Override `~/.jk/cache/`. |
| `JK_JDK_DIR` | Override `~/.jk/jdks/`. |
| `JK_OFFLINE` | Same as `--offline`. |
| `JK_LOG` | Log level (`error`, `warn`, `info`, `debug`, `trace`). |
| `JK_NO_COLOR` / `NO_COLOR` | Disable ANSI. |
| `CI` (and friends) | Auto-select `profile=ci`. |
| `JK_GIT_TOKEN_<HOST>` | Bearer token for a git host. |

---

## 29. Out of Scope (v1.0)

Explicit non-goals at v1.0 (most have a future-phase home, see §30):

- Remote build cache, Bazel REAPI client, remote execution.
- Plugin SDK / third-party plugin marketplace.
- Android (AAR, R8/D8, resources, AGP compat).
- Kotlin Multiplatform (JS/Native/iOS/Wasm), Compose Multiplatform iOS.
- `kapt` annotation processing.
- Buildpacks-based image emission.
- Gradle build-script *export* (round-trip out).
- ABI-fingerprint compile avoidance (v1.5).
- IDE-native protocol support beyond synthetic-POM bridge.
- Self-hosted artifact registry / Sonatype-replacement.
- Sandbox execution (`--hermetic`) is opt-in only at v1.0.
- Per-symbol incremental compilation (Buck/Bazel tier).
- Multi-language polyglot beyond Java + Kotlin (no Scala, Clojure, Groovy first-class).

---

## 30. Future Phases (Roadmap)

### v1.1 (Phase 2 — "Scale")

- **Remote build cache.** Bazel REAPI read-only client + simple HTTP/2 CAS for self-hosting. Compatible with BuildBuddy, EngFlow, NativeLink, `bazel-remote`. Optional auth via OIDC.
- **Gradle build script export** (`jk export build.gradle.kts`).
- **Sigstore verification for git deps** (cosign-signed commits/tags).
- **Native IDE protocol support** in IntelliJ plugin (drop the synthetic-POM bridge).

### v1.2

- **ABI-fingerprint compile avoidance** (Gradle-tier).
- **Android (AAR) build support.** AGP-comparable functionality: R8/D8, resources, manifest merging. This is a significant scope addition and may slip to v1.3+ depending on community demand and engineering capacity.

### v1.5

- **Per-symbol incremental compilation** (Buck/Bazel tier).
- **Kapt support** (community-plugin path; jk core stays KSP-only). Decision deferred based on real usage data.

### v2.0 (Phase 3 — "Multiplatform")

- **Kotlin Multiplatform**: JVM + JS targets initially; Native (Linux/macOS/Windows) and iOS as separate sub-phases.
- **Compose Multiplatform** Desktop/Web blessed; iOS community.
- **Limited plugin SDK.** Two years of v1.x experience should reveal which extension points are actually needed. Cargo waited five years; jk waits two.

### Permanently out

- Self-hosted artifact registry / Central replacement.
- A Bazel-grade sandboxed RBE backend (jk is a *client* of REAPI, not a server).

---

## 31. Open Questions and Risks

### Open questions

1. **IntelliJ plugin staffing** — without a credible plugin, jk is dead in the water for the enterprise audience. Who builds it?
2. **`jk fmt` source-formatting scope** — does jk ship google-java-format / ktlint blessed, or stay out of source-style entirely? Recommend: ship blessed defaults but allow override.
3. **TOML syntax-error UX** — TOML's diagnostics are mediocre. jk must wrap the parser with line-precise error rendering.
4. **`~/.m2`-cache sharing safety** — reading existing `~/.m2/repository` entries means trusting a cache jk didn't populate. Mitigate via SHA-256 verification against the Maven Central metadata; refuse on mismatch.
5. **`gh auth token` reuse trust boundary** — automatically reusing the `gh` CLI's token is convenient but security-flavored. Should it require explicit opt-in?
6. **Workspace member discovery cost** — glob-based `members = [ "libs/*" ]` requires filesystem walks. On huge monorepos this can be slow. Cache the resolved list and invalidate on directory mtime.

### Risks

- **GraalVM native-image compatibility.** Some Java/Kotlin idioms (reflection, dynamic class loading, JNI) need configuration. Maintaining a `META-INF/native-image/` config file for jk itself is ongoing engineering work.
- **PubGrub on Maven's Wild West.** PubGrub assumes a relatively clean dependency ecosystem. Maven Central has decades of badly-versioned, conflicting, looped, or weirdly-classified artifacts. The resolver implementation needs significant defensive engineering.
- **Adoption ceiling.** Maven and Gradle are entrenched. Even with great UX, jk faces the same headwind every replacement tool faces. The `jk mvn`/`jk gradle` passthrough is the key bet that lets users adopt jk without giving up their existing build.
- **POM export fidelity edge cases.** Round-tripping arbitrary jk concepts (features, target predicates, git deps) into a Central-valid POM is harder than it looks. Document the limits early and refuse to publish lossy POMs by default.
- **Trust model of git deps.** A user-facing security caveat. Be transparent in the docs and consider Sigstore-required mode for production-critical projects.

---

## 32. Glossary

- **Action cache** — `hash(action) → hash(outputs)` lookup. The mechanism that makes incremental builds correct.
- **BOM** (Bill of Materials) — a Maven POM with `<dependencyManagement>` only, declaring versions to be inherited.
- **CAS** (Content-Addressed Store) — blob storage keyed by content hash.
- **Coord** (Coordinate) — `groupId:artifactId:version[:classifier@type]`.
- **Disco API** — foojay.io's JDK discovery REST API. The de facto JDK index.
- **TOML** (Human-Optimized Config Object Notation) — Lightbend's JSON superset.
- **PubGrub** — the dependency-resolution algorithm behind `pub`, `uv`, and modern Cargo.
- **REAPI** — Bazel's Remote Execution API; the lingua franca of remote build caching/execution.
- **SBOM** — Software Bill of Materials (CycloneDX or SPDX format).
- **SLSA** (Supply-chain Levels for Software Artifacts) — `slsa.dev`'s framework for build provenance.
- **Sigstore / cosign** — keyless signing infrastructure with a public transparency log.
- **`uv`** — Astral's Python package and project manager, the spiritual model for jk.

---

## Appendix A — Decisions Locked by the Project Owner (2026-05-21)

For traceability, here are the controversial calls made during the design debate and their resolutions:

| Question | Decision |
|---|---|
| v1.0 scope ambition | Broad v1 (incl. `jk audit`, `jk image`, SLSA L3, local cache). REAPI/remote cache + limited plugin SDK documented as future phases. |
| Plugin/extensibility model at v1.0 | **No plugin API at all.** Escape hatch = JBang-style single-file Java task scripts with declared inputs/outputs. |
| Version selector default semantics | **Caret-by-default** (Cargo-style). `"2.18.2"` means `^2.18.2`. `=`/`~`/range forms supported. Imported POMs translated to exact pins. |
| Kotlin v1.0 scope | **Kotlin/JVM only.** K2 + KSP + Spring/JPA compiler plugins + serialization. Android and KMP documented for v1.2 / v2.0 respectively. |
| Scope naming | **Keep Maven names** (`main`/`provided`/`runtime`/`test`/`processor`/`platform`). Drop `system`. |
| Cargo-style features | **Yes, dependency-set features in v1.0** (no conditional source compilation). |
| Hermeticity default | **Cargo-plus.** Env scrubbing, lock, checksum verification by default. Filesystem sandbox is opt-in via `--hermetic`. |
| SNAPSHOT policy | **Allow ingest, reject publish, lock timestamp.** Refuse SNAPSHOT publish unless `--allow-snapshot`. |

---

*End of document. Comments, corrections, and counter-proposals welcome.*
