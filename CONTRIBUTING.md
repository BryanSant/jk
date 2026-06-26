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

Run `./gradlew :cli:installDist` to produce a runnable JVM distribution under `cli/build/install/`. The end-to-end test suite (`./gradlew build`) downloads artifacts from Maven Central; avoid running it in environments with rate-limited network access.

The native binary is opt-in:

```
./gradlew :cli:nativeCompile
```

`nativeCompile` requires a GraalVM-capable JDK (the pinned `25.0.3-graal` above satisfies this). After compilation, install locally with:

```
./install.sh cli/build/native/nativeCompile/jk
```

## Project layout

See [`docs/implementation-plan.md`](docs/implementation-plan.md) for the module map and dependency tier ordering, and [`docs/requirements.md`](docs/requirements.md) for the product spec.
