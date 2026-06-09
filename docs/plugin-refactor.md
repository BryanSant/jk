# jk — Plugin Architecture Refactor

**Status:** Proposal / RFC. Not yet scheduled against a milestone.
**Author:** (drafted with Claude Code)
**Companion to:** [implementation-plan.md](./implementation-plan.md), [requirements.md](./requirements.md)

---

## 1. Purpose

Today jk has *accidentally* grown a plugin system. The "runner" modules
(`test-runner`, `kotlin-compiler`, `java-compiler`, `audit-runner`,
`publish-runner`, `image-runner`, `compat-runner`, `git-runner`) are already
child-JVM workers that the parent locates in the CAS by SHA-256 and drives over
an NDJSON-on-stdout protocol. That is a plugin architecture in everything but
name — but it was built one runner at a time, so the wire format, the launcher,
the spec encoding, the CAS locator, and the build wiring are **reimplemented per
runner** with no shared contract.

This document proposes promoting that pattern into a deliberate, documented
**Plugin SPI**: a small, stable surface that first-party *and* third-party
plugins implement; a focused core/runtime that owns only universal capabilities;
and a single home for every piece of functionality (core **or** a plugin, never
both).

It also folds in two adjacent asks that the same refactor should settle:

- A real **Command model** in the domain layer (`Command` / `CliCommand` /
  `BuildCommand` / `UiCommand`), replacing the picocli-annotation-only commands.
- **Removing picocli** in favour of jk's own help/usage renderer.

---

## 2. Where we are today

### 2.1 What is already right (keep it)

- **`core/run/` is a clean, TUI-agnostic orchestration kernel.** `Goal`,
  `Phase`, `PhaseContext`, `GoalListener`, `GoalKey`, `GoalResult`,
  `PhaseStatus`, `PhaseKind` (SYNC/IO/CPU) form a DAG scheduler with typed
  cross-phase state and an observer interface. The CLI couples to the engine
  *only* through `GoalListener` — `ProgressBarListener`, `VerboseListener`,
  `NdjsonListener`, `EventLogListener`, `SilentListener` all live in
  `cli/run/`. **This is the seam the plugin SPI should reuse, not replace.**
- **Core modules have no picocli imports.** `core`, `io`, `resolver`,
  `toolchain`, `engine` are framework-clean.
- **The child-JVM isolation goal is already met.** Heavy/conflicting deps
  (JGit, Jib + Guava + Protobuf, BouncyCastle, sigstore, Jackson, the Kotlin
  compiler closure) are kept out of the native binary's reachable set by living
  in forked workers. The native image stays small precisely because of this.
- **CAS-keyed worker delivery works.** Workers are addressed by SHA-256, placed
  under `~/.jk/cache/sha256/AB/CD/<rest>`, side-loaded in dev via
  `installLocalCas`, and (eventually) fetched from Maven Central by `jk sync`.

### 2.2 What is duplicated / accidental (fix it)

The same eight-runner pattern is open-coded eight times:

| Concern | Today | Count |
|---|---|---|
| Worker `main()` + spec parse + JSON escape + exit codes | Hand-rolled per runner | 8 copies |
| Wire prefix marker (`##JK:`, `##JKJC:`, `##JKGIT:`, `##JKAU:`, …) | Ad-hoc per runner | 8 markers |
| `Ndjson` flat-JSON parser | Copy-pasted | 2 copies (`engine/compile/`, `kotlin-compiler/`) |
| Parent-side jar locator | `*WorkerSetup` class per runner | 6 classes in `runtime/` |
| Worker registry | Hand-maintained `JkWorkerSync.WORKERS` list | 8 entries |
| `build.gradle.kts` SHA-emit task | Copy-pasted `writeXxxWorkerSha` blocks | 8 in `runtime/`, +1 in `engine/` |
| Test-time `-Djk.*.worker.jar` plumbing | Repeated per runner in 3 build files | ~21 lines |

There is **no `Plugin` or `Runner` interface, no shared protocol module, no
shared launcher.** Adding a ninth runner today means copying all seven rows
above.

