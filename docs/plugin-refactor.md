# jk — Plugin Architecture Refactor

**Status:** Phases 0–6 complete. Phase 7 (third-party plugin resolution) next.
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

- **`core/run/` is a clean, TUI-agnostic orchestration kernel.** `Pipeline`,
  `Step`, `StepContext`, `PipelineListener`, `PipelineKey`, `PipelineResult`,
  `StepStatus`, `StepKind` (SYNC/IO/CPU) form a DAG scheduler with typed
  cross-step state and an observer interface. The CLI couples to the engine
  *only* through `PipelineListener` — `ProgressBarListener`, `VerboseListener`,
  `NdjsonListener`, `EventLogListener`, `SilentListener` all live in
  `cli/run/`. **This is the seam the plugin SPI should reuse, not replace.**
- **Core modules have no picocli imports.** `core`, `io`, `resolver`,
  `toolchain`, `engine` are framework-clean.
- **The child-JVM isolation goal is already met.** Heavy/conflicting deps
  (JGit, Jib + Guava + Protobuf, BouncyCastle, sigstore-java, Jackson, the Kotlin
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
| Test-time `-Djk.*.plugin.jar` plumbing | Repeated per runner in 3 build files | ~21 lines |

There is **no `Plugin` or `Runner` interface, no shared protocol module, no
shared launcher.** Adding a ninth runner today means copying all seven rows
above.

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
│  • terminal ownership + TUI (PipelineListener → progress bars)    │
│  • `jk tool` / `jkx` ephemeral tool runs                     │
│  • resolves + launches the Workspace Host                    │
└───────────────┬─────────────────────────────────────────────┘
                │  protocol over stdio (typed events)
                ▼
┌─────────────────────────────────────────────────────────────┐
│  Workspace Host  (JVM on the project-pinned JDK)             │
│  ONE per workspace; runs modules in parallel on threads.     │
│  • owns the Pipeline/Step scheduler (today's core/run)          │
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
  isolation, one JVM per workspace." Workspace modules share that JVM and the
  warm CAS/action-cache/resolver state.
- **`isolation = process` is the escape hatch**, not the default — used only
  when a plugin's transitive deps would poison the Host (e.g. two plugins
  needing incompatible Guava). Same protocol, just over stdio instead of an
  in-process classloader boundary. This subsumes every runner that exists today.
- **Host lifetime is one-shot — no daemon, by design** (see §3.8). The Host
  starts, runs the workspace's Pipeline, exits. This is a deliberate departure from
  Gradle's daemon: it keeps the model simple (no lifecycle, staleness, IPC, or
  `--stop` surface) and the warm-JVM upside a daemon buys shrinks as AOT /
  Project Leyden land. Per-invocation startup is the cost we accept for it.

### 3.2 Module map (current)

```
jk/
├── kernel/                      # universal capabilities — no plugin deps
│   ├── model/        ✓ done     # Pipeline/Step/PipelineListener, Dependency/Coordinate
│   │                            # Zero external deps (JDK + Lombok + JSpecify)
│   ├── core/         ✓ done     # TOML config parser, lockfile, layout, deny policy
│   │                            # Depends on :model + tomlj
│   ├── io/           ✓ done     # HTTP, CAS, repo, forge — depends on :core
│   ├── resolver/     ✓ done     # PubGrub solver, version coercion
│   ├── toolchain/    ✓ done     # JDK manager, tools, Gradle/Maven importers
│   └── host/         ✓ done     # Workspace Host JVM, PluginLoader, BuildPipelines,
│                                # action cache, WorkerProcess/WorkerJar registry
│
├── plugin-api/       ✓ done     # Plugin SPI + HostEvent wire codec
│                                # Depends on :model only
│
├── plugins/                     # first-party plugins, shipped with jk
│   ├── java-compiler/  ✓ done  # in-process via PluginLoader (URLClassLoader)
│   ├── kotlin-compiler/ ✓ done # in-process via PluginLoader
│   ├── test-runner/   ✓ done   # in-process (pull-mode via PluginLoader.converse)
│   ├── auditor/       ✓ done   # isolation=process (stays forked)
│   ├── publisher/     ✓ done   # isolation=process (BouncyCastle)
│   ├── image-builder/ ✓ done   # isolation=process (Jib + Guava)
│   ├── compat-bridge/ ✓ done   # isolation=process; maven+gradle import in one module
│   │                            # (future: split into maven-bridge + gradle-bridge)
│   └── git-client/    ✓ done   # isolation=process (JGit)
│
└── cli/              ✓ done     # GraalVM native image; own arg parser + TUI
```

