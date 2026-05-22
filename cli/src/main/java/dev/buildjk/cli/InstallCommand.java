// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.cache.Cas;
import dev.buildjk.http.Http;
import dev.buildjk.jdk.InstalledJdk;
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
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk install <coord>} — install a Maven-published tool as a launcher
 * under {@code ~/.jk/bin/} (PRD §20.1).
 *
 * <p>v0.6 first cut handles Maven coords only. {@code --git} and {@code --with}
 * land in follow-up sub-slices.
 */
@Command(name = "install", description = "Install a tool from a Maven coordinate")
public final class InstallCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<coord>",
            description = "Maven coordinate (group:artifact:version).")
    String coord;

    @Option(names = "--bin",
            description = "Launcher name under ~/.jk/bin/. Default: the artifact id.")
    String binName;

    @Option(names = "--main",
            description = "Override the Main-Class to exec (otherwise read from the jar's manifest).")
    String mainClass;

    @Option(names = "--home", hidden = true,
            description = "Override the jk home root. Default: ~/.jk.")
    Path home;

    @Option(names = "--repo-url", hidden = true,
            description = "Override the Maven repository URL (for tests).")
    URI repoUrl;

    @Override
    public Integer call() throws IOException, InterruptedException {
        Coordinate primary = Coordinate.parse(coord);
        String bin = binName != null && !binName.isBlank() ? binName : primary.artifact();

        Path jkHome = home != null
                ? home : Path.of(System.getProperty("user.home"), ".jk");
        Path cacheDir = jkHome.resolve("cache");
        Path envsRoot = jkHome.resolve("tools").resolve("envs");
        Path binDir = jkHome.resolve("bin");
        Files.createDirectories(cacheDir);

        Cas cas = new Cas(cacheDir);
        Http http = new Http();
        URI url = repoUrl != null ? repoUrl : RepositorySpec.MAVEN_CENTRAL.url();
        RepoGroup repos = RepoGroup.of(new MavenRepo("central", url, http, cas));
        ToolResolver toolResolver = new ToolResolver(repos);

        System.out.println("Resolving " + primary.toGav() + " ...");
        ToolEnv env = toolResolver.resolve(primary, bin, mainClass);
        Path javaHome = resolveJavaHome();
        Path launcher = ToolLauncher.install(envsRoot, binDir, javaHome, env);

        System.out.println("Installed " + primary.toGav() + " → " + launcher);
        System.out.println("Add to PATH if needed:");
        System.out.println("  export PATH=\"" + binDir + ":$PATH\"");
        return 0;
    }

    /**
     * The JDK launchers should use. v0.6 first cut uses the running JVM's
     * java home; tool-specific JDK pinning per env arrives later.
     */
    private static Path resolveJavaHome() {
        Optional<InstalledJdk> ignored = Optional.empty(); // future hook
        return Path.of(System.getProperty("java.home"));
    }
}
