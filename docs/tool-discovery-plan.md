# Tool discovery / good-neighbor plan

**Status:** implemented (commits 92e1177, e1f1da6, 72d091f)
**Scope:** `JdkRegistry` + `JdkInstaller` and `ToolRegistry` + `ToolInstaller`
**Goal:** before downloading, look for tools the user already has — JBang
style — and reuse them via a symlink under `~/.jk/`. Detect and repair
broken links automatically. Never symlink on Windows.

---

## 1. Goals and non-goals

**Goals**

- When a `jk` verb needs a tool (a JDK, kotlinc, mvn, gradle), discover
  existing installs in standard locations *before* downloading.
- If a discovered install matches the requested version, materialise it
  under `~/.jk/` as a symlink so subsequent runs hit the same code path
  as a fresh download.
- Detect broken links (the source got uninstalled, deleted, or moved)
  and treat them as if the tool were missing — re-probe, re-install if
  needed.
- One probe-and-link pattern, applied uniformly to JDKs, Kotlin, Maven,
  Gradle, and any future tool (`scala`, `clojure`, etc.).
- Windows: skip symlinking entirely. Always download to `~/.jk/`.

**Non-goals**

- We do not "adopt" external installs (no rewriting their files; jk only
  reads through the link).
- We do not verify SHA-256 of discovered installs — same trust posture as
  Gradle / Maven / SDKMAN when they reuse a local toolchain. This is a
  documented downgrade vs. our own downloads.
- We do not aggressively try to *upgrade* an external install if it's
  almost-but-not-exactly the requested version. Exact-match only;
  fuzzy-match is a follow-up.
- We do not run a background daemon. Discovery happens once per verb
  invocation and the symlink is the cache.

---

## 2. Discovery model

Add a `LocalToolProbe` SPI alongside the existing `ToolInstaller`:

```java
public interface LocalToolProbe {
    String name();
    Optional<DiscoveredTool> find(ToolQuery query) throws IOException;
}
```

```java
public record ToolQuery(BuildTool tool, String exactVersion, /* optional */ String distribution) {}
public record DiscoveredTool(Path home, String detectedVersion, String source) {}
```

A `Probes.defaultChain()` static returns the standard ordered list. The
ordering is *deliberate* — earlier probes win on ties:

| Order | Probe | What it inspects |
|---|---|---|
| 1 | `JkInstallProbe` | `~/.jk/jdks/<id>/` and `~/.jk/tools/<slug>/<version>/` — already-cached jk downloads or prior links. |
| 2 | `EnvVarProbe` | `JAVA_HOME` / `KOTLIN_HOME` / `M2_HOME` / `GRADLE_HOME`. Used only when the version we extract matches the query. |
| 3 | `SdkmanProbe` | `~/.sdkman/candidates/<slug>/<version>/`. The most common case for JVM developers. |
| 4 | `JbangProbe` | `~/.jbang/cache/jdks/<version>/`. JDKs only. |
| 5 | `AsdfProbe` | `~/.asdf/installs/<slug>/<version>/`. |
| 6 | `JenvProbe` | `~/.jenv/versions/<version>/` (JDKs only). |
| 7 | `HomebrewProbe` | `/opt/homebrew/Cellar/<formula>/<version>/libexec/` on macOS; `/usr/local/Cellar/...` on Intel Macs. Resolves through `/opt/homebrew/opt/<formula>` symlinks. |
| 8 | `SystemProbe` | macOS `/Library/Java/JavaVirtualMachines/<vendor>-<n>.jdk/Contents/Home/`; Linux `/usr/lib/jvm/<id>/`. JDKs only. |

ServiceLoader-discoverable so plugins (corporate envs) can add their own
probe (e.g. Nix profile dirs). Default chain is hard-wired in the order
above; the loader appends.

The order is "least surprising": jk's own dir first, then env vars
(explicit user intent), then version-managers in popularity order,
finally OS-level locations.

