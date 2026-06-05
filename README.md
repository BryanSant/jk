<img width="1024" height="274" alt="jk" src="https://github.com/user-attachments/assets/5cf7e056-1eed-43f7-9c6f-67bbb8d1e807" />


# jk - A modern build tool for Java and Kotlin.

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![GraalVM](https://img.shields.io/badge/native--image-GraalVM%2025-yellow.svg)](https://www.graalvm.org/)
[![Status](https://img.shields.io/badge/status-alpha-red.svg)](docs/implementation-plan.md)

> The fastest way to run your existing Maven or Gradle build, and it just happens to come with a better build tool you can opt into when you're ready.

`jk` is a single-binary build tool inspired by `cargo` and `uv`: PubGrub
resolver with prose diagnostics, content-addressed action cache,
reproducible-by-default artifacts, GraalVM-compiled native binary. It
speaks Maven Central's protocol natively and ships first-class
`jk mvn` / `jk gradle` passthroughs so you don't have to migrate to
start using it.

## Supported JDKs

`jk` targets the forward-facing Java/Kotlin developer. The supported
range is **JDK 17 and above** — specifically, every LTS at or after 17
(17, 21, 25, …) plus the single most recent release on the JetBrains
feed. There is no support for Java 8, 11, or any other interim release
before 17. The catalog, registry, and `jk.toml` parser all enforce this
floor.

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

# Pin a JDK per-project
jk jdk install 25.0.3-tem
jk jdk use 25.0.3-tem

# Use your existing Maven build unchanged
jk mvn package
```

## What's in the box

| Subsystem | Verbs |
|---|---|
| Dependencies | `add` `remove` `lock` `sync` `update` `tree` `why` `fetch` |
| Build | `compile` `build` `test` `clean` `explain` `why-rebuilt` |
| Toolchain | `jdk install/list/use/uninstall/reconcile/home` |
| CLI tools | `tool install/list/uninstall/run/dir` |
| Maven / Gradle | `mvn` `gradle` (passthroughs), `import` `export` |
| Publishing | `publish` (GPG + Sigstore + SLSA + CycloneDX/SPDX SBOM) |
| Supply chain | `audit` (OSV), `deny` (license/source/yanked policy) |
| Containers / native | `image` (Jib-core OCI), `native` (GraalVM native-image) |
| Reproducibility | `verify-build` |
| Cache & maintenance | `cache dir/info/prune/clean`, `doctor` |
| Scripting | `run script.java` (JBang-compatible) |

`jk --help` lists every canonical verb. Hidden migration aliases
(`jk package` → `jk build`, `jk install` → `jk tool install`,
`jk nativeCompile` → `jk native`, …) are documented in
[`docs/aliases.md`](docs/aliases.md).

## Workspaces

A workspace is a root `jk.toml` with a `[workspace]` table listing members.
There is one `jk.lock` at the root, and `jk lock` / `jk build` run from any
member resolve the whole workspace — Cargo/uv semantics.

Like `cargo new` and `uv init`, the project commands register members for
you when run inside a workspace, so you never hand-edit
`[workspace].members`:

```bash
# Scaffold a new member; appends "libs/widget" to [workspace].members,
# inherits the workspace group, and writes no per-member jk.lock.
jk new libs/widget

# Same, but initialise the current directory as a member.
cd libs/widget-core && jk init

# Depend on a local member from another member: adds the dependency edge
# AND registers the path as a workspace member (≈ `uv add ./lib`).
jk add ../widget          # path form
jk add :widget            # ':' marks a local member by name
```

A bare name with no `:` and no path separator (e.g. `jk add jackson`) is
treated as a library-catalog name / Maven coordinate, not a local path.

## Git-source dependencies

Depend on a git repository instead of a published coordinate — the JitPack
model, but first-class and built with `jk`'s own pipeline. `jk` clones the
repo (reusing forge auth), builds it from its `jk.toml`, locally publishes
the artifact, and hands the resolver an exact coordinate pin — so the solver
resolves it like any other dependency, transitive deps and all.

```toml
[dependencies.main]
# Coordinate is discovered from the repo's own [project]; the version is
# derived from the ref — a tag coerces to SemVer, a branch becomes
# <branch>-SNAPSHOT, a rev becomes a tag-anchored pseudo-version.
mylib  = { git = "https://github.com/acme/widgets", tag = "v1.4.0" }
edge   = { git = "https://gitlab.com/acme/edge",     branch = "main" }
pinned = { git = "https://github.com/acme/widgets",  rev = "3f2a9c1…" }
submod = { git = "https://github.com/acme/monorepo", tag = "v2.0.0", path = "libs/core" }

# Override the discovered coordinate / derived version (handy for forks):
fork   = { git = "https://github.com/me/widgets-fork", branch = "main",
           group = "com.acme", name = "widgets", version = "1.4.0-acme" }
```

The resolved commit SHA is pinned in `jk.lock`, so a locked build is
reproducible and offline-friendly — `jk build` never re-clones. `jk lock`
fails loudly if an upstream **tag** was force-moved since the lock;
`jk update` re-resolves branch tips (and accepts moved tags) and rebuilds
only when the SHA changed. Only `jk.toml`-based source builds are supported
for now. See [`docs/git-source-deps.md`](docs/git-source-deps.md).

## Why another build tool

- **Native binary, no daemon.** `jk --help` is a sub-50ms cold start. No JVM warmup tax, no daemon to restart when configuration cache breaks.
- **Reproducibility is the default.** Locked deps, locked toolchain, scrubbed environment, sorted jar entries, deterministic timestamps. `jk verify-build` re-builds in a scratch dir and diffs SHA-256.
- **Diagnostics are a product.** PubGrub-style English error messages, `jk why`, `jk explain`, `jk why-rebuilt`, all offline.
- **Adoption first.** `jk mvn` and `jk gradle` work day one. `jk import pom.xml` produces a `jk.toml`; `jk export pom.xml` keeps Maven Central publishing fidelity.
- **Supply chain is built-in.** GPG, Sigstore, SLSA v1 provenance, CycloneDX, SPDX, OSV vulnerability scanning, dependency-confusion defense — all first-party verbs, not plugins.

## Status

Pre-v1.0. The v0.1 → v0.9 milestones in the
[implementation plan](docs/implementation-plan.md#5-milestone-roadmap)
are shipped — including self-hosting (jk builds itself). v1.0 work
focuses on IntelliJ / VS Code integration, the benchmark dashboard,
and the GA release artifact pipeline.

The native binary is built via `gradle :cli:nativeCompile`. A
JVM-mode entry point ships as `./gradlew :cli:run`.

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
