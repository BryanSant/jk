# Incremental Java Compilation

**Status:** In progress — phased. Phases 1 (ASM dirty-set, no worker) and 3
(classpath ABI snapshots) are DONE; phase 2 (source-generating-AP incrementality)
has its engine core done through slice 3b (worker + provenance + arity gate),
pending pipeline activation (PROCESSOR-scope plumbing). This doc defines how jk
gains a robust incremental Java compiler, modelled on what we learned building the
Kotlin incremental worker (Build Tools API). javac always performs the actual
compilation; the incremental layer only decides **which sources to hand javac**
and **carries the rest over**.

**Scope:** the compile-main / compile-test Java path in `BuildPipeline` (and the
`IncrementalCompiler` seam it already exposes). Out of scope: replacing javac,
cross-module workspace incrementality, and the existing Kotlin worker (covered
in `kotlin-incremental-bta-decision`, the project memory).

---

## 1. Why this is mostly "fill in a dormant seam"

jk already ships the orchestration for incremental Java; it's parked behind a
no-op planner:

- `dev.jkbuild.compile.incremental.IncrementalCompiler` — the seam:
  `plan(PlanRequest{request, prior, stateDir}) → CompilePlan{recompile, carryOver, dropped}`
  and `attribute(plan, outputDir) → UnitOutputs` (source → produced `.class`).
- `dev.jkbuild.compile.incremental.FullRebuildCompiler` — the **only** impl today
  (recompiles everything, carries nothing). No `ServiceLoader` registration, so
  `IncrementalCompilers.resolve()` falls back to it.
- `dev.jkbuild.task.IncrementalCompile.run(...)` — the orchestrator, **already
  implemented**: action-key fast path → materialise carried-over classes from the
  CAS → recompile only `plan.recompile()` with javac → `attribute()` → store the
  result with a per-source unit grouping.

So the machinery for "compile a subset, carry the rest over from CAS, record
per-source outputs" exists and is exercised by the Kotlin/action-cache tests.
What's missing is a **real planner**: the dependency analysis + dirty-set
computation. That, plus the execution vehicle to run it, is this project.

`org.ow2.asm:asm` + `asm-tree` are **already** engine dependencies. javac runs
today via `SubprocessJavacStrategy`.

---

## 2. What we learned from Kotlin (BTA), mapped to Java

| BTA concept | Java equivalent |
|---|---|
| Persistent IC working dir (`caches-jvm/`) | the per-task `stateDir` already passed to `plan()`: dependency graph + ABI hashes + source→class map |
| `SourcesChanges.ToBeCalculated` (compiler self-tracks edits) | jk diffs current source content-hashes vs the prior action-record input snapshot (`PriorBuild.inputs`) |
| Classpath ABI snapshots | ASM-extracted ABI snapshot per classpath jar (mirror the snapshot cache built for Kotlin) |
| ABI-level granularity (body change ≠ dependent recompile) | per-class **ABI hash** (public/protected signature only) |
| Minimal recompile + carry the rest | `IncrementalCompile.run` already carries over from CAS |
| In-process compiler under a worker JVM | the `jk-java-compiler` worker (see §4) |

**The crucial inversion vs Kotlin:** the Kotlin compiler *provides* its
incremental engine (BTA), and needed a worker only because it isn't in the JDK.
javac *is* in the project's JDK, but provides *no* incremental engine — so the
new work is the dirty-set analysis, not "driving a compiler."

---

## 3. The design is one hybrid, not two competing options

