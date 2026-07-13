# Variants in core: build-type axis, core-section overlays, docs & examples

Status: PROPOSED (2026-07-13). Follows the profiles-vs-flavors analysis: jk keeps three
orthogonal concepts — **profiles** (how to build: toolchain knobs), **features** (what
capabilities: optional deps, additive), **variants** (which product: mandatory-exclusive
axes) — and promotes the variant *mechanism* (already core, `Variants.java`) to a core
*schema* any project type can use.

## 0. Terminology (decide first — everything downstream names itself from this)

AGP's vocabulary, which we currently borrow, is three words for three different things:

- A **dimension** is an axis: `contentType`, or the built-in build-type axis.
- A **flavor** is one *value along a custom dimension*: `demo`, `prod`.
- A **variant** is the *resolved combination* — one value per dimension crossed with a
  build type: `demoDebug`, `prodRelease`. It is a point in the matrix, not an axis.

So flavor ≠ variant: a flavor is a coordinate; a variant is the whole selection.
`Variants.Selection` already models exactly this (`buildType` + `flavors` map).

Decision: **retire "flavor" from jk's generic vocabulary.** It is Android jargon, opaque
to cargo/maven users, and redundant once "a dimension's values" exists as a phrase.
Generic terms: *variant* (resolved selection), *dimension* (axis), *value* (named entry
in a dimension). Build type is simply the built-in dimension `build-type` with built-in
values `debug`/`release` and default `debug` — which unifies `--release` as sugar rather
than a special case. The android plugin keeps `[android.flavors.*]` working (AGP
migrants) but docs present it as the compatibility spelling of dimensions.

CLI: add `--variant <dim>=<name>` (repeatable) as the generic selector; `--flavor`
stays as a deprecated alias; `--release` unchanged (`--variant build-type=release`).
The wire encoding `release|contentType=demo` is already exactly this and does not change.

## 1. Core schema

```toml
[variants.build-type.release]        # built-in dimension; built-ins debug|release, default debug
jvm-args = ["-Xmx512m"]              # core overlay keys (see §2)

[variants.contentType.demo]          # custom dimension: declaring it makes selection MANDATORY
extra-src = ["src/demo/java"]        # per-value source roots (promoted from the android plugin)

[variants.contentType.demo.dependencies]   # per-value dep overlay (MAIN scope; other scopes by
stub-backend = { group = "...", version = "^1" }  # their usual section names nested the same way)

[variants.contentType]               # optional dimension header
default = "demo"                     # OPT-IN default softens mandatory-exclusive for generic use
```

Semantics carried over unchanged from `Variants.apply` (they are what make caching
sound): a declared dimension without a default **must** be selected or the build fails;
overlay precedence is dimension declaration order, then build-type last; the selected
names are injected as config keys (`build-type`, `variant.<dim>`) so packaging/step
predicates can condition on them. Selection is workspace-wide (verified 2026-07-13:
the entry module's selection applies to every sibling).

## 2. What core overlays may touch (deliberately narrow at first)

v1 overlay surface: `[dependencies]` (+ the other dep scopes), `extra-src`, `javac`,
`jvm-args`, `[build]` scalar keys. Explicitly **not** overlayable: `[project]` identity
(group/artifact/version), `[workspace]`, `[repositories]`, `[profiles]`, `[features]` —
a variant that changes coordinates or repo set is a different project, and axis-inside-
axis (variants flipping profiles/features) is the Maven-profile tarpit we're avoiding.

`extra-src` promotion: the android plugin's finding-18 implementation (contributed-
sources step; KSP sees contributed roots) moves to core so any project type gets
per-value source dirs; the android plugin then consumes the core mechanism.

## 3. Lock semantics (the hard part — decide before implementing §2's dep overlays)

Per-value dep overlays change resolution, and jk.lock must stay variant-independent or
builds stop being reproducible across selections. Adopt **cargo's union model**: `jk
lock` resolves with *all* variant dep overlays present (union graph), the lock covers
every value, and a concrete build uses the subgraph its selection activates.

Honest caveat: mutually exclusive values can over-constrain each other (demo's dep
pinning a transitive that prod's dep needs elsewhere). Cargo accepts this; Gradle built
variant-aware resolution to escape it. v1: accept it, fail with a message that names the
two values in conflict and suggests aligning versions. If real projects hit it, the
fallback design is per-dimension-value lock partitions — additive lock sections keyed by
`dim=value` — which is mechanical but makes lock diffs noisier; don't pay that until
forced.

## 4. Implementation phases

- **V1 — CLI + vocabulary** (small): `--variant` flag, `--flavor` deprecated alias,
  help text, selector encoding untouched. Docs use dimension/value/variant everywhere.
- **V2 — core build-type dimension** (small/medium): parse `[variants.build-type.*]`,
  fold overlays into core model post-parse (a core-side sibling of `Variants.apply`,
  same Selection, applied in LockFlow/BuildPipeline/describe alongside the plugin-side
  apply); injected `build-type` key; goal/action keys already carry the selection via
  config — verify describe keys for core sections include it.
- **V3 — custom dimensions + extra-src promotion** (medium): dimension declaration +
  mandatory-selection error (reuse Variants' message shape), opt-in `default`,
  contributed-sources moves to core, android plugin consumes it. WorkspaceMerge must
  apply core overlays per module (the finding-5 class of bug: `applyToModule` dropping
  sections — add a test that a flavored workspace module keeps its overlay after merge).
- **V4 — per-value dependency overlays + union lock** (medium/large, riskiest): §3.
  Gate: land V2/V3 first; deps overlays are useless without sound locking.
- **V5 — documentation** (medium, can parallel V2+): `docs/variants.md` — the decision
  matrix (profiles = toolchain knobs / features = optional capabilities / variants =
  distinct products), when to reach for each, overlay precedence, lock semantics, and
  the anti-pattern callout: dev/test/prod *environments* are runtime concerns (Spring
  profiles, config files, 12-factor build-once-promote) — do not model them as variants.
  Cross-link from recent-features.md and the android docs.
- **V6 — examples in ~/src/oss/jk-examples** (small, after V3): `jvm/variants-cli`
  (custom dimension, stub vs real dep + extra-src, both values built in CI script);
  `jvm/profiles-vs-variants` (one project showing a `ci` profile, a feature-gated dep,
  and a build-type overlay side by side — the decision matrix as running code); README
  rows for each; android/nowinandroid stays the dimensioned-variant showcase.

## 5. Open questions

1. `--variant` vs extending `--flavor`: plan says new flag + alias; revisit if the
   deprecation churn isn't worth it.
2. Should plugin-declared axes and core dimensions share a namespace (android's
   `[android.flavors.contentType]` == core `[variants.contentType]`)? Proposed: yes —
   one Selection, one dimension namespace; a plugin axis is just a plugin-config overlay
   *consumer* of the shared dimension. Needs a collision rule (core declares the
   dimension; plugins may not redeclare it with different values).
3. Per-value test-dependency overlays: in scope for V4 (same mechanism) but verify the
   test goal's action keys pick up the selection.
