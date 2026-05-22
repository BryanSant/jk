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

# Run a published CLI tool ephemerally (the jkx ≈ uvx analog)
jkx com.diffplug.spotless:spotless-cli:2.45.0 -- check

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
| Toolchain | `jdk install/list/use/uninstall/reconcile/dir` |
| CLI tools | `tool install/list/uninstall/run/dir`, `jkx` |
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

## Why another build tool

- **Native binary, no daemon.** `jk --help` is a sub-50ms cold start. No JVM warmup tax, no daemon to restart when configuration cache breaks.
- **Reproducibility is the default.** Locked deps, locked toolchain, scrubbed environment, sorted jar entries, deterministic timestamps. `jk verify-build` re-builds in a scratch dir and diffs SHA-256.
- **Diagnostics are a product.** PubGrub-style English error messages, `jk why`, `jk explain`, `jk why-rebuilt`, all offline.
- **Adoption first.** `jk mvn` and `jk gradle` work day one. `jk import pom.xml` produces a `build.jk`; `jk export pom.xml` keeps Maven Central publishing fidelity.
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
- [Tool discovery design](docs/tool-discovery-plan.md) — the good-neighbor SDKMAN/JBang/asdf/jenv/Homebrew probing
- [Verb aliases](docs/aliases.md) — migration shortcuts from Maven/Gradle/npm

## License

[Apache 2.0](LICENSE).
