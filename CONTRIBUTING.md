# Contributing to jk

## Source headers

Every source file in this repository starts with an SPDX header line:

```
// SPDX-License-Identifier: Apache-2.0
```

Use the appropriate comment syntax for the file type (`//` for Java/Kotlin/Gradle Kotlin DSL, `#` for shell/TOML/YAML, `<!--` for HTML/XML, etc.).

## Toolchain

The bootstrap build pins JDK and Gradle versions in `.sdkmanrc`:

```
java=25.0.3-graal
gradle=9.5.1
```

With SDKMAN: `sdk env install && sdk env`. Without SDKMAN: Gradle will toolchain-provision the JDK via the foojay resolver convention on first use.

## Building

```
./gradlew classes
```

Run `./gradlew :cli-engine:installDist` to produce a runnable JVM distribution under `cli-engine/build/install/`. The end-to-end test suite (`./gradlew build`) downloads artifacts from Maven Central; avoid running it in environments with rate-limited network access.

The native client binary is opt-in:

```
./gradlew dist
```

(`dist` assembles `build/dist/`: the slim native `jk` client from `:cli:nativeCompile` next to `lib/jk-engine-<version>.jar`, the engine's fat jar from `:cli-engine:shadowJar` — the engine is a plain JVM app the client spawns from `~/.jk/lib/` on the jk-managed JDK, never a native image.) `nativeCompile` requires a GraalVM-capable JDK (the pinned `25.0.3-graal` above satisfies this). After compilation, install locally with:

```
./install.sh build/dist/jk
```

### One build at a time per checkout

Builds in the same checkout are serialized by an OS file lock taken in
`settings.gradle.kts` (`.gradle/cross-daemon-build.lock`). A second `./gradlew`
invocation — another terminal, another agent session — prints
"Another Gradle build is running in this checkout…" and waits instead of
running concurrently. This is deliberate: Gradle does not protect task output
directories across daemons, and concurrent runs corrupted
`:cli-engine:test`'s binary results (`java.io.EOFException` at the end of a
green run). For truly parallel work, use a separate worktree.

## Project layout

See [`docs/implementation-plan.md`](docs/implementation-plan.md) for the module map and dependency tier ordering, and [`docs/requirements.md`](docs/requirements.md) for the product spec.
