// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.compat.InstalledTool;
import dev.jkbuild.compat.PassthroughEnv;
import dev.jkbuild.compat.ToolDistribution;
import dev.jkbuild.compat.ToolProvisioning;
import dev.jkbuild.compat.ToolRegistry;
import dev.jkbuild.gradle.GradleResolver;
import dev.jkbuild.http.Http;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.util.JkDirs;
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
 * {@code jk gradle ...} — passthrough to Gradle (PRD §24.1). Mirrors
 * {@link MvnCommand} for the Gradle wrapper.
 */
@Command(
        name = "gradle",
        description = "Passthrough to Gradle (jk manages the install)",
        mixinStandardHelpOptions = false)
public final class GradleCommand implements Callable<Integer> {

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Option(names = "--tools-dir", hidden = true,
            description = "Override the tools install root. Default: $JK_CACHE_DIR/tools.")
    Path toolsDir;

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @Option(names = "--no-discover",
            description = "Don't probe SDKMAN / Homebrew / etc. for an existing install; always download.")
    boolean noDiscover;

    @Parameters(arity = "0..*", paramLabel = "<args>",
            description = "Arguments forwarded to gradle.")
    List<String> args = new ArrayList<>();

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path projectDir = directory != null
                ? directory : Path.of(".").toAbsolutePath().normalize();

        Path toolsRoot = toolsDir != null ? toolsDir : JkDirs.cache().resolve("tools");
        ToolRegistry registry = new ToolRegistry(toolsRoot);

        ToolDistribution dist = new GradleResolver().resolve(projectDir);
        ToolProvisioning.Result result = ToolProvisioning.provision(
                dist, registry, new Http(), noDiscover);
        InstalledTool gradle = result.tool();
        switch (result.source()) {
            case LINKED -> System.err.println("Linked Gradle " + dist.version()
                    + " from " + result.detail());
            case DOWNLOADED -> System.err.println("Installed Gradle " + dist.version()
                    + " from " + result.detail());
            case CACHED -> { /* silent — common case */ }
        }

        Optional<InstalledJdk> jdk = EnvCommand.resolvePinnedJdk(projectDir, jdksDir);

        List<String> command = new ArrayList<>();
        command.add(gradle.binary().toString());
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
