# Spring Boot 4 + Gradle: Ecosystem Analysis

> **Purpose.** A deep reference on how Spring Boot 4.x integrates with Gradle — every
> plugin, task, DSL, dependency-management mechanism, and packaging/runtime pattern that
> shapes the developer experience. Scoped strictly to **Spring Boot 4.0+**; older
> versions are mentioned only to mark a *delta*. Compiled from the official Spring Boot
> 4.x Gradle-plugin reference, the SB4 release notes & migration guide, the GraalVM
> Native Build Tools docs, and the Paketo buildpack sources (URLs in each section).

## Table of contents

1. [Release facts & baselines](#1-release-facts--baselines)
2. [The Spring Boot Gradle plugin (`org.springframework.boot`)](#2-the-spring-boot-gradle-plugin-orgspringframeworkboot)
3. [Tasks registered by the plugin](#3-tasks-registered-by-the-plugin)
4. [Dependency management: two approaches](#4-dependency-management-two-approaches)
5. [Module restructuring & starter renames](#5-module-restructuring--starter-renames)
6. [Executable jars & layered packaging](#6-executable-jars--layered-packaging)
7. [OCI image building (`bootBuildImage` + buildpacks)](#7-oci-image-building-bootbuildimage--buildpacks)
8. [GraalVM native image & Spring AOT](#8-graalvm-native-image--spring-aot)
9. [CDS / AOT cache / CRaC — the efficiency ladder](#9-cds--aot-cache--crac--the-efficiency-ladder)
10. [Testing & local-development experience](#10-testing--local-development-experience)
11. [Version alignment (the BOM)](#11-version-alignment-the-bom)
12. [Open questions / things to verify](#12-open-questions--things-to-verify)
13. [Source index](#13-source-index)

---

## 1. Release facts & baselines

- **Spring Boot 4.0.0 GA: 20 November 2025.** Built on **Spring Framework 7.0** (GA 13 Nov 2025). Maintenance line 4.0.x; 4.1.x followed. GA and *all* milestones/RCs are published to **Maven Central** (new in 4.0 — pre-release testing no longer needs `repo.spring.io`).
- **Java: minimum 17, first-class 25.** The build/runtime baseline is **Java 17** (not 21, not 25 — several third-party blogs get this wrong). Tested/compatible through JDK 26. LTS 25 is the recommended target. GraalVM native and the AOT cache raise this floor (see §8/§9).
- **Gradle: 8.14+ (on the 8.x line) or any 9.x.** Gradle 9 support is new in 4.0. Configuration cache is supported.
- **Kotlin: 2.2+** (managed toolchain ships 2.2.20, Kotlin Serialization 1.9).
- **Jakarta EE 11 baseline** — Servlet 6.1, Persistence (JPA) 3.2, (Bean) Validation 3.1, Annotation 3.0, JAX-RS 4.0. This is the most consequential platform bump: it forces Tomcat 11 / Jetty 12.1 / Hibernate 7 and **drops Undertow** (incompatible with Servlet 6.1).
- **`io.spring.dependency-management` plugin: 1.1.7** — still current, **not** deprecated or dropped.

**Docs live at** `docs.spring.io/spring-boot/` (Antora site, current tree is 4.1.x), with
the **release notes** and **migration guide** on the GitHub wiki
(`github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes`,
`.../Spring-Boot-4.0-Migration-Guide`). The Gradle-plugin reference is at
`docs.spring.io/spring-boot/gradle-plugin/`. Everything below reflects the current 4.x
line (4.1.x) as the state of the art.

---

## 2. The Spring Boot Gradle plugin (`org.springframework.boot`)

```kotlin
// build.gradle.kts
plugins {
    java
    id("org.springframework.boot") version "4.0.0"
}
```
```groovy
// build.gradle
plugins {
    id 'java'
    id 'org.springframework.boot' version '4.0.0'
}
```

**Applied alone the plugin does almost nothing — it is purely reactive.** It watches for
other plugins and configures itself accordingly. Crucially, **it does NOT apply
dependency management on its own** (see §4).

### What it reacts to

| Detected plugin | What the Spring Boot plugin does |
|---|---|
| `java` (also via `java-library`) | Registers `bootJar`, `bootRun`, `bootTestRun`, `bootBuildImage`; makes `assemble` depend on `bootJar`; sets the plain `jar` task's `archiveClassifier` convention to `plain`; creates configurations `bootArchives`, `developmentOnly`, `testAndDevelopmentOnly`, `productionRuntimeClasspath`; configures all `JavaCompile` for UTF-8 + `-parameters`. |
| `war` | Registers `bootWar`; `assemble` depends on it; plain `war` gets `plain` classifier; `providedRuntime` deps go to `WEB-INF/lib-provided`. |
| `application` | Registers `bootStartScripts`; creates a `boot` distribution; wires `application { mainClass }` / `applicationDefaultJvmArgs` into `bootRun`/`bootTestRun`/`bootJar`/`bootWar`. |
| `org.jetbrains.kotlin.jvm` | Aligns the `kotlin.version` managed property to the applied Kotlin plugin; adds `-java-parameters` to `KotlinCompile`. |
| `io.spring.dependency-management` | Auto-imports the `spring-boot-dependencies` BOM. |
| `org.graalvm.buildtools.native` | Applies `org.springframework.boot.aot` (→ `processAot`/`processTestAot`, `aot`/`aotTest` source sets); wires AOT output onto the GraalVM binaries; makes `bootBuildImage` produce a **native** image; adds reachability metadata + `Spring-Boot-Native-Processed: true` to `bootJar`. |
| CycloneDX plugin | Configures `cyclonedxBom` (JSON SBOM → `META-INF/sbom/application.cdx.json`, manifest `Sbom-Format`/`Sbom-Location`). **4.0: min CycloneDX plugin 3.0.0.** |
| Protobuf plugin | Aligns `protoc`/gRPC codegen artifact versions to the runtime classpath. |

### `springBoot { }` extension

```kotlin
springBoot {
    mainClass.set("com.example.ExampleApplication")
    buildInfo()   // enables the bootBuildInfo task (§3)
}
```

---

## 3. Tasks registered by the plugin

| Task | Type | Trigger | Purpose |
|---|---|---|---|
| `bootJar` | `BootJar` (extends `Jar`) | `java` | Executable uber-jar; classes → `BOOT-INF/classes`, deps → `BOOT-INF/lib` |
| `bootWar` | `BootWar` (extends `War`) | `war` | Executable war; deps → `WEB-INF/lib`, provided → `WEB-INF/lib-provided` |
| `bootRun` | `BootRun` (extends `JavaExec`) | `java` | Runs the app from the `main` source set without building an archive |
| `bootTestRun` | `BootRun` | `java` | Runs a `main` class from the **test** source set on the test classpath |
| `bootBuildImage` | `BootBuildImage` | `java`/`war` | Builds an OCI image via Cloud Native Buildpacks |
| `bootBuildInfo` | `BuildInfo` | `springBoot { buildInfo() }` | Generates `META-INF/build-info.properties` |
| `resolveMainClass` | `ResolveMainClassName` | `java` | Resolves the main class from main source-set output; feeds `bootJar`/`bootRun` |
| `resolveTestMainClass` | `ResolveMainClassName` | `java` | Resolves main class from test (then main) output; feeds `bootTestRun` |
| `processAot` | `ProcessAot` (`JavaExec`) | `org.springframework.boot.aot` | AOT-processes the app into the `aot` source set |
| `processTestAot` | `ProcessTestAot` | `org.springframework.boot.aot` | AOT-processes tests into the `aotTest` source set |
| `bootStartScripts` | `CreateStartScripts` | `application` | Start scripts for the `boot` distribution |

### `bootJar` / `bootWar`

Inherit all `Jar`/`War` options, plus: `mainClass`, `requiresUnpack(patterns…)`,
`layered { }`, `includeTools` (boolean), `classpath(...)`, manifest customization
(`Start-Class`, `Main-Class`). By default `bootJar` produces the executable archive with
**no classifier** and pushes the plain `jar` to classifier `plain`.

```kotlin
tasks.named<BootJar>("bootJar") {
    mainClass.set("com.example.ExampleApplication")
    requiresUnpack("**/jruby-complete-*.jar")
}
tasks.named<Jar>("jar") { enabled = false }   // skip the plain jar entirely
```

> **4.0 removal:** embedded **launch scripts** ("fully executable" jars — the
> `launchScript { }` feature) are **removed**; run with `java -jar`. The **classic
> uber-jar loader** is also gone — delete any `loaderImplementation = …CLASSIC`.

### `bootRun` / `bootTestRun`

Both are `BootRun` (⊂ `JavaExec`), so accept `mainClass`, `args`, `jvmArgs`,
`systemProperty`, `environment`, plus the `BootRun`-specific `optimizedLaunch`
(default `true`; disable to profile production-like performance) and
`sourceResources(sourceSets.main)` for live static-resource reloading.

```bash
./gradlew bootRun --args='--spring.profiles.active=dev --server.port=8081'
```

`bootTestRun` differs from `bootRun` only in that it finds its `main` in the **test**
source set and runs on the **test runtime classpath**. It is *not* a test runner — it
launches your app with test-only deps (Testcontainers, `@TestConfiguration` beans)
available. This powers the development-time Testcontainers flow (§10).

### `bootBuildInfo`

```kotlin
springBoot {
    buildInfo {
        properties {
            artifact.set("example-app"); version.set("1.2.3")
            group.set("com.example"); name.set("Example application")
            additional.set(mapOf("commit" to gitSha))
        }
        excludes.set(setOf("time"))   // build.time otherwise defeats up-to-date checks
    }
}
```
Output `build/bootBuildInfo/META-INF/build-info.properties`, auto-added to main
resources; feeds Actuator's `info` endpoint via `BuildProperties`.

### Main-class resolution

`resolveMainClass` scans the main source-set output for
`public static void main(String[])`. Precedence: task `mainClass` → `springBoot { mainClass }`
→ `application { mainClass }` → manifest `Start-Class`. Kotlin uses the `…Kt` class name.

---

## 4. Dependency management: two approaches

Spring Boot never version-manages implicitly. You choose **one** of two mechanisms; the
choice is unchanged in 4.0.

### (a) `io.spring.dependency-management` plugin (Maven-style BOM import)

```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.0.0"
    id("io.spring.dependency-management") version "1.1.7"
}
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}
```

- When present, the Boot plugin auto-imports the `spring-boot-dependencies` BOM into it.
- **Unique benefit: property-based version overrides** — `ext['slf4j.version'] = '2.0.16'`
  (Kotlin: `extra["slf4j.version"] = "2.0.16"`).
- **`start.spring.io` still generates this by default** for SB4 Gradle projects, so it
  remains the out-of-the-box path. It is **not applied automatically** by the Boot plugin
  and is **not deprecated** in 4.0.

### (b) Gradle-native `platform()` (recommended for speed)

```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.0.0"
    // NO io.spring.dependency-management
}
dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}
```

- Uses Gradle's native constraint engine → **"likely faster builds"** (official docs).
- `BOM_COORDINATES` resolves to `org.springframework.boot:spring-boot-dependencies:<applied version>` — never hardcode it.
- Use `enforcedPlatform(...)` to make BOM versions hard requirements that override transitive requests.
- **No property overrides.** Override via a Gradle constraint or `resolutionStrategy`:
  ```kotlin
  dependencies { constraints { implementation("org.slf4j:slf4j-api:2.0.16") } }
  // or
  configurations.all {
      resolutionStrategy.eachDependency { if (requested.group == "org.slf4j") useVersion("2.0.16") }
  }
  ```

### Version catalogs

**Spring does not publish an official Gradle version catalog** (tracking issue
spring-projects/spring-boot#29588 is still open, `4.x`, pending design). You can use your
own `gradle/libs.versions.toml` — put plugin versions and *non-managed* libs there, and
keep Boot-managed deps **versionless** so the BOM controls them (a catalog entry with a
version acts as a direct pin and won't be overridden by the BOM).

### Snapshots/milestones

Now that 4.0/4.1 are GA, **`mavenCentral()` alone suffices**. Only for consuming
pre-release 4.x do you add `https://repo.spring.io/milestone` (in `pluginManagement` for
the plugin, in `repositories` for artifacts) and `https://repo.spring.io/snapshot`
(`mavenContent { snapshotsOnly() }`).

---

## 5. Module restructuring & starter renames

**The headline change of 4.0.** The monolithic `spring-boot-autoconfigure` jar (~2 MiB
by 3.5) was split into **70+ focused modules**, each under its own package
`org.springframework.boot.<module>` bundling that feature's API + auto-config + actuator
support. Naming convention:

| Kind | Pattern |
|---|---|
| Library | `spring-boot-<technology>` |
| Root package | `org.springframework.boot.<technology>` |
| Starter | `spring-boot-starter-<technology>` |
| Test module/starter | `spring-boot-<technology>-test` / `spring-boot-starter-<technology>-test` |

### What a Gradle build must change

**1. Starter renames** (group stays `org.springframework.boot`; old names remain as
**deprecated aliases** that still resolve with managed versions, slated for removal):

| Old | New |
|---|---|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| `spring-boot-starter-web-services` | `spring-boot-starter-webservices` |
| `spring-boot-starter-aop` | `spring-boot-starter-aspectj` |
| `spring-boot-starter-oauth2-client` | `spring-boot-starter-security-oauth2-client` |
| `spring-boot-starter-oauth2-resource-server` | `spring-boot-starter-security-oauth2-resource-server` |
| `spring-boot-starter-oauth2-authorization-server` | `spring-boot-starter-security-oauth2-authorization-server` |

`spring-boot-starter-webflux` is unchanged.

**2. Features formerly auto-configured off a raw dependency now need an explicit
starter** — a real build-breaker, since the library stays on the classpath but its wiring
moved to a module you no longer pull in (corroborated by strong secondary sources; verify
per feature):

- Flyway: `flyway-core` → `spring-boot-starter-flyway`
- Liquibase: `liquibase-core` → `spring-boot-starter-liquibase`
- H2 console: `h2` → `spring-boot-starter-h2-console`
- REST client (was implicit in web) → `spring-boot-starter-restclient`
- WebClient (was implicit in WebFlux) → `spring-boot-starter-webclient`

**3. Per-feature test starters** — e.g. `spring-boot-starter-webmvc-test`,
`spring-boot-starter-security-test`, `spring-boot-starter-restclient-test`. Slice tests
silently fail to wire beans if the matching test starter is absent.

**4. Package/import relocations** out of the flat `org.springframework.boot.autoconfigure.*`
namespace, e.g.
`o.s.b.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer` →
`o.s.b.jackson.autoconfigure.JsonMapperBuilderCustomizer`. OpenRewrite/Moderne recipes
automate both the import moves and the starter renames.

### New starters in 4.0

- `spring-boot-starter-opentelemetry` — OTLP metric/trace export with an auto-configured OTel SDK.
- `spring-boot-starter-kotlin-serialization` — provides a `Json` bean.

### Migration escape hatches (transitional)

- `spring-boot-starter-classic` (replaces `spring-boot-starter`)
- `spring-boot-starter-test-classic` (replaces `spring-boot-starter-test`)
- `spring-boot-autoconfigure-classic` (raw autoconfigure aggregate)

These restore a "fat" classpath so you can migrate imports incrementally; they pull in the
auto-config classes but exclude the newly-split transitive third-party deps.

### Other notable removals

- **Undertow** dropped entirely (Servlet 6.1 incompatibility).
- **JUnit 4** support removed; `SpringRunner` deprecated (use `SpringExtension`); Vintage engine deprecated; **JUnit 6** baseline.
- **Spring Retry** dependency management removed (`@Retryable`/`@ConcurrencyLimit` moved into Spring Framework 7 core) — pin `spring-retry` yourself if still used.
- **Spring Authorization Server** version now governed by `spring-security.version`.
- **Jackson 3 is the default** — group ID moves `com.fasterxml.jackson` → **`tools.jackson`** (exception: `jackson-annotations` stays `com.fasterxml.jackson.core`). A `spring-boot-jackson2` shim keeps Jackson 2 during migration.
- `spring-boot-properties-migrator` (runtimeOnly) still available to ease property renames.

---

## 6. Executable jars & layered packaging

**Structure:** `bootJar` → `BOOT-INF/classes` + `BOOT-INF/lib`; `bootWar` →
`WEB-INF/classes` + `WEB-INF/lib` (+ `WEB-INF/lib-provided`). Loader classes are
`org.springframework.boot.loader.launch.*` (`JarLauncher`, `PropertiesLauncher`).

**Layering is on by default.** Default layers, in write order (least- to most-frequently
changing, for cache efficiency):

1. `dependencies` — non-project deps without `SNAPSHOT`
2. `spring-boot-loader` — loader classes (`org/springframework/boot/loader/**`)
3. `snapshot-dependencies` — non-project `SNAPSHOT` deps
4. `application` — project classes/resources + project deps

```kotlin
tasks.named<BootJar>("bootJar") {
    layered {
        application {
            intoLayer("spring-boot-loader") { include("org/springframework/boot/loader/**") }
            intoLayer("application")
        }
        dependencies {
            intoLayer("application") { includeProjectDependencies() }
            intoLayer("snapshot-dependencies") { include("*:*:*SNAPSHOT") }
            intoLayer("dependencies")
        }
        layerOrder.set(listOf("dependencies", "spring-boot-loader", "snapshot-dependencies", "application"))
    }
    // layered { enabled.set(false) }  // disable layering
    // includeTools.set(false)         // omit the jarmode-tools jar
}
```

### jarmode tooling — `tools` replaces `layertools`

With layering on, the archive bundles **`spring-boot-jarmode-tools`**
(the property to exclude it is `includeTools`). The old
**`spring-boot-jarmode-layertools`** / `-Djarmode=layertools` / `includeLayerTools` are
obsolete in 4.x (superseded in the 3.3 line, being removed entirely).

```bash
java -Djarmode=tools -jar app.jar list-layers
java -Djarmode=tools -jar app.jar extract --layers --destination extracted
```

**4.0 packaging additions:** `Spring-Boot-Jar-Type: development-tool` manifest value lets
you *exclude* a dependency from uber jars; **optional dependencies are excluded from uber
jars by default** (were previously included).

### Multi-stage Dockerfile (jarmode extract)

```dockerfile
FROM bellsoft/liberica-openjre-debian:25-cds AS builder
WORKDIR /builder
COPY target/*.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM bellsoft/liberica-openjre-debian:25-cds
WORKDIR /application
COPY --from=builder /builder/extracted/dependencies/ ./
COPY --from=builder /builder/extracted/spring-boot-loader/ ./
COPY --from=builder /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder /builder/extracted/application/ ./
ENTRYPOINT ["java", "-jar", "application.jar"]
```

---

## 7. OCI image building (`bootBuildImage` + buildpacks)

`bootBuildImage` (type `BootBuildImage`) builds an OCI image from the layered jar using
**Cloud Native Buildpacks** — no Dockerfile — talking to a local Docker/podman daemon.

### Defaults (4.0)

- **Builder:** `paketobuildpacks/builder-noble-java-tiny:latest` (Ubuntu 24.04 "Noble",
  near-distroless, **no shell**). Moved off the 3.x `jammy` defaults. If you need a shell,
  set `runImage` to `paketobuildpacks/ubuntu-noble-run:latest`.
- **Image name:** `docker.io/library/${project.name}:${project.version}`.
- **JDK:** buildpack installs BellSoft Liberica matched to `targetCompatibility` /
  `BP_JVM_VERSION`. Images run as **non-root**. `pullPolicy = ALWAYS`.

### Key DSL

```kotlin
tasks.named<BootBuildImage>("bootBuildImage") {
    builder.set("paketobuildpacks/builder-noble-java-tiny:latest")
    imageName.set("docker.example.com/library/${project.name}:${project.version}")
    tags.set(listOf("docker.example.com/library/${project.name}:latest"))
    imagePlatform.set("linux/amd64")
    publish.set(true)
    environment.putAll(mapOf(
        "BP_JVM_VERSION"          to "25",
        "BP_JVM_AOTCACHE_ENABLED" to "true",
        "BPE_APPEND_JAVA_TOOL_OPTIONS" to "-XX:+UseCompactObjectHeaders",
    ))
    docker {
        // host.set("unix:///run/user/1000/podman/podman.sock"); bindHostToBuilder.set(true)
        publishRegistry {
            username.set("ci-user"); password.set("ci-secret")
            url.set("https://docker.example.com/v1/")
        }
        // builderRegistry { ... }  // separate creds for pulling a private builder
    }
}
```

Full property set includes: `builder`, `runImage`, `imageName`, `tags`, `buildpacks`,
`bindings`, `environment`, `network`, `cleanCache`, `verboseLogging`, `publish`,
`imagePlatform`, `createdDate` (`"now"` or ISO-8601; default is a fixed epoch for
reproducibility), `applicationDirectory`, `pullPolicy`, `securityOptions`, `trustBuilder`
(default `true` for Paketo/GCR/Heroku), and `buildCache`/`launchCache`/`buildWorkspace`
volume/bind config. `docker { }` exposes `host`, `context`, `tlsVerify`, `certPath`,
`bindHostToBuilder`, and the nested `builderRegistry`/`publishRegistry` blocks (each:
`username`, `password`, `url`, `email`, `token`). Absent creds resolve from
`~/.docker/config.json` (credential helpers → store → static `auths`).

CLI overrides: `gradle bootBuildImage --imageName=… --publishImage --environment BP_JVM_VERSION=25`.

### Buildpack mechanics

CNB `lifecycle` phases — **detect** (each buildpack votes) → **analyze** → **restore**
(rehydrate cached layers) → **build** (contribute JRE/JDK, jar, Spring slices) →
**export** (assemble final image from run-image + content-addressed layers, reusing
unchanged layers by digest). The Paketo `spring-boot` buildpack reads the
`Spring-Boot-Layers-Index` and maps each Boot layer to a separate image layer, so
dependencies/loader/snapshot/application cache independently. It also auto-installs
`spring-cloud-bindings` unless disabled.

---

## 8. GraalVM native image & Spring AOT

### Plugin wiring

```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.0.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.graalvm.buildtools.native")   // version managed by the Boot BOM (NBT 1.1.1)
}
```

Once `org.graalvm.buildtools.native` is applied, the Boot plugin **auto-wires the Spring
AOT tasks** and `bootBuildImage` switches to producing a **native** image. You just run
`nativeCompile`.

> **4.0 baseline shift:** GraalVM **25+ is now required** (was 22.3+ in 3.x); Native Build
> Tools jumped to **1.x (1.1.1)**. GraalVM 25 introduces the unified **exact reachability
> metadata** format that Spring Framework 7 adopts.

### NBT tasks

| Task | Purpose |
|---|---|
| `nativeCompile` | Build the native executable → `build/native/nativeCompile/` |
| `nativeRun` | Run it |
| `nativeTestCompile` / `nativeTest` | Build & run tests as a native image |
| `metadataCopy` | Copy tracing-agent metadata into the project |
| `collectReachabilityMetadata` | Download reachability metadata into the build dir |
| `listLibrariesMissingMetadata` | Report deps lacking metadata |

```kotlin
graalvmNative {
    metadataRepository { enabled.set(true) }   // GraalVM Reachability Metadata Repository
    binaries {
        named("main") {
            imageName.set("application")
            buildArgs.add("--link-at-build-time")
            jvmArgs.add("-Xmx8g")
        }
    }
}
```

### Spring AOT (`processAot` / `processTestAot`)

AOT runs the context up to *bean-definition* knowledge (no bean instantiation), converts
`@Configuration`/`@Bean` into explicit `RootBeanDefinition` + `BeanInstanceSupplier`
source, and emits an `ApplicationContextInitializer` used at runtime instead of
scanning/reflection. Outputs (Gradle):

| Artifact | Location |
|---|---|
| Generated Java source (`*__BeanDefinitions`, initializer) | `build/generated/aotSources` |
| Generated resources (GraalVM hint JSON) | `build/generated/aotResources` |
| Generated bytecode (build-time CGLIB proxies) | `build/generated/aotClasses` |

AOT can also run **on the JVM** (no native compile) — package with it and activate via
`-Dspring.aot.enabled=true`; useful for validating AOT cheaply. Closed-world rules apply:
fixed classpath, no runtime bean registration, and `@Profile` / `@ConditionalOnProperty`
are evaluated **at build time**, so the profiles active during `processAot` are baked in:

```kotlin
tasks.withType<org.springframework.boot.gradle.tasks.aot.ProcessAot>().configureEach {
    args("--spring.profiles.active=prod")
}
```

### Runtime hints

`RuntimeHintsRegistrar` + `@ImportRuntimeHints`, `@Reflective`,
`@RegisterReflectionForBinding` (for `RestClient`/`WebClient`/`RestTemplate` bodies).
Under GraalVM 25 the metadata consolidates into a single `reachability-metadata.json`
(`reflection`/`jni`/`resources`), the condition key changed `typeReachable` → `typeReached`,
and resource hints use **glob** patterns instead of regex. Static extra hints go in
`src/main/resources/META-INF/native-image/{group}/{artifact}-additional-hints/`. Hints are
unit-testable via `RuntimeHintsPredicates`. **Spring Data AOT Repositories** (SF7/SB4
line) generate repository query implementations at build time — startup win, native-friendly.

### Two ways to build native

| | `nativeCompile` (local) | `bootBuildImage` + `BP_NATIVE_IMAGE=true` |
|---|---|---|
| Local GraalVM 25 needed | Yes | No (containerized) |
| Output | Executable on host | OCI image |
| Reproducibility | Host-dependent | High (pinned builder) |
| Best for | Dev/CI with GraalVM present | Hermetic CI/CD to registries |

```kotlin
tasks.named<BootBuildImage>("bootBuildImage") {
    environment.put("BP_NATIVE_IMAGE", "true")
    // environment.put("BP_NATIVE_IMAGE_BUILD_ARGUMENTS", "--verbose")
}
```
The buildpack native path must run on **JDK 25** (it pins native-image to the compile JDK).

### Testing native

`processTestAot` → `nativeTestCompile` → `nativeTest` (JUnit Platform inside the native
image), auto-wired when NBT is applied. Guidance: keep most tests on the JVM; reserve
`nativeTest` for behavior likely to differ under native, and validate AOT on the JVM via
`-Dspring.aot.enabled=true`.

---

## 9. CDS / AOT cache / CRaC — the efficiency ladder

SB4's "Efficient Deployments" story. The default extracted jar layout (libs in `lib/`,
app classes + manifest in the jar) is CDS/AOT-cache friendly. All three techniques do a
**training run** at build time and consume the artifact at launch.

### AOT Cache (JEP 483) — preferred on Java 25+

```bash
java -Djarmode=tools -jar app.jar extract --destination application
cd application
java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar app.jar   # train
java -XX:AOTCache=app.aot -jar app.jar                                          # use
```

### Classic CDS/AppCDS — Java 24 and earlier

```bash
java -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh -jar app.jar  # train
java -XX:SharedArchiveFile=app.jsa -jar app.jar                                      # use
```

`-Dspring.context.exit=onRefresh` initializes the context then exits cleanly — the natural
end of a training run. Both archives must run against the **extracted** app, not the fat jar.

### In buildpacks

| Var | Scope | Default | Notes |
|---|---|---|---|
| `BP_JVM_AOTCACHE_ENABLED` | build | `false` | **Current** perf toggle: training run → cache → used at launch |
| `BPL_JVM_AOTCACHE_ENABLED` | runtime | inherits build value | runtime toggle |
| `BP_JVM_CDS_ENABLED` | build | `false` | **Deprecated** — use `BP_JVM_AOTCACHE_ENABLED` |
| `TRAINING_RUN_JAVA_TOOL_OPTIONS` | build | — | JVM opts for the training run only |
| `CDS_TRAINING_JAVA_TOOL_OPTIONS` | build | — | Deprecated alias |
| `BP_SPRING_AOT_ENABLED` | build | `false` | Spring AOT (distinct from JVM AOT cache) |
| `BP_NATIVE_IMAGE` | build | `false` | Switch to GraalVM native buildpack (mutually exclusive) |

The buildpack picks `-XX:AOTCache*` on Java 25+, CDS flags below 25. Known bug:
SB 4.0.1 + Java 25 + `BP_JVM_CDS_ENABLED=true` can fail with "unable to contribute
spring-performance layer" (paketo-buildpacks/spring-boot#581) — another reason to use
`BP_JVM_AOTCACHE_ENABLED`. Typical gain: ~1.5× faster startup, ~16% lower memory.

```kotlin
tasks.named<BootBuildImage>("bootBuildImage") {
    environment.putAll(mapOf("BP_JVM_VERSION" to "25", "BP_JVM_AOTCACHE_ENABLED" to "true"))
}
```

### Project CRaC & Project Leyden

SB4 has built-in **CRaC** (checkpoint/restore) support on a CRaC-enabled JDK
(Liberica/Zulu CRaC): on-demand checkpoint or automatic checkpoint-on-refresh, with Boot
managing sockets/files/thread pools. Paketo has no CRaC support. **Project Leyden** is not
a distinct SB4 feature — its first deliverable *is* the AOT Cache (JEP 483) above; Spring's
CDS/AOT-cache work is the Leyden-anticipation path.

---

## 10. Testing & local-development experience

### Development-time services

Spring's "dev services" = **Docker Compose support** + **development-time Testcontainers**
(no third abstraction like Quarkus Dev Services). Both wire real backing services via
`@ServiceConnection`.

**Docker Compose:**

```kotlin
dependencies { developmentOnly("org.springframework.boot:spring-boot-docker-compose") }
```
On startup the app finds `compose.yaml` (or `.yml`/`docker-compose.*`), runs `docker
compose up`, creates a `ConnectionDetails` bean per recognized image, waits for readiness,
and runs `docker compose stop` on shutdown. Config under `spring.docker.compose.*`
(`lifecycle-management`, `start.command`, `stop.command`, `profiles.active`,
`readiness.timeout`, `skip.in-tests` — **default `true`**, so Compose is skipped under
`@SpringBootTest`). Custom images opt in via the `org.springframework.boot.service-connection`
label.

**Development-time Testcontainers** — the canonical SB4 local-dev flow:

```kotlin
dependencies {
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}
```
```java
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean @ServiceConnection
    PostgreSQLContainer<?> postgres() { return new PostgreSQLContainer<>("postgres:16"); }
}

// src/test/java — launched by `./gradlew bootTestRun`
public class TestMyApplication {
    public static void main(String[] args) {
        SpringApplication.from(MyApplication::main)
            .with(TestcontainersConfiguration.class)
            .run(args);
    }
}
```

`@ServiceConnection` on a container `@Bean` (or `@Container` field) auto-creates the
matching `ConnectionDetails` (`JdbcConnectionDetails`, etc.), so no `spring.datasource.url`
is needed. Containers-as-`@Bean`s is the **recommended** style (Spring manages lifecycle
and context-cache interaction); `@Testcontainers`/`@Container` can tear containers down
before context beans are destroyed. `@ServiceConnection(name="redis")` for
`GenericContainer`; `DynamicPropertyRegistrar` beans for services lacking
`@ServiceConnection`. `@SpringBootTest` reuses the same container config, so dev-run and
test-run share one definition. `spring.testcontainers.beans.startup: parallel` tunes
startup order.

> **Testcontainers 2.0** is the SB4 baseline: per-service artifacts renamed to
> `testcontainers-<name>`, packages relocated (e.g.
> `org.testcontainers.postgresql.PostgreSQLContainer`), JUnit 4 support removed.

### DevTools

```kotlin
dependencies { developmentOnly("org.springframework.boot:spring-boot-devtools") }
```
Two-classloader automatic restart (base = jars, restart = your code; recompile triggers
it). **4.0 change: LiveReload is off by default** (was on); re-enable with
`spring.devtools.livereload.enabled=true` — then **deprecated in 4.1**. Use `@RestartScope`
on container `@Bean`s so they survive restarts; for that to work under `bootTestRun`, move
DevTools to `testAndDevelopmentOnly`.

### Plugin-created configurations

- **`developmentOnly`** — dev-time deps (DevTools, Docker Compose); on the `bootRun`/main
  runtime classpath, excluded from artifacts, not transitive.
- **`testAndDevelopmentOnly`** — dev + test deps; reaches the test source set (so
  `bootTestRun` and `@SpringBootTest`) plus dev-run, still excluded from production.
- **`productionRuntimeClasspath`** — `runtimeClasspath` minus dev/test-only deps; what
  packaging tasks consume, so dev-only deps never ship.

### Other 4.0 testing changes

- **`RestTestClient`** (new, SF7) replaces the deprecated `TestRestTemplate`; works over
  MockMvc and a live server (`@AutoConfigureRestTestClient`).
- `@MockitoBean`/`@MockitoSpyBean`/`@TestBean` now support prototype/custom-scoped beans.
- Cached-but-inactive test contexts have their `Lifecycle` beans **paused** (SF7) —
  less cross-context interference in big suites.
- Virtual threads for JDK-`HttpClient`-backed clients under `spring.threads.virtual.enabled=true`.

---

## 11. Version alignment (the BOM)

`org.springframework.boot:spring-boot-dependencies:4.0.0` pins the whole portfolio
major-for-major. Upgrading the Boot plugin transitively drags all of it — including the
Jakarta EE 11 jump that forces Hibernate 7 / Tomcat 11 / Validation 3.1.

| Project | SB4 version | | Project | SB4 version |
|---|---|---|---|---|
| Spring Framework | 7.0 | | Hibernate ORM | 7.1 |
| Spring Security | 7.0 | | Hibernate Validator | 9.0 |
| Spring Data | 2025.1 | | Tomcat | 11.0 |
| Spring Integration | 7.0 | | Jetty | 12.1 |
| Spring Batch | 6.0 | | Jakarta Persistence | 3.2 |
| Spring Session | 4.0 | | Jakarta Validation | 3.1 |
| Spring Kafka | 4.0 | | Jackson | 3.0 (`tools.jackson`) |
| Spring AMQP | 4.0 | | Kotlin | 2.2.20 |
| Spring GraphQL | 2.0 | | Micrometer / Tracing | 1.16 / 1.6 |
| Spring HATEOAS | 3.0 | | Reactor | 2025.0 |
| Spring REST Docs | 4.0 | | OpenTelemetry | 1.54.0 |
| Spring WS | 5.0 | | Testcontainers | 2.0 |
| Spring LDAP | 4.0 | | JUnit | 6.0 |
| Spring for Apache Pulsar | 2.0 | | Mockito | 5.20 |

*(The authoritative pin is always the published `spring-boot-dependencies:4.0.0` POM.)*

---

## 12. Open questions / things to verify

- **Deprecated starter alias longevity** — old names (`-web`, `-aop`, `-oauth2-*`) still
  resolve with managed versions, but the removal timeline is unstated.
- **Exact "raw dependency → explicit starter" list** (Flyway/Liquibase/H2/restclient/
  webclient) — corroborated by strong secondary sources rather than enumerated verbatim in
  the release notes; confirm per feature against the current BOM.
- **Buildpack CRaC** — `BP_JVM_CRAC_ENABLED` was inferred; Paketo currently has no CRaC
  support, so image-based CRaC is not a live path.

---

## 13. Source index

**Official — Gradle plugin reference**
- Index / Getting Started / Reacting to Other Plugins: `docs.spring.io/spring-boot/gradle-plugin/{index,getting-started,reacting}.html`
- Managing Dependencies: `docs.spring.io/spring-boot/gradle-plugin/managing-dependencies.html`
- Packaging Executable Archives: `.../gradle-plugin/packaging.html`
- Running your Application: `.../gradle-plugin/running.html`
- Ahead-of-Time Processing: `.../gradle-plugin/aot.html`
- Packaging OCI Images: `.../gradle-plugin/packaging-oci-image.html`
- Integrating with Actuator (build-info): `.../gradle-plugin/integrating-with-actuator.html`

**Official — reference & how-to**
- System Requirements: `docs.spring.io/spring-boot/system-requirements.html`
- AOT / native intro / advanced: `.../reference/packaging/{aot,native-image/introducing-graalvm-native-images,native-image/advanced-topics}.html`
- AOT cache / efficient / checkpoint-restore: `.../reference/packaging/{aot-cache,efficient,checkpoint-restore}.html`, `.../how-to/aot-cache.html`
- Dockerfiles / container images: `.../reference/packaging/container-images/dockerfiles.html`
- Dev-time services / Testcontainers / DevTools: `.../reference/features/dev-services.html`, `.../reference/testing/testcontainers.html`, `.../reference/using/devtools.html`
- Native first app / testing native: `.../how-to/native-image/{developing-your-first-application,testing-native-applications}.html`

**Official — release notes / migration (GitHub wiki)**
- `github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes`
- `github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide`
- Blog: `spring.io/blog/2025/10/28/modularizing-spring-boot/`, `.../2025/11/20/spring-boot-4-0-0-available-now/`, `.../2025/11/13/spring-framework-7-0-general-availability/`, `.../2024/08/29/spring-boot-cds-support-and-project-leyden-anticipation/`

**Ecosystem**
- GraalVM Native Build Tools (Gradle): `graalvm.github.io/native-build-tools/latest/gradle-plugin.html`
- GraalVM reachability metadata: `graalvm.org/latest/reference-manual/native-image/metadata/`
- Paketo `spring-boot` buildpack + perf blog: `github.com/paketo-buildpacks/spring-boot`, `blog.paketo.io/posts/spring-boot-performance/`
- `io.spring.dependency-management`: `plugins.gradle.org/plugin/io.spring.dependency-management`, `docs.spring.io/dependency-management-plugin/docs/current/reference/html/`
- Version-catalog issue: `github.com/spring-projects/spring-boot/issues/29588`
- OpenRewrite/Moderne SB4 recipes: `docs.openrewrite.org/recipes/java/spring/boot4/`, `docs.moderne.io/.../java/spring/boot4/`
- Testing-in-SB4 (Rieckpil): `rieckpil.de/whats-new-for-testing-in-spring-boot-4-0-and-spring-framework-7/`

---

*Compiled 2026-07-10 from six parallel research passes against the current 4.x line (4.1.x).*
