// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.compat.PassthroughEnv;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.JdkResolver;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk gradle ...} — passthrough to Gradle (PRD §24.1). Mirrors
 * {@link MvnCommand}: provisions via {@code jk-compat-runner}, then execs
 * {@code bin/gradle} directly.
 */
@Command(
        name = "gradle",
        description = "Passthrough to Gradle (jk manages the install)",
        mixinStandardHelpOptions = false)
public final class GradleCommand implements Callable<Integer> {

    @Option(names = {"-C", "--directory"})
    Path directory;

    @Option(names = "--tools-dir", hidden = true)
    Path toolsDir;

    @Option(names = "--jdks-dir", hidden = true)
    Path jdksDir;

    @Option(names = "--no-discover")
    boolean noDiscover;

    @Parameters(arity = "0..*", paramLabel = "<args>")
    List<String> args = new ArrayList<>();

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path projectDir = directory != null
                ? directory.toAbsolutePath().normalize()
                : Path.of(".").toAbsolutePath().normalize();
        Path toolsRoot = toolsDir != null ? toolsDir : JkDirs.cache().resolve("tools");
        Path cache = JkDirs.cache();

        Path gradleBin = MvnCommand.provision(cache, projectDir, toolsRoot, noDiscover, true);
        if (gradleBin == null) return 1;

        Optional<InstalledJdk> jdk = JdkResolver.forProject(projectDir, jdksDir);
        List<String> command = new ArrayList<>();
        command.add(gradleBin.toString());
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command).directory(projectDir.toFile()).inheritIO();
        PassthroughEnv.apply(pb.environment(), jdk.map(InstalledJdk::home).orElse(null));
        return pb.start().waitFor();
    }
}
