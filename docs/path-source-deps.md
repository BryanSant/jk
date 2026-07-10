# Path-Source Dependencies (consume-only local projects)

**Status:** Implemented (v1). Resolve-only — a `path` dependency points at a
local project directory; `jk` builds it just enough to get its jar and
resolves the result as an ordinary coordinate. It is **not** a workspace member.

## Dependency vs. workspace member

A local sibling project can be consumed two ways, and the distinction is
deliberate:

| | `[dependencies]` `path = "…"` | `[workspace] modules = […]` |
|---|---|---|
| Build | compile/package **only** | full build |
| Tests | **never run** | run |
| Target build systems | jk, Gradle, Maven | jk only |
| Purpose | consume a stable local lib | co-develop siblings together |

If you want a project's tests to run, or to co-develop it in lockstep, make it a
`[workspace]` module. A `path` dependency is for pulling in a local library the
fast way — the same "just give me the jar" contract as a
[git-source dependency](git-source-deps.md), but from a directory instead of a
clone.

## Declaring

```toml
[dependencies]
shared = { path = "../shared" }   # table form
util   = "./libs/util"            # shorthand: a leading . or / is a path dep
```

Like git deps, a path dep is pure discovery: `group`/`name`/`version` are read
from the target and must **not** be set on the dependency entry.

## How it resolves

1. The target directory is resolved against the consuming `jk.toml`'s directory.
2. It is built by `SourceProjectBuilder`, which dispatches on what it finds:
   - **`jk.toml`** → `jk`'s own compile→jar→POM pipeline (no tests).
   - **`build.gradle[.kts]` / `settings.gradle[.kts]`** → best-effort
     `gradle assemble -x test`.
   - **`pom.xml`** → best-effort `mvn package -Dmaven.test.skip=true`.
3. The built jar + a synthesized POM are published into a `file://` Maven repo,
   and the dep is rewritten to an exact coordinate pin — so the PubGrub solver
   treats it like any other coordinate.

### Foreign (Gradle/Maven) builds are best-effort

- Tool selection: the project's own wrapper (`./gradlew` / `./mvnw`) is
  preferred; otherwise `gradle` / `mvn` on `PATH`; otherwise the build fails
  fast. jk does **not** provision a Gradle/Maven distribution for this.
- All tool output is discarded; any nonzero exit or missing/ambiguous artifact
  fails fast.
- The GAV is derived (Maven from `pom.xml`, inheriting from `<parent>`; Gradle
  from a `gradle properties` query), and the synthesized POM is **GAV-only** —
  the foreign jar is a leaf dependency, so its transitive dependencies are **not**
  resolved. A project that needs its transitive graph, or its tests, belongs in a
  jk `[workspace]` module.

## Caching and freshness

A path dep has no commit SHA, so its artifact is keyed by a **content
fingerprint** of the target's source tree (build-output and tool-metadata
directories — `build/`, `target/`, `.git/`, `.gradle/`, … — are excluded so a
rebuild doesn't invalidate the cache). The artifact is rebuilt exactly when the
sources change and reused otherwise, under
`$JK_CACHE_DIR/path-artifacts/<pathHash>/<fingerprint>/repo`.

As with git deps, **the lockfile is the pin**: a path dep is (re-)materialized
when a lock is produced (`jk lock`, `jk update`, first resolve), not on every
incremental build. If you edit a path-dep target, run `jk lock` to move the pin
to the new content.

## Limitations

- Single-artifact targets only: a multi-module/multi-artifact Gradle or Maven
  build whose output is ambiguous fails fast.
- Unresolved version placeholders in a Maven `pom.xml` (`${revision}`) or a
  Gradle `unspecified` group/version fail fast — declare a literal version.
- No transitive resolution for foreign targets (GAV-only POM, see above).
