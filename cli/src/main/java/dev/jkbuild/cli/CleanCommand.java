// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.tui.Spinner;
import dev.jkbuild.cli.tui.Theme;
import picocli.CommandLine.Command;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code jk clean} — delete generated build outputs.
 *
 * <p>Removes the project's {@code target/} and {@code .jk/generated/}
 * trees per PRD §4 / §16.1. The shared cache at {@code $JK_CACHE_DIR}
 * is left alone — that's per-machine state, not per-project.
 */
@Command(name = "clean", description = "Delete generated build outputs (target/ and .jk/generated/)")
public final class CleanCommand implements Callable<Integer> {

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException {
        Path dir = global.workingDir();

        long startMs = System.currentTimeMillis();
        long[] stats = {0L, 0L}; // [fileCount, totalBytes]

        try (Spinner spinner = Spinner.show(System.out, "Cleaning...")) {
            deleteRecursively(dir.resolve("target"), stats);
            deleteRecursively(dir.resolve(".jk").resolve("generated"), stats);
        }

        long elapsedMs = System.currentTimeMillis() - startMs;

        if (stats[0] == 0) {
            System.out.println(Theme.colorize("✓", Theme.brightGreen().bold())
                    + " Nothing to remove");
        } else {
            String check   = Theme.colorize("✓", Theme.brightGreen().bold());
            String removed = Theme.colorize("Removed", Theme.focused());
            String detail  = String.format("%,d file%s, %s total, in %s",
                    stats[0], stats[0] == 1 ? "" : "s",
                    CacheCommand.fmtBytes(stats[1]),
                    fmtMs(elapsedMs));
            System.out.println(check + " " + removed + " " + detail);
        }
        return 0;
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
