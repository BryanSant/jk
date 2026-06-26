// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.theme.Theme;
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

    /** {@link #of(Path, Path)} relativized and painted in the theme's {@code path} color. */
    public static String styled(Path target, Path workingDir) {
        return Theme.colorize(of(target, workingDir), Theme.active().path());
    }

    /** {@link #of(Path)} relativized and painted in the theme's {@code path} color. */
    public static String styled(Path target) {
        return Theme.colorize(of(target), Theme.active().path());
    }

    /**
     * Paint an already-rendered path string in the theme's {@code path} color,
     * <em>without</em> relativizing — for error/context messages whose path is
     * the working directory itself (relativizing it to "." would be useless).
     */
    public static String styledRaw(Object pathLike) {
        return Theme.colorize(String.valueOf(pathLike), Theme.active().path());
    }

    /**
     * Display {@code target} relative to the closest of {the working dir,
     * workspace root, git repo root} that contains it, else absolute.
     *
     * @param target     the path to render (relative paths resolve against the JVM cwd)
     * @param workingDir the command's working directory (see {@link GlobalOptions#workingDir()});
     *                   may be {@code null} to use the JVM cwd
     */
    /**
     * Display {@code target} relative to the closest anchor, using the JVM
     * working directory as the "working dir" anchor. Convenient for static
     * helpers with no {@link GlobalOptions} in scope — the workspace-root and
     * git-root anchors don't depend on the working dir, so a path inside the
     * project still renders relative regardless.
     */
    public static String of(Path target) {
        return of(target, null);
    }

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
        for (Path anchor : new Path[] {cwd(workingDir), workspaceRoot(abs), gitRoot(abs)}) {
            if (anchor != null
                    && abs.startsWith(anchor)
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
