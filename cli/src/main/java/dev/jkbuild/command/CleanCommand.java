// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;

import dev.jkbuild.cli.tui.Spinner;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.model.JkBuild;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code jk clean} — delete generated build outputs for the workspace root
 * and every declared member.
 *
 * <p>Each member owns its own {@code target/} directory (final artifacts +
 * build intermediates). By default {@code jk clean} removes the full
 * {@code target/} tree for every project directory. With
 * {@code --keep-artifacts}, only {@code target/build/} (compiler outputs,
 * test reports, etc.) is removed — handy when you want a fresh compile but
 * still need the existing jars around for downstream consumers.
 *
 * <p>The shared cache at {@code $JK_CACHE_DIR} is left alone — that's
 * per-machine state, not per-project.
 */
@Command(name = "clean", description = "Delete generated build outputs")
public final class CleanCommand implements Callable<Integer> {

    @Option(names = "--keep-artifacts",
            description = "Only delete build/ intermediates; keep target/ artifacts.")
    boolean keepArtifacts;

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException {
        Path dir = global.workingDir();
        Path workspaceRoot = resolveWorkspaceRoot(dir);
        List<Path> projectDirs = collectProjectDirs(workspaceRoot);

        long startMs = System.currentTimeMillis();
        long[] stats = {0L, 0L}; // [fileCount, totalBytes]

        try (Spinner spinner = Spinner.show(System.out, "Cleaning...")) {
            for (Path projectDir : projectDirs) {
                if (!keepArtifacts) {
                    deleteRecursively(projectDir.resolve("target"), stats);
                } else {
                    deleteRecursively(projectDir.resolve("target").resolve("build"), stats);
                }
                // Legacy pre-layout directories, best-effort.
                deleteRecursively(projectDir.resolve("build"), stats);
                deleteRecursively(projectDir.resolve(".jk").resolve("generated"), stats);
            }
        }

        long elapsedMs = System.currentTimeMillis() - startMs;

        if (stats[0] == 0) {
            System.out.println(Theme.colorize("✓", Theme.active().success())
                    + " Nothing to remove");
        } else {
            String check   = Theme.colorize("✓", Theme.active().success());
            String removed = Theme.colorize("Removed", Theme.active().focused());
            String stats_  = String.format("%,d file%s, %s total",
                    stats[0], stats[0] == 1 ? "" : "s",
                    CacheCommand.fmtBytes(stats[1]));
            String inTime  = Theme.colorize("in " + fmtMs(elapsedMs), Theme.active().darkGray());
            System.out.println(check + " " + removed + " " + stats_ + " " + inTime);
        }
        return 0;
    }

    /**
     * Returns the workspace root plus every declared member directory.
     * Falls back to just {@code [workspaceRoot]} when parsing fails or
     * there are no members (single-project).
     */
    private static List<Path> collectProjectDirs(Path workspaceRoot) {
        List<Path> dirs = new ArrayList<>();
        dirs.add(workspaceRoot);
        Path rootToml = workspaceRoot.resolve("jk.toml");
        if (!Files.exists(rootToml)) return dirs;
        try {
            JkBuild root = JkBuildParser.parse(rootToml);
            if (root.isWorkspaceRoot()) {
                for (String member : root.workspace().members()) {
                    Path memberDir = workspaceRoot.resolve(member);
                    if (Files.isDirectory(memberDir)) dirs.add(memberDir);
                }
            }
        } catch (IOException | RuntimeException ignored) {}
        return dirs;
    }

    private static Path resolveWorkspaceRoot(Path dir) {
        try {
            Optional<Path> root = WorkspaceLocator.findRoot(dir);
            return root.orElse(dir);
        } catch (IOException ignored) {
            return dir;
        }
    }

    private static void deleteRecursively(Path root, long[] stats) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    if (Files.isRegularFile(p)) {
                        stats[1] += Files.size(p);
                        stats[0]++;
                    }
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static String fmtMs(long ms) {
        if (ms < 1000) return ms + "ms";
        return String.format("%.2fs", ms / 1000.0);
    }
}
