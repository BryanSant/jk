# jk vs uv / Cargo / Gradle / Maven

A side-by-side feature reference. Commands that line up directly are clustered
at the top; meaningful behavioural differences are called out in **Notes**.
The further you scroll, the more `jk` diverges from one or more of the others.

Legend in the table cells:

| Symbol | Meaning |
|---|---|
| `cmd` | The tool has a direct equivalent invoked as shown. |
| *(plugin)* | Available via a community / official plugin, not built-in. |
| *(file)* | Done by editing a manifest by hand — no first-class command. |
| — | The tool has no equivalent. |

---

## 1-to-1 similarities

These commands map directly across all five tools. Differences shown in the
**Notes** column are real but not surprising.

| Feature | jk | uv | Cargo | Gradle | Maven | Notes |
|---|---|---|---|---|---|---|
| Init a new project | `jk init` | `uv init` | `cargo new` / `cargo init` | `gradle init` | `mvn archetype:generate` | jk and cargo default to a binary; uv, gradle and maven prompt for the template. |
| Add a dependency | `jk add <coord>` | `uv add <pkg>` | `cargo add <crate>` | *(file)* | *(file)* | jk / uv / cargo edit the manifest in place; gradle and maven still expect the user to hand-edit `build.gradle.kts` / `pom.xml`. |
| Remove a dependency | `jk remove <coord>` | `uv remove <pkg>` | `cargo remove <crate>` | *(file)* | *(file)* | Same shape as add. |
| Print dependency tree | `jk tree` | `uv tree` | `cargo tree` | `gradle dependencies` | `mvn dependency:tree` | jk / cargo / uv use a unicode tree; gradle prints a per-configuration tree; maven prints a flat indented form. |
| Explain why a dep is in the graph | `jk why <coord>` | `uv tree --package <pkg>` | `cargo tree -i <crate>` | `gradle dependencyInsight --dependency <coord>` | `mvn dependency:tree -Dincludes=<coord>` | All five answer "which root pulled this in"; jk's diagnostic is prose like Cargo's `tree -i`. |
| Compile / type-check (no jar) | `jk check` | — | `cargo check` | `gradle compileJava` | `mvn compile` | jk and cargo split type-check from package; gradle and maven always go through the lifecycle. |
| Build a jar / binary | `jk build` | — | `cargo build` | `gradle build` | `mvn package` | uv has no compile step (Python is interpreted). gradle's `build` runs tests by default; jk's does not (use `jk test`). |
| Run tests | `jk test` | — | `cargo test` | `gradle test` | `mvn test` | uv defers to `pytest` / `unittest`. jk emits Surefire-shaped XML so existing CI test reporters work. |
| Clean build outputs | `jk clean` | — | `cargo clean` | `gradle clean` | `mvn clean` | jk wipes `target/` and `.jk/generated/`; the others wipe their own conventional dirs. |
| Publish artifacts | `jk publish` | `uv publish` | `cargo publish` | `gradle publish` | `mvn deploy` | jk publishes a Maven-Central-grade POM + jar by default; signing, Sigstore, SLSA and SBOM are flags on the same verb (see §3 below). |
| Install a tool globally | `jk tool install <coord>` | `uv tool install <pkg>` | `cargo install <crate>` | — | — | jk and cargo install the artifact's own launcher; jk writes to `$JK_BIN_DIR` (`~/.jk/bin` by default), cargo to `~/.cargo/bin/`. uv creates a per-tool venv. `jk install <coord>` also installs a tool (and builds + locally-publishes the current project when no coord is given). |
| Ephemeral tool exec | `jk tool run <coord>` (shell alias: `jkx`) | `uvx <pkg>` | — | — | — | "Resolve, cache, run, evict LRU." `jkx` is a shell function installed by `eval "$(jk activate <shell>)"` that expands to `jk tool run`. |
| Repair discovered build tools | `jk doctor` | — | — | — | — | Prunes broken mvn/gradle/kotlin symlinks under `$JK_CACHE_DIR/tools/`. |
| Single-file scripts | `jk tool run script.java` | `uv run script.py` | — | — | — | jk's header (`//jk dep …`) is also JBang-compatible. uv reads PEP 723 inline metadata. |

---

## 2. Similar concept, differing implementation

Same job, different default behaviour or filesystem layout.

