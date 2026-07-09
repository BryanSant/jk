<img width="1024" height="274" alt="jk" src="https://github.com/user-attachments/assets/5cf7e056-1eed-43f7-9c6f-67bbb8d1e807" />


# jk — the modern, blazing-fast build tool for the JVM

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![GraalVM](https://img.shields.io/badge/native--image-GraalVM%2025-yellow.svg)](https://www.graalvm.org/)
[![Status](https://img.shields.io/badge/status-alpha-red.svg)](docs/implementation-plan.md)

> **Tired of XML bloat, fragile Groovy/Kotlin build scripts, and babysitting a
> build daemon?** `jk` is the fastest way to run your existing Maven or Gradle
> build — and it just happens to come with a better build tool you can opt
> into when you're ready.

---

## Why jk?

For two decades the JVM has been trapped in a duopoly: the rigid, verbose XML
of Maven, or the hyper-flexible, hyper-complex scripting of Gradle. Meanwhile
Rust (`cargo`), Python (`uv`), and Go (`go build`) proved that build tooling
can be fast, declarative, reproducible by default, and a joy to use.

`jk` brings that generation of tooling to Java and Kotlin. It is a single
natively-compiled binary that manages your JDKs, resolves dependencies with
mathematical precision, handles monorepos effortlessly, and never rebuilds
anything it can prove it already built.

- **One native binary, nothing to install.** No bootstrap scripts, no wrapper
  jars, no runtime to set up first. `jk --help` answers in under 50 ms.
- **Batteries included.** The Java and Kotlin compilers, test runner,
  publisher, OCI image builder, dependency auditor, and git client are
  first-party subsystems — not marketplace plugins you wire up by hand.
- **Bazel-shape caching.** A content-addressed store plus an action cache
  keyed by input hashes: a fully-cached build recompiles nothing, and a
  partial change rebuilds exactly what it must.
- **Dependency resolution that's actually correct.** A PubGrub solver,
  highest-version-wins semantics, and a canonical lockfile — conflicts come
  back as English sentences, not a wall of tree output.
- **Reproducible by default.** Locked dependencies, locked toolchain,
  scrubbed environment, deterministic archives. `jk verify` proves it.
- **Adopt it without migrating.** `jk mvn` / `jk gradle` run your *real*
  existing build, and `jk import` / `jk export` move you across when ready.

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

## Core architecture

### A fast client, a lightweight engine

The `jk` you type is a small native binary with a sub-50 ms cold start. Build
work is served by the **jk engine** — the same binary, re-invoked as a lazy
resident service the first time you build. The engine hosts the build graph,
caches, and worker pools, and schedules memory across *everything happening on
the machine*: two workspaces building at once, or a CI box running many `jk`
invocations, share one coordinated memory plan instead of each claiming "all
the free RAM" and OOM-ing each other.

This is not the Gradle daemon experience, and the differences are deliberate:

- **It's the same single binary** — nothing extra to install, version-skew is
  detected and the engine transparently replaced.
- **It's memory-disciplined.** The engine targets a small fixed footprint and
  sizes compiler/test workers against physical RAM — not multiple idle
  gigabytes per project.
- **It manages itself.** Lazily spawned, idle-exits on its own (configurable),
  and never needs a ritual restart to un-corrupt a configuration cache.
  `jk engine status` / `jk engine stop` are there when you want them.

Presentation stays in the client: ANSI rendering, progress, Ctrl-C, shell
integration. The engine is a server; the CLI is just its first front-end (see
[Architecture & extensibility](#architecture--extensibility)).

Being hyper-responsible with memory is a design pillar, not a tuning
afterthought — measured on this repo's own 8-module workspace (Linux x86-64,
2026-07):

| | jk | Gradle 9.6.1 |
|---|---|---|
| Download / install | one 33 MB binary | wrapper + ~130 MB distribution + JVM |
| CLI invocation | < 10 ms, ~19 MiB RSS | JVM client per invocation |
| Resident process | engine, **hard-capped 256 MiB heap** (SerialGC), ~22 MiB RSS idle, exits after 2 h idle | daemon ~680 MiB RSS observed (1.5 GiB with two), lingers indefinitely |
| Compile/test memory | forked workers, sized to the machine, exit with the build | daemon + Kotlin daemon stay resident |

The engine's ceiling is enforced at spawn (`-Xmx`), observable at any time via
`jk engine status`, and configurable per machine — see
[docs/engine.md](docs/engine.md).

### The performance model: content-addressed everything

Every build task is keyed by the SHA-256 of its inputs, and outputs live in a
content-addressed store (CAS). If the inputs haven't changed, outputs are
*restored*, not recomputed. Identical outputs are stored once and shared
across tasks and projects. Because keys are content hashes, a restored build
is bit-for-bit identical to a fresh one — this is Gradle's opt-in build cache
made the default execution model.

- `jk explain` forecasts what a build will do and why, before it runs.
- `jk cache info / prune / purge` keep the store in check.

### Next-gen toolchain management & shell integration

Say goodbye to fighting `JAVA_HOME`.

- **Built-in JDK management** (replaces SDKMAN / jenv / Gradle toolchains):
  `jk jdk install temurin-25` downloads from the JetBrains feed; jk also
  *discovers* the JDKs that SDKMAN, jenv, asdf, mise, Gradle, IntelliJ, and
  Homebrew already installed.
- **Directory-aware shells** (`bash`, `zsh`, `fish`, `pwsh`):
  `eval "$(jk activate bash)"` keeps `JAVA_HOME` / `GRAALVM_HOME` pointed at
  whatever JDK your current project pins — `cd` and your environment follows.
  (`jkx`, the uvx-style alias for `jk tool run`, is a real binary
  installed next to `jk` — it works in shebangs and CI without any
  shell integration.)
- Maven, Gradle, and the Kotlin compiler are auto-discovered or fetched on
  demand. See [docs/jdk-resolution.md](./docs/jdk-resolution.md).

### One coherent TOML manifest

No XML, no embedded scripting. A `jk.toml` reads like a `Cargo.toml`:
declarative, schema-validated, the same shape for a one-file app or a
40-module workspace.

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

---

## Feature breakdown

### Dependencies & reproducibility

- **PubGrub resolution** (the algorithm behind Dart's `pub` and `uv`):
  highest-version-wins across the whole graph — never Maven's surprising
  *nearest-wins* footgun. Conflicts come back as prose: *"no version of X is
  compatible with Y because…"* — plus `jk why <coord>` and `jk tree` to trace
  any edge, offline.
- **Caret semantics by default** (`"1.2.3"` ≡ `^1.2.3`), `=1.2.3` to pin,
  `~1.2.3` for patch-only, ranges like `>=1.2,<2`.
- **`jk.lock` is canonical.** `jk build` never re-resolves; `jk update`
  re-resolves from your declared constraints on purpose. Locked builds are
  reproducible and offline-friendly.
- **`jk verify`** rebuilds your project in a scratch directory and diffs the
  SHA-256 of every artifact against what you shipped.

### Workspaces & monorepos

A workspace is a root `jk.toml` with a `[workspace]` table listing modules.
There is **one `jk.lock` at the root**, and `jk lock` / `jk build` from any
module resolve the whole workspace — Cargo/uv semantics, not Maven's
per-module model.

```bash
jk new libs/widget        # scaffold a module; auto-registers it in [workspace]
jk add ../widget          # depend on a local module by path
jk add :widget            # …or by name (':' marks a local module)
```

The project commands register modules for you — you never hand-edit
`[workspace].modules`. A bare name with no `:` and no path separator
(`jk add jackson`) is a catalog name / Maven coordinate, not a path.

### Git repositories as dependencies

Depend on a git repo instead of a published coordinate — the JitPack idea,
first-class. `jk` clones (reusing your forge auth), builds from the repo's
`jk.toml`, locally publishes, and hands the resolver an exact pin.

```toml
[dependencies]
mylib  = { git = "https://github.com/acme/widgets", tag = "v1.4.0" }
edge   = { git = "https://gitlab.com/acme/edge",     branch = "main" }
pinned = { git = "https://github.com/acme/widgets",  rev = "3f2a9c1…" }
submod = { git = "https://github.com/acme/monorepo", tag = "v2.0.0", path = "libs/core" }
```

The resolved commit SHA is pinned in `jk.lock`; `jk lock` fails loudly if an
upstream tag was force-moved. Only `jk.toml`-based source builds are supported
for now — see [docs/git-source-deps.md](docs/git-source-deps.md).

### Supply chain, built in — not bolted on

| Concern | How |
|---|---|
| Vulnerability scanning | `jk audit` (OSV, direct) |
| Policy gate | `jk deny` (license / source / yanked) |
| Dependency-confusion defense | `from = "<repo>"` pin per coordinate — `jk` refuses to fetch it anywhere else |
| Signing | `jk publish --sign` (BouncyCastle, no system `gpg`) |
| Keyless signing | `jk publish --sigstore` (CI OIDC auto-detected) |
| Provenance | `jk publish --slsa` (SLSA v1 in-toto) |
| SBOM | `jk publish --sbom` (CycloneDX 1.6 + SPDX 2.3) |

### Repositories & credentials

`jk` speaks Maven Central's protocol natively and reaches every backend
through one credential model — reusing your forge token when the backend is a
forge's package registry.

- **Public & private HTTP:** Maven Central, Nexus, Artifactory, plain
  HTTP/WebDAV (Basic or Bearer auth).
- **Forge package registries:** GitHub Packages, GitLab Package Registry —
  the same token used for git, via `jk auth` (GitHub / GitLab / Gitea /
  Bitbucket token resolution + OAuth device flow).
- **Object stores:** S3 / MinIO, Google Cloud Storage (Azure Blob is phased).

Secrets stay out of committed files — TOML repository entries reference
`${ENV}` / settings rather than literals, and `~/.m2/settings.xml` is parsed
for existing credentials. See [docs/artifact-repos.md](docs/artifact-repos.md).

### Ecosystem interoperability

We know you can't migrate overnight. `jk` meets you where you are.

- **Passthroughs:** `jk mvn …` / `jk gradle …` download and run the *real*
  Maven/Gradle (honouring your wrapper properties), adding output grouping
  and shared caching on top.
- **Import:** `jk import pom.xml` generates a `jk.toml` with a tiered
  fidelity report, so you know exactly what carried over. Limitations are
  explicit, not hidden: POM import is solid; Gradle import is string-level
  (no script evaluation) — bespoke plugin logic doesn't translate.
- **Export:** `jk export maven` emits a Maven-Central-grade POM; `jk export
  idea` / `jk vscode` generate IDE project files.
- **A curated library catalog:** `jk add jackson` resolves a short name
  through a layered catalog (project → per-user → global registry → bundled
  floor). The bundled layer works offline; `jk library update` refreshes.
- **Scripting:** `jk run script.java` runs JBang-compatible single-file
  scripts; `jk tool run <tool>` (or the `jkx` binary) takes a catalog name
  (`jkx ktlint`), a coordinate spec (`g:a` = latest, `g:a@1.2`, `g:a:v`), or
  a script/jar file — resolving, caching, running, and LRU-evicting any
  published CLI without polluting your project.

---

## What's in the box

| Subsystem | Verbs |
|---|---|
| Dependencies | `add` `remove` `lock` `sync` `update` `tree` `why` |
| Build | `compile` `build` `test` `clean` `explain` |
| Engine | `engine start/status/stop` |
| Toolchain | `jdk install/list/default/graal/pin/ensure/home/uninstall/update`, `activate` `shell` `deactivate` |
| CLI tools | `tool install/list/uninstall/run/dir` |
| Library catalog | `library update/list/search` |
| Maven / Gradle | `mvn` `gradle` (passthroughs), `import`, `export gradle/maven/idea` |
| IDE | `ide` `vscode` |
| Auth & repos | `auth login/logout/status/token`, `repo login/logout` |
| Publishing | `publish` (GPG + Sigstore + SLSA + CycloneDX/SPDX SBOM) |
| Supply chain | `audit` (OSV), `deny` (license/source/yanked policy) |
| Containers / native | `image` (Jib-core OCI), `native` (GraalVM native-image) |
| Reproducibility | `verify` |
| Formatting | `format` |
| Cache & maintenance | `cache dir/info/search/prune/purge`, `doctor` |
| Scripting | `run script.java` (JBang-compatible) |

`jk --help` lists every canonical verb. Hidden migration aliases
(`jk package` → `jk build`, `jk nativeCompile` → `jk native`, …) are
documented in [`docs/aliases.md`](docs/aliases.md).

---

## Translating modern concepts for JVM developers

If you've spent your career in Maven or Gradle, two of `jk`'s paradigms
deserve a proper introduction.

### What is a lockfile (and why you want one)

Declaring `implementation("org.slf4j:slf4j-api:2.0.0")` does not lock your
build. If a transitive dependency publishes a broken version tonight, your
build can change tomorrow without you touching a line. The first time `jk`
resolves, it writes the exact version *and hash* of every artifact in the
graph to `jk.lock`. You commit it. Builds are now deterministic: if it builds
on your machine, it builds identically on CI, forever — and entirely offline.
Maven has no lockfile; Gradle treats verification as opt-in homework. `jk`
treats the lockfile as canonical.

### TOML vs. XML vs. code-as-configuration

- **Maven (XML):** declarative, but verbose and boilerplate-heavy.
- **Gradle (Groovy/Kotlin):** a full programming language — your build file
  is itself a software project, with compile times, debugging sessions, and
  plugin API breakage.
- **jk (TOML):** configuration as *data*. Trivial to read, fast to parse,
  safe for the tool itself to edit (`jk add`), and impossible to break with
  someone's clever build script.

## For non-JVM developers

If you're coming from Rust, Go, or modern Python, you may see the JVM as a
great runtime wrapped in 2005-era tooling. `jk` was built to dismantle that
stereotype — the ergonomics you expect, on the platform you need:

- A single `cargo`/`uv`-style native binary; no runtime managers to install.
- Workspaces that behave like Cargo workspaces, with one lockfile at the root.
- No multi-gigabyte daemon to babysit — a slim, self-managing engine process
  that idle-exits when the work is done.
- `jk jdk install` and directory-aware shell activation mean the right JDK is
  simply *there*, like `rustup` or `uv python`.

---

## Architecture & extensibility

`jk` is built around a strict engine/front-end split: the engine (build
graph, resolver, caches, toolchains) is a server with a facade API, and the
CLI is merely its first client. That boundary is compiler-enforced today and
is what the post-1.0 roadmap builds on:

```
                  ┌─────────────────────────────────┐
                  │          jk core engine         │
                  │  resolver · action graph · CAS  │
                  │  toolchains · workers · events  │
                  └────────────────┬────────────────┘
                                   │
         ┌─────────────────────────┼─────────────────────────┐
         ▼                         ▼                         ▼
┌─────────────────┐      ┌──────────────────┐      ┌──────────────────┐
│ Native CLI      │      │ IDE integrations │      │ Future clients   │
│ (jk / jkx)      │      │ (IntelliJ, VS    │      │ (build-insights  │
│                 │      │  Code — v1.0)    │      │  UI, MCP/agents) │
└─────────────────┘      └──────────────────┘      └──────────────────┘
```

There is deliberately **no third-party plugin API before v2.0** — every
built-in works on day one, versions together, and can't break your build from
a marketplace. The extension surface is the engine API, not build-script
hooks.

## Supported JDKs

`jk` targets the forward-facing Java/Kotlin developer: **JDK 17 and above** —
every LTS at or after 17 (17, 21, 25, …) plus the single most recent release
on the JetBrains feed. There is no support for Java 8 or 11. The catalog,
registry, and `jk.toml` parser all enforce this floor.

## Status

Pre-v1.0. The v0.1 → v0.9 milestones in the
[implementation plan](docs/implementation-plan.md#5-milestone-roadmap) are
shipped — including self-hosting (jk builds itself). v1.0 work focuses on
IntelliJ / VS Code integration, the benchmark dashboard, and the GA release
artifact pipeline.

The shippable layout is built via `./gradlew dist` under `build/dist/`: the
slim native `jk` client (`:cli:nativeCompile`) next to `lib/jk-engine-<version>.jar`,
the engine's fat jar (installed to `~/.jk/lib/`) — the engine runs as a normal
Java app on the jk-managed JDK, never as a native image. A runnable all-JVM
distribution is produced by `./gradlew :cli-engine:installDist` under
`cli-engine/build/install/`.

## Documentation

- [Product requirements](docs/requirements.md) — the PRD
- [Implementation plan](docs/implementation-plan.md) — module map, library picks, milestone roadmap
- [Comparison with uv / Cargo / Gradle / Maven](docs/comparison.md)
- [The jk engine](docs/engine.md) — the resident engine service: lifecycle, transport, memory plan
- [Tool discovery](docs/tool-discovery-plan.md) — good-neighbor probing for Maven/Gradle/Kotlin
- [Verb aliases](docs/aliases.md) — migration shortcuts from Maven/Gradle/npm
- [Forge authentication](docs/forge-auth.md) — `jk auth` across GitHub/GitLab/Gitea/Bitbucket
- [Artifact repository backends](docs/artifact-repos.md) — unified credentials + transports

## License

[Apache 2.0](LICENSE).