Worse, several capabilities live in **two places at once**, violating DRY:

- **`compat/`** (Maven/Gradle import-export, tool installer, `JkBuildRenderer`)
  is on the **main jk classpath** *and* duplicated through `compat-runner`.
- **`engine/compile/` + `engine/test/`** hold parent-side compile/test logic
  that overlaps the `java-compiler` / `kotlin-compiler` / `test-runner` workers
  (e.g. `engine/compile/WorkerJavac.java` is the parent half of the
  `java-compiler` worker; `engine/compile/Ndjson.java` is duplicated in the
  kotlin worker).
- **`supply-chain/`** has been hollowed out to a single class
  (`deny/PolicyChecker.java`) now that audit/publish/sbom moved to runners — the
  module is vestigial.
- **`image/`** is a single record (`ImageConfig.java`) plus the `image-runner`.

And the command surface (78 classes under `cli/command/`, 67 importing picocli)
has **no model-level abstraction** — every command is just a
`@Command`-annotated `Callable<Integer>`.

---

## 3. Target architecture

### 3.1 Process model: CLI → Workspace Host → Plugins

A native-image binary **cannot load arbitrary plugin bytecode in-process** —
GraalVM closes the world at build time. The current design already works around
this by forking JVM workers. The target makes that explicit with **two tiers**,
and reconciles the "run plugins in one JVM with classloader isolation and
threads, one JVM per workspace" goal:

```
┌─────────────────────────────────────────────────────────────┐
│  jk  (GraalVM native image — thin, fast, always-on)          │
│  • arg parse + help/usage rendering (own renderer)           │
│  • JDK install/select/pin, shell + env integration           │
│  • build-file & environment validation (fail-fast)           │
│  • terminal ownership + TUI (GoalListener → progress bars)    │
│  • `jk tool` / `jkx` ephemeral tool runs                     │
│  • resolves + launches the Workspace Host                    │
└───────────────┬─────────────────────────────────────────────┘
                │  protocol over stdio (typed events)
                ▼
┌─────────────────────────────────────────────────────────────┐
│  Workspace Host  (JVM on the project-pinned JDK)             │
│  ONE per workspace; runs members in parallel on threads.     │
│  • owns the Goal/Phase scheduler (today's core/run)          │
│  • owns CAS, action cache, resolver, model, I/O services     │
│  • loads plugins via ISOLATED classloaders (in-process)      │
│  • forks a child JVM only for plugins that demand it         │
│  • emits structured events; never owns a terminal            │
└───────────────┬─────────────────────────────────────────────┘
                │  in-JVM call across an isolating classloader
                │  (or stdio to a forked JVM for `isolation=process`)
                ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│ java-compiler│ │ kotlin-      │ │ test-runner  │  … (plugins)
│   plugin     │ │ compiler     │ │   plugin     │
└──────────────┘ └──────────────┘ └──────────────┘
```

Why this shape:

- **The CLI stays native and tiny.** It carries no plugin bytecode and no heavy
  deps — exactly today's win, made structural.
- **The Host is a real JVM**, so it *can* load plugin jars into isolated
  classloaders and run them on threads — satisfying "same JVM, classloader
  isolation, one JVM per workspace." Workspace members share that JVM and the
  warm CAS/action-cache/resolver state.
- **`isolation = process` is the escape hatch**, not the default — used only
  when a plugin's transitive deps would poison the Host (e.g. two plugins
  needing incompatible Guava). Same protocol, just over stdio instead of an
  in-process classloader boundary. This subsumes every runner that exists today.
- **Host lifetime is one-shot — no daemon, by design** (see §3.8). The Host
  starts, runs the workspace's Goal, exits. This is a deliberate departure from
  Gradle's daemon: it keeps the model simple (no lifecycle, staleness, IPC, or
  `--stop` surface) and the warm-JVM upside a daemon buys shrinks as AOT /
  Project Leyden land. Per-invocation startup is the cost we accept for it.

