# JDK resolution & toolchain management

`jk` manages the JDK (and the GraalVM used by `jk native`) the way `uv` manages
Python or `rustup` manages Rust toolchains — so you don't need **SDKMAN**,
**jenv**, **asdf**, or **Gradle Java toolchains** to do it. It does three things
those tools split between themselves:

1. **Discovers** every JDK already on the machine (whoever installed it), and
   **installs** new ones on demand from the JetBrains JDK feed.
2. **Resolves** a single canonical "which JDK applies here?" order that the
   build, the compiler, and your shell all agree on.
3. **Manages your environment** — `JAVA_HOME` and `GRAALVM_HOME` — through a
   directory-aware shell hook (`jk activate`), the way jenv/asdf shims do, but
   without per-version shim binaries.

One install root, one resolution order, one source of truth. No parallel install
trees, no shim farms.

---

## 1. The three roles a JDK can hold

A JDK shown in `jk jdk list` carries a **status**, which is one or more of:

| Status | Meaning |
|---|---|
| **active** | The JDK `javac` on `PATH` resolves to right now — what this shell compiles with. Bright-cyan, bold; its vendor + spec are bolded. |
| **default** | jk's configured global default (`jk jdk default`). Used everywhere a project doesn't pin something else. Bright-yellow. |
| **native** | The default GraalVM (`jk jdk graal`) — backs `GRAALVM_HOME` and `jk native`. Bright-green. |
| **installed** | On disk, holds none of the above roles. |
| **available** | Not installed; offered by the feed (only with `jk jdk list --all`). |

A single JDK can hold several at once; the label composes (e.g. `active/native`).

**default vs active.** *default* is jk's persistent choice; *active* is whatever
the current shell actually has on `PATH`. With the shell hook installed they
usually coincide — but in a project that pins a different JDK, *active* follows
the pin while *default* stays put.

---

## 2. Resolution order

Both the build and the `jk activate` hook resolve the JDK through **one** order
(highest precedence first):

| # | Tier | Source |
|---|---|---|
| 1 | `--jdk <spec>` switch | CLI flag for this run |
| 2 | `JK_JDK` env var | per-shell / CI override |
| 3 | `.jdk-version` file | project-local pin (top *project* tier) |
| 4 | `jk.lock` | the resolved JDK identifier recorded at lock time |
| 5 | `jk.toml` `[project].jdk` | committed project config |
| 6 | `project.java` floor | when `project.java` exceeds the latest LTS, implies `>=<release>` |
| 7 | **current** | the per-shell pointer the hook steers into a pinned project |
| 8 | **default** | the global default (`jk jdk default`), else the *de-facto* default (§5) |
| 9 | `JAVA_HOME` | the ambient env (build only) |
| 10 | `GRAALVM_HOME` | the ambient env (build only) |
| 11 | first `javac` on `PATH` | the ambient env (build only) |

GraalVM resolves through a **parallel, independent** chain (for `jk native` and
`GRAALVM_HOME`): `--graal` > `JK_GRAAL` > `[project].graal` > the **default
GraalVM** (`jk jdk graal`). It is *not* tied to `JAVA_HOME` — your `JAVA_HOME`
can be Temurin while `GRAALVM_HOME` is Oracle GraalVM.

**Build vs shell-hook difference.** Tiers 9–11 (ambient `JAVA_HOME`/`GRAALVM_HOME`/`PATH`)
and the bootstrap-install (§5) apply to the **build** only — a build must find
*some* JDK. The **shell hook never installs and never re-exports the ambient
env**: if no jk pin or default resolves, it leaves your shell untouched rather
than pointlessly re-asserting the `JAVA_HOME` you already had.

If a named pin (tiers 1–6) names a JDK that isn't installed, the **build**
installs it; the **hook** falls through to the next tier (it must never block a
shell on a download).

