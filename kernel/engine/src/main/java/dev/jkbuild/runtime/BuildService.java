// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.model.JkBuild;
import dev.jkbuild.resolver.pubgrub.UnsatisfiableException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The engine-side entry point a front-end (the "client") calls to drive a build — the facade the
 * re-foundation hoists {@code BuildCommand}'s orchestration into, so the CLI, an IntelliJ plugin, a
 * web app, or a GitHub Action all share one build API.
 *
 * <p>Pure orchestration/policy: like {@link LockFlow}, nothing here writes to {@code stdout}/{@code
 * stderr}; results are returned for the caller's view layer to render. This is the growing surface —
 * M2 moves the workspace scheduler and the shared explain/build planner behind it next; today it owns
 * the pre-build workspace lock-freshness guard.
 */
public final class BuildService {

    private BuildService() {}

    /**
     * Outcome of the pre-build workspace lock-freshness guard.
     *
     * @param status process exit code — {@code 0} means the lock is fresh (or was re-locked OK)
     * @param error a bare message to surface (no verb prefix), or {@code null}
     */
    public record LockGuard(int status, String error) {
        public static final LockGuard OK = new LockGuard(0, null);
    }

    /**
     * Ensure the workspace lock reflects its manifests before a build: if the root {@code jk.lock} is
     * absent, older than the root {@code jk.toml}, or older than any declared member manifest, re-run
     * the {@link LockFlow lock pipeline}. Soft failures (I/O, network) don't block the build — the
     * per-module path surfaces genuine problems when it resolves classpaths.
     */
    public static LockGuard ensureWorkspaceLockFresh(Path root, JkBuild rootBuild, Path cache) {
        Path rootLock = root.resolve("jk.lock");
        if (!workspaceLockStale(root, rootBuild, rootLock)) return LockGuard.OK;
        try {
            LockFlow.Result r = LockFlow.run(root, cache, List.of(), true, null);
            return r.status() != 0 ? new LockGuard(r.status(), r.error()) : LockGuard.OK;
        } catch (UnsatisfiableException e) {
            return new LockGuard(6, e.getMessage());
        } catch (Exception e) {
            return LockGuard.OK; // soft failure — let the per-module path surface real errors
        }
    }

    /**
     * True when {@code rootLock} is absent or older than the root manifest or any declared member
     * manifest — i.e. the merged workspace lock no longer reflects the manifests it was derived from.
     */
    public static boolean workspaceLockStale(Path root, JkBuild rootBuild, Path rootLock) {
        if (!Files.exists(rootLock)) return true;
        if (AutoLock.isStale(root, rootLock)) return true; // root jk.toml newer than the lock
        if (rootBuild.workspace() != null) {
            for (String module : rootBuild.workspace().modules()) {
                Path moduleDir = root.resolve(module).normalize();
                if (AutoLock.isStale(moduleDir, rootLock)) return true; // a member manifest is newer
            }
        }
        return false;
    }
}
