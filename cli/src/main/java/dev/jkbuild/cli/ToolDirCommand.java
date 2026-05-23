// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.util.JkDirs;
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

    @Option(names = "--tools-dir", hidden = true,
            description = "Override the tools install root. Default: $JK_CACHE_DIR/tools.")
    Path toolsDir;

    @Override
    public Integer call() {
        Path root = toolsDir != null ? toolsDir : JkDirs.cache().resolve("tools");
        System.out.println(root);
        return 0;
    }
}