A JDK is identified by its **home path**, not just its `vendor-major` identifier
— so two installs that share an identifier (e.g. one under `~/.jk/jdks`, one
under `~/.jdks`) never both count as the default.

---

## 3. Discovery (the probe chain)

`jk jdk list` and resolution see JDKs from every common location, in this
preference order (first probe to claim a canonical home path wins its
attribution):

1. `~/.jk/jdks` — jk's own installs (source `jk`)
2. `~/.jdks` / `~/Library/Java/JavaVirtualMachines` — IntelliJ (source `intellij`)
3. `~/.gradle/jdks` — Gradle toolchain auto-provisioning (source `gradle`)
4. `~/.sdkman/candidates/java` — SDKMAN (source `sdkman`)
5. `~/.jbang/cache/jdks`, mise, asdf, jenv, Homebrew, system (`/usr/lib/jvm`, …)
6. `$JAVA_HOME` (relabelled to its owning manager when one also claims it)

A JDK installed by *any* of these is reusable by jk with zero ceremony, and a
JDK `jk` installs lands in `~/.jk/jdks` where IntelliJ also looks — so the two
share installs instead of maintaining parallel trees.

When a spec names no vendor, jk prefers vendors in this order:
**Temurin → Liberica → Oracle OpenJDK → Corretto → others** (for the GraalVM
chain: **Oracle GraalVM → GraalVM CE**).

---

## 4. The shell hook — `jk activate`

`jk activate <shell>` prints a shell-integration script (bash | zsh | fish |
pwsh). Source it once from your rc file:

```bash
eval "$(jk activate bash)"
```

It installs a prompt/`chpwd` hook (`jk hook-env`) that re-resolves on every
prompt and `cd`, so `JAVA_HOME`, `GRAALVM_HOME`, and `PATH` always reflect the
JDK that applies to your current directory:

- **Outside any jk project** → the global default (and default GraalVM).
- **Inside a project tree** (a dir with `jk.toml`, or below it) → the project's
  pin (`.jdk-version` / `jk.lock` / `[project].jdk`); stepping back out reverts
  to the default. This is the jenv/asdf "directory follows the pin" behavior,
  done by re-export rather than per-version shims.
- **When nothing jk-managed resolves** → the hook leaves your existing env alone.

Related commands:

| Command | Does |
|---|---|
| `jk activate <shell>` | print the hook script (`eval "$(jk activate <shell>)"`) |
| `jk deactivate` | undo the hook for the current shell |
| `jk shell` | spawn a one-off subshell with the project's JDK (+ `KOTLIN_HOME`) on `PATH` |
| `jk jdk home` | print a single `export JAVA_HOME=…` line for one-shot `eval` |

For its own child processes (javac, kotlinc, tests, scripts) jk sets `JAVA_HOME`
directly and unsets `JDK_HOME` — independent of whatever your shell has.

---

## 5. Defaults & the de-facto default

You never *have* to pick a default. When you haven't run `jk jdk default`, jk
computes one on demand:

- **0 JDKs installed** → a build installs the latest LTS (Temurin) and persists
  it as the default.
- **exactly 1 installed** → that one.
- **more than 1** → the **current latest-LTS major if it's installed, else the
  latest installed version**. Examples (latest LTS = 25):
  `{17, 21, 26}` → **26** (25 isn't installed); `{24, 25, 26}` → **25** (the
  latest installed LTS, even though 26 is newer).

An explicit `jk jdk default` — or any JDK `jk` installs because nothing was
configured — is persisted to `~/.jk/config.toml` and wins thereafter.

---

## 6. Spec grammar

Everywhere a JDK/GraalVM spec is accepted (`--jdk`, `JK_JDK`, `[project].jdk`,
`jk jdk install/default/graal`):

