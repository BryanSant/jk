# Authoring a jk build plugin

A build plugin teaches jk a new `jk.toml` table — `[spring-boot]`, `[android]`, `[protobuf]` —
and shapes the standard verbs around it. This guide walks the whole surface using the plugin jk
itself ships, [`plugins/spring-boot`](../plugins/spring-boot), as the blueprint. Design
rationale lives in [build-plugins-plan.md](./build-plugins-plan.md).

**The bar:** you never learn jk's content-addressed store, action keys, freshness stamps, or
directory layout. You declare *what*; jk owns *when* (incrementality) and *whether it can be
skipped* (caching).

## 1. Anatomy

A plugin is **one jar** containing:

```
jk-plugin.toml                                       ← the declarative layer (root of the jar)
scaffold/…                                           ← template resources the manifest references
<your classes>                                       ← the code layer (optional)
```

The **declarative layer** covers most integrations and executes zero plugin code in the engine:
jk parses `jk-plugin.toml` (data — safe even for untrusted plugins) and applies its
contributions itself. The **code layer** runs only in a forked worker JVM over an NDJSON
protocol; the engine never classloads your classes.

## 2. The manifest, section by section

### `[plugin]` + `[schema]` — own a table

```toml
[plugin]
id        = "spring-boot"        # your plugin's identity
table     = "spring-boot"        # the jk.toml table you own
version   = "1.0.0"
jk-compat = ">=0.10"

[schema]                          # typed keys; jk validates the user's table for you
version       = { type = "string", required = true,
                  example = "4.0.1", hint = "the Spring Boot release to build against" }
aot           = { type = "bool" }                  # no default = tri-state (absent = auto)
build-info    = { type = "bool", default = false }
aot-args      = { type = "string-list", default = [] }
```

Types: `string`, `bool`, `int`, `string-list`. A `required` key missing from the user's table
fails the parse with your `example`/`hint` baked into the message. Your code receives the
validated, defaulted config — never raw TOML.

### `[[contribute.*]]` — declarative build shaping

```toml
[[contribute.platform-dependency]]        # BOM auto-import, applied at parse time
coordinate = "org.springframework.boot:spring-boot-dependencies:${config.version}"

[[contribute.compiler-args]]
javac  = ["-parameters"]
kotlin = ["-java-parameters"]

[[contribute.kotlin-plugin]]
id         = "org.jetbrains.kotlin.noarg"
coordinate = "org.jetbrains.kotlin:kotlin-noarg-compiler-plugin-embeddable:${kotlin.version}"
options    = ["preset=jpa"]
when       = { classpath-has = "jakarta.persistence:jakarta.persistence-api" }

[[contribute.packager-dependency]]        # extra artifacts handed to your packager by name
artifact   = "loader"
coordinate = "org.springframework.boot:spring-boot-loader:${config.version}"
```

Interpolation is closed: `${config.<schema-key>}`, `${kotlin.version}`,
`${project.group|name|version}`. Conditions are a closed predicate set — `classpath-has`,
`config`/`equals`, `native-declared`, `kotlin-project` — exactly one per `when`. Anything
conditional beyond these belongs in code; the manifest stays boring on purpose.

### `[packaging]` — the artifact descriptor

```toml
[packaging]
packager       = "boot-jar"      # the code packager replacing the main artifact
exec-mode      = "jar"           # jar | classpath | binary
self-contained = true            # install links one artifact, no dependency jars
classes-run    = true            # run/dev exec from classes (the packaged layout isn't a classpath)
main-scan      = true            # entry point may be discovered by scanning compiled classes
layered-image  = true            # jk image splits deps/snapshots/app layers
```

This is what `jk run`/`install`/`image`/`aot-cache` consult instead of framework-presence
branches — static data, no fork needed.

### `[scaffold]` and `[[import.gradle-plugin]]` — `jk new` and `jk import`

