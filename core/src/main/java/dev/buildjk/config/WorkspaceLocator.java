// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.config;

import dev.buildjk.model.BuildJk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
     * Return the workspace root that owns {@code memberDir}, or empty if
     * {@code memberDir} is not inside a workspace.
     */
    public static java.util.Optional<Path> findRoot(Path memberDir) throws IOException {
        Path normalized = memberDir.toAbsolutePath().normalize();
        Path candidate = normalized;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            Path parent = candidate.getParent();
            if (parent == null) break;
            Path rootJkToml = parent.resolve("jk.toml");
            if (Files.exists(rootJkToml)) {
                BuildJk root;
                try {
                    root = BuildJkParser.parse(rootJkToml);
                } catch (RuntimeException ignored) {
                    candidate = parent;
                    continue;
                }
                if (root.isWorkspaceRoot()) {
                    String relative = parent.relativize(normalized).toString().replace('\\', '/');
                    if (root.workspace().members().contains(relative)) {
                        return java.util.Optional.of(parent);
                    }
                }
            }
            candidate = parent;
        }
        return java.util.Optional.empty();
    }
}