| Feature | jk | uv | Cargo | Gradle | Maven | Notes |
|---|---|---|---|---|---|---|
| Lockfile | `jk.lock` (TOML, written by `jk lock`) | `uv.lock` | `Cargo.lock` | *(implicit; `--write-locks` for verification)* | — | jk, uv, cargo treat the lockfile as canonical. Gradle treats it as optional verification; maven has none — every build re-resolves. |
| Lockfile refresh | `jk update` | `uv lock --upgrade` | `cargo update` | `gradle --refresh-dependencies` | — | jk and uv re-resolve from declared constraints; cargo same; gradle's flag drops caches but doesn't pin. |
| Materialise the cache / offline prep | `jk sync` *(`--offline-prepare` for CI)* | `uv sync` | `cargo fetch` | `gradle --offline` *(with prior populate)* | `mvn dependency:go-offline` | jk and uv have an explicit "make the workspace match the lock" verb; jk's `sync` doubles as the CI "download everything, build nothing" entry point. |
| Workspace / multi-module | `workspace { modules = [...] }` in `jk.toml` | `[tool.uv.workspace]` | `[workspace]` in `Cargo.toml` | `include(":a", ":b")` in `settings.gradle.kts` | `<modules>` in `pom.xml` | All five have multi-package support; jk's root-aggregated lockfile mirrors Cargo's, not Maven's per-module model. |
| Add a workspace module | `jk new <path>` / `jk add <path>` register modules | `uv init` / `uv add ./lib` | `cargo new <path>` auto-registers | *(edit `settings.gradle.kts`)* | *(edit `<modules>`)* | jk, uv and cargo edit `[workspace].modules` for you when run inside a workspace; gradle and maven still expect a hand-edit. |
| Toolchain manager | `jk jdk install / default / graal / list` | `uv python install / pin / list` | `rustup toolchain` (separate binary) | `java { toolchain }` *(auto-download)* | toolchains.xml *(manual)* | jk treats JDK management as a first-class command and also discovers JDKs SDKMAN/jenv/asdf/Gradle/IntelliJ already installed. |
| Per-project pin | `.jdk-version` / `[project].jdk` | `.python-version` | `rust-toolchain.toml` | *(in build.gradle.kts)* | *(in pom.xml `<requireJavaVersion>`)* | `jk jdk pin` writes a single-line `<vendor>-<major>` (e.g. `temurin-21`); `[project].jdk` also accepts ranges/keywords. |
| Environment hook | `jk activate <shell>` | *(via `uv run`)* | *(via `rustup which`)* | — | — | `eval "$(jk activate bash)"` keeps `JAVA_HOME`/`GRAALVM_HOME` in sync per directory (jenv-style); `jk jdk home` prints a one-shot export. |
| Open a project shell | `jk shell` | `uv run -- bash` | — | — | — | Subshell with the project's pinned JDK on PATH. |
| Build profiles | `profiles.{dev,ci,…}` block | — | `[profile.dev]` / `[profile.release]` | `gradle -PsomeProp` | `<profiles>` | jk's profiles change javac/JVM args, not deps (deps belong in features). |
| Optional feature sets | `features { … }` block | `[project.optional-dependencies]` | `[features]` | *(no native; via separate sourceset)* | `<profile>` *(awkward)* | jk uses cargo's word "feature" deliberately — additive, named dep sets. |
| Native compile | `jk native` *(GraalVM)* | — | `cargo build` *(native by default)* | *(graalvm plugin)* | *(graalvm plugin)* | jk drives the GraalVM `native-image` binary; cargo is native-by-default because Rust is AOT. |
| Reproducibility check | `jk verify` | — | *(community: cargo-bisect-rustc, manual)* | — | — | jk's verb rebuilds in a scratch dir and diffs the jar's SHA-256. |
| Action / build cache | `$JK_CACHE_DIR/actions/` *(content-addressed)* | — | `target/` *(incremental compile)* | `--build-cache` *(local + optional remote)* | *(none)* | jk caches per-task by the SHA of its inputs (Bazel-shape); gradle's build cache is the nearest analogue. |

---

## 3. Major differences

Where jk does something the others don't, or does it differently enough that
the rows above hide more than they reveal.

