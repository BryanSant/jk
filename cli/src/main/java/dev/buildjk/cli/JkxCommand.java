// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

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
 * {@code jk jkx <coord> -- <args>} — ephemeral exec (PRD §20.3). Same
 * code path as {@code jk tool run}; {@code jkx} is the top-level
 * shorthand, kept visible in {@code --help} as the most-typed ergonomic
 * verb (mirrors {@code uvx} from uv).
 */
@Command(name = "jkx", description = "Ephemeral tool execution from a Maven coord")
public final class JkxCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<coord>",
            description = "Maven coordinate (group:artifact:version).")
    String coord;

    @Option(names = "--main",
            description = "Override the Main-Class to exec.")
    String mainClass;

    @Option(names = "--home", hidden = true,
            description = "Override the jk home root. Default: ~/.jk.")
    Path home;

    @Option(names = "--repo-url", hidden = true,
            description = "Override the Maven repository URL (for tests).")
    URI repoUrl;

    @Parameters(arity = "0..*", paramLabel = "<args>",
            description = "Arguments forwarded to the tool (separate from jk's own flags with `--`).")
    List<String> toolArgs = new ArrayList<>();

    @Override
    public Integer call() throws IOException, InterruptedException {
        return ToolRunCommand.Ephemeral.run(coord, mainClass, home, repoUrl, toolArgs);
    }
}
