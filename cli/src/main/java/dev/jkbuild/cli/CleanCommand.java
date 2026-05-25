// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

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

        boolean removed = false;
        removed |= deleteRecursively(dir.resolve("target"));
        removed |= deleteRecursively(dir.resolve(".jk").resolve("generated"));

        if (removed) {
            System.out.println("jk clean: removed target/ and .jk/generated/");
        } else {
            System.out.println("jk clean: nothing to remove");
        }
        return 0;
    }

    private static boolean deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return false;
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        return true;
    }
}