### 3.3 The Plugin SPI (`:plugin-api`)

A plugin is a jar that exposes one `Plugin` via `ServiceLoader`
(`META-INF/services/build.jumpkick.plugin.Plugin`) — no hand-maintained registry.
Sketch (illustrative, not final):

```java
public interface Plugin {
    PluginManifest manifest();          // id, version, capabilities, isolation hint
    void register(PluginContext ctx);   // contribute pipelines/steps/commands
}

public interface PluginContext {
    Workspace workspace();              // read-only domain model
    Services services();                // CAS, ActionCache, Http, Resolver, Clock
    EventSink events();                 // structured progress — never System.out
    void contribute(PipelineContribution c);// steps this plugin fulfills
    void addCommand(Command command);   // optional: plugins may add commands
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

- **`plugin-api` depends only on `:model`** (so plugins see `Pipeline`, `Step`,
  `Coordinate`, `Dependency`, …) plus JDK/Lombok/JSpecify. It is the *only*
  thing a third-party plugin compiles against.
- **Plugins contribute Steps, not whole pipelines.** They hand the Host
  `Step` objects (with `requires`, `kind`, `ticks`, a body that takes
  `StepContext`). The Host merges contributions from all plugins for a command
  into one `Pipeline` DAG — exactly how `BuildPipelines` composes steps today, but
  the step bodies now come from plugins instead of from `runtime/`.
- **Plugins never touch the terminal.** They report through `StepContext` /
  `EventSink` (`progress`, `label`, `output`, `warn`, `error`). The CLI's
  `PipelineListener` implementations render. This is already true of `core/run`;
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
    // Declares the Pipelines/Steps it contributes; the Host merges them into one
    // pipeline. Exposes everything the progressbar TUI needs (already modeled
    // by PipelineListener/PipelineView/PipelineResult).
    public abstract List<PipelineContribution> pipelines(Invocation in);
}

public abstract class UiCommand implements CliCommand {
    // Interactive session (Wizard/TUI today; GUI/IntelliJ tomorrow).
    // Oriented around steps + prompts rather than a batch pipeline.
    public abstract void run(InteractiveSession session);
}
```

- `BuildCommand` covers `build`, `compile`, `test`, `clean`, `image`, `native`,
  `publish`, `audit`, … — everything that drives the progress TUI. Most of its
  body becomes "ask the Host to run the Pipeline assembled from plugin
  contributions."
- `UiCommand` covers `new` / `init` / `activate` — the Wizard-driven commands. The
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

- **Framing:** single-prefix NDJSON (keep NDJSON for debuggability; one prefix
  `##JKH:`, one parser — both `Ndjson.java` copies collapsed into one in
  `plugin-api`).
- **Messages map to the existing event vocabulary:** `steps`, `pipelineStart`,
  `stepStart`, `progress`, `tickUpdate`, `label`, `output`, `warn`, `error`,
  `stepFinish`, `pipelineFinish`, `exit` — i.e. the `PipelineListener` interface
  serialized. The Host deserializes a child plugin's stream straight into
  `PipelineListener` calls, so process-mode and in-process plugins are
  indistinguishable to the TUI.
