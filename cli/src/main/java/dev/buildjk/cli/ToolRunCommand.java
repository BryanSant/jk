// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.cache.Cas;
import dev.buildjk.http.Http;
import dev.buildjk.model.Coordinate;
import dev.buildjk.model.RepositorySpec;
import dev.buildjk.repo.MavenRepo;
import dev.buildjk.repo.RepoGroup;
import dev.buildjk.tool.ToolEnv;
import dev.buildjk.tool.ToolLauncher;
import dev.buildjk.tool.ToolResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jk tool run <coord> -- <args>} — ephemeral exec from a Maven coord
 * (PRD §20.3). Same code path as {@code jkx}; {@code jkx} stays as a
 * top-level shorthand for the most-typed ergonomic case.
 */
@Command(name = "run", description = "Ephemerally run a tool from a Maven coord")
public final class ToolRunCommand implements Callable<Integer> {

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
        return Ephemeral.run(coord, mainClass, home, repoUrl, toolArgs);
    }

    /** Shared implementation used by {@code jk tool run} and {@code jkx}. */
    static final class Ephemeral {
        static int run(String coord, String mainClass, Path home, URI repoUrl,
                       List<String> toolArgs)
                throws IOException, InterruptedException {
            Coordinate primary = Coordinate.parse(coord);
            Path jkHome = home != null
                    ? home : Path.of(System.getProperty("user.home"), ".jk");
            Path cacheDir = jkHome.resolve("cache");
            Files.createDirectories(cacheDir);

            Cas cas = new Cas(cacheDir);
            Http http = new Http();
            URI url = repoUrl != null ? repoUrl : RepositorySpec.MAVEN_CENTRAL.url();
            RepoGroup repos = RepoGroup.of(new MavenRepo("central", url, http, cas));
            ToolResolver toolResolver = new ToolResolver(repos);

            ToolEnv env = toolResolver.resolve(primary, primary.artifact(), mainClass);
            Path javaHome = Path.of(System.getProperty("java.home"));
            return ToolLauncher.execEphemeral(javaHome, env, toolArgs);
        }
    }
}
