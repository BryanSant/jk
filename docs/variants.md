# Variants, profiles, and features

jk has three ways to make one project build differently, and they are deliberately not the
same feature. Pick by what changes:

| Concept | Section | Changes | Selection | Analogue |
|---|---|---|---|---|
| **Profiles** | `[profiles.<name>]` | *How* you build: toolchain knobs (javac flags, JVM args) | Optional — `--profile <name>`, `ci` auto-selects on CI | cargo profiles |
| **Features** | `[features]` | *What capabilities* are compiled in: optional dependency sets, additive | Optional — `--features a,b`, defaults apply | cargo features |
| **Variants** | `[variants]` | *Which product* you build: sources, dependencies, plugin config | **Mandatory** per declared dimension (unless a default is set) | Gradle/AGP variants |

The rules of thumb:

- A knob you'd flip on CI but that produces the *same artifact* → **profile**.
- An optional capability a consumer opts into → **feature**.
- Two genuinely different products from one module (demo vs prod app, free vs paid tier,
  debug vs release) → **variant**.

**Anti-pattern: dev/test/prod environments as variants.** An *environment* is a runtime
concern — Spring profiles, config files, environment variables — and the 12-factor posture
is build once, promote the same artifact through every environment. Baking the environment
into the build multiplies artifacts and voids the "what you tested is what you shipped"
guarantee. Reach for variants only when the *products* differ (different application id,
different bundled sources/deps), not when only the runtime configuration does. (Maven allows
profiles to change dependencies and artifacts; that is widely considered its most-misused
feature, and jk deliberately does not import it.)

## Vocabulary

- A **dimension** is an axis: the built-in `build-type`, or a custom one like `contentType`.
- A **value** is one named entry along a dimension: `demo`, `prod`, `release`.
- A **variant** is the resolved combination — one value per dimension: *demoRelease*.

A build selects one value per declared dimension. **A declared dimension without a `default`
must be selected** — `jk build` fails with the dimension's values rather than silently
building "no variant". That exclusivity is what keeps action keys and caches sound. A
selection naming a dimension a module doesn't declare is ignored, so one workspace-wide
selection works across flavored and unflavored modules.

## Declaring variants

```toml
[variants.contentType]            # a custom dimension (name is yours)
default = "demo"                  # optional — omit to make selection mandatory

[variants.contentType.demo]       # one value; the body is an OVERLAY
extra-src = ["src/demo/kotlin"]   # extra source roots for this value

[variants.contentType.demo.dependencies]   # per-value deps (any scope section works)
stub-backend = { group = "com.acme", name = "stub-backend", version = "^1" }

[variants.contentType.demo.android]        # plugin-config overlay, schema-validated
application-id-suffix = ".demo"

[variants.contentType.prod]
extra-src = ["src/prod/kotlin"]

[variants.build-type.release]              # the built-in dimension: attach overlays
[variants.build-type.release.android]
minify  = true
signing = "release"
```

What a value's overlay may contain:

- **`extra-src`** — module-relative source dirs appended to `[build] extra-src` (which also
  works standalone, outside variants). The compiler and KSP see them as ordinary roots.
- **Dependency scope tables** — `dependencies`, `test-dependencies`, etc., with the full
  normal grammar (catalog shorthands, `workspace = true`, git sources).
- **Plugin tables** — key overlays onto that plugin's config, validated against the plugin's
  schema (partial: nothing required, no defaults re-applied). Group *definitions* like
  `[android.signing.<name>]` stay on the plugin table; overlays reference them by name
  (`signing = "release"`).

Deliberately **not** overlayable: `[project]` identity (a variant that changes coordinates is
a different project), repositories, profiles, features, and toolchain flags (those are
profiles' job).

### build-type

`build-type` is a built-in dimension that always exists: `debug` and `release` are valid
values even when undeclared, and the default is `debug`. `jk build --release` is shorthand
for `--variant build-type=release`. Declaring `[variants.build-type.<name>]` attaches
overlays to a built-in value or adds a custom build type.

### Precedence and injected keys

Overlays fold over the base manifest in a fixed order: custom dimensions in declaration
order, then `build-type` last — the build type wins a key conflict. The selected names are
injected into every plugin config as `build-type` and `variant.<dim>`, which is what
`[[packaging.variant]] when = { config = "build-type", equals = "release" }` and plugin code
condition on.

## Selecting variants

```sh
jk build --variant contentType=demo             # one dimension
jk build --release --variant contentType=demo   # build type + dimension
jk build --variant demo                         # bare value: OK when only one custom dimension
jk build --variant contentType=demo,tier=free   # comma-join or repeat the flag
jk run --release                                # every artifact-producing command takes the flags:
jk test --variant contentType=demo              # run, dev, test, compile, image, native, publish,
jk install --variant contentType=prod           # and install (current-project mode)
```

Selections are **workspace-wide**: building from any module applies the same selection to
every workspace sibling, and modules apply only the dimensions they declare. The selection
follows the whole invocation: `jk run --release` on an Android app builds the release AAB
*and* deploys it (the exec plan and the plugin's deploy command resolve the selected packaging).
Read-only consumers that must answer before a selection exists (`jk android licenses`, exec
planning) apply leniently — selected dimensions fold, unselected mandatory ones are skipped
rather than errors; builds alone enforce the mandatory check.

## Locking

`jk lock` resolves the **union** of every value's dependency overlays — one lockfile covers
every variant, and a concrete build uses the subgraph its selection activates (cargo's
model). Two guardrails keep that honest:

- Declaring the **same module at different versions** across values (or vs the base
  section) fails the lock immediately, naming both declarations — the resolver settles
  duplicate roots by highest-wins, so the "losing" value would otherwise silently build
  against the other value's version. Align the version across declarations.
- A **transitive** conflict (demo's dep pinning something a prod dep needs elsewhere)
  surfaces as a resolve failure annotated with each value's contributed deps.

Per-value lock partitions are the designed escape hatch if real projects ever need it.

## Switching variants in place

Variant builds share the module's `target/` and switching is safe: the selection is in
every action key, the Java compiler starts full compiles clean and prunes removed
sources' outputs incrementally, and when the Kotlin source set shrinks (an `extra-src`
root leaving the selection) the merged classes tree is restarted clean before the
compile — a dropped value's classes never survive into the packaged artifact.

## Worked example

`jk-examples/jvm/profiles-vs-variants` shows a profile, a feature, and a variant side by side
in one project; `jk-examples/jvm/variants-cli` is a minimal custom dimension with stub-vs-real
deps and per-value sources; `jk-examples/android/nowinandroid` is the full-scale showcase
(two-value `contentType` dimension × build types, R8 + signing on release).
