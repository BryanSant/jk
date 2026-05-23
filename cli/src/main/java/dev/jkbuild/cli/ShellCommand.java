// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.jdk.InstalledJdk;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk shell} — spawn a subshell with {@code JAVA_HOME} and
 * {@code PATH} set to the project's pinned JDK.
 *
 * <p>v0.4 first iteration: POSIX shells only. Picks {@code $SHELL} (or
 * falls back to {@code /bin/sh}). Windows users can read {@code jk env}
 * and apply manually; native cmd / PowerShell wiring is a follow-up.
 */
@Command(name = "shell", description = "Spawn a subshell with the project's pinned JDK on PATH")
public final class ShellCommand implements Callable<Integer> {

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path dir = directory != null ? directory : Path.of(".").toAbsolutePath().normalize();
        Optional<InstalledJdk> jdk = EnvCommand.resolvePinnedJdk(dir, jdksDir);
        if (jdk.isEmpty()) {
            System.err.println("jk shell: no pinned JDK for " + dir
                    + " (write `.jk-version` via `jk jdk use <spec>`)");
            return 2;
        }
        String shell = System.getenv().getOrDefault("SHELL", "/bin/sh");
        ProcessBuilder pb = new ProcessBuilder(shell);
        pb.directory(dir.toFile());
        pb.inheritIO();
        pb.environment().put("JAVA_HOME", jdk.get().home().toString());
        pb.environment().put("PATH",
                jdk.get().home().resolve("bin") + ":"
                        + System.getenv().getOrDefault("PATH", ""));
        // Strip well-known tool-options envs that would override jk's choice
        // (per PRD §23.7).
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        pb.environment().remove("_JAVA_OPTIONS");
        pb.environment().remove("JDK_HOME");
        System.out.println("Entering jk shell with JDK " + jdk.get().identifier());
        Process p = pb.start();
        return p.waitFor();
    }
}
