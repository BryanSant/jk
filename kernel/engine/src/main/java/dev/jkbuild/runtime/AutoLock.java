// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileWriter;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.resolver.LockOrchestrator;
import dev.jkbuild.resolver.ResolveObserver;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Collection;
import java.util.List;

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
