// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.model.command.Exit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The resolved on-disk shape of a single-project invocation: its working directory, {@code jk.toml},
 * and {@code jk.lock}. Consolidates the "resolve working dir → require {@code jk.toml} (and maybe
 * {@code jk.lock}) → otherwise print the standard {@code jk <verb>: no jk.toml in <dir>} error and
 * exit {@link Exit#CONFIG}" preamble that leaf verbs (compile/test/explain/tree/lock/…) each spelled
 * out. Commands that discover a workspace root by ascent (build/install/native) resolve their own
 * directory and don't use this.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * ProjectContext ctx = ProjectContext.require(dir, "compile").orElse(null);
 * if (ctx == null) return Exit.CONFIG;
 * ... ctx.buildFile() / ctx.lockFile() ...
 * }</pre>
 */
public record ProjectContext(Path dir, Path buildFile, Path lockFile) {

    /**
     * Resolve the project at {@code dir}, requiring {@code jk.toml}. On absence, prints {@code jk
     * <verb>: no jk.toml in <dir>} to stderr and returns empty (the caller returns {@link
     * Exit#CONFIG}).
     */
    public static Optional<ProjectContext> require(Path dir, String verb) {
        Path buildFile = dir.resolve("jk.toml");
        if (!Files.exists(buildFile)) {
            CliOutput.err("jk " + verb + ": no jk.toml in " + PathDisplay.styledRaw(dir));
            return Optional.empty();
        }
        return Optional.of(new ProjectContext(dir, buildFile, dir.resolve("jk.lock")));
    }

    /** True when the project has been locked ({@code jk.lock} exists). */
    public boolean isLocked() {
        return Files.exists(lockFile);
    }
}