- **One codec, one `WorkerLauncher`** replaces the six `*WorkerSetup` classes,
  the `JkWorkerSync.WORKERS` list, and the per-runner `main()` boilerplate. A
  plugin's process-mode entry point is `PluginHostMain` — zero protocol/launch
  code per plugin.

### 3.7 Plugin packaging & build wiring

- One Gradle convention plugin (`jk.plugin-conventions`) replaces the
  copy-pasted `maven-publish` + fat-jar + `installLocalCas` + `writeXxxSha`
  blocks. Applying it to a module under `plugins/` gives it the manifest
  resource, the CAS side-load task, and the SHA emission automatically.
- The Host reads available plugins from the `WorkerJar` enum (one registry, no
  hand-edited list), generated SHA resources embedded in the jar.

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

Note this is orthogonal to `jk tool run` / `jkx`: those run published CLIs the
user explicitly invoked — arbitrary code by definition, user-initiated, and not
loaded into the Host. They keep their current model.

---

## 4. What moves where (status)

| Today | Target | Status |
|---|---|---|
| `core/model`, `core/run` | `kernel/model` | ✓ Done |
| `core/{util,credential,publish,image}` | `kernel/model` | ✓ Done |
| `core/{config,lock,library,deny,layout,audit}` | `kernel/core` | ✓ Done |
| `io/*` | `kernel/io` | ✓ Done |
| `resolver/*` | `kernel/resolver` | ✓ Done |
| `toolchain/*` | `kernel/toolchain` | ✓ Done |
| `engine/task/*`, `runtime/*` | `kernel/host` | ✓ Done (Phase 5) |
| `engine/compile/*`, `engine/test/*` | absorbed into `kernel/host` + plugins | ✓ Done (Phase 5) |
| `supply-chain/*`, `image/`, `compat/` | — | ✓ Deleted (Phase 5) |
| `engine`, `runtime` | — | ✓ Deleted (Phase 5) |
| `audit-runner` | `plugins/auditor` | ✓ Done (renamed) |
| `publish-runner` | `plugins/publisher` | ✓ Done (renamed) |
| `image-runner` | `plugins/image-builder` | ✓ Done (renamed) |
| `compat-runner` | `plugins/compat-bridge` | ✓ Done (renamed; split into maven+gradle deferred) |
| `git-runner` | `plugins/git-client` | ✓ Done (renamed) |
| `java-compiler`, `kotlin-compiler`, `test-runner` | `plugins/*` | ✓ Done (moved) |
| `cli/command/*` (picocli) | own renderer + `CliCommand` impls | ✓ Done (Phase 3) |
| `Ndjson.java` ×2, `##JK*:` markers ×8 | `plugin-api` protocol codec | ✓ Done (Phase 0–1) |
| `*WorkerSetup` ×6, `JkWorkerSync.WORKERS` | `WorkerJar` enum | ✓ Done (Phase 2) |
| `compat-bridge` → `maven-bridge` + `gradle-bridge` | `plugins/maven-bridge`, `plugins/gradle-bridge` | Deferred (Phase 7+) |

---

## 5. Removing picocli — done (Phase 3)

picocli was confined to `cli/` (67 files) and removed in Phase 3. jk now uses
its own recursive-descent arg parser over `Command.parameters()/options()` and
its own `HelpRenderer`. The native-image reflection config for picocli and the
`picocli-codegen` annotation processor were both dropped.

---

## 6. DRY wins delivered

- Deleted 5 of 6 `*WorkerSetup` classes → `WorkerJar` enum.
- Deleted the `JkWorkerSync.WORKERS` hand-list → `WorkerJar` enum + SHA resources.
- Deleted both `Ndjson.java` copies → one codec in `plugin-api`.
- Deleted 8 `writeXxxWorkerSha` blocks → one `jk.plugin-conventions` plugin.
- Deleted the repeated `-Djk.*.plugin.jar` test plumbing → one block per module.
- Collapsed all per-runner `main()`+spec-parse+JSON-escape impls → `PluginHostMain`.
- Deleted `supply-chain`, `image`, `compat`, `engine`, `runtime` as standalone modules.

