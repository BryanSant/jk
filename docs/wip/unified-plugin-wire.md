# Unified plugin wire protocol

**Goal:** one engine↔plugin protocol for every forked plugin, replacing the 9 bespoke
ones (build-plugin harness JSONL + javac/kotlinc/test/audit/format/compat/publish/image
hand-rolled specs). Breaking changes welcome; no backward compatibility. Correctness and a
single, superset protocol are the objective. (CLI/client plugin support is out of scope.)

## Transport & envelope

- A plugin is forked `java <Main> <spec-file>`. It reads an **JSONL spec file** (one
  `{"t":…}` object per line) and writes **JSONL reply lines** to stdout, each prefixed with
  the plugin's `##JK<XX>:` marker. Per-plugin prefixes stay (stdout disambiguation) and are
  normalized to the `##JK<XX>:` shape — **test-runner `##JKT:` → `##JKT:`**.
- **One discriminator: `t`** on every line (test-runner's `e` is converted to `t`).
- Kill the two other spec encodings: KEY-value text (javac/kotlinc/audit/compat/publish/
  image) and tab records (formatter). Everything is JSONL.
- Terminal marker: every op ends with `{"t":"done","exit":<code>}`. A typed payload (image
  ref, compile status, …) rides a `{"t":"result",…}` line immediately before `done`.
- Bidirectional (test pull-mode only): the plugin emits `{"t":"test","event":"ready"}` and
  the engine writes `RUN <fqcn>` / `DONE` on the plugin's **stdin** — the one two-way op,
  preserved via the existing `converse` loop.

## Ops (`{"t":"op","op":<name>,"name":<step/command>?,"plugin":<id>}`)

| op | plugin(s) | notes |
|---|---|---|
| `describe` | build-plugins | cached declaration replay (unchanged) |
| `run-step` | build-plugins | one step body; `name` = step |
| `package` | build-plugins | main-artifact packager |
| `command` | build-plugins, audit, format, compat | plugin command; `name` = command |
| `compile` | java-compiler, kotlin-compiler | |
| `test` | test-runner | `config` flags select one-shot / list-only / pull |
| `image` | image-builder | terminal goal |
| `publish` | publisher | terminal goal |
| `run` | (future dev runner) | terminal goal |

Audit/format/compat are `command` ops (zero-phase command plugins). Compat's `import` /
`provision` become the command `name`.

## Spec-line vocabulary (superset; each op uses its subset)

| `t` | fields | used by |
|---|---|---|
| `op` | `op, name?, plugin` | all |
| `config` | `key, kind∈{string,bool,int,list}, value|values` | all scalar settings |
| `project` | `group,name,version,javaRelease,mainClass,nativeDeclared,kotlin` | build, publish, image, test |
| `manifest-attr` | `key,value` | build/package |
| `layout` | `classesDir, sourceOutput?, moduleDir?, scratch?, workdir?, snapshotDir?` | build, compile |
| `java-home` | `path` | build, compile, image |
| `artifact` | `path` | package, image, publish |
| `cp` | `path, role∈{compile,processor,friend,runtime}` (default compile) | compile, build |
| `entry` | `file, path, snapshot, container?` | package, image (runtime closure) |
| `source` | `path` | compile |
| `step-output` | `name, dir` | build |
| `extra` | `name, path` | build, publish, image |
| `secret` | `key, value` | publish (creds/passphrase), package |
| `command-args` | `values[]` | command |
| `arg` | `value` | compile (raw javac/kotlinc passthrough) |
| `compiler-plugin` | `id, jar, options[]` | kotlin-compiler |

Scalar settings that were bespoke keys become `config` entries: `release`, `jvmTarget`,
`moduleName`, `languageVersion`, `apiVersion` (compile); `base,user,registry,tag,mode,
dockerExecutable,ports,env,labels,platforms,tarball` (image); `repoUrl,repoAuthType,dryRun,
slsa,sbom,signGpg,signSigstore,objectStore*` (publish, with creds/passphrase as `secret`);
`mode(apply/check),java*,kotlin*,optimizeImports,rewriteConfig,cacheDir` (format);
`lockfile,batchUrl,vulnsUrl` (audit). Unknown `t` / unknown `config.kind` are ignored
(forward-compat).

## Reply-line vocabulary (superset; each op emits its subset)

| `t` | fields | emitted by |
|---|---|---|
| `label` | `text` | any (progress label) |
| `progress` | `done, total?` | any (numeric) |
| `out` | `line` | command (user-facing stdout) |
| `diagnostic` | `sev, file?, line?, col?, msg` | compile, format — **structured (adds file/line/col that javac/kotlinc drop today)** |
| `provenance` | `gen, src[]` | java-compiler (AP incremental) |
| `test` | `event∈{discovered,discovery-total,started,finished,skipped,dynamic,ready,plan-finished}, id?,display?,parent?,type?,source?,status?,durationMs?,throwable?{class,message,stack},reason?,classes?,tests?,plugin?` | test-runner |
| `step` / `packager` / `command` | describe declarations (unchanged) | describe |
| `finding` | `module,version,id,severity,summary` | audit |
| `file` | `path, status∈{changed,clean,error,skipped}, msg?` | format |
| `wrote` | `path` | compat import |
| `result` | `ok` + op-specific: image `{ref?|tarball?,daemon?}`; publish `{files,dryRun?}`; compile `{status}`; compat-provision `{bin,version,source}`; import `{warnings}` | terminal ops |
| `error` | `code, message` | any failure |
| `done` | `exit` | all (terminal) |

## Deliberate cleanups (breaking, welcome)

- **Structured diagnostics**: `diagnostic` carries file/line/col; javac (`InProcessJavac.Diag`)
  and kotlinc (`KcLogger`) are upgraded to populate them where the underlying API exposes
  them, instead of the current sev+msg-only.
- **Drop fields the engine ignores today**: audit `result.total`, format `done` counts (recomputed
  from `file` events), image `tarball`/`daemon`/`ok` (engine knows the tarball path; keep `ref`
  + a boolean via `result`), publish `dry_run`/`ok`, compat `home`/`ok`, test `report`/
  `plan_started`. Keep only what a consumer reads.
- **One terminal shape**: `result` (typed) + `done{exit}` everywhere, replacing each plugin's
  ad-hoc terminal line.

## Foundation & migration order

1. **Foundation** (`plugin-api` `build.jumpkick.plugin.protocol`): protocol constants (types +
   field names + op names), a `WorkerSpec` reader (generalize `BuildPluginHarness.Spec`), a
   `SpecWriter` (generalize `PluginBuild.SpecWriter`), and the engine-side `PluginClient` reply
   vocabulary. Generalize `BuildPluginHarness` → the one plugin harness dispatching all ops.
   Delete back-compat ctors (`StepDecl`, `Declarations`, `PackageIo.RuntimeEntry`).
2. **Command/terminal plugins** (each its own verified commit): audit, format, compat, image,
   publish (image/publish = the correct redo of S6).
3. **Compilers**: java-compiler, kotlin-compiler (JSONL spec, structured diagnostics).
4. **Test-runner** (last, most care): `e`→`t`, prefix `##JKT:`, `test` event envelope, pull loop
   preserved.

Each step verified by unit tests where possible; the compile/test/image/publish runtime paths
need an end-to-end run to confirm.