Earlier discussion framed an "ASM, no worker" Option A vs a "JavacTask worker"
Option B. The production-grade answer (this is essentially Gradle's design) is a
**single hybrid**, and the pieces *layer*:

- **Execution:** javac in-process in a worker JVM under the project's JDK.
- **Dependency graph + ABI hashing:** ASM bytecode analysis of the produced
  classes — the durable analysis engine, reused regardless of execution.
- **Source-generating-AP incrementality:** the compiler's `Filer` /
  `RoundEnvironment` (only observable in-process).

The ASM analysis engine is a **permanent component**, not a throwaway. The only
discarded idea is "subprocess javac, no worker" as the *execution* choice.
~70% of the work is **option-agnostic and built once**:

- change detection (content-hash diff vs prior snapshot),
- ABI hashing,
- the dirty-set closure (`plan()`),
- carry-over + `attribute()` + store (already built),
- classpath-jar ABI snapshots (necessarily ASM — there is no source for deps).

---

## 4. Execution: the `jk-java-compiler` worker

A child-JVM worker that runs **under the project's JDK** and invokes javac
in-process via `javax.tools.ToolProvider.getSystemJavaCompiler()` /
`com.sun.source.util.JavacTask`. This mirrors `:kotlin-compiler` exactly and
reuses the now-proven worker infrastructure:

- Module `:java-compiler`, `compileOnly` nothing exotic (javac is in the JDK);
  manifest `Main-Class`, a line-oriented spec, `##JKJC:` NDJSON back to jk.
- Located via **CAS-by-SHA** with a `-Djk.java.worker.jar` override
  (`JkWorkerSync` already syncs from `~/.m2` after `publishToMavenLocal`; add the
  third artifact). `installLocalCas` for the dev loop.

**Why in-process javac (a worker), not subprocess:**

1. `Filer`/`RoundEnvironment` for source-generating-AP incrementality (§6) — only
   available in-process.
2. Warm compiler across recompiles (no JVM spawn per build).
3. Native-image jk has no JDK, so an in-process compiler must run somewhere with a
   JDK — i.e. a worker under the project JDK (same reason the Kotlin worker exists).

The dependency graph + ABI can still be derived by **ASM bytecode analysis of the
output** inside the worker (Gradle does exactly this even with in-process javac);
a `TaskListener`-based source-level graph is a later precision option, not
required.

---

## 5. The planner algorithm

State persisted in `stateDir` (per compile task): `source → classes`, a per-class
**ABI hash**, and the **type dependency graph** (class → classes it references).

**`plan(PlanRequest{request, prior, stateDir})`:**

1. Load persisted state. If absent (first build) or `--force` → full rebuild.
2. Compute changed sources from `prior` input snapshot: modified / added / removed.
3. Seed the dirty set with modified+added sources' classes. For each class whose
   **ABI hash changed**, add its **reverse-dependency closure** (walk the graph to
   a fixed point) — computed conservatively up front so a single javac pass
   suffices. Removed sources → `dropped`.
4. Constants: a class that *defines* a `static final` compile-time constant, when
   changed, additionally pulls in everything that could reference it (inlined
   constants leave no bytecode edge — the one unavoidable bytecode-analysis blind
   spot, shared with Gradle).
5. Return `CompilePlan{recompile, carryOver, dropped}`.

**Single-pass vs precise — an orchestration choice (resolved during phase 1):**
the existing `IncrementalCompile.run` calls `plan()` **once**, compiles once,
attributes once. That single-pass shape can only support a *conservative* plan:
without compiling first you can't know whether a changed source's **ABI**
actually changed, so you'd have to recompile a changed source's *entire* reverse
closure — over-recompiling on body-only edits (the common case), which guts the
benefit. Precise incremental (body-only edit → recompile just that file) needs
the **compile → diff ABI → expand dirty set → repeat to fixed point** loop. Two
ways to get it:
- **(b) Iterate the orchestrator:** have `IncrementalCompile.run` loop —
  recompile the directly-changed sources, let `attribute()` compare new vs stored
  ABI, ask the planner for the next wave, repeat. Keeps the `plan()`/`attribute()`
  seam but changes its contract to iterative.
- **(c) Java-specific orchestrator:** a `JavaIncrementalCompile.run` (sibling to
  `KotlinCompile.run`) owning the loop over `JavacDriver` + the ASM analyzer +
  `ActionCache`/CAS, not using the single-pass seam.

Recommended: **(c)** — it mirrors the proven `KotlinCompile.run` shape and keeps
the multi-pass logic in one place; the `IncrementalCompiler` seam stays as the
(now clearly insufficient for precision) single-pass abstraction or is retired.

**Carry-over + store (reusable):** linking carried-over `.class` from CAS,
dropping removed ones, snapshotting outputs, and recording per-source units is
already implemented in `IncrementalCompile.run` / `CasPrewriter` / `ActionCache`
and is reused by whichever orchestration we pick.

**`attribute(plan, outputDir)`:** map produced `.class` → source (bytecode
`SourceFile` attribute); recompute the dep graph + ABI hashes for recompiled
classes; merge with carried-over; persist to `stateDir`. Returns the source→output
grouping jk records in the action cache.

---

## 6. Annotations: the Lombok majority vs the source-generating tail

Spring-style apps use annotations *aggressively*, but the overwhelming majority
(`@Service`, `@RestController`, `@Autowired`, `@Transactional`, …) are
**`@Retention(RUNTIME)` metadata** read by the container at startup. To the
compiler they are ordinary **type references** — recorded in the `.class`, seen by
ASM, tracked as dependencies. They have **zero special impact** on incremental
compilation.

Compile-time **annotation processors** split into two cases:

### Lombok (and other AST-mutating processors) — handled by the core
Lombok generates getters/builders/etc. **into the bytecode via AST mutation — no
separate `.java` files.** Consequences:

- The generated modules live in `Foo.class` → ASM **sees** them → they're in
  `Foo`'s ABI hash.
- Dependents calling `foo.getName()` carry an `invokevirtual Foo.getName` →
  ASM captures the edge.
- Edit `Foo` → recompile `Foo` (Lombok re-runs on it) → ABI changes → dependents
  recompile. Edit a leaf → recompile just it, `Foo` carried over.

So **Lombok is fully incremental with the ASM core**, provided javac runs Lombok
on each recompiled subset (it does — Lombok is on the processor path every
invocation) and carried-over classes are on the classpath as compiled `.class`
(they are). This corrects an earlier over-pessimistic "any processor → full
rebuild" stance. `lombok.config` is treated as a global compile input (a change
forces a full rebuild, like a compiler-flag change).

