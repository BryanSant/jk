// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.compat.PassthroughEnv;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JdkResolver;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk gradle ...} — passthrough to Gradle (PRD §24.1). Mirrors {@link MvnCommand}: provisions
 * via {@code jk-compat-runner}, then execs {@code bin/gradle} directly.
 */
public final class GradleCommand implements CliCommand {

    @Override
    public String name() {
        return "gradle";
    }

    @Override
    public String description() {
        return "Passthrough to Gradle (jk manages the install)";
    }

    @Override
    public boolean passthrough() {
        return true;
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Override the tools install root.", "--tools-dir")
                        .hide(),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir")
                        .hide(),
                Opt.flag("Skip tool discovery.", "--no-discover"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("args", Arity.ZERO_OR_MORE, "Arguments forwarded to Gradle."));
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        Path directory = in.value("directory").map(Path::of).orElse(null);
        Path toolsDir = in.value("tools-dir").map(Path::of).orElse(null);
        Path jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        boolean noDiscover = in.isSet("no-discover");
        List<String> args = in.positionals();

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
        ProcessBuilder pb =
                new ProcessBuilder(command).directory(projectDir.toFile()).inheritIO();
        PassthroughEnv.apply(pb.environment(), jdk.map(InstalledJdk::home).orElse(null));
        return pb.start().waitFor();
    }
}
