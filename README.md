<img width="1024" height="274" alt="jk" src="https://github.com/user-attachments/assets/5cf7e056-1eed-43f7-9c6f-67bbb8d1e807" />


# jk - A modern build tool for Java and Kotlin.

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![GraalVM](https://img.shields.io/badge/native--image-GraalVM%2025-yellow.svg)](https://www.graalvm.org/)
[![Status](https://img.shields.io/badge/status-alpha-red.svg)](docs/implementation-plan.md)

> The fastest way to run your existing Maven or Gradle build, and it just happens to come with a better build tool you can opt into when you're ready.

`jk` is a single-binary build tool for the JVM, inspired by `cargo` and
`uv`. One ~30 MB GraalVM-compiled native binary gives you a PubGrub
dependency resolver with prose diagnostics, a content-addressed action
cache, JDK/toolchain management, reproducible-by-default artifacts, and a
batteries-included supply-chain pipeline — with **zero runtime
dependencies** and no daemon. It speaks Maven Central's protocol natively
and ships first-class `jk mvn` / `jk gradle` passthroughs, so you don't have
to migrate to start using it.

## Supported JDKs

`jk` targets the forward-facing Java/Kotlin developer. The supported range is
**JDK 17 and above** — specifically, every LTS at or after 17 (17, 21, 25, …)
plus the single most recent release on the JetBrains feed. There is no support
for Java 8, 11, or any other interim release before 17. The catalog, registry,
and `jk.toml` parser all enforce this floor.

## Quick start

```bash
# Bootstrap a new project
jk init my-app
cd my-app

# Add a dependency
jk add com.fasterxml.jackson.core:jackson-databind:2.18.2

# Type-check / build / test
jk compile
jk build
jk test

# Run a published CLI tool ephemerally (≈ uvx)
jk tool run com.diffplug.spotless:spotless-cli:2.45.0 -- check

# Install a JDK (jk downloads + manages it) and pin it to this project
jk jdk install temurin-25
jk jdk pin temurin-25

# Use your existing Maven build unchanged
jk mvn package
```

---

## What makes `jk` different

The features below are roughly ordered by how quickly they pay off — start at
the top.

### 1. Batteries included — one binary, no daemon, nothing to install

`jk` is a single native executable. There is no JVM to warm up, no daemon to
restart when the configuration cache breaks, and **no separate plugins to hunt
down on a marketplace**. The things you reach for on every project — the Java
and Kotlin compilers, the test runner, the publisher, the OCI image builder,
the dependency auditor, the git client — are first-party subsystems compiled
*into the binary*, not third-party plugins you wire up by hand.

- **Sub-50 ms cold start.** `jk --help` returns before you've let go of Enter.
- **Built-in toolchain management (replaces SDKMAN / jenv / Gradle toolchains).**
  `jk jdk install temurin-25` downloads a JDK from the JetBrains feed; jk also
  *discovers* the JDKs SDKMAN, jenv, asdf, Gradle, and IntelliJ already installed.
  `eval "$(jk activate bash)"` installs a directory-aware hook that keeps
  `JAVA_HOME` / `GRAALVM_HOME` pointed at the JDK your current project pins — you
  never manage `JAVA_HOME` by hand. Maven, Gradle, and the Kotlin compiler are
  auto-discovered or fetched on demand. See [docs/jdk-resolution.md](./docs/jdk-resolution.md).
- **Ephemeral tool exec (≈ `uvx`).** `jk tool run <coord>` resolves, caches,
  runs, and LRU-evicts any published CLI without polluting your project.
- **Reproducible by default.** Locked deps, locked toolchain, scrubbed
  environment, sorted jar entries, deterministic timestamps. `jk verify`
  rebuilds in a scratch dir and diffs the SHA-256.

### 2. Adopt it without migrating

You can install `jk` today and keep your existing build. `jk mvn …` and
`jk gradle …` download and run the *real* Maven/Gradle (honouring your
wrapper properties), adding ANSI output grouping and shared caching on top.
When you're ready to move:

```bash
jk import pom.xml              # generate a jk.toml from an existing build
jk export pom.xml             # emit a Maven-Central-grade POM from jk.toml
```

`jk import` produces a tiered fidelity report so you know exactly what
carried over. **Limitations are explicit, not hidden:** POM import is solid;
Gradle import is string-level (no script evaluation), and only declarative
constructs are understood — bespoke plugin logic doesn't translate. IntelliJ
project import is on the roadmap (TODO). Export round-trips your dependencies,
coordinates, and BOM imports, but not custom `<build>` plugin executions.

### 3. One coherent TOML manifest

No XML, no Groovy/Kotlin DSL, no embedded scripting. A `jk.toml` reads like a
`Cargo.toml` or a `pyproject.toml` — declarative, schema-validated, and the
same shape whether you're describing a one-file app or a 40-module workspace.

```toml
[project]
group   = "com.acme"
name    = "widgets"
version = "1.4.0"
jdk     = 25

[dependencies]
jackson = "2.18.2"                       # caret by default: ^2.18.2
guava   = ">=33,<34"
mylib   = { git = "https://github.com/acme/mylib", tag = "v1.4.0" }

[test-dependencies]
junit   = "5.11.0"
```

Because the manifest carries no logic, `jk` can edit it for you safely
(`jk add` / `jk remove`) and reason about it offline.

### 4. Dependency resolution that's actually correct

`jk` uses a **PubGrub** solver (the algorithm behind Dart's `pub` and `uv`) and
treats the lockfile as canonical — not as optional verification the way Gradle
does, and not absent entirely the way Maven leaves it.

- **Highest-version-wins across the whole graph**, never Maven's surprising
  *nearest-wins* footgun.
- **Caret semantics by default** (`"1.2.3"` ≡ `^1.2.3`), with `=1.2.3` for an
  exact pin, `~1.2.3` for patch-only, or arbitrary ranges like `>=1.2,<2`.
- **`jk.lock` makes builds reproducible and offline.** `jk build` never
  re-resolves; `jk update` re-resolves from your declared constraints on
  purpose.
- **Conflicts come back as English.** Instead of a wall of tree output, you get
  *"no version of X is compatible with Y because…"* — plus `jk why <coord>` and
  `jk tree` to trace any edge, all offline.

### 5. A content-addressed action cache (Bazel-shape)

Every build task is keyed by the SHA-256 of its inputs and its outputs are
stored in a content-addressed store (CAS). If the inputs haven't changed, the
outputs are *restored*, not recomputed — so a no-op build is instant and a
partial change rebuilds exactly what it must, and nothing more.

- Identical outputs are stored once and shared across tasks and projects.
- `jk why-rebuilt` tells you precisely which input invalidated a task.
- `jk cache info / prune / purge` keep the store in check.

This is the same idea as Gradle's build cache, but it's the default execution
model rather than an opt-in flag — and because keys are content hashes, a
restored build is bit-for-bit identical to a fresh one.

### 6. Workspaces & monorepos

A workspace is a root `jk.toml` with a `[workspace]` table listing modules.
There is **one `jk.lock` at the root**, and `jk lock` / `jk build` from any
module resolve the whole workspace — Cargo/uv semantics, not Maven's
per-module model.

```bash
jk new libs/widget        # scaffold a module; auto-registers it in [workspace]
cd libs/widget-core && jk init
jk add ../widget          # depend on a local module by path
jk add :widget            # …or by name (':' marks a local module)
```

The project commands register modules for you, so you never hand-edit
`[workspace].modules`. A bare name with no `:` and no path separator
(`jk add jackson`) is treated as a catalog name / Maven coordinate, not a path.

### 7. Supply chain, built in — not bolted on

Most JVM builds reach this through a pile of third-party plugins. In `jk` these
are first-party verbs that work on day one:

| Concern | How |
|---|---|
| Vulnerability scanning | `jk audit` (OSV, direct) |
| Policy gate | `jk deny` (license / source / yanked) |
| Dependency-confusion defense | `from = "<repo>"` pin per coordinate — `jk` refuses to fetch it anywhere else |
| Signing | `jk publish --sign` (BouncyCastle, no system `gpg`) |
| Keyless signing | `jk publish --sigstore` (CI OIDC auto-detected) |
| Provenance | `jk publish --slsa` (SLSA v1 in-toto) |
| SBOM | `jk publish --sbom` (CycloneDX 1.6 + SPDX 2.3) |

### 8. Git-source dependencies

Depend on a git repository instead of a published coordinate — the JitPack
idea, but first-class and built with `jk`'s own pipeline. `jk` clones the repo
(reusing forge auth), builds it from its `jk.toml`, locally publishes the
artifact, and hands the resolver an exact coordinate pin — so transitive deps
resolve like any other dependency.

```toml
[dependencies]
mylib  = { git = "https://github.com/acme/widgets", tag = "v1.4.0" }
edge   = { git = "https://gitlab.com/acme/edge",     branch = "main" }
pinned = { git = "https://github.com/acme/widgets",  rev = "3f2a9c1…" }
submod = { git = "https://github.com/acme/monorepo", tag = "v2.0.0", path = "libs/core" }
```

The resolved commit SHA is pinned in `jk.lock`, so a locked build is
reproducible and offline-friendly. `jk lock` fails loudly if an upstream **tag**
was force-moved; `jk update` re-resolves branch tips and rebuilds only when the
SHA changed. Built-in git support also powers `jk auth` across
GitHub/GitLab/Gitea/Bitbucket (token resolution + OAuth device flow). Only
`jk.toml`-based source builds are supported for now — see
[`docs/git-source-deps.md`](docs/git-source-deps.md).

### 9. Comprehensive repository support

`jk` speaks Maven Central's protocol natively and reaches every backend through
one consistent credential model — reusing your forge token automatically when
the backend is a forge's package registry.

- **Public & private HTTP:** Maven Central, Nexus, Artifactory, plain
  HTTP/WebDAV (Basic or Bearer auth).
- **Forge package registries:** GitHub Packages, GitLab Package Registry —
  authenticated with the same forge token used for git.
- **Object stores:** S3 / MinIO, Google Cloud Storage, Azure Blob (phased).

Secrets stay out of committed files — TOML repository entries reference
`${ENV}` / settings rather than literals, and `~/.m2/settings.xml` is parsed for
existing credentials. See [`docs/artifact-repos.md`](docs/artifact-repos.md).

### 10. A curated library catalog

Skip memorising coordinates. `jk add jackson` resolves a short name through a
layered catalog (project → per-user → global registry → bundled-with-the-binary
floor), so common libraries are one word. The bundled layer works offline; the
global layer refreshes via `jk library update`, and any name can be overridden
locally in your `jk.toml`'s `[libraries]` table.

---

## What's in the box

| Subsystem | Verbs |
|---|---|
| Dependencies | `add` `remove` `lock` `sync` `update` `tree` `why` `fetch` |
| Build | `compile` `build` `test` `clean` `explain` `why-rebuilt` |
| Toolchain | `jdk install/list/default/graal/pin/home/uninstall/update`, `activate` `shell` `deactivate` |
| CLI tools | `tool install/list/uninstall/run/dir` |
| Library catalog | `library update`, short-name resolution in `add` |
| Maven / Gradle | `mvn` `gradle` (passthroughs), `import` `export` |
| Publishing | `publish` (GPG + Sigstore + SLSA + CycloneDX/SPDX SBOM) |
| Supply chain | `audit` (OSV), `deny` (license/source/yanked policy) |
| Containers / native | `image` (Jib-core OCI), `native` (GraalVM native-image) |
| Reproducibility | `verify` |
| Cache & maintenance | `cache dir/info/prune/purge`, `doctor` |
| Scripting | `run script.java` (JBang-compatible) |

`jk --help` lists every canonical verb. Hidden migration aliases
(`jk package` → `jk build`, `jk nativeCompile` → `jk native`,
`jk verify-build` → `jk verify`, …) are documented in
[`docs/aliases.md`](docs/aliases.md).

## Status

Pre-v1.0. The v0.1 → v0.9 milestones in the
[implementation plan](docs/implementation-plan.md#5-milestone-roadmap) are
shipped — including self-hosting (jk builds itself). v1.0 work focuses on
IntelliJ / VS Code integration, the benchmark dashboard, and the GA release
artifact pipeline.

The native binary is built via `./gradlew :cli:nativeCompile`. A runnable JVM
distribution is produced by `./gradlew :cli:installDist` under `cli/build/install/`.

## Documentation

- [Product requirements](docs/requirements.md) — the PRD
- [Implementation plan](docs/implementation-plan.md) — module map, library picks, milestone roadmap
- [Comparison with uv / Cargo / Gradle / Maven](docs/comparison.md)
- [Tool discovery design](docs/tool-discovery-plan.md) — good-neighbor probing for Maven/Gradle/Kotlin (JDKs are sourced directly from IntelliJ's JDK directory and the JetBrains feed)
- [Verb aliases](docs/aliases.md) — migration shortcuts from Maven/Gradle/npm
- [Forge authentication](docs/forge-auth.md) — `jk auth` across GitHub/GitLab/Gitea/Bitbucket: token resolution + OAuth device flow
- [Artifact repository backends](docs/artifact-repos.md) — unified credentials + transports for Nexus/Artifactory/HTTP/WebDAV/S3/GCS/Azure and package registries (phased)

## License

[Apache 2.0](LICENSE).
