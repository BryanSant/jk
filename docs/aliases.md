# Verb aliases

`jk` ships a handful of hidden aliases that map onto its canonical verbs.
They exist to soften the migration from Maven, Gradle, npm, and other
build tools — a Maven user can keep typing `mvn package` shapes
(`jk package`) and a Gradle user can keep typing `:nativeCompile`
shapes (`jk nativeCompile`).

Aliases are deliberately **not listed in `jk --help`**: the canonical
name is the single source of truth, and we don't want to suggest five
ways of saying the same thing. `jk <alias> --help` works, but it
renders the canonical verb's help.

| Type this | And jk runs | Why this alias exists |
|---|---|---|
| `jk generate` | `jk new` | Matches `mvn archetype:generate`. |
| `jk dependencies` | `jk tree` | Matches `gradle dependencies`. |
| `jk package` | `jk build` | Matches `mvn package`. |
| `jk deploy` | `jk publish` | Matches `mvn deploy`. |
| `jk upgrade` | `jk update` | Matches npm / yarn / apt / brew vocabulary. |
| `jk sh` | `jk shell` | Short form. |
| `jk bash` | `jk shell` | Names the underlying shell. |
| `jk nativeCompile` | `jk native` | Matches Gradle's `:nativeCompile` task. |
| `jk verify-target` | `jk verify` | Matches Maven's `verify` phase output naming. |
| `jk verify-build` | `jk verify` | Pre-v1.0 name for the reproducibility check verb. Kept for back-compat. |
| `jk check` | `jk compile` | Pre-v1.0 name for the type-check verb. Kept for back-compat. |
| `jk why-rebuilt` | `jk explain` | Early-roadmap name for the cache-diff report; `explain` absorbed it. |
| `jk exec` / `jk tool exec` | `jk run` / `jk tool run` | Matches `dotnet tool exec`. Command alias (`CliCommand.aliases()`), not a verb rewrite. |

Since the 2026-07-09 inversion, `jk run` and `jk install` are the primary
verbs; `jk tool run` and `jk tool install` are second mounts of the same
command instances (not rewrites): the universal target grammar is
identical at both spellings, and a bare invocation runs/installs the
current jk.toml project.

## How rewriting works

Aliases are rewritten *before* `ArgParser` parses argv: the first positional
arg is looked up in a static `Map<String, List<String>>`. If matched,
the array is rebuilt with the canonical positionals in its place —
**possibly more than one** for a multi-word expansion. Everything after
the first positional passes through untouched.

Implications:

- `jk add package com.example:foo:1.0` keeps `package` as-is — only the
  first positional is rewritten, so aliases inside arguments survive.
- Multi-word expansion is supported if needed. Any flags or positionals
  following the alias slot into place after the expanded canonical path.
- Shell tab-completion does **not** include aliases. The completion menu
  shows canonical names only.
- `jk help <alias>` is not supported; use `jk <alias> --help` instead.

## Adding a new alias

1. Add an entry to `Jk.VERB_ALIASES` in
   `cli/src/main/java/dev/jkbuild/cli/Jk.java`. The value is a
   `List<String>` — single element for a straight rename, multiple
   elements for a path expansion.
2. Add a row to the table above.
3. Add a case to `JkAliasTest.rewrite_maps_known_aliases_to_canonical_verbs`
   (or `JkAliasTest.rewrite_can_expand_an_alias_into_multiple_positionals`
   for path expansions).

Do not register aliases in the `@Command` annotation or equivalent
declaration — that surfaces them in `--help`, which is what this whole
scheme is designed to avoid.