### 3.2 Module map (target)

Split the tree cleanly into **kernel**, **SPI**, and **plugins**.

```
jk/
├── kernel/                      # the "core/engine/runtime" — universal only
│   ├── model/        (was core/model + core/run)      # JDK + Lombok + JSpecify ONLY
│   ├── core/         (was core/{util,event,config,lock,layout,library,credential})
│   ├── io/           (was io/*  — http, cache/CAS, repo, forge; git client → plugin)
│   ├── resolver/     (unchanged — PubGrub, version coercion, deny policy)
│   ├── toolchain/    (jdk, script, tool, discovery)
│   └── host/         (was engine/task + runtime — scheduler, plugin host, launcher)
│
├── plugin-api/                  # the SPI. Tiny. JDK + Lombok + JSpecify ONLY.
│   └── (Plugin, PluginContext, services, protocol types, Command model)
│
├── plugins/
│   ├── java-compiler/   (was java-compiler + engine/compile/{javac,incremental})
│   ├── kotlin-compiler/ (was kotlin-compiler + engine/compile/{kotlinc})
│   ├── test-runner/     (was test-runner + engine/test)
│   ├── auditor/         (was audit-runner)
│   ├── publisher/       (was publish-runner + supply-chain/sbom)
│   ├── image-builder/   (was image + image-runner)
│   ├── git-client/      (was git-runner + io/git)
│   ├── maven-bridge/    (was compat/mvn + compat-runner share)
│   ├── gradle-bridge/   (was compat/gradle)
│   └── intellij-bridge/ (new; v1.0 target)
│
└── cli/                         # native image; own help renderer (no picocli)
```

Notes:

- **`model` has zero deps beyond JDK + Lombok + JSpecify**, as required. `Build`,
  `Workspace`, `Command`, `Goal`, `Phase`, `Dependency`, `Coordinate`, `Scope`,
  `VersionSelector`, etc. all live here. `core/run/` merges into `model` (Goal/
  Phase are domain types) or sits beside it in the same dep tier.
- **`host` is the merge of `engine/task/` (action graph, ActionCache, CAS sweep,
  freshness) and today's `runtime/`** (BuildPipeline, the `*WorkerSetup`
  locators, `JkWorkerSync`) — minus all the per-runner duplication, which
  collapses into one launcher.
- **`supply-chain` disappears**: `deny/PolicyChecker` is lightweight policy →
  moves to `resolver` (it gates resolution) or `kernel/core`; sbom → `publisher`
  plugin.
- **`engine` and `compat` disappear** as standalone modules; their parent-side
  halves merge into `host` (scheduling) or into the owning plugin (compile/test/
  import logic). This is the DRY win: one home per capability.

### 3.3 The Plugin SPI (`:plugin-api`)

A plugin is a jar that exposes one `Plugin` via `ServiceLoader`
(`META-INF/services/dev.jkbuild.plugin.Plugin`) — no hand-maintained registry.
Sketch (illustrative, not final):

```java
public interface Plugin {
    PluginManifest manifest();          // id, version, capabilities, isolation hint
    void register(PluginContext ctx);   // contribute goals/phases/commands
}

public interface PluginContext {
    Workspace workspace();              // read-only domain model
    Services services();                // CAS, ActionCache, Http, Resolver, Clock
    EventSink events();                 // structured progress — never System.out
    void contribute(GoalContribution c);// phases this plugin fulfills
    void addCommand(Command command);   // optional: plugins may add verbs
}

// Services: the universal capabilities the kernel exposes to every plugin.
public interface Services {
    Cas cas();
    ActionCache actionCache();
    HttpClient http();
    Resolver resolver();
    Logger log();                       // structured; routed to the event stream
    Path workDir();
}
```

Key properties:

- **`plugin-api` depends only on `model`** (so plugins see `Goal`, `Phase`,
  `Coordinate`, `Dependency`, …) plus JDK/Lombok/JSpecify. It is the *only*
  thing a third-party plugin compiles against.
