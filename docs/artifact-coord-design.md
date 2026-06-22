# Artifact / Coordinate Design — `jk.toml`

**Status:** Locked for v0.7. Replaces the v0.6 string-array dep format.
**Scope:** This document defines the dependency declaration format and its
adjacent sections of `jk.toml`. It does NOT redesign sections we already
ship cleanly (`[project]`, `[workspace]`, `[profiles.*]`, `[features]`,
`[repositories]`, `[image]`).

## Motivation

The v0.6 format kept dependencies as arrays of `"group:artifact:version"`
strings, keyed by scope:

```toml
[dependencies]
main = [
  "org.springframework.boot:spring-boot-starter-web:3.4.0",
  "com.fasterxml.jackson.core:jackson-databind:2.18.2",
]
```

This is Maven's mental model verbatim — coordinate-as-identity. It works,
but every other modern build tool moved to **name-as-identity**: Cargo's
`serde = "1.0"`, uv's `dependencies = ["fastapi"]`, npm's `"react":
"^18.0.0"`. The reason that pattern won is not aesthetic; it's that
treating the **name as the interface** and the **coordinate as the
resolution detail** unlocks a chain of downstream wins:

- Short, stable references at every site of use (features, source-sets,
  workspace inheritance, lockfile, tool output).
- Per-dep metadata (classifier, exclusions, transitive flag, source
  overrides) becomes natural table fields, not coord-suffix hacks.
- Renaming a coordinate (group change, artifact rename) is a one-line
  edit; every reference is unaffected.
- A bundled curated catalog of common short names → coords can later
  enable `jk add jackson-databind` without typing groupIds.
- Lockfile entries become readable: humans see `spring-web` got bumped,
  not a wall of GAVs.
- Future tooling (Renovate, Dependabot) produces readable PR titles.

The v0.6 format also bundled two distinctions into one syntax: pinned vs
floating was carried by `:` vs `@` in the coordinate string. The new
format collapses this into the version selector grammar — `"1.2.3"` is
caret-floating per the locked v1 default, `"=1.2.3"` is exact.

## The core shift

```toml
# Before — coord-as-key, scope is an array of strings
[dependencies]
main = ["org.springframework.boot:spring-boot-starter-web:3.4.0"]

# After — name-as-key, scope is a sub-table mapping name → dep
[dependencies.main]
spring-web = { group = "org.springframework.boot", name = "spring-boot-starter-web", version = "3.4.0" }
```

The **key** (`spring-web`) is the local identifier the user controls.
The **value** is a TOML table holding the coordinate components plus
optional metadata.

## Schema

### `[project]` — unchanged

```toml
[project]
group       = "dev.jkbuild"      # required
name        = "jk"               # required
version     = "0.7.0-SNAPSHOT"   # required
description = "..."              # optional
jdk         = 25                 # optional, ≥17
java        = 25                 # optional, mutually exclusive with `kotlin`
kotlin      = 21                 # optional, mutually exclusive with `java`
main        = "dev.jkbuild.cli.Jk"  # optional, runnable entry point
shadow      = false              # optional, shaded JAR opt-in
native      = false              # optional, false|true|"always"
```

No changes from v0.6.

### `[workspace]` — adds `[workspace.dependencies]`

```toml
[workspace]
modules = ["core", "io", "cli"]

# Optional: shared external deps that multiple children use.
# Children inherit by writing `name.workspace = true`.
[workspace.dependencies]
junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.0" }
junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "6.1.0" }
assertj-core            = { group = "org.assertj", name = "assertj-core", version = "3.27.7" }
```

**Workspace module resolution.** When a child writes
`jk-core.workspace = true`, jk looks up the name in this order:

1. **Workspace module** with matching `name` field (after the
   conventional `jk-` prefix is stripped — see "Implicit naming" below).
2. **`[workspace.dependencies]`** entry with that key.
3. Error: "no workspace dependency or sibling named `jk-core`".

For workspace siblings, the resolved coord uses the sibling's
`[project].group`, `[project].name`, and `[project].version`.

**Registering modules.** The `modules` array is not hand-edited. Run inside
a workspace, `jk new <path>` and `jk init` scaffold a module and append its
root-relative path here (inheriting the root `[project].group`, and writing
no per-module `jk.lock`). `jk add <path>` — where the argument starts with
`:` or contains a path separator (`:widget`, `./widget`, `libs/widget`,
`widget/`) — registers a local sibling **and** adds the dependency edge,
pinned to the sibling's `[project].version`. A bare name (`jk add jackson`)
is a catalog library / Maven coord, never a path. All edits preserve the
array's formatting and comments.