---

## 7. Migration plan (phased — keep self-hosting & native build green at every step)

- **Phase 0** ✓ — Extract `plugin-api` + protocol codec, no behaviour change.
- **Phase 1** ✓ — `WorkerProcess` / `PluginHostMain` unified launcher; `WorkerJar` registry.
- **Phase 2** ✓ — `jk.plugin-conventions` convention plugin; SHA emission per-module.
- **Phase 3** ✓ — Own arg parser + help renderer; picocli deleted.
- **Phase 4** ✓ — Workspace Host (`HostMain`, `HostDispatch`, `HostLauncher`, `StreamingPipelineListener`, `ReceivingPipelineListener`); progress bar upgrade via `steps` event.
- **Phase 5** ✓ — Module reorg: `engine`/`runtime` → `kernel/host`; vestigial modules deleted; module moves + renames.
- **Phase 6** ✓ — `PluginLoader` in-process dispatch (URLClassLoader isolation for friendly plugins); `PluginManifest.isolation` field.
- **Phase 7** — Third-party plugin resolution from `[plugins]` in `jk.toml`: resolve coordinates → CAS, pin SHA in `jk.lock`, forced `isolation=process`. Prerequisite: `plugin-api` exposes `Pipeline`/`Step` to plugins via `:model` dependency (done).

Each step is independently shippable and leaves the tree green.

---

## 8. Settled decisions

All foundational decisions are settled.

1. **Host lifetime — one-shot, no daemon.** The Host starts, runs the workspace
   Pipeline, exits. Deliberate differentiation from Gradle; keeps the model simple
   and the warm-JVM upside shrinks as AOT/Leyden land. (§3.1)
2. **Plugins always run on a real JDK, never inside the native binary.** The
   native CLI cannot host plugins (GraalVM closes the world at build time); only
   a JVM Host can. The Host runs on the project-pinned JDK jk already installs.
   jk *never* attempts to load plugin bytecode into the native binary — the
   CLI→Host hop is always a process boundary. (§3.1)
3. **Third-party trust posture — Posture A for v1 (§3.8).** Pin+verify every
   plugin, load only declared plugins, force `isolation=process` for third-party
   (preserving the sandbox seam). Capability declaration/audit (Posture B) and
   OS sandboxing (Posture C) are deferred.
4. **`Command` and friends live in `model`.** `Command`, `CliCommand`,
   `BuildCommand`, `UiCommand`, `Parameter`, `Option`, `Usage` are domain types
   in `kernel/model`, alongside `Pipeline`/`Step` — one SPI module, shared by the
   CLI and any future front-end.
5. **Wire protocol — NDJSON.** Single `##JKH:` prefix, one codec in `plugin-api`.
   Binary framing is not planned.

---

## 9. Risks

- **Reproducibility regression.** Moving code between modules changes jar
  contents/order. Mitigated by `jk verify` gating every step.
- **Native-image breakage.** Reflection/resource config is currently
  per-subsystem under `META-INF/native-image/`. Module merges must carry that
  config along. The CLI shrinks (picocli gone, plugin bytecode never reachable),
  which should net-improve size and cold start.
- **In-process classloader isolation leaks.** Two plugins with conflicting deps
  in-process is the classic failure mode. Mitigated by `isolation=process` as an
  always-correct fallback and by parenting plugin loaders to an API-only loader.
- **Over-promising plugin security.** Classloader/process isolation is not a
  security boundary (§3.8). Docs and `jk` output must not imply third-party
  plugins are sandboxed when they run with full user privileges.

---

*Companion design specs (protocol schema, plugin manifest format) to be added
under `docs/` as Phase 7 lands.*
