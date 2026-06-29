// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.lock.LockfileWriter;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;
import dev.jkbuild.resolver.LockOrchestrator;
import dev.jkbuild.resolver.ResolveObserver;
import dev.jkbuild.resolver.Versions;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Auto-lock: when {@code jk.toml} is newer than {@code jk.lock}, transparently re-locks with a
 * conservative strategy before any command reads the lockfile.
 *
 * <h3>Conservative vs explicit lock</h3>
 *
 * <ul>
 *   <li><b>Auto (conservative)</b> — locked versions are used as soft preferences fed into
 *       PubGrub's candidate ordering. The solver selects the locked version first; only versions
 *       that conflict with a new or changed constraint are bumped. Deps removed from {@code
 *       jk.toml} are dropped from the lock. New deps are resolved normally.
 *   <li><b>Explicit {@code jk lock}</b> — full fresh resolution, no version preferences; always
 *       picks the latest compatible versions.
 * </ul>
 */
public final class AutoLock {

    private AutoLock() {}

    /**
     * Returns {@code true} when {@code jk.toml} has a newer modification time than {@code jk.lock}.
     * Both files must exist; any I/O error returns {@code false} (fail-open: assume up-to-date).
     */
    public static boolean isStale(Path dir, Path lockFile) {
        try {
            Path buildFile = dir.resolve("jk.toml");
            if (!Files.exists(buildFile) || !Files.exists(lockFile)) return false;
            FileTime tomlTime = Files.getLastModifiedTime(buildFile);
            FileTime lockTime = Files.getLastModifiedTime(lockFile);
            return tomlTime.compareTo(lockTime) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Two-tier staleness check: cheap mtime first; deep dependency validation only when stale.
     *
     * <ul>
     *   <li><b>Fast tier</b> (common case): if {@code jk.toml} is NOT newer than {@code jk.lock},
     *       return {@code false} immediately — O(1) stat, no parsing.
     *   <li><b>Deep tier</b> (stale case): parse both files and verify every dependency declared in
     *       {@code jk.toml} is present in {@code jk.lock} with a version that satisfies the declared
     *       constraint. If any dep is missing or out of range, return {@code true} so the build goal
     *       runs and {@link #maybeReLock} transparently updates the lock.
     * </ul>
     *
     * <p>A {@code jk.toml} that is newer than {@code jk.lock} but whose deps are all already
     * satisfied (e.g., only a comment or {@code [build]} setting changed) returns {@code false} —
     * avoiding an unnecessary re-lock round-trip.
     */
    public static boolean needsRelocking(Path dir, Path lockFile) {
        if (!isStale(dir, lockFile)) return false; // fast path — lock is fresh
        try {
            JkBuild build = JkBuildParser.parse(dir.resolve("jk.toml"));
            Lockfile lock = LockfileReader.read(lockFile);
            return !lockSatisfiesDeps(build, lock);
        } catch (Exception e) {
            return true; // unreadable → assume stale, let parse-lock handle it
        }
    }

    /**
     * Returns {@code true} when every dependency declared in {@code build} is present in
     * {@code lock} with a version satisfying the declared constraint.
     */
    private static boolean lockSatisfiesDeps(JkBuild build, Lockfile lock) {
        Map<String, String> lockedVersions = new HashMap<>();
        for (Lockfile.Artifact a : lock.artifacts()) {
            lockedVersions.put(a.name(), a.version()); // "group:artifact" → "1.2.3"
        }
        for (Scope scope : Scope.values()) {
            for (Dependency dep : build.dependencies().of(scope)) {
                if (dep.gitSource() != null || dep.pathSource() != null) continue; // non-Maven
                String module = dep.module(); // "group:artifact"
                String locked = lockedVersions.get(module);
                if (locked == null) return false; // dep not in lock
                if (!versionSatisfied(dep.version(), locked)) return false;
            }
        }
        return true;
    }

    private static boolean versionSatisfied(VersionSelector sel, String locked) {
        if (sel == null) return true;
        return switch (sel) {
            case VersionSelector.Latest lat  -> true; // any locked version is acceptable
            case VersionSelector.Exact e     -> locked.equals(e.version());
            case VersionSelector.Caret c     -> satisfiesCaret(c.version(), locked);
            case VersionSelector.Tilde t     -> satisfiesTilde(t.version(), locked);
            case VersionSelector.Range r     -> satisfiesRange(r.raw(), locked);
        };
    }

    /** {@code ^X.Y.Z} → locked >= X.Y.Z AND locked < (X+1).0.0 */
    private static boolean satisfiesCaret(String declared, String locked) {
        if (Versions.compare(locked, declared) < 0) return false;
        String[] p = declared.split("\\.", -1);
        try {
            String upper = (Integer.parseInt(p[0]) + 1) + ".0.0";
            return Versions.compare(locked, upper) < 0;
        } catch (Exception ignored) {
            return true;
        }
    }

    /** {@code ~X.Y.Z} → locked >= X.Y.Z AND locked < X.(Y+1).0 */
    private static boolean satisfiesTilde(String declared, String locked) {
        if (Versions.compare(locked, declared) < 0) return false;
        String[] p = declared.split("\\.", -1);
        try {
            String upper = p[0] + "." + (Integer.parseInt(p[1]) + 1) + ".0";
            return Versions.compare(locked, upper) < 0;
        } catch (Exception ignored) {
            return true;
        }
    }

    /**
     * Maven bracket-notation range: {@code [1,2)}, {@code (1,2]}, {@code >=1.0,<2.0}, etc.
     * Parses both bracket and inequality forms; unknown forms are treated as satisfied.
     */
    private static boolean satisfiesRange(String raw, String locked) {
        try {
            String s = raw.trim();
            // Bracket form: [lo,hi) / (lo,hi] / [lo,hi] / (lo,hi)
            if (s.startsWith("[") || s.startsWith("(")) {
                boolean loIncl = s.startsWith("[");
                boolean hiIncl = s.endsWith("]");
                String inner = s.substring(1, s.length() - 1);
                String[] parts = inner.split(",", 2);
                if (parts.length == 2) {
                    String lo = parts[0].trim(), hi = parts[1].trim();
                    int cmpLo = Versions.compare(locked, lo);
                    int cmpHi = Versions.compare(locked, hi);
                    boolean loOk = loIncl ? cmpLo >= 0 : cmpLo > 0;
                    boolean hiOk = hiIncl ? cmpHi <= 0 : cmpHi < 0;
                    return loOk && hiOk;
                }
            }
            // Inequality form: ">=1.0.0", ">=1.0.0,<2.0.0", ">1.0", "<2.0"
            String[] clauses = s.split(",");
            for (String clause : clauses) {
                clause = clause.trim();
                if (clause.startsWith(">=")) {
                    if (Versions.compare(locked, clause.substring(2).trim()) < 0) return false;
                } else if (clause.startsWith(">")) {
                    if (Versions.compare(locked, clause.substring(1).trim()) <= 0) return false;
                } else if (clause.startsWith("<=")) {
                    if (Versions.compare(locked, clause.substring(2).trim()) > 0) return false;
                } else if (clause.startsWith("<")) {
                    if (Versions.compare(locked, clause.substring(1).trim()) >= 0) return false;
                }
            }
            return true;
        } catch (Exception ignored) {
            return true; // unrecognised range → optimistic
        }
    }

    /**
     * If {@code lockFile} is stale (jk.toml newer), performs a conservative re-lock, writes the
     * updated lock file, and returns the new {@link Lockfile}. Returns {@code null} if the lock is
     * up-to-date or if re-locking fails (the caller should fall back to reading the existing lock and
     * optionally surface a warning).
     *
     * @param dir project root (contains {@code jk.toml})
     * @param existing current contents of {@code jk.lock}
     * @param lockFile path to {@code jk.lock} (will be overwritten)
     * @param cache jk CAS directory
     * @param repoUrl optional single-URL override (tests / CI)
     * @param jkVersion version string stamped in the lockfile header
     * @param features active feature flags
     * @param withDefaults whether to include the project's default features
     * @param observer resolver progress callbacks
     */
    public static Lockfile maybeReLock(
            Path dir,
            Lockfile existing,
            Path lockFile,
            Path cache,
            URI repoUrl,
            String jkVersion,
            Collection<String> features,
            boolean withDefaults,
            ResolveObserver observer) {
        if (!isStale(dir, lockFile)) return null;
        try {
            JkBuild build = JkBuildParser.parse(dir.resolve("jk.toml"));
            // Apply workspace context if this is a module (resolves workspace: deps)
            JkBuild effective = applyWorkspaceContext(dir, build);

            Cas cas = new Cas(cache);
            dev.jkbuild.repo.RepoGroup repos = RepoGroupBuilder.buildFor(effective, repoUrl, cas);
            LockOrchestrator orchestrator = new LockOrchestrator(repos);

            Lockfile updated = orchestrator.lockConservative(
                    effective, existing, jkVersion, features == null ? List.of() : features, withDefaults, observer);

            // Stamp git-source provenance using already-locked SHAs (no re-fetch).
            java.util.Map<String, String> lockedShas = GitSourceResolution.lockedImmutableShas(existing);
            dev.jkbuild.runtime.GitSourceResolution.Prepared prep;
            try {
                prep = GitSourceResolution.prepare(
                        effective, repos, cas, CompileToolchain.runningJavaHome(), jkVersion, lockedShas);
                updated = GitSourceResolution.stamp(updated, prep.gitInfoByKey());
            } catch (Exception ignored) {
                // Git-source stamping is best-effort in auto-lock
            }

            // Preserve Kotlin version if the lock already has one and the project
            // didn't change its kotlin selector.
            if (updated.kotlin() == null && existing.kotlin() != null) {
                updated = updated.withKotlin(existing.kotlin());
            }

            LockfileWriter.write(updated, lockFile);
            dev.jkbuild.task.AccessLedger.atDefaultPath().touchLock(updated);
            return updated;
        } catch (dev.jkbuild.resolver.pubgrub.UnsatisfiableException e) {
            // Hard failure: dependencies are genuinely unsatisfiable — re-throw so
            // the build fails instead of silently continuing with a stale lock.
            throw e;
        } catch (Exception e) {
            // Soft failure (network, I/O, etc.): warn and fall back to the existing
            // lock so a transient connectivity issue doesn't block the build.
            System.err.println("‼ jk: auto-lock warning — could not update jk.lock: " + e.getMessage());
            System.err.println("    Run `jk lock` to resolve manually.");
            return null;
        }
    }

    private static JkBuild applyWorkspaceContext(Path dir, JkBuild build) {
        if (build.isWorkspaceRoot()) return build;
        try {
            var rootOpt = dev.jkbuild.config.WorkspaceLocator.findRoot(dir);
            if (rootOpt.isEmpty()) return build;
            Path wsRoot = rootOpt.get();
            JkBuild wsRootBuild = JkBuildParser.parse(wsRoot.resolve("jk.toml"));
            if (!wsRootBuild.isWorkspaceRoot()) return build;
            var siblings = dev.jkbuild.config.WorkspaceLoader.loadModules(wsRoot, wsRootBuild);
            return dev.jkbuild.model.WorkspaceMerge.applyToModule(wsRootBuild, build, siblings.values());
        } catch (Exception e) {
            return build;
        }
    }
}
