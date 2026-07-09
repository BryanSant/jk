# jbang → jkx

`jkx` (a real binary alias for `jk tool run`) covers the JBang workflows
most scripts actually use. This page is the honest inventory: what drops
in unchanged, and what doesn't (yet).

## Drop-in: works unchanged

| JBang habit | jk equivalent |
|---|---|
| `jbang hello.java` / `.kt` / `.kts` / `.jar` | `jkx hello.java` — same file types. |
| `//DEPS`, `//REPOS`, `//JAVA` (incl. `17+`), `//MAIN`, `//SOURCES`, `//FILES`, `//JAVAC_OPTIONS` / `//COMPILE_OPTIONS`, `//JAVA_OPTIONS` / `//RUNTIME_OPTIONS`, `//PREVIEW`, `//GAV`, `//DESCRIPTION`, `//KOTLIN` | Parsed by the same header parser in every file type. |
| `@file:DependsOn` / `@file:Repository` in `.kt`/`.kts` | Resolved by jk's own CAS-first resolver (not main-kts's Ivy) and fed to kotlinc via `-classpath`. |
| `jbang https://github.com/u/r/blob/main/x.java` | `jkx <same url>` — blob→raw rewrites for GitHub/GitLab/Bitbucket, single-file gists too. |
| `jbang g:a:v` | `jkx g:a:v`; also `g:a` (latest stable) and `g:a@1.2` selectors, which JBang doesn't have. |
| `jbang hello@user[/repo][/branch][~path]` | Same alias grammar; `jbang-catalog.json` located on GitHub → GitLab → Bitbucket; alias `script-ref`, `arguments`, `dependencies`, and `java-options` honored. |
| `jbang folder/` (a `main.java` folder) | `jkx folder/` — plus jk projects (`jk.toml`) and single-script folders. |
| `jbang app install …` | `jk tool install <anything jkx runs>` — launchers under `~/.jk/bin`. |
| Trusted sources | Same prefix model. One-time import: `jk trust import --jbang`. |
| `///usr/bin/env jbang "$0" "$@"` shebangs | `///usr/bin/env jkx "$0" "$@"` — jkx is a real binary, so shebangs work. |

Unknown `//DIRECTIVES` are ignored (scripts still run), exactly as JBang
treats directives it doesn't know.

## Not (yet) drop-in

- **`.jsh` (JShell) scripts** — not supported; no current plan.
- **Groovy scripts and `@Grab`** — deferred until jk grows Groovy
  support.
- **Alias default `arguments` on installed launchers** — honored for
  `jkx alias@…` runs, but an installed launcher doesn't bake them yet
  (jk warns at install time).
- **`//MODULE`, `//MANIFEST`, `//JAVAAGENT`, `//NATIVE_OPTIONS`,
  `//CDS`** — parsed and ignored.
- **`jbang init` / `edit` / `export` / templates** — out of scope; jk has
  its own `jk new` / IDE story.

## The jk extras JBang doesn't have

Catalog short-names (`jkx ktlint`), floating version selectors
(`g:a@^1.2`), `--with` extra deps, multi-file gists via the gist API,
native-binary tools picked automatically when a `native-<arch>-<os>` (or
protoc-style) classifier is published (PRD §20.4), engine-cached
resolution shared with your project builds, and installs that snapshot
an immutable env (the launcher survives the source file moving) with
provenance in `env.json`.
