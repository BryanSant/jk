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
java=25.0.3-tem
gradle=9.5.1
```

With SDKMAN: `sdk env install && sdk env`. Without SDKMAN: Gradle will toolchain-provision the JDK via the foojay resolver convention on first use.

## Building

```
gradle clean build
```

The native binary is opt-in (not part of `build`):

```
gradle :cli:nativeCompile
```

`nativeCompile` requires a GraalVM-capable JDK; Gradle's toolchain auto-provisions one when needed.

## Project layout

See [`docs/implementation-plan.md`](docs/implementation-plan.md) for the module map and dependency tier ordering, and [`docs/requirements.md`](docs/requirements.md) for the product spec.
