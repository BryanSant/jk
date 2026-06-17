// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.config.WorkspaceLocator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Renders filesystem paths for human-facing output. Virtually every path jk
 * prints should be relative to a familiar anchor — the directory the command
 * was run from, the enclosing workspace root (root {@code jk.toml}), or the
 * git repo root — so output stays short and recognizable.
 *
 * <p>{@link #of} relativizes against whichever of those three anchors sits
 * <em>closest above</em> the target (the deepest ancestor), which keeps paths
 * short and guarantees no {@code ../} escapes. A path is shown absolute only
 * when it falls outside all three scopes, where a relative form would be
 * meaningless.
 */
public final class PathDisplay {

    private PathDisplay() {}

    /**
     * Display {@code target} relative to the closest of {the working dir,
     * workspace root, git repo root} that contains it, else absolute.
     *
     * @param target     the path to render (relative paths resolve against the JVM cwd)
     * @param workingDir the command's working directory (see {@link GlobalOptions#workingDir()});
     *                   may be {@code null} to use the JVM cwd
     */
    public static String of(Path target, Path workingDir) {
        Path abs = target.toAbsolutePath().normalize();
        Path anchor = closestAnchor(abs, workingDir);
        if (anchor == null) return abs.toString();
        String rel = anchor.relativize(abs).toString();
        return rel.isEmpty() ? "." : rel;
    }

    /** The deepest ancestor of {@code abs} among the working dir, workspace root, and git root. */
    private static Path closestAnchor(Path abs, Path workingDir) {
        Path best = null;
        for (Path anchor : new Path[] { cwd(workingDir), workspaceRoot(abs), gitRoot(abs) }) {
            if (anchor != null && abs.startsWith(anchor)
                    && (best == null || anchor.getNameCount() > best.getNameCount())) {
                best = anchor;
            }
        }
        return best;
    }

    private static Path cwd(Path workingDir) {
        return (workingDir != null ? workingDir : Path.of("")).toAbsolutePath().normalize();
    }

    private static Path workspaceRoot(Path abs) {
        try {
            return WorkspaceLocator.findEnclosingWorkspace(abs).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static Path gitRoot(Path abs) {
        for (Path dir = abs; dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".git"))) return dir;
        }
        return null;
    }
}