### `[dependencies.<scope>]` — name-as-key sub-tables

Six scopes, identical to v0.6: `main`, `provided`, `runtime`, `test`,
`processor`, `platform`.

```toml
[dependencies.main]
# Fully structured
picocli = { group = "info.picocli", name = "picocli", version = "4.7.7" }

# Workspace sibling (auto-resolves group, artifact, version from the module)
jk-core.workspace = true

# Workspace external dep (looks up in [workspace.dependencies])
jackson-databind.workspace = true

# Path source override (replaces v0.6 [sources] table)
shared-utils = { group = "com.acme", name = "shared-utils", path = "../shared-utils" }

# Git source override (replaces v0.6 [sources] table)
codec = { group = "com.acme", name = "codec", git = "https://github.com/acme/codec", tag = "v0.9.1" }
```

#### Field reference for a dep table

| Field        | Required when                     | Notes |
|--------------|-----------------------------------|-------|
| `group`      | not a workspace-resolved dep      | Maven groupId. |
| `name`       | library handle differs from it    | Defaults to the key (`spring-web` → `spring-web`). |
| `version`    | no `path`, `git`, or `workspace`  | Version selector. See grammar below. |
| `path`       | local-path source                 | Mutually exclusive with `version` and `git`. |
| `git`        | git source                        | Mutually exclusive with `version` and `path`. |
| `tag` / `branch` / `rev` | git source              | Exactly one required when `git` is set. |
| `workspace`  | inheriting from workspace         | `true` only. Mutually exclusive with everything else. |
| `optional`   | feature-gated dep                 | bool, default false. Withheld from the default resolution; pulled in only when a `[features]` entry names this dep (see [features]). Orthogonal to the source form. |
| `submodules` | git source                        | bool, default true. |
| `verify-signed` | git source                     | bool, default false. |
| `classifier` | future                            | Maven classifier (e.g., `sources`, `javadoc`). |
| `transitive` | future                            | bool, default true. |

#### Version selector grammar

```
"1.2.3"          → caret-floating ^1.2.3 (v1 default per locked decisions)
"=1.2.3"         → exact pin
"~1.2.3"         → tilde range ~1.2.3 (patch-only updates)
">=1, <2"        → explicit range
"latest"         → floating latest stable
"1.2.3-SNAPSHOT" → SNAPSHOT (ingest allowed; publish rejected by [snapshots] policy)
```

The pinned-vs-floating distinction is now expressed by the selector
itself, not by manifest syntax. `=` is pinned; everything else floats
within its grammar.

### Default scope shorthand

When all deps fall in `main`, the scope sub-table is optional:

```toml
# Equivalent to [dependencies.main]
[dependencies]
spring-web = { group = "...", name = "...", version = "3.4.0" }
```

When mixed scopes appear, prefer the explicit sub-table form:

```toml
[dependencies.main]
spring-web = { group = "...", name = "...", version = "3.4.0" }

[dependencies.test]
junit-jupiter.workspace = true
```

### `[repositories]` — unchanged

```toml
[repositories]
central = "https://repo.maven.apache.org/maven2"
acme    = { url = "https://nexus.acme.dev/releases" }
```

### `[profiles.<name>]` — unchanged

```toml
[profiles.dev]
inherits  = "default"
javac     = ["-g", "-parameters"]
jvm-args  = ["-Xms256m"]

[profiles.release]
javac     = ["-g:none"]
jvm-args  = ["-XX:+UseZGC"]
```

### `[features]` — name-list values, not coord-strings

Features remain dep-sets (per locked v1 decision). The values are lists
of **dep names** (or other feature names), not coord strings:

```toml
[features]
default = ["postgres", "jackson"]

[features.postgres]
deps = ["hikari", "postgres-jdbc"]   # names from [dependencies.*]

[features.mysql]
deps = ["hikari", "mysql-connector"]

[features.with-jackson]
features = ["jackson"]                # nested feature reference
deps     = []
```

