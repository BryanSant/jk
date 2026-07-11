# Spring Boot on jk — migration guide & parity table

**Status:** current as of 2026-07-11. Verified against Spring Boot **4.0.x and 4.1.x**.
Companion design: [spring-boot-plan.md](./spring-boot-plan.md).

jk replaces the Spring Boot Gradle plugin (and Gradle itself) for Boot applications.
There are no Boot-specific verbs to learn: declaring `[spring-boot]` in `jk.toml` makes
the standard verbs (`jk build / run / test / dev / native / image / install`) behave the
way the Gradle plugin makes Gradle behave — reactive, by presence.

## Quick start

New project:

```
jk new --spring --name shop --group com.example          # Java
jk new --spring --lang kotlin --name shop --group com.example   # Kotlin
```

Migrating an existing Gradle build:

```
cd my-boot-app && jk import     # translates build.gradle(.kts) → jk.toml + a report
jk build                        # lock, compile, test, boot jar
```

## The manifest

```toml
[project]
group = "com.example"; name = "shop"; version = "1.0.0"; jdk = 25; java = 25

[spring-boot]
version = "4.1.0"           # the ONE version you declare — imports the BOM,
                            # pins loader/AOT/jarmode tooling to the same line
# aot = true                # AOT processing (auto-true when [native] is present)
# aot-args = ["--spring.profiles.active=prod"]   # bake profiles at build time
# build-info = true         # emit META-INF/build-info.properties (/actuator/info)
# include-tools = false     # drop the jarmode-tools jar from the boot jar

[application]               # main optional — jk scans for the unique main class

[dependencies]              # versionless: the BOM pins them
starter-webmvc = { group = "org.springframework.boot", name = "spring-boot-starter-webmvc" }

[dev-dependencies]          # jk run / jk dev only — never packaged, by construction
devtools = { group = "org.springframework.boot", name = "spring-boot-devtools" }
```

Version overrides: an exact pin (`version = "=42.7.4"`) on a direct dependency beats the
BOM's managed version (Maven/Gradle semantics); floating selectors keep the BOM's pin.

## Parity table

| Boot Gradle plugin | jk | Notes |
|---|---|---|
| `id("org.springframework.boot") version "X"` | `[spring-boot] version = "X"` | `jk import` translates this automatically |
| `io.spring.dependency-management` / `platform(...)` | automatic BOM import | versionless deps become platform-managed |
| versionless starters | same | `{ group = "...", name = "..." }`, no version |
| `developmentOnly` | `[dev-dependencies]` | on `jk run`/`jk dev`, never in artifacts/POMs |
| `testAndDevelopmentOnly` | `[test-dev-dependencies]` | dev + test classpaths only |
| `bootJar` | `jk build` | main jar IS the executable boot jar (loader, `layers.idx`, `classpath.idx`, STORED nested libs) |
| `bootJar` layers | same four default layers | `-Djarmode=tools list-layers` / `extract --layers` work |
| jarmode tools bundling | on by default | `include-tools = false` to drop |
| `springBoot { buildInfo() }` | `build-info = true` | no `build.time` (reproducible jars); Boot handles absence |
| CycloneDX SBOM (extra plugin) | always on | generated from `jk.lock` (exact coords + SHA-256), zero config; `Sbom-*` manifest headers |
| `bootRun` | `jk run` | classes dir + RUN classpath, dev deps ride |
| `bootRun` + devtools reload | `jk dev` | watch → engine incremental recompile → DevTools context restart; devtools auto-injected if undeclared |
| `mainClass` convention | main-class scan | `[application] main` optional when exactly one `public static void main` exists |
| `-parameters` (plugin default) | same | javac `-parameters` / kotlinc `-java-parameters` default on Boot projects |
| `kotlin("plugin.spring")` (all-open) | automatic | jk wires the all-open compiler plugin (`spring` preset) for `[spring-boot]` Kotlin projects |
| `processAot` / `compileAot` | `aot = true` | forks `SpringApplicationAotProcessor`, compiles generated sources, embeds classes + hints; action-cached |
| AOT runtime | `java -Dspring.aot.enabled=true -jar app.jar` | same flag as everywhere |
| `org.graalvm.buildtools.native` + Boot | `[native]` + `jk native` | AOT auto-enabled; exploded classes + generated hints on the image classpath; Start-Class scan |
| reachability-metadata repository | automatic on `jk native` | lock artifacts matched against the GraalVM metadata repo; verified: postgresql driver does real JDBC inside a native image |
| `kotlin("plugin.jpa")` (no-arg) | automatic | wired when `jakarta.persistence-api` is on a Boot Kotlin project's classpath |
| `bootBuildImage` (buildpacks) | `jk image` | daemonless layered jib: release deps / snapshot deps / exploded classes as separate OCI layers; entrypoint `java -cp /app/classes:/app/libs/*` |
| CDS/AOT-cache Dockerfile recipe | `jk build --aot-cache` | one flag: jarmode-extract layout + trained JEP 514 cache (`-Dspring.context.exit=onRefresh` training); AppCDS fallback on JDK < 25. Measured ~1.5× startup |
| `bootWar` | not supported | war deployment is out of scope (plan §risks) |
| `processTestAot` / `nativeTest` | not yet | deferred with Gradle-world guidance ("reserve for native-specific behavior") |

## Measured on a Boot 4.1 webmvc app

| Mode | Startup |
|---|---|
| `java -jar` (boot jar) | ~0.9 s |
| `java -XX:AOTCache=app.aot -jar` (`jk build --aot-cache`) | ~0.6 s |
| `java -Dspring.aot.enabled=true -jar` (aot = true) | ~0.64 s |
| native binary (`jk native`) | **0.022 s** |

## What `jk import` translates

- Boot plugin version → `[spring-boot] version` (applied-without-version prompts you to
  fill it in); `io.spring.dependency-management` is subsumed by the BOM auto-import.
- Versionless `g:a` coordinates → platform-managed deps.
- `developmentOnly` / `testAndDevelopmentOnly` → the dev / test-dev scopes.
- Everything else follows the general Gradle importer (repositories, manifest
  attributes, version catalogs); unmapped constructs land in the import report.

## Known gaps

- **`bootWar`**: out of scope (Servlet-container deploys are a shrinking niche; revisit
  on demand).
- **Test AOT / `nativeTest`**: deliberately deferred. `processTestAot` exists to feed
  `nativeTest` — running the test suite as a native image — which needs a JUnit-platform
  native launcher, a test-classpath image build, and a new jk verb; a standalone feature,
  not a Boot-support gap. On the JVM, test AOT adds little (Spring's test framework
  caches contexts), and Gradle's own guidance reserves it for native-specific behavior.
  When demand appears: mirror `SpringAotRunner` with `SpringBootTestAotProcessor` over
  the test classes, then build the native test image from test classpath + AOT output.
