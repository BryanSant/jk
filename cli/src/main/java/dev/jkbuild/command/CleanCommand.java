// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;

import dev.jkbuild.cli.tui.Spinner;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.config.WorkspaceLocator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code jk clean} — delete generated build outputs under the two-tier
 * layout.
 *
 * <p>By default removes the workspace's shared {@code target/} tree
 * (final artifacts) AND the current member's {@code build/} tree
 * (intermediates). With {@code --keep-artifacts}, only the member's
 * {@code build/} is removed — handy when you want a fresh compile but
 * still need the existing jar around for downstream consumers.
 *
 * <p>The shared cache at {@code $JK_CACHE_DIR} is left alone — that's
 * per-machine state, not per-project. {@code .jk/generated/} (a
 * pre-layout artefact path) is also wiped on best-effort basis.
 */
@Command(name = "clean", description = "Delete generated build outputs")
public final class CleanCommand implements Callable<Integer> {

    @Option(names = "--keep-artifacts",
            description = "Only delete the member's build/ intermediates; keep the workspace's target/.")
    boolean keepArtifacts;

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException {
        Path dir = global.workingDir();
        Path workspaceRoot = resolveWorkspaceRoot(dir);

        long startMs = System.currentTimeMillis();
        long[] stats = {0L, 0L}; // [fileCount, totalBytes]

        try (Spinner spinner = Spinner.show(System.out, "Cleaning...")) {
            // Member intermediates — always removed.
            deleteRecursively(dir.resolve("build"), stats);
            // Workspace artifacts — kept only when --keep-artifacts is set.
            if (!keepArtifacts) {
                deleteRecursively(workspaceRoot.resolve("target"), stats);
            }
            // Pre-layout generated-sources dir, best-effort.
            deleteRecursively(dir.resolve(".jk").resolve("generated"), stats);
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
     * Resolve the workspace root for the current directory. Falls back
     * to the directory itself when no enclosing workspace is found.
     *
     * <p>The two paths are:
     * <ul>
     *   <li>{@code dir} is a workspace member ({@code jk.toml} sits
     *       inside a parent that has {@code [workspace]}) — return the
     *       parent.</li>
     *   <li>{@code dir} is either a single project, or the workspace
     *       root itself — return {@code dir}.</li>
     * </ul>
     */
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