```toml
[scaffold]
flag = "spring"                              # jk new --spring

[[scaffold.append]]                          # jk.toml fragment appended to the base render
template = "scaffold/jk-toml-fragment.toml"

[[scaffold.file]]                            # sample sources; `lang` is the only predicate
path     = "src/${package-path}/Application.java"
template = "scaffold/Application.java"
when     = { lang = "java" }

[[import.gradle-plugin]]                     # Gradle plugin id → your table, on jk import
id         = "org.springframework.boot"
version-to = "version"
```

Scaffold interpolation adds `${package}`, `${package-path}`, `${main-root}`, `${test-root}`,
`${resources-root}` — jk resolves the layout; you write templates.

### `[code]` — declare the code layer

```toml
[code]
protocol-prefix = "##JKSB:"      # your worker's protocol line marker
# worker = "jk-spring-boot"      # first-party only; a third-party plugin IS its own jar
```

## 3. The code layer

Implement `dev.jkbuild.plugin.build.BuildPlugin` and register hooks; drive the jar's `main`
through `BuildPluginHarness` (it speaks the describe/run-step/package/verb protocol for you):

```java
public final class SpringBootPlugin implements BuildPlugin {
    @Override
    public void register(BuildPluginContext ctx) {
        ctx.step(StepSpec.named("spring-aot")
            .after(Anchor.COMPILE).before(Anchor.PACKAGE)
            .inputs(In.classes(), In.runtimeClasspath(), In.config())
            .outputs(Out.dir("aot/classes"), Out.dir("aot/resources"))
            .contributesClasses("aot/classes")
            .contributesResources("aot/resources")
            .run(exec -> exec.java()
                .classpath(exec.in().runtimeClasspath())
                .mainClass("org.springframework.boot.SpringApplicationAotProcessor")
                .args(...)));

        ctx.packaging(PackagerSpec.replacingMainArtifact("boot-jar")
            .inputs(In.classes(), In.runtimeEntries(), In.stepOutput("spring-aot"), In.config())
            .produce((in, out) -> { /* assemble the jar from in.runtimeEntries() */ }));

        ctx.verb(VerbSpec.named("hello")
            .description("Say hello")
            .run(exec -> { exec.out("hello"); return 0; }));
    }
}
```

What you get for free: incrementality (jk fingerprints your declared inputs), cache
restore/skip, `--rerun` semantics, progress labels, failure surfacing, and your
`contributes*` dirs folded into packaging/native/run automatically. What you never see:
action keys, CAS paths, stamps, `target/` conventions.

Steps and packagers run in **your worker JVM** — one fork per plugin per build, multiplexing
every step over the protocol. Keep the jar self-contained (shade your dependencies);
`plugin-api` types are provided by jk.

## 4. Publish, declare, trust

Publish the jar like any Maven artifact (`jk publish` works). Users declare it:

```toml
[plugins]
my-plugin = { group = "com.example", name = "my-jk-plugin", version = "1.2.0" }

[my-plugin]           # your table, validated against your schema
…
```

Then:

- `jk lock` / `jk sync` resolve the coordinate, **pin its SHA-256 in `jk.lock`**, and extract
  your manifest. Declarative contributions apply from the next parse on — manifest data is
  safe without trust.
- **Code hooks are consent-gated.** The engine refuses to fork an untrusted third-party plugin
  worker; the user runs `jk trust plugin com.example:my-jk-plugin` (or `com.example:` for the
  whole group) once per machine. First-party plugins shipped inside jk are implicitly trusted.
- A table nobody owns is a parse error naming the remedy — so a typo'd table or a forgotten
  `[plugins]` entry fails loudly, never silently.

## 5. Ground rules

- One packager may replace the main artifact; contributions otherwise merge — conflicts are
  errors, not priorities.
- The manifest DSL will not grow expressions. If a condition isn't in the closed set, write a
  code hook.
- Version your schema conservatively: `jk-compat` is checked at load; adding optional keys is
  compatible, changing types is not.
