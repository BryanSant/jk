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
import java.util.concurrent.Callable;

/**
 * {@code jk tool install <coord>} — install a Maven-published tool as a
 * launcher under {@code $JK_BIN_DIR} (PRD §20.1). Was {@code jk install}
 * pre-v1.0; {@code install} remains a hidden alias.
 */
@Command(name = "install", description = "Install a tool from a Maven coordinate")
public final class ToolInstallCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<coord>",
            description = "Maven coordinate (group:artifact:version).")
    String coord;

    @Option(names = "--bin",
            description = "Launcher name under $JK_BIN_DIR. Default: the artifact id.")
    String binName;

    @Option(names = "--main",
            description = "Override the Main-Class to exec (otherwise read from the jar's manifest).")
    String mainClass;

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
    Path cacheDirOverride;

    @Option(names = "--state-dir", hidden = true,
            description = "Override the tool state directory. Default: $JK_STATE_DIR.")
    Path stateDirOverride;

    @Option(names = "--bin-dir", hidden = true,
            description = "Override the bin directory. Default: $JK_BIN_DIR or ~/.local/bin.")
    Path binDirOverride;

    @Option(names = "--repo-url", hidden = true,
            description = "Override the Maven repository URL (for tests).")
    URI repoUrl;

    @Override
    public Integer call() throws IOException, InterruptedException {
        Coordinate primary = Coordinate.parse(coord);
        String bin = binName != null && !binName.isBlank() ? binName : primary.artifact();

        Path cacheDir = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
        Path stateDir = stateDirOverride != null ? stateDirOverride : JkDirs.state();
        Path binDir = binDirOverride != null ? binDirOverride : JkDirs.binDir();
        Path envsRoot = stateDir.resolve("tools").resolve("envs");
        Files.createDirectories(cacheDir);

        Cas cas = new Cas(cacheDir);
        Http http = new Http();
        URI url = repoUrl != null ? repoUrl : RepositorySpec.MAVEN_CENTRAL.url();
        RepoGroup repos = RepoGroup.of(new MavenRepo("central", url, http, cas));
        ToolResolver toolResolver = new ToolResolver(repos);

        System.out.println("Resolving " + primary.toGav() + " ...");
        ToolEnv env = toolResolver.resolve(primary, bin, mainClass);
        Path javaHome = CompileToolchain.runningJavaHome();
        Path launcher = ToolLauncher.install(envsRoot, binDir, javaHome, env);

        System.out.println("Installed " + primary.toGav() + " → " + launcher);
        System.out.println("Add to PATH if needed:");
        System.out.println("  export PATH=\"" + binDir + ":$PATH\"");
        return 0;
    }
}
