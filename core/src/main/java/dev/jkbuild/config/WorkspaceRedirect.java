// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.model.JkBuild;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the directory a workspace-wide verb should operate in.
 *
 * <p>Lock-producing verbs ({@code jk lock}, {@code jk update}) operate on
 * the whole workspace and write a single {@code jk.lock} at the workspace
 * root — Cargo/uv semantics. When invoked from inside a member directory
 * we don't reject; we <em>redirect</em> to the enclosing workspace root so
 * the command resolves every member into the shared root lockfile (PRD
 * §13.2) instead of trying to fetch sibling SNAPSHOT coords from Maven
 * Central.
 */
public final class WorkspaceRedirect {

    private WorkspaceRedirect() {}

    /**
     * The directory a workspace-wide lock should run in: the enclosing
     * workspace root when {@code invokedDir} is a member of one, otherwise
     * {@code invokedDir} itself (a standalone project or the root).
     *
     * <p>Missing or unparseable {@code jk.toml} returns {@code invokedDir}
     * unchanged so the caller's own parse phase reports the error in
     * context.
     */
    public static Path effectiveDir(Path invokedDir) throws IOException {
        Path buildFile = invokedDir.resolve("jk.toml");
        if (!Files.exists(buildFile)) return invokedDir;
        JkBuild peek;
        try {
            peek = JkBuildParser.parse(buildFile);
        } catch (RuntimeException ignored) {
            return invokedDir;
        }
        if (peek.isWorkspaceRoot()) return invokedDir;
        return WorkspaceLocator.findRoot(invokedDir).orElse(invokedDir);
    }
}
