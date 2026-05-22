// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code jk tool dir} — print the tools install root (parallel to
 * {@code jk cache dir} and {@code jk jdk dir}).
 */
@Command(name = "dir", description = "Print the tools install root")
public final class ToolDirCommand implements Callable<Integer> {

    @Option(names = "--home", hidden = true,
            description = "Override the jk home root. Default: ~/.jk.")
    Path home;

    @Override
    public Integer call() {
        Path jkHome = home != null
                ? home : Path.of(System.getProperty("user.home"), ".jk");
        System.out.println(jkHome.resolve("tools"));
        return 0;
    }
}
