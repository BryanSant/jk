// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.compat.InstalledTool;
import dev.buildjk.compat.PassthroughEnv;
import dev.buildjk.compat.ToolDistribution;
import dev.buildjk.compat.ToolInstaller;
import dev.buildjk.compat.ToolRegistry;
import dev.buildjk.http.Http;
import dev.buildjk.jdk.InstalledJdk;
import dev.buildjk.mvn.MavenResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk mvn ...} — passthrough to Maven (PRD §24.1). jk picks the Maven
 * version (wrapper if present, otherwise {@link MavenResolver#DEFAULT_VERSION}),
 * downloads/caches the distribution under {@code ~/.jk/tools/maven/<version>/},
 * sets {@code JAVA_HOME} from the project's pinned JDK, scrubs the
 * Maven-influencing env vars, and execs {@code bin/mvn} with the user's args.
 */
@Command(
        name = "mvn",
        description = "Passthrough to Maven (jk manages the install).",
        mixinStandardHelpOptions = false)
public final class MvnCommand implements Callable<Integer> {

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Option(names = "--tools-dir", hidden = true,
            description = "Override the tools install root. Default: ~/.jk/tools.")
    Path toolsDir;

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: ~/.jk/jdks.")
    Path jdksDir;

    @Parameters(arity = "0..*", paramLabel = "<args>",
            description = "Arguments forwarded to mvn.")
    List<String> args = new ArrayList<>();

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path projectDir = directory != null
                ? directory : Path.of(".").toAbsolutePath().normalize();

        Path toolsRoot = toolsDir != null
                ? toolsDir : Path.of(System.getProperty("user.home"), ".jk", "tools");
        ToolRegistry registry = new ToolRegistry(toolsRoot);

        ToolDistribution dist = new MavenResolver().resolve(projectDir);
        InstalledTool maven = registry.find(dist.tool(), dist.version())
                .orElse(null);
        if (maven == null) {
            System.err.println("Installing Maven " + dist.version() + " from " + dist.downloadUri() + " ...");
            maven = new ToolInstaller(new Http(), registry).install(dist);
        }

        Optional<InstalledJdk> jdk = EnvCommand.resolvePinnedJdk(projectDir, jdksDir);

        List<String> command = new ArrayList<>();
        command.add(maven.binary().toString());
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(projectDir.toFile())
                .inheritIO();
        Map<String, String> env = pb.environment();
        PassthroughEnv.apply(env, jdk.map(InstalledJdk::home).orElse(null));

        Process process = pb.start();
        return process.waitFor();
    }
}