| Form | Example | Meaning |
|---|---|---|
| keyword | `lts`, `stable`, `latest` | latest LTS (`lts`=`stable`), or newest GA |
| keyword | `native` | latest Oracle GraalVM |
| bare major | `25`, `21` | that feature release, preferred vendor |
| range | `>=21`, `>25` | lowest installed (else available) major satisfying the bound |
| vendor-major | `temurin-25`, `corretto-21`, `graalvm-25` | vendor + feature release |
| point release | `zulu-21.0.9`, `liberica-26.0.1` | vendor + exact version |

`.jdk-version` is intentionally stricter — a resolved `<vendor>-<major>` pin
(e.g. `temurin-25`), since `jk jdk pin` writes it and jk keeps the patch current
behind the stable pointer. (SDKMAN-style suffixes like `21-tem` / `21.0.5-tem`
are **not** accepted.)

---

## 7. Configuring a project

| You want… | Do this | Tier |
|---|---|---|
| Just build (no opinion) | nothing — jk uses the **default** (latest LTS), installing it if needed | 8 |
| A specific language level | `[project].java = 21` — passed to `javac --release`; the JDK still defaults to the latest LTS unless `java` > latest LTS, which forces a JDK ≥ that major | 6 |
| Pin the project's JDK (committed) | `[project].jdk = "temurin-25"` (or `25`, `>=21`, …) | 5 |
| Pin locally (not committed) | `jk jdk pin <spec>` → writes `.jdk-version` | 3 |
| Override for one command | `jk build --jdk temurin-21` | 1 |
| Override for a shell / CI | `export JK_JDK=temurin-21` | 2 |
| Choose the GraalVM for `jk native` | `[project].graal = "graalvm-25"` (or `native`), or `--graal` / `JK_GRAAL` | graal chain |
| Set the machine default | `jk jdk default <spec>` | 8 |
| Set the machine default GraalVM | `jk jdk graal <spec>` | graal chain |

`jk jdk install` prompts (on a TTY) to adopt a freshly-installed JDK as the
default — and, for a GraalVM, as the default GraalVM — when it's ≥ the current
one. `--make-default` skips the prompt.

---

## 8. `jk jdk` commands

| Command | Does |
|---|---|
| `jk jdk list [--all]` | show installed JDKs (and, with `--all`, downloadable ones) + their status |
| `jk jdk install <spec> [--make-default]` | download + install from the feed |
| `jk jdk default <spec> \| --lts` | set the global default JDK |
| `jk jdk graal [<spec>]` | set the default GraalVM (for `GRAALVM_HOME` / `jk native`) |
| `jk jdk pin <spec>` | write the project's `.jdk-version` |
| `jk jdk home` | print the resolved `export JAVA_HOME=…` line |
| `jk jdk ensure <spec>` | install only if not already present |
| `jk jdk update [<spec>]` | update jk-managed installs to the latest point release |
| `jk jdk uninstall <spec>` | remove a jk-managed install |

---

## 9. Replacing SDKMAN / jenv / Gradle toolchains

| Job | SDKMAN / jenv / asdf | Gradle Java toolchains | jk |
|---|---|---|---|
| Install a JDK | `sdk install java …` | (auto-provision on build) | `jk jdk install <spec>` |
| Set a global default | `sdk default java …` / `jenv global` | — | `jk jdk default <spec>` |
| Per-project pin | `.sdkmanrc` / `.java-version` | `java { toolchain { … } }` in `build.gradle` | `.jdk-version` / `[project].jdk` |
| `JAVA_HOME` in your shell | shims / `sdk env` | — (build-internal only) | `jk activate` hook |
| Use an already-installed JDK | (its own tree only) | discovers a few locations | discovers all of the above + Gradle's |

jk **discovers** JDKs SDKMAN/jenv/Gradle already installed (so you can migrate
incrementally) and **manages your shell `JAVA_HOME`** like jenv/asdf — while also
being the build tool, so the build and the shell never disagree about the JDK.

---

See also: [requirements.md §12](./requirements.md#12-jdk-and-toolchain-management)
(spec), [comparison.md](./comparison.md) (vs other tools).