| Feature | jk | uv | Cargo | Gradle | Maven | Notes |
|---|---|---|---|---|---|---|
| Maven / Gradle passthrough | `jk mvn …` / `jk gradle …` | `uv tool run …` *(generic)* | — | itself | itself | jk downloads and runs the real `mvn` / `gradle` honouring `.mvn/wrapper/maven-wrapper.properties` etc., adding ANSI grouping + cache sharing. The point is adoption: you can install jk without giving up your Maven build. |
| Import from existing builds | `jk import pom.xml` *(Tier 1–3)* / `jk import build.gradle.kts` *(declarative)* | — | — | — | — | Three-tier fidelity report. POM import is solid; Gradle import is honest about being string-level (no script evaluation). |
| Round-trip export | `jk export pom.xml` | — | — | — | itself | jk emits a Maven-Central-grade POM from `jk.toml` (no `<repositories>`, no `<build>`, BOMs flattened to `<dependencyManagement>`). Gradle export is v1.1+. |
| Built-in vulnerability scan | `jk audit` *(OSV)* | — | `cargo audit` *(separate crate)* | *(plugins: OWASP, Snyk)* | *(plugins: OWASP)* | jk talks to OSV directly. cargo-audit is a separate tool; jk treats audit as a core verb. |
| Built-in policy gate | `jk deny` *(source / yanked / license)* | — | `cargo deny` *(separate crate)* | *(plugin)* | *(plugin)* | Same pattern as audit — packaged with the tool, not a separate ecosystem. |
| GPG signature on publish | `jk publish --sign --key-file …` | — | — | *(signing plugin)* | `mvn gpg:sign` | jk uses BouncyCastle (no system `gpg` required); the system binary stays a fallback. |
| Sigstore keyless signing | `jk publish --sigstore` | — | — | *(third-party plugin)* | *(third-party plugin)* | jk wraps sigstore-java; CI's OIDC token is auto-detected. |
| SLSA provenance | `jk publish --slsa` | — | — | *(third-party plugin)* | *(third-party plugin)* | Emits a SLSA v1 in-toto Statement (`.intoto.json`) for the main jar. |
| SBOM emission | `jk publish --sbom` *(CycloneDX 1.6 + SPDX 2.3)* | — | *(no native)* | *(cyclonedx plugin)* | *(cyclonedx plugin)* | jk ships both formats; GitHub prefers SPDX, most vuln scanners prefer CycloneDX. |
| OCI image build | `jk image` *(Jib-core, daemonless)* | — | — | *(jib-gradle-plugin)* | *(jib-maven-plugin)* | Jib lives inside jk; no Docker daemon, multi-arch, reproducible layers. |
| Git dependencies | `foo = { group = "g.a", git = "gh:foo/bar", tag = "v1" }` under `[dependencies.<scope>]` | `tool.uv.sources` git | `[dependencies.foo]` `git = "…"` | *(composite build / includeBuild)* | *(no native)* | jk hashes the canonical URL, pins SHA in the lockfile, and refuses on tag rewrites unless `jk update` is run. |
| Config file format | TOML (`jk.toml`) | TOML (`pyproject.toml`) | TOML (`Cargo.toml`) | Kotlin/Groovy DSL (`build.gradle.kts`) | XML (`pom.xml`) | A single, idiomatic TOML manifest. Schema-validated; no embedded scripting. |
| Version selector default | Caret (`"1.2.3"` ≡ `^1.2.3`) | PEP 440 specifier set | Caret default | *(strict + dynamic; user picks)* | Exact pin | jk treats `"1.2.3"` as `^1.2.3` per v1 locked decision (Cargo-style). Opt into exact pins with `"=1.2.3"`, patch-only with `~1.2.3`, or arbitrary ranges with `>=1.2,<2`. |
| Conflict resolution | Highest-version-wins across the whole graph | Pip's resolver (closer to cargo) | Highest-version-wins | Highest-version-wins | **Nearest-wins** *(footgun)* | jk explicitly rejects Maven's nearest-wins per PRD §7.4. |
| Dependency-confusion defense | `from = "<repo>"` pin per coord | *(via index URL pin)* | *(via crate registry pin)* | *(via repo content filters)* | *(no native — use `<mirrors>`)* | jk refuses to fetch a pinned coord from any other repo. |
| Conflict diagnostic | Prose ("no version of X is compatible with Y because…") via PubGrub | Pip-style | Cargo-style | Verbose tree | Single-line failure | jk borrows the Dart `pub` reference for its diagnostics. |
| Single-binary distribution | Native (GraalVM); `jk` binary fits ~30 MB | Native | Native | JVM wrapper script | JVM wrapper script | jk targets `≤50 ms` `--help` cold start on M-series Macs. |

---

## At-a-glance summary

`jk` reads as if it were assembled from the best ideas of the other four:

* **From Cargo:** caret defaults, highest-wins resolution, lockfile discipline,
  workspaces, `cargo install` shape, prose conflict diagnostics.
* **From uv:** the `uvx`-style ephemeral-exec UX (`jk tool run`, shell alias `jkx`), integrated Python/JDK
  toolchain management, the "fast native binary" delivery.
* **From Gradle:** workspaces, multi-module composite-build semantics,
  build cache (jk's action cache is the equivalent), Kotlin support.
* **From Maven:** the artifact ecosystem (Central, scopes, BOM imports, GPG
  signatures), and the `~/.m2`-compatible cache layout.
* **From elsewhere:** Sigstore + SLSA + SBOM as first-class flags (most JVM
  builds bolt these on via plugins).

The pitch from PRD §1: *"the fastest way to run your existing Maven or Gradle
build, and it just happens to come with a better build tool you can opt into
when you're ready."* The passthrough rows and the import / export rows are
how that pitch shows up at the command line.