Activating a feature pulls the listed deps into the build. Each listed dep
must be declared in `[dependencies.*]` with `optional = true` — optional deps
are withheld from the default resolution and enter the graph only when an
activated feature names them. Referencing a dep that isn't declared
`optional` (or doesn't exist) is a config error. Because the listed name
resolves to a normal dep entry, feature deps support every dep form
(`version` / `git` / `path` / `workspace` / catalog short-name / `sha256`).
The active set is the `default` list plus any `--features=A,B`.

### `[sources]` — **removed**

Replaced by the inline `path` / `git` fields on the dep table. No
separate top-level table.

## Worked examples

### Workspace root

```toml
[project]
group       = "dev.jkbuild"
artifact    = "jk"
version     = "0.7.0-SNAPSHOT"
description = "A modern build tool for Java and Kotlin."
jdk         = 25
java        = 25

[workspace]
modules = ["core", "io", "resolver", "toolchain", "engine",
           "supply-chain", "image", "compat", "cli"]

[workspace.dependencies]
junit-jupiter           = { group = "org.junit.jupiter",        name = "junit-jupiter",           version = "6.1.0" }
junit-platform-launcher = { group = "org.junit.platform",       name = "junit-platform-launcher", version = "6.1.0" }
assertj-core            = { group = "org.assertj",              name = "assertj-core",            version = "3.27.7" }
```

### Library child (`core/jk.toml`)

```toml
[project]
group    = "dev.jkbuild"
name = "jk-core"
version  = "0.7.0-SNAPSHOT"
jdk      = 25
java     = 25

[dependencies.main]
typesafe-config  = { group = "com.typesafe",        name = "config",           version = "1.4.8" }
tomlj            = { group = "org.tomlj",           name = "tomlj",            version = "1.1.1" }
jackson-databind = { group = "tools.jackson.core",  name = "jackson-databind", version = "3.1.3" }

[dependencies.test]
junit-jupiter.workspace           = true
junit-platform-launcher.workspace = true
assertj-core.workspace            = true
```

### Application child (`cli/jk.toml`)

```toml
[project]
group    = "dev.jkbuild"
name = "jk-cli"
version  = "0.7.0-SNAPSHOT"
jdk      = 25
java     = 25
main     = "dev.jkbuild.cli.Jk"

[dependencies.main]
# Workspace siblings — short-name resolves to the matching module.
jk-compat.workspace       = true
jk-core.workspace         = true
jk-engine.workspace       = true
jk-image.workspace        = true
jk-io.workspace           = true
jk-resolver.workspace     = true
jk-supply-chain.workspace = true
jk-toolchain.workspace    = true
# External
picocli = { group = "info.picocli", name = "picocli", version = "4.7.7" }

[dependencies.test]
assertj-core.workspace            = true
junit-jupiter.workspace           = true
junit-platform-launcher.workspace = true

[image]
main-class = "dev.jkbuild.cli.Jk"
```

## Parser semantics

1. **Dep table walk.** For each scope `s` in `[dependencies.s]`, iterate
   the table's keys. The key is the dep's short name; the value must be
   an inline table.
2. **Resolution mode.** Pick exactly one of:
   - `workspace = true` → look up in workspace.
   - `path = "..."` → local-path source.
   - `git = "..."` + one of `tag`/`branch`/`rev` → git source.
   - `version = "..."` → Maven coord; `group` is required, `artifact`
     defaults to the key.
3. **Default-scope shorthand.** `[dependencies]` (no sub-key) is treated
   as `[dependencies.main]` IF every direct child is an inline table
   (i.e., a dep). If any direct child is itself a table (sub-scope),
   throw a parse error: "mixed flat and sub-scope dep tables are
   ambiguous".
4. **Mutual exclusivity.** A dep table with both `version` and `path`
   (or any other two source modes) is a parse error.
5. **Coordinate construction.**
   - `workspace = true`: resolve via the workspace lookup chain.
   - Else: `Coordinate(group, artifact ?? key, version-or-source)`.
6. **Pinned flag (lockfile semantics).** Derive from the version
   selector: `=X.Y.Z` → pinned; everything else floats. `path` and `git`
   deps are pinned to their source.

## Migration impact (internal)

`jk` has never shipped. All migration is to the project's own
`jk.toml` files, the parser/editor code, the importers/exporters, and
the docs. No external user impact.

Files touched (Java):
- `core/src/main/java/dev/jkbuild/config/JkBuildParser.java`
- `core/src/main/java/dev/jkbuild/config/JkBuildEditor.java`
- `core/src/main/java/dev/jkbuild/model/Dependency.java` (add `name` field)
- `core/src/main/java/dev/jkbuild/model/Workspace.java` (add `dependencies`)
- `core/src/main/java/dev/jkbuild/model/WorkspaceMerge.java`
- `compat/src/main/java/dev/jkbuild/compat/JkBuildRenderer.java`
- `compat/src/main/java/dev/jkbuild/mvn/PomImporter.java`
- `compat/src/main/java/dev/jkbuild/mvn/PomExporter.java`
- `compat/src/main/java/dev/jkbuild/gradle/GradleImporter.java`
- `cli/src/main/java/dev/jkbuild/cli/NewJkBuildRenderer.java`
- `cli/src/main/java/dev/jkbuild/cli/NewScaffolder.java`
- `cli/src/main/java/dev/jkbuild/cli/AddCommand.java`
- `cli/src/main/java/dev/jkbuild/cli/RemoveCommand.java`
- Tests across the modules above.

Files touched (TOML):
- Root `jk.toml` + 9 child `jk.toml` files.

Files touched (docs):
- `docs/requirements.md`, `docs/libraries.md`, `docs/comparison.md`,
  `docs/implementation-plan.md`, `README.md`, `CONTRIBUTING.md`.

## Footgun: name defaulting

The `name = "..."` field defaults to the table key when omitted. This
matches Cargo's `serde = "1.0"` ergonomics and covers ~88% of real-world
deps cleanly (the artifactId and the natural short name agree). But it
creates one specific failure mode worth knowing about.

If the user picks a short name that **differs** from the Maven artifactId
and forgets to set `name = ...`, the parser builds a coordinate that
doesn't exist on the catalog:

```toml
# WRONG — defaults artifact to "postgres", but the Maven artifact is "postgresql"
postgres = { group = "org.postgresql", version = "42.7.4" }
```

The resolver then fails with a 404 on `org.postgresql:postgres:42.7.4`.
The diagnostic message (`Diagnostics.render` in the resolver) detects
this case and surfaces a hint:

```
- no versions of org.postgresql:postgres match =42.7.4

Hint: the dep `postgres` resolves to `org.postgresql:postgres`, which Maven Central does not
recognize. If it is published under a different name, set
`name` explicitly:

  postgres = { group = "org.postgresql", name = "<correct-name>", version = "..." }
```

The hint fires only when:
- The package source returned **zero** versions (artifact unknown), not
  when versions exist but the constraint doesn't match.
- The user's table key equals the artifact portion of the failed
  coordinate (i.e., `artifact` was defaulted or matched by coincidence).
- The dep is a **root dep** declared in `jk.toml`. Transitive deps come
  from POMs we trust as authoritative, so the hint would be misleading.

**Rule of thumb:** if the key you want to use in `jk.toml` differs from
the published artifactId, write `name = "..."` explicitly. Otherwise
let the default carry it.

```toml
# Right — short name matches artifactId; default carries it
jackson-databind = { group = "com.fasterxml.jackson.core", version = "2.18.2" }

# Right — short name deliberately differs from artifactId; set name
postgres-jdbc = { group = "org.postgresql", name = "postgresql", version = "42.7.4" }
```

`jk add`, `jk init`, and `jk import pom.xml` always write `name = ...`
when the user-supplied key (or the artifactId-derived default) differs
from the artifactId, so this footgun only fires on hand-edited
manifests.

## Short-name library catalog

jk ships a curated `name → group:artifact` index and resolves manifest
shorthand against it. The catalog is layered — the same name can be
defined in multiple places, and the topmost layer wins.

### Layers (highest precedence first)

| Layer | Source | Updated by |
|--|--|--|
| **project** | `[libraries]` table in the project's `jk.toml` | Hand-edited |
| **local** | `~/.jk/libs.local.toml` | Hand-edited |
| **global** | `~/.jk/libs.global.toml` | `jk library update` |
| **bundled** | classpath resource at `dev/jkbuild/library/libraries.toml` | Shipped with the binary |

Only the bundled layer is guaranteed to exist. The local file lights up
when present; the global file lights up after the first
`jk library update`. Project-level overrides apply only to the
manifest they're declared in.

### Shorthand forms it enables

When a dep's short name matches a curated entry, the user can drop the coord:

```toml
[dependencies.main]
# String shorthand — cargo-style one-liner.
picocli = "4.7.7"
jackson-databind = "2.18.2"

# Table form with no `group` — same catalog lookup.
junit-jupiter = { version = "6.1.0" }
```

Resolution rules:

- **Explicit `group` always wins.** Writing `picocli = { group = "io.fork", version = "..." }` overrides the catalog — useful for forks and ambiguous artifacts.
- **`path` / `git` sources still require explicit `group`.** The catalog only resolves version-based deps; path/git overrides are deliberate enough that defaulting silently would be surprising.
- **Unknown names get a parse error pointing at this section.**
- **Tooling emits the shorthand when it can.** `jk add picocli --ver 4.7.7` writes `picocli = "4.7.7"` to the manifest; `jk add picocli --group io.fork --ver 4.7.7` writes the structured form.

The bundled index is intentionally version-free — it's a name-to-coord index, not a curated catalog. Version pinning is the project's responsibility.

`jk add <name>` consults the catalog too: with a known short name the user only supplies `--ver` (no `--group` needed). Unknown names still require explicit `--group`.

### `jk library` subcommands

- **`jk library update`** pulls `https://raw.githubusercontent.com/jkbuild/jk-library-registry/refs/heads/main/libraries.toml` and replaces `~/.jk/libs.global.toml`. The previous file is preserved at `libs.global.toml.prev`. A malformed or HTTP-failed response never replaces a good cache.
- **`jk library list`** prints every library with its source layer. `--layer <name>` filters (e.g. `--layer project`).
- **`jk library search <term>...`** substring-matches against name, group, and artifact across every layer. Multiple terms are AND-ed (each must appear in at least one of the three fields). Case-insensitive. `--limit N` caps the result count for noisy queries.

### Curation policy (`jkbuild/jk-library-registry`)

- The repo's `main` branch is the source of truth — `jk library update` always pulls HEAD; there's no client-side pinning. Every team is on the same revision globally.
- Only BryanSant can commit for now. Future maintainers join via CODEOWNERS once the curation surface justifies it.
- First-PR-wins for short-name conflicts. If `foo` is the natural short name for two different artifacts, whichever PR lands first claims it; the other artifact must use an explicit `group =` in `jk.toml` or a project-level `[libraries]` override.
- The bundled `libraries.toml` shipped with each jk release seeds the floor. Once downloaded layers exist they take precedence — but the binary is never useless on a fresh machine.

### Local overrides

Projects can shadow catalog entries — useful for forks of upstream
libraries or internal artifacts that aren't in the curated set:

```toml
[libraries]
picocli = "io.fork:picocli"        # local fork wins over bundled
internal-widget = "com.acme:internal-widget"

[dependencies.main]
picocli = "4.7.7"
internal-widget = "0.1.0"
```

User-global overrides live in `~/.jk/libraries.toml` (same schema). The
file is read at parser invocation time; no daemon caching.

## Out of scope for v0.7

These are intentionally deferred — the format leaves room for them but
the parser does not enforce or honor them yet.

- **Sigstore verification of `jk library update`.** Wire the same verifier `jk audit` uses; pinned identity is `*.jkbuild.dev` via GitHub OIDC.
- **PEP 621-style `[project]`** with `authors`, `license`, `urls`,
  `readme`, `keywords`. Adds option value but no immediate feature
  unlock.
- **`[platforms]` BOM imports** as first-class. v0.6 already supports
  `[dependencies.platform]` for BOMs; we keep that.
- **`[features.conflicts]`** declaring mutually-exclusive features.
- **`target.cfg(...)` profile-conditional deps.** Maven `<profile>` is
  the JVM mental model; we'll likely keep `[profiles.<name>]` rather
  than borrow Cargo's `[target]` syntax.
- **`[lints]`, `[audit]`, `[provenance]`, `[reproducibility]`,
  `[publish]`** as top-level blocks. Some already exist under other
  names; consolidating into uniform top-level sections is a follow-up.
- **`[[tasks]]`** for single-file Java task scripts. Deferred.

## Open questions

1. **Coord-string fallback?** Some users will paste a Maven coord from a
   blog post. Do we accept `spring-web = "org.springframework:spring-web:6.2.0"`
   as a degenerate one-string form, or reject it to force the structured
   shape? Recommendation: **reject** — the structured form is the only
   form, paste-friction is small, and the catalog will eventually
   eliminate the paste case.
2. **`name` defaulting to key.** Trivial collision: a user names a
   dep `spring-web` but the artifact is `spring-boot-starter-web`. The
   key/name independence is the whole point — we just require
   `name = "..."` when they differ. Documented above.
3. **Caret meaning for `0.x.y` versions.** Cargo treats `^0.2.0` as
   `>=0.2.0, <0.3.0` (compatible within minor). Maven semantics are
   different. We adopt Cargo's interpretation per the v1 locked
   caret-default decision. Tested in `VersionSelectorTest`.