### Source-file generators — need the Filer layer
**MapStruct, Querydsl, Dagger, Immutables/AutoValue, AutoService** emit new
`.java` that participates in compilation. The core can't know which generated
sources to regenerate when an originating source changes. This needs the JSR-269
**isolating / aggregating** model (Gradle's): track each generated file's
originating element(s) via the `Filer`, re-run only the affected ones (isolating),
or re-run broadly (aggregating / undeclared).

**Detection (cheap, even without the Filer):** a generated-source class appears in
the output as a `.class` whose `SourceFile` attribute names a file **not in our
input set** ("orphan"). Zero orphans (Lombok, plain Java) → fully core-incremental.
Orphans present → engage Filer tracking, or conservatively recompile the AP scope
until that lands.

jk's own CLI uses `picocli-codegen` (generates reflection config) — a real
in-tree example of a processor whose handling we must get right.

---

## 7. Integration points (all already present)

- **Seam:** register the new impl so `IncrementalCompilers.resolve()` selects it
  (`ServiceLoader` / `jk.incremental-compiler` property), replacing the
  `FullRebuildCompiler` fallback.
- **Orchestration / cache:** `IncrementalCompile.run`, `ActionKey.forJavac`,
  `ActionCache`, `Cas`, `CasPrewriter` — reused unchanged.
- **Front tier:** the `.jstamp` `FreshnessStamp` still short-circuits the
  unchanged case before any analysis (3-tier model, same as Kotlin:
  `.jstamp` → action-cache CAS restore → incremental recompile).
- **Classpath ABI snapshots:** reuse the snapshot-cache pattern from
  `KotlinBtaResolver`/the Kotlin worker — ASM-extract each dependency jar's public
  API so a dep change recompiles only affected sources rather than everything.
- **Worker plumbing:** `JkWorkerSync`, `installLocalCas`, the `writeWorkerSha`
  resource pattern, the `-D…worker.jar` override — extend to a third worker.

---

## 8. Hard parts / fallbacks (explicit)

- **First build / no state / `--force`:** full compile (exactly like BTA build 1).
- **Compile-time constants:** conservative recompile of referencers on a
  constant-defining class change (bytecode blind spot).
- **Source-generating APs:** orphan-class detection → Filer isolating/aggregating
  tracking; conservative full-AP-scope recompile until that's implemented.
- **`lombok.config` / annotation-processor-path / javac-args changes:** global
  inputs → full rebuild (already part of the action key).
- **Cross-language modules:** a Java↔Kotlin module wants each side's ABI snapshot
  to feed the other's dirty set. Both workers producing comparable ABI snapshots
  makes this composable later; not in initial scope.

---

## 9. Phasing

1. **Shared ASM core — DONE (no worker needed).** `ClassAbi` + `ClassDependencies`
   (ASM) and `dev.jkbuild.task.JavaIncrementalCompile` (the precise multi-pass
   orchestrator chosen in §5 — a sibling to `KotlinCompile`, not the single-pass
   seam) using the **existing subprocess javac**; the analysis runs in jk's
   process on the output bytecode, so phase 1 needs no worker. Wired into
   `compile-java`. Delivers precise incremental for plain Java **and Lombok**
   (Lombok output is in-bytecode → ASM sees it). Constants handled conservatively.
   **Source removals are incremental** (DONE): a removed source's classes are
   deleted and the referencers of the now-vanished classes recompile (surfacing any
   dangling reference); a removed *constant holder* — persisted via `ClassFacts.constants`
   — falls back to recompiling the remaining sources, since its inliners have no
   bytecode edge. The worker arrives in phase 2 only because `Filer` incrementality
   requires in-process javac.
2. **Source-generating-AP incrementality.** `Filer`/`RoundEnvironment`
   isolating/aggregating tracking, gated on orphan-class detection. Unlocks
   MapStruct/Querydsl/Dagger/etc. Nothing from phase 1 is rebuilt.
   - **Slice 1 — DONE.** `:java-compiler` module + `InProcessJavac`: runs javac
     in-process via `JavacTask`, wrapping each processor's `Filer` to capture
     generated-file → originating-source provenance (resolved via `Trees`).
     Validated with a real processor. The hard, novel core.
   - **Slice 2 — DONE.** `JavaCompilerWorker` (main + line-oriented spec +
     `##JKJC:` NDJSON: diagnostics / provenance / result), ServiceLoader-discovering
     processors from the processor path. Packaged like the other workers
     (`maven-publish` `dev.jkbuild:jk-java-compiler`, `installLocalCas`, runtime
     `writeJavaWorkerSha`, `JkWorkerSync` 3rd entry, `JavaWorkerSetup` locator —
     no impl closure needed). `jk sync` pulls it from `~/.m2`.
   - **Slice 3a — DONE.** `WorkerJavac` launcher (engine): runs the worker
     subprocess, parses the `##JKJC:` NDJSON into `(success, diagnostics,
     generated→originating)`. Validated end-to-end against the real worker jar.
   - **Slice 3b — ENGINE CORE DONE.** `JavaIncrementalCompile` integration. A
     private `Compiler` seam routes javac (subprocess vs `WorkerJavac`), selected by
     a per-project `java-ap.json` flag `{sourceGenAps, isolating}`. **Detection** is
     the orphan signal: the plain-javac build sees a generated `.class` with no
     provenance → flips `sourceGenAps` on → the *next* build routes through the
     worker (which captures provenance). Lombok (in-bytecode, no generated `.java`)
     never trips it, so it never needs the worker. **Attribution + arity gate:**
     `analyze()` maps a generated `.class` → generated `.java` (SourceFile attr) →
     originating source via provenance; exactly-1 originator → isolating (fold into
     the dirty-set/ABI graph); >1 → aggregating → FULL via the worker (a subset
     compile would stale the aggregate). Worker mode goes incremental only when the
     prior build was isolating; carried-over generated classes are recognised via
     prior-units paths (not treated as orphans). Conservative-on-doubt — worst case
     "less incremental," never stale. Tested against the real worker
     (`JavaApIncrementalCompileTest`): isolating detect→establish→incremental (proven
     via an output sentinel) + never-stale; aggregating classified + stays full.
     **Pipeline activation — DONE.** The PROCESSOR scope (previously a stub, dropped
     by `LockOrchestrator.SCOPES`) now resolves + tags lock packages; `CompileRequest`
     carries a `processorPath`, `SubprocessJavacStrategy` emits `-processorpath`, and
     the action key + `.jstamp` + `canIncrement` all track processor changes.
     `compile-java` resolves a `PROCESSOR_CP`, sets it on the request, and — when
     non-empty — builds an `ApSetup(locateWorkerJar(cas) /*null-safe*/, genDir)` for
     `JavaIncrementalCompile.run`. Non-AP builds are unaffected (empty processor path
     → no `ApSetup`). Not yet exercised at the dist level with a real processor (jk's
     path-deps are local *projects*, not arbitrary jars); validated at the engine seam
     against the real worker. `compile-test` is still non-AP.
3. **Classpath ABI snapshots — DONE.** `JavaClasspathAbi` snapshots each cp
   entry's per-class ABI (+ inlinable-constant flag); per-jar cached by CAS sha,
   directory entries scanned fresh (so a mixed module's Kotlin output dir is
   diffed → Java reacts to Kotlin ABI changes). `JavaIncrementalCompile` diffs the
   prior union (persisted in `stateDir`) against the current and seeds
   `referencers(changedDepClasses)` into the dirty set — a dependency bump
   recompiles only affected sources instead of forcing a full rebuild
   (conservative when a changed dep inlines constants).
4. **Worker packaging.** Publish `jk-java-compiler` to mavenLocal; extend
   `JkWorkerSync`; CAS-by-SHA location; `installLocalCas`.
5. *(optional)* **`TaskListener` source-level dependency graph** if ASM bytecode
   precision proves insufficient (e.g. finer-grained than class-level).

---

## 10. Open questions

- ABI-hash granularity: class-level (simple, slightly over-recompiles) vs
  module-level (precise, more state). Start class-level.
- Worker lifecycle: one-shot per compile vs a persistent daemon (warm javac across
  builds). Start one-shot; daemon is a later perf lever (shared with the Kotlin
  worker's deferred daemon question).
- Test compilation (`compile-test`) reuses the same planner with the main output
  on the classpath — confirm the dirty-set closure spans main→test correctly.