- **Plugins contribute Phases, not whole pipelines.** They hand the Host
  `Phase` objects (with `requires`, `kind`, `scope`, a body that takes
  `PhaseContext`). The Host merges contributions from all plugins for a command
  into one `Goal` DAG — exactly how `BuildPipeline` composes phases today, but
  the phase bodies now come from plugins instead of from `runtime/`.
- **Plugins never touch the terminal.** They report through `PhaseContext` /
  `EventSink` (`progress`, `label`, `output`, `warn`, `error`). The CLI's
  `GoalListener` implementations render. This is already true of `core/run`;
  the SPI makes it a contract.

### 3.4 The Command model

Promote commands into `model` (so the CLI, a future GUI, and the IntelliJ
bridge share one definition):

```java
public interface Command {
    String name();
    String description();
    List<Parameter> parameters();       // required/optional/default
    List<Option> options();             // required/optional/default
}

public interface CliCommand extends Command {  // CLI-presentable
    String shortName();
    String shortDescription();
    Usage usage();
    List<UsageExample> usageExamples();
}

public abstract class BuildCommand implements CliCommand {
    // Declares the Goals/Phases it contributes; the Host merges them into one
    // pipeline. Exposes everything the progressbar TUI needs (already modeled
    // by GoalListener/GoalView/GoalResult).
    public abstract List<GoalContribution> goals(Invocation in);
}

public abstract class UiCommand implements CliCommand {
    // Interactive session (Wizard/TUI today; GUI/IntelliJ tomorrow).
    // Oriented around steps + prompts rather than a batch pipeline.
    public abstract void run(InteractiveSession session);
}
```

- `BuildCommand` covers `build`, `compile`, `test`, `clean`, `image`, `native`,
  `publish`, `audit`, … — everything that drives the progress TUI. Most of its
  body becomes "ask the Host to run the Goal assembled from plugin
  contributions."
- `UiCommand` covers `new` / `init` / `activate` — the Wizard-driven verbs. The
  existing `Wizard` / `WizardStep` machinery becomes the `UiCommand` runtime.
- Parameter/Option/Usage are plain records in `model`. **This is what lets us
  delete picocli** (§5).

### 3.5 Plugin isolation & lifecycle

1. **Discovery.** The Host resolves plugin jars (first-party shipped + any in
   `[plugins]` of `jk.toml`) into the CAS — the same SHA-256/`jk sync` path used
   for workers today, generalized. A plugin's `PluginManifest` (a resource in
   the jar) is read without loading its classes.
2. **In-process load (default).** Each plugin gets a child `URLClassLoader`
   parented to a shared API classloader that exports only `plugin-api` + `model`
   + a curated services facade. Plugin transitive deps stay in the child loader
   — no leakage between plugins, none into the Host. Tasks run on the Host's
   `JkThreads.cpu()/io()` pools.
3. **Out-of-process (opt-in).** A manifest with `isolation = process` (or a Host
   policy override) makes the Host fork a JVM and speak the §3.6 protocol over
   stdio — byte-for-byte the model the runners use today. Reserved for hostile
   classpaths.
4. **Native-image CLI.** When jk runs as the native binary with no Host daemon,
   "in-process" degenerates to "the Host process," which is itself a forked JVM
   from the CLI's point of view. So the CLI→Host hop *always* crosses a process
   boundary; the Host→plugin hop is in-process unless `isolation=process`.

### 3.6 The wire protocol (one codec, not eight)

Replace the eight ad-hoc `##JK*:`-prefixed encodings with **one** versioned,
typed protocol in `plugin-api`, used for both Host↔plugin (process mode) and
CLI↔Host:

- **Framing:** length-prefixed or single-prefix NDJSON (keep NDJSON for
  debuggability; one prefix, one parser — kill both `Ndjson.java` copies).
- **Messages map to the existing event vocabulary:** `phaseStart`, `progress`,
  `scopeUpdate`, `label`, `output`, `warn`, `error`, `phaseFinish`, `result` —
  i.e. the `GoalListener` interface serialized. The Host deserializes a child
  plugin's stream straight into `GoalListener` calls, so process-mode and
  in-process plugins are indistinguishable to the TUI.