---

## 3. Version matching

Two-stage validation per discovered candidate:

**3a. Structural check** — the expected binary exists at the expected
path. For each tool:

| Tool | Required artifact |
|---|---|
| JDK | `<home>/bin/java` (or `java.exe`) + `<home>/release` file. |
| Kotlin | `<home>/bin/kotlinc` and `<home>/lib/kotlin-compiler.jar`. |
| Maven | `<home>/bin/mvn` and `<home>/lib/maven-core-*.jar`. |
| Gradle | `<home>/bin/gradle` and `<home>/lib/gradle-launcher-*.jar`. |

Path-pattern check is purely filesystem — no subprocess.

**3b. Version match** — read the version off disk:

- **JDK:** parse `<home>/release` (`JAVA_VERSION="21.0.5"`,
  `IMPLEMENTOR="Eclipse Adoptium"`, `IMPLEMENTOR_VERSION="Temurin-21.0.5+11"`).
  Mapping `IMPLEMENTOR` → SDKMAN distribution suffix (`tem`, `graalce`,
  …) reuses the table already in `JdkPackage`.
- **Maven:** filename pattern of `<home>/lib/maven-core-<version>.jar`.
- **Gradle:** `<home>/lib/gradle-launcher-<version>.jar`.
- **Kotlin:** read `META-INF/MANIFEST.MF`'s
  `Implementation-Version` from `<home>/lib/kotlin-compiler.jar` (no
  subprocess; same trick `JarManifest` already does).

If either check fails, the probe returns `Optional.empty()` and the next
probe runs. No `<tool> -version` subprocess required at probe time —
keeps discovery cheap (target: < 50 ms total).

**Distribution awareness for JDKs.** The query carries a distribution
(`tem`, `graalce`, …). The release-file check has to match it; a
Temurin install does not satisfy a `graalce` request. This is already
how `JdkRegistry.findByPrefix` works against jk's own identifiers; the
external probes extend the same matcher.

---

## 4. Provisioning: link, exec, repair

A new `ToolProvisioner` orchestrates the full flow:

```java
public final class ToolProvisioner {
    public InstalledTool provide(ToolDistribution dist) throws IOException, InterruptedException;
}
```

Pseudocode:

```
1. existing = registry.find(dist.tool, dist.version)
2. if existing != null && isHealthy(existing): return existing
3. if existing != null && !isHealthy(existing): delete the broken entry, fall through
4. for probe in probes:
       discovered = probe.find(toolQuery)
       if discovered != null:
           if isWindows: skip (no symlink possible)
           else: symlink discovered.home -> registry.installDir
           return new InstalledTool(linked)
5. return installer.install(dist)        // network download fallback
```

The "is healthy" check on step 2 is exactly the structural check from
§3a, applied to the registry entry. If it's a symlink, `Files.exists`
follows the link — so a broken link returns false and we get to step 3.

After step 3 deletes the broken entry, control flow re-enters the probe
chain. If a probe finds a (now-valid) external install we re-link; if
not, step 5 downloads. The user experience is: "thing that worked
yesterday still works today; if your SDKMAN got nuked, jk silently
re-resolves."

`isHealthy` also catches the case where the link target was *replaced*
with a different version of the tool. Defence: re-read the version from
disk during `isHealthy()` (cheap — same logic as §3b) and reject if it
no longer matches the directory name. Detected on next run.

---

## 5. Symlink layout

Two registries, same shape:

```
~/.jk/jdks/<sdkman-id>-<arch>-<os>/    -> ~/.sdkman/candidates/java/<sdkman-id>/  (when linked)
                                       -> real dir                                (when downloaded)
~/.jk/tools/<slug>/<version>/          -> ~/.sdkman/candidates/<slug>/<version>/  (when linked)
                                       -> real dir                                (when downloaded)
```

No sidecar metadata file. `Files.isSymbolicLink(path)` plus
`Files.readSymbolicLink(path)` is enough to tell the user where a linked
install came from in `jk jdk list` / future `jk tool list`.

