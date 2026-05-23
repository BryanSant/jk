// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.jdk.IntellijJdkDir;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code jk jdk dir} — print the JDK install root (parallel to
 * {@code jk cache dir} and {@code jk tool dir}).
 */
@Command(name = "dir", description = "Show the JDK install directory")
public final class JdkDirCommand implements Callable<Integer> {

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @Override
    public Integer call() {
        Path root = jdksDir != null ? jdksDir : IntellijJdkDir.root();
        System.out.println(root);
        return 0;
    }
}