- **One codec, one `WorkerLauncher`** replaces the six `*WorkerSetup` classes,
  the `JkWorkerSync.WORKERS` list, and the per-runner `main()` boilerplate. A
  plugin's process-mode entry point is a single shared `PluginHostMain` that
  loads the plugin via ServiceLoader and bridges stdio ↔ `PluginContext` — so a
  new plugin writes *zero* protocol/launch code.

### 3.7 Plugin packaging & build wiring

- One Gradle convention plugin (`jk.plugin-conventions`) replaces the
  copy-pasted `maven-publish` + fat-jar + `installLocalCas` + `writeXxxSha`
  blocks. Applying it to a module under `plugins/` gives it the manifest
  resource, the CAS side-load task, and the SHA emission automatically.
- The Host reads available plugins from a generated manifest index (one resource
  listing `{id, sha256, coordinates}`), regenerated by the convention plugin —
  no hand-edited `WORKERS` array.

### 3.8 Plugin trust model

First, the honest framing a build tool has to start from: **jk already runs
arbitrary code with your privileges.** Your build script, your tests, annotation
processors, `kotlinc` plugins — all execute as you, today, in every build tool.
Gradle/Maven plugins and npm `postinstall` scripts are unsandboxed by design.
So the goal is *not* "perfectly contain a malicious plugin" — that's
unwinnable without OS-level sandboxing, and no mainstream build tool attempts
it. The goal is to nail the controls that are **cheap, achievable, and
high-value**, and to be clear-eyed about what isolation does and doesn't buy.

The controls are three independent axes; treat them separately:

**Axis 1 — Integrity / provenance ("did I run the plugin I intended?").**
Cheap and *mostly already built*: the CAS is content-addressed, so a plugin is
identified by its SHA-256. Record that hash in `jk.lock` and a tampered or
substituted jar simply misses the CAS and is refused. Optionally require a
signature (jk already ships GPG + Sigstore for `jk publish`) for plugins pulled
from a registry. This is the single highest-value control and it falls out of
the existing design almost for free. **Recommendation: always pin first- and
third-party plugins by SHA in `jk.lock`; verify before load.**

**Axis 2 — Consent ("did the user choose to add this?").**
Plugins load **only when explicitly declared** — first-party plugins shipped
with jk, or third-party plugins the user wrote into `[plugins]` in `jk.toml`.
No auto-discovery from the dependency graph, no transitive plugin pull-in. A new
or changed third-party plugin SHA prompts on first add (trust-on-first-use, same
as a new dependency). **Recommendation: explicit declaration only.**

**Axis 3 — Containment ("if it's hostile, what can it reach?").**
This is the axis where isolation is widely misunderstood, so be precise:

- **Classloader isolation is NOT a security boundary.** It stops two plugins'
  dependencies from colliding. It does nothing to stop a plugin from calling
  `java.net.http`, `ProcessBuilder`, or `Files` directly. An in-process plugin
  runs with the full privileges of the Host process — your user account.
- **A forked JVM is NOT, by itself, a security boundary either.** Same user,
  same filesystem, same network. What process isolation *does* give you is
  (a) dependency + crash isolation and (b) a **seam to wrap with a real OS
  sandbox later** (Linux landlock/seccomp/namespaces or bubblewrap; macOS
  `sandbox-exec`; or a container).
- **The Java SecurityManager is gone** (deprecated for removal; effectively
  unavailable in modern JDKs), so there is no in-JVM sandbox to fall back on.

Given that, jk's leverage on containment is the **`Services` facade as a
*declared capability* surface**. A plugin's manifest states which capabilities
it needs — `cas`, `http`, `filesystem-outside-workdir`, `exec`. The Host hands
it a facade containing only those. This buys:

- **Transparency / auditability** — `jk` can show "this plugin requests network
  + exec" on add, and refuse silent escalation when a plugin's declared
  capabilities change.
