// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.tool.ToolEnv;
import dev.jkbuild.tool.ToolLauncher;
import dev.jkbuild.tool.ToolResolver;
import dev.jkbuild.util.JkDirs;
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
 * (PRD §20.3). Resolves the coordinate, caches the jar under
 * {@code $JK_CACHE_DIR}, and execs its {@code Main-Class}. {@code jk activate}
 * scripts also expose this on the path as a {@code jkx} alias for
 * uvx-style muscle memory.
 */
@Command(name = "run", description = "Ephemerally run a tool from a Maven coord")
public final class ToolRunCommand implements Callable<Integer> {

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
        Coordinate primary = Coordinate.parse(coord);
        Path cacheDir = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
        Files.createDirectories(cacheDir);

        Cas cas = new Cas(cacheDir);
        Http http = new Http();
        URI url = repoUrl != null ? repoUrl : RepositorySpec.MAVEN_CENTRAL.url();
        RepoGroup repos = RepoGroup.of(new MavenRepo("central", url, http, cas));
        ToolResolver toolResolver = new ToolResolver(repos);

        ToolEnv env = toolResolver.resolve(primary, primary.artifact(), mainClass);
        Path javaHome = CompileToolchain.runningJavaHome();
        return ToolLauncher.execEphemeral(javaHome, env, toolArgs);
    }
}