**SDKMAN `current` symlink resolution.** `~/.sdkman/candidates/maven/current`
is itself a symlink to a real version dir. We resolve through it before
linking — link from jk to the *real* dir, not to `current`, so jk's link
stays stable even when the user `sdk default`s a different version.

**Homebrew cellar / opt resolution.** Same idea: resolve
`/opt/homebrew/opt/maven` → `/opt/homebrew/Cellar/maven/3.9.9/libexec/`
and link to the cellar version dir directly.

**macOS JDK `/Contents/Home`.** Probes return the `Contents/Home` subdir,
not the `.jdk` bundle root; that's the actual `JAVA_HOME` shape jk
expects.

---

## 6. Windows behaviour

`Files.createSymbolicLink` on Windows requires either Developer Mode or
elevated privileges. Junctions work without elevation but only for
directories on the same volume, and JBang has had a long tail of bugs
around them. Per the requirement: **always download on Windows.**

The provisioner detects `os.name` contains `"win"` and short-circuits
step 4's link branch. Probes still run (they're cheap), but a discovered
external install is logged as "skipped: would need a symlink, falling
back to download." Future enhancement: hard-copy the discovered tree if
the user passes `--copy-instead-of-link` or similar.

---

## 7. CLI surface

`jk jdk list` already exists. Augment with a `--source` column:

```
21.0.5-tem-x64-linux     linked (~/.sdkman/candidates/java/21.0.5-tem)
17.0.13-graalce-x64-linux  downloaded
```

Add `jk jdk reconcile` (and `jk tool reconcile`) — a no-op verb that
walks every entry under `~/.jk/jdks/` and `~/.jk/tools/`, runs the
healthiness check, prunes broken links, and reports. Useful in
post-checkout hooks and CI fixtures.

`jk jdk install` and `jk install` get a new flag `--no-discover` that
skips the probe chain and always downloads — escape hatch for users
who hit a probe bug or want isolated jk-owned installs.

---

## 8. Code changes

**New** — in `:toolchain/dev.buildjk.tool/`:

- `LocalToolProbe.java` — SPI interface.
- `DiscoveredTool.java`, `ToolQuery.java` — data records.
- `Probes.java` — `defaultChain()` plus the concrete probes.
- `ToolProvisioner.java` — the orchestrator.
- `ToolHealth.java` — the structural + version check shared between
  registries.

**Modified:**

- `JdkRegistry.find` / `findByPrefix` — delegate to `ToolProvisioner`
  when nothing exists locally (or when an entry is broken).
- `ToolRegistry.find` — same.
- `JdkInstallCommand` — invokes provisioner; adds `--no-discover`.
- `MvnCommand`, `GradleCommand`, `CompileToolchain.resolveKotlinHome`
  — call provisioner instead of `registry.find().or(installer.install())`.
- `JdkListCommand` — show source column.

**New verbs:**

- `JdkReconcileCommand` — `jk jdk reconcile`.
- `ToolReconcileCommand` — `jk tool reconcile`.

**Removed:** nothing. The pure-download path stays as the fallback;
provisioner just wraps it.

---

## 9. Tests

Unit:

- `ProbesTest` — each probe against a fake filesystem fixture (use the
  existing `@TempDir` pattern); verify version-mismatches are rejected,
  exact matches are returned.
- `ToolProvisionerTest` — six paths through the state machine:
  (a) already-healthy jk install, (b) broken link → probe finds → relink,
  (c) broken link → no probe finds → download, (d) fresh + probe finds,
  (e) fresh + no probe finds → download, (f) windows path always
  downloads.
- `ToolHealthTest` — broken symlinks reported broken; version-mismatch on
  a real dir reported broken.

End-to-end:

- `MvnGradleCommandTest` gets a "probe finds Maven in scratch SDKMAN
  layout" case that verifies the link, then deletes the SDKMAN source
  and verifies the next run downloads.
