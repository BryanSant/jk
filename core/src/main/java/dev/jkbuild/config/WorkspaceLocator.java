// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.model.JkBuild;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Walks the directory chain to find the enclosing workspace root for a
 * given module. Used by verbs that operate on a single member but need
 * the workspace's shared {@code jk.lock} (PRD §13).
 *
 * <p>Search policy: starting from {@code memberDir}, walk up the
 * parent chain (capped at a reasonable depth) looking for a
 * {@code jk.toml} whose {@code workspace.members} contains the relative
 * path from that parent down to {@code memberDir}.
 */
public final class WorkspaceLocator {

    private static final int MAX_DEPTH = 8;

    private WorkspaceLocator() {}

    /**
     * Return the nearest enclosing workspace root above {@code dir}, or
     * empty if there is none.
     *
     * <p>Unlike {@link #findRoot}, this does <em>not</em> require {@code dir}
     * to already appear in {@code workspace.members} — it's used by verbs
     * that are about to create or register a member ({@code jk new},
     * {@code jk init}, {@code jk add <path>}), so the member may not be
     * listed (or even exist) yet. We search strict ancestors only, so a
     * workspace root is never reported as enclosing itself.
     */
    public static Optional<Path> findEnclosingWorkspace(Path dir) throws IOException {
        Path normalized = dir.toAbsolutePath().normalize();
        Path candidate = normalized;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            Path parent = candidate.getParent();
            if (parent == null) break;
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
     * Return the workspace root that owns {@code memberDir}, or empty if
     * {@code memberDir} is not inside a workspace.
     */
    public static Optional<Path> findRoot(Path memberDir) throws IOException {
        Path normalized = memberDir.toAbsolutePath().normalize();
        Path candidate = normalized;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            Path parent = candidate.getParent();
            if (parent == null) break;
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
                    if (root.workspace().members().contains(relative)) {
                        return Optional.of(parent);
                    }
                }
            }
            candidate = parent;
        }
        return Optional.empty();
    }
}