- **Soft enforcement in-process** — withholding `http()` stops the *honest*
  plugin from phoning home, and makes a *dishonest* one have to reach around the
  facade (which it can, in-process — so this is transparency, not a guarantee).
- **Hard enforcement only out-of-process + sandboxed** — capability declarations
  become *enforceable* only when the plugin runs as a child JVM inside an OS
  sandbox that actually blocks the syscalls. That is the one configuration where
  "this plugin can't open a socket" is true rather than aspirational.

**The three realistic postures**, cheapest to strongest:

| Posture | Integrity | Consent | Capability decl. | OS sandbox | Cost | Who does this |
|---|---|---|---|---|---|---|
| **A — Baseline** | pin+verify | explicit | — | — | low | Gradle, Maven, Cargo, npm |
| **B — Transparent** | pin+verify | explicit | declared + audited (soft) | — | low–med | (jk target) |
| **C — Enforced** | pin+verify | explicit | declared | child JVM in OS sandbox | high, platform-specific | ~nobody, in build tools |

**Decision: jk v1 ships Posture A.** Concretely: pin+verify every plugin; load
only declared plugins; first-party plugins are trusted and run in-process;
**force `isolation = process` for third-party plugins** so the sandbox seam
exists without re-architecting. Capability declaration + audit (Posture B) is
deferred — a natural follow-on if/when a third-party plugin *registry* with
untrusted authors appears. Posture C's real OS sandboxing stays out of scope —
it's at odds with jk's single-static-binary, keep-it-simple ethos.

(The `Services` facade is still built capability-first — the Host only hands a
plugin the services it uses — but in v1 that's an internal API-hygiene measure,
not a declared/audited contract.)

Note this is orthogonal to `jk tool run` / `jkx`: those run published CLIs the
user explicitly invoked — arbitrary code by definition, user-initiated, and not
loaded into the Host. They keep their current model.

---

## 4. What moves where

| Today | Target | Disposition |
|---|---|---|
| `core/model`, `core/run` | `kernel/model` | Keep; add Command model; ensure JDK+Lombok+JSpecify-only |
| `core/{util,event,config,lock,layout,library,credential}` | `kernel/core` | Keep |
| `io/{http,cache,repo,forge}` | `kernel/io` | Keep (CAS, action-cache, HTTP are universal) |
| `io/git` | `plugins/git-client` | Move (JGit is a plugin dep) |
| `resolver/*` + `supply-chain/deny` | `kernel/resolver` | Merge deny policy in |
| `toolchain/*` | `kernel/toolchain` | Keep |
| `engine/task/*` | `kernel/host` | Move (action graph = universal scheduler) |
| `engine/compile/*`, `engine/test/*` | `plugins/{java,kotlin}-compiler`, `plugins/test-runner` | Merge parent halves into the owning plugin |
| `runtime/*` (BuildPipeline, `*WorkerSetup`, `JkWorkerSync`) | `kernel/host` | Collapse 6 setups + sync + 8 launch sites → 1 launcher |
| `supply-chain/*` (now just deny) | — | Module deleted |
| `image/ImageConfig` + `image-runner` | `plugins/image-builder` | Merge |
| `compat/{mvn,kotlin}` + `compat-runner` | `plugins/maven-bridge` | Merge; de-dup |
| `compat/gradle` | `plugins/gradle-bridge` | Move |
| `compat/{ToolInstaller,ToolProvisioning,…}` | `kernel/toolchain` | These are tool/JDK provisioning — universal |
| `*-runner` modules | `plugins/*` | Renamed/merged; share `PluginHostMain` |
| `Ndjson.java` ×2, `##JK*:` markers ×8 | `plugin-api` protocol codec | One implementation |
| `cli/command/*` (78, picocli) | `BuildCommand`/`UiCommand` impls + own renderer | §5 |

---

## 5. Removing picocli

picocli is confined to `cli/` (67 files) and reached only through annotations +
`Callable<Integer>`. Replace it incrementally:

1. **Define the Command model** (§3.4) in `model`. Parameters/options/usage
   become data, not annotations.
2. **Build a parser + help renderer in `cli/`.** A small recursive-descent arg
   parser over `Command.parameters()/options()` (jk already owns its terminal,
   theme, `Glyphs`, and `HelpLayout`/`HelpRenderer` — the rendering half mostly
   exists). Alias rewriting (`package`→`build`, etc.) stays a pre-parse pass as
   it is today in `Jk.rewriteAlias`.
3. **Port commands in tranches** (leaf verbs first, then parent/subcommand
   groups like `jdk`, `tool`, `auth`). Each ported command drops its picocli
   imports and implements `CliCommand`.
4. **Delete picocli + picocli-codegen** once the last command is ported; drop
   the `-Aproject=` annotation-processor arg and the picocli native-image
   reflection config. Net native-image-size and cold-start win.

This is independent of the plugin work and can land first or in parallel — it
unblocks the GUI/IntelliJ story (a `Command` is renderable by any front-end).

---

## 6. DRY cleanups this enables (concrete deletions)

- Delete 5 of 6 `*WorkerSetup` classes → one `WorkerLauncher`.
- Delete the `JkWorkerSync.WORKERS` hand-list → generated manifest index.
- Delete both `Ndjson.java` copies → one protocol codec in `plugin-api`.
- Delete 8 `writeXxxWorkerSha` blocks in `runtime/build.gradle.kts` + 1 in
  `engine/build.gradle.kts` → one convention plugin.
- Delete the repeated `-Djk.*.worker.jar` test plumbing (3 build files) → one
  helper in the convention plugin.
- Delete the duplicated `compat/` ↔ `compat-runner` split → single plugin.
- Delete `supply-chain` and `image` as modules (absorbed).
- Collapse 8 per-runner `main()`+spec-parse+JSON-escape implementations → one
  `PluginHostMain`.

---

## 7. Migration plan (phased — keep self-hosting & native build green at every step)

jk builds itself, so the bar is: **`jk verify-build` stays byte-reproducible and
the native image keeps building after every phase.** Sequence to minimize
risk:

- **Phase 0 — Extract `plugin-api` + protocol codec, no behaviour change.**
  Create the module; land one canonical NDJSON codec (the parent-side *reader* —
  `str`/`intValue`/`bool`/`has`/`strArray`/`nested` — plus a `quote()` *writer*
  helper). Repoint the parent-side reader consumers (engine: `KotlincDriver`,
  `WorkerJavac`, `JUnitLauncher`) and delete `engine/compile/Ndjson.java`.
  Note: the two `Ndjson.java` files are *not* duplicates — one is the reader,
  one is the kotlin worker's `quote()` *writer*. The writer lives in a thin-jar
  worker whose runtime classpath is assembled by the launcher, so migrating it
  off its private copy is folded into Phase 1 (where that classpath is reworked)
  rather than forced here. Phase 0 verifies the codec is sufficient before
  anything depends on it.
- **Phase 1 — One `WorkerLauncher` + `PluginHostMain`.** Collapse the 6
  `*WorkerSetup` classes and the 8 launch sites behind one launcher; put
  `plugin-api` on every worker's classpath so workers consume the shared codec
  (retiring `kotlin-compiler/Ndjson.java` and the inline `quote()`s in the other
  runners). Convert one runner (suggest `git-client`, smallest, self-contained)
  to the ServiceLoader + manifest + shared-main shape end-to-end. Prove the SPI
  on one plugin.
- **Phase 2 — Convention plugin + manifest index.** Replace the per-runner
  Gradle boilerplate and the `JkWorkerSync.WORKERS` list. Migrate the remaining
  runners to it mechanically.
- **Phase 3 — Command model + drop picocli** (§5). Independent; can overlap
  Phases 0–2.
- **Phase 4 — The Workspace Host.** Introduce `kernel/host` as the JVM that owns
  the scheduler and loads plugins in-process via isolated classloaders. The CLI
  launches it. Initially one-shot (no daemon) to match today's semantics.
