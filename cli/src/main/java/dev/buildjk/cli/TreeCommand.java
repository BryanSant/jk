// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.config.BuildJkParser;
import dev.buildjk.lock.Lockfile;
import dev.buildjk.lock.LockfileReader;
import dev.buildjk.model.BuildJk;
import dev.buildjk.resolver.DependencyTree;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/** {@code jk tree} — print the resolved dependency tree. */
@Command(name = "tree", description = "Print the resolved dependency tree")
public final class TreeCommand implements Callable<Integer> {

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Option(names = "--depth",
            description = "Maximum tree depth. Default: unlimited.")
    Integer depth;

    @Override
    public Integer call() throws IOException {
        Path dir = directory != null ? directory : Path.of(".").toAbsolutePath().normalize();
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(buildFile)) {
            System.err.println("jk tree: no build.jk in " + dir);
            return 2;
        }
        if (!Files.exists(lockFile)) {
            System.err.println("jk tree: no jk.lock in " + dir + " (run `jk lock` first)");
            return 2;
        }

        BuildJk project = BuildJkParser.parse(buildFile);
        Lockfile lock = LockfileReader.read(lockFile);
        int max = depth != null ? depth : Integer.MAX_VALUE;
        System.out.print(DependencyTree.render(project, lock, max));
        return 0;
    }
}
