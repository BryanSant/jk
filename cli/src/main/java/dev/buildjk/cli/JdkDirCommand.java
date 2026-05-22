// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code jk jdk dir} — print the JDK install root (parallel to
 * {@code jk cache dir} and {@code jk tool dir}).
 */
@Command(name = "dir", description = "Print the JDK install root")
public final class JdkDirCommand implements Callable<Integer> {

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: ~/.jk/jdks.")
    Path jdksDir;

    @Override
    public Integer call() {
        Path root = jdksDir != null
                ? jdksDir : Path.of(System.getProperty("user.home"), ".jk", "jdks");
        System.out.println(root);
        return 0;
    }
}