- **Phase 5 — Module reorg & DRY merges** (§4): fold `engine`/`runtime` into
  `kernel/host`, merge each runner's parent half into its plugin, delete
  `supply-chain`/`image`/`compat` as standalone modules, move `io/git` →
  `git-client`. Do this *after* the SPI is proven so merges are mechanical.
- **Phase 6 — In-process isolation as default; `isolation=process` opt-in.**
  Flip first-party plugins with friendly classpaths (java-compiler, test-runner)
  to in-process; keep hostile ones (image-builder/Jib, publisher/BouncyCastle,
  git-client/JGit) on `isolation=process` until verified. Third-party plugins
  are forced to `isolation=process` (§3.8).
- **Phase 7 (optional) — Third-party plugin resolution** from Maven coordinates
  in `jk.toml`: pin+verify into `jk.lock`, forced `isolation=process` (§3.8,
  Posture A).

Each phase is independently shippable and leaves the tree green. (There is no
daemon phase — the Host is one-shot by design, §3.1/§3.8.)

---

## 8. Settled decisions

All foundational decisions are settled; none block starting Phase 0.

1. **Host lifetime — one-shot, no daemon.** The Host starts, runs the workspace
   Goal, exits. Deliberate differentiation from Gradle; keeps the model simple
   and the warm-JVM upside shrinks as AOT/Leyden land. (§3.1)
2. **Plugins always run on a real JDK, never inside the native binary.** The
   native CLI cannot host plugins (GraalVM closes the world at build time); only
   a JVM Host can. The Host runs on the project-pinned JDK jk already installs.
   jk *never* attempts to load plugin bytecode into the native binary — the
   CLI→Host hop is always a process boundary. (§3.1)
3. **Third-party trust posture — Posture A for v1 (§3.8).** Pin+verify every
   plugin, load only declared plugins, force `isolation=process` for third-party
   (preserving the sandbox seam). Capability declaration/audit (Posture B) and
   OS sandboxing (Posture C) are deferred — B is a natural follow-on if/when a
   third-party plugin registry appears.
4. **`Command` and friends live in `model`.** `Command`, `CliCommand`,
   `BuildCommand`, `UiCommand`, `Parameter`, `Option`, `Usage` are domain types
   in `kernel/model`, alongside `Goal`/`Phase` — one SPI module, shared by the
   CLI and any future front-end (GUI, IntelliJ bridge). No separate
   `command-api`.
5. **Wire protocol — NDJSON.** Kept for human-readability and debuggability;
   interactions are string-heavy enough that a binary framing's CPU/throughput
   edge wouldn't outweigh losing greppable, eyeball-able streams. Lives behind a
   codec interface in `plugin-api` so the framing can change later without
   touching call sites — but binary is not planned.

---

## 9. Risks

- **Scope.** This touches every module. Mitigated by the phased plan — each
  phase is shippable and reversible, and Phase 0–3 deliver most of the DRY win
  before the risky module reorg.
- **Reproducibility regression.** Moving code between modules changes jar
  contents/order. Mitigated by `jk verify-build` gating every phase.
- **Native-image breakage.** Reflection/resource config is currently
  per-subsystem under `META-INF/native-image/`. Module merges must carry that
  config along. The CLI shrinks (picocli gone, plugin bytecode never reachable),
  which should net-improve size and cold start.
- **In-process classloader isolation leaks.** Two plugins with conflicting deps
  in-process is the classic failure mode. Mitigated by `isolation=process` as a
  always-correct fallback and by parenting plugin loaders to an API-only loader.
- **Over-promising plugin security.** Classloader/process isolation is not a
  security boundary (§3.8). Docs and `jk` output must not imply third-party
  plugins are sandboxed when they run with full user privileges. Mitigated by
  honest capability surfacing and by treating real containment (Posture C) as
  explicitly out of scope.

---

*End of proposal. Companion design specs (protocol schema, plugin manifest
format, Host daemon RFC) to be added under `docs/` as phases land.*