- A Linux-only test that explicitly skips on Windows via
  `@DisabledOnOs(WINDOWS)`. The Windows path is a smaller test that
  validates the always-download branch.

---

## 10. Open questions for review

1. **Fuzzy version match.** A user has Temurin 21.0.6 installed and a
   project pins Maven Wrapper's recommended JDK 21.0.5. Should we link
   the 21.0.6? The plan says no (exact match only). If yes, what's the
   compatibility window — `21.x.*`? `21.0.*`?

2. **Probe ServiceLoader.** Worth the complexity, or hard-code the
   default chain? IDE plugins might want a `CorporateMirrorProbe`. My
   instinct: ServiceLoader, because it costs us a few lines.

3. **Verification of linked installs.** We currently sha256-check our
   downloads. Linked installs trust the filesystem. Document explicitly?
   Add an opt-in `--verify-linked` that runs `sha256sum` over the linked
   tree and compares against a recorded fingerprint?

4. **What about user-pinned tools.** `.jk-version` / `.sdkmanrc` pin the
   project to a JDK. If the user *already* has that JDK on SDKMAN, we
   should prefer linking over downloading even when nothing's in
   `~/.jk/jdks/`. The plan covers this implicitly via step 4 of
   provisioning; called out here so we don't forget to test the path.

5. **Sharing the cache across machines / containers.** Containers often
   mount `~/.sdkman` from the host. The link from `~/.jk/jdks/` to
   `~/.sdkman/…/` works inside the container only if the host's SDKMAN
   path is the same. Worth a docs note; no code impact.

6. **Should `jk install` (the per-Maven-coord tool installer) also
   discover?** It currently writes a launcher under `~/.jk/bin/`
   pointing at the coord's main jar. The discovery model doesn't fit
   that pattern (no canonical location for arbitrary Maven-coord
   tools). Leave as-is — the new probe pipeline only applies to the
   known external installers (SDKMAN, JBang, etc.).

---

*Sign-off granted. Decisions (commits 92e1177, e1f1da6, 72d091f):*

1. *Exact version only — no fuzzy match. Implemented in `ToolHealth.isHealthy`.*
2. *Probe `ServiceLoader`, with the acknowledged limitation that it's JVM-mode only. `Probes.defaultChain()` appends ServiceLoader-discovered entries after the built-ins.*
3. *`--verify-linked` opt-in. Lives on `jk jdk reconcile` and `jk tool reconcile`. Default-off. Writes `.fingerprint` sidecars via `TreeFingerprint`.*
4. *Tested. `SdkmanProbeTest.user_pinned_tool_already_in_sdkman_resolves_without_download` covers the `.jk-version`-pins-a-SDKMAN-install path.*
5. *Containers (see §11 below).*
6. *No. `jk install` (Maven-coord tool installer) stays as-is.*

---

## 11. Containers and shared SDKMAN mounts

If you mount the host's `~/.sdkman` into a container (a common dev-loop
pattern), the symlinks jk writes under `~/.jk/jdks/` and
`~/.jk/tools/` point at host paths that only exist when the mount is in
place. Two gotchas:

- **`~/.jk/` should not be shared.** Each container gets its own
  `~/.jk/`. Sharing it means linking to host paths that aren't
  guaranteed to be valid inside the container, and one container's
  reconcile can prune another's links.
- **Mount paths must match.** A link target stored as
  `/home/host-user/.sdkman/candidates/maven/3.9.9` doesn't resolve if
  the container mounts the same dir at `/sdkman/`. Either mount under
  the same absolute path the link expects, or pass `--no-discover` on
  the first invocation and let jk download into the container's
  `~/.jk/tools/` directly.

If your container CI is sensitive to this, run `jk tool reconcile`
once at container start — broken links get pruned and the next call
either re-links (when the mount is visible at the expected path) or
falls back to a download.
