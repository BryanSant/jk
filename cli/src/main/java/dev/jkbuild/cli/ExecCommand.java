// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jk exec <coord> -- <args>} — ephemeral exec (PRD §20.3). Same
 * code path as {@code jk tool run}; {@code exec} is the top-level
 * shorthand, kept visible in {@code --help} as the most-typed ergonomic
 * verb (mirrors {@code uvx} from uv). {@code jkx} is retained as an
 * alias for muscle-memory continuity.
 */
@Command(name = "exec", aliases = {"jkx"},
        description = "Ephemeral tool execution from a Maven coord")
public final class ExecCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<coord>",
            description = "Maven coordinate (group:artifact:version).")
    String coord;

    @Option(names = "--main",
            description = "Override the Main-Class to exec.")
    String mainClass;

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
    Path cacheDirOverride;

    @Option(names = "--repo-url", hidden = true,
            description = "Override the Maven repository URL (for tests).")
    URI repoUrl;

    @Parameters(arity = "0..*", paramLabel = "<args>",
            description = "Arguments forwarded to the tool (separate from jk's own flags with `--`).")
    List<String> toolArgs = new ArrayList<>();

    @Override
    public Integer call() throws IOException, InterruptedException {
        return ToolRunCommand.Ephemeral.run(coord, mainClass, cacheDirOverride, repoUrl, toolArgs);
    }
}
