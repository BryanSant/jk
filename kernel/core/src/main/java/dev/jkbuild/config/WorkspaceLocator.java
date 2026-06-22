// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.model.JkBuild;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Walks the directory chain to find the enclosing workspace root for a
 * given module. Used by verbs that operate on a single module but need
 * the workspace's shared {@code jk.lock} (PRD §13).
 *
 * <p>Search policy: starting from {@code moduleDir}, walk all the way up
 * to the filesystem root ({@code /} on Linux/macOS, {@code <drive>:\} on
 * Windows) looking for a {@code jk.toml} whose {@code workspace.modules}
 * contains the relative path from that ancestor down to {@code moduleDir}.
 */
public final class WorkspaceLocator {

    /** Guard against symlink cycles or other pathological filesystems. */
    private static final int MAX_DEPTH = 8192;

    private WorkspaceLocator() {}

    /**
     * Return the nearest enclosing workspace root above {@code dir}, or
     * empty if there is none.
     *
     * <p>Unlike {@link #findRoot}, this does <em>not</em> require {@code dir}
     * to already appear in {@code workspace.modules} — it's used by verbs
     * that are about to create or register a module ({@code jk new},
     * {@code jk init}, {@code jk add <path>}), so the module may not be
     * listed (or even exist) yet. We search strict ancestors only, so a
     * workspace root is never reported as enclosing itself.
     */
    public static Optional<Path> findEnclosingWorkspace(Path dir) throws IOException {
        Path normalized = dir.toAbsolutePath().normalize();
        Path candidate = normalized;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            Path parent = candidate.getParent();
            if (parent == null) break;   // reached filesystem root
            Path rootJkToml = parent.resolve("jk.toml");
            if (Files.exists(rootJkToml)) {
                try {
                    if (JkBuildParser.parse(rootJkToml).isWorkspaceRoot()) {
                        return Optional.of(parent);
                    }
                } catch (RuntimeException ignored) {
                    // Unparseable ancestor manifest — keep walking up.
                }
            }
            candidate = parent;
        }
        return Optional.empty();
    }

    /**
     * Return the workspace root that owns {@code moduleDir}, or empty if
     * {@code moduleDir} is not inside a workspace.
     */
    public static Optional<Path> findRoot(Path moduleDir) throws IOException {
        Path normalized = moduleDir.toAbsolutePath().normalize();
        Path candidate = normalized;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            Path parent = candidate.getParent();
            if (parent == null) break;   // reached filesystem root
            Path rootJkToml = parent.resolve("jk.toml");
            if (Files.exists(rootJkToml)) {
                JkBuild root;
                try {
                    root = JkBuildParser.parse(rootJkToml);
                } catch (RuntimeException ignored) {
                    candidate = parent;
                    continue;
                }
                if (root.isWorkspaceRoot()) {
                    String relative = parent.relativize(normalized).toString().replace('\\', '/');
                    if (root.workspace().modules().contains(relative)) {
                        return Optional.of(parent);
                    }
                }
            }
            candidate = parent;
        }
        return Optional.empty();
    }
}
