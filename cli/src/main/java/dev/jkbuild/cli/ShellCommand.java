// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.activate.JkEnv;
import dev.jkbuild.jdk.JdkRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code jk shell} — spawn a subshell with the env the project would have
 * under {@code jk activate}. Resolves the JDK via {@link JkEnv} (the
 * {@code jk.toml} + {@code jk.lock} flow), so {@code jk shell} and
 * {@code jk activate} can never disagree on which JDK is "current".
 *
 * <p>POSIX shells only for this iteration — picks {@code $SHELL} (or
 * {@code /bin/sh}) and inherits stdio. Native cmd / PowerShell wiring lands
 * in a follow-up.
 */
@Command(name = "shell", description = "Spawn a subshell with the project's pinned JDK on PATH")
public final class ShellCommand implements Callable<Integer> {    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path dir = global.workingDir();
        var origPath = System.getenv().getOrDefault("PATH", "");
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        var target = new JkEnv(registry, origPath).resolve(dir);
        if (!target.isActive()) {
            System.err.println("jk shell: no pinned JDK for " + dir
                    + " (run `jk new` to scaffold, or stamp `jdk = \"<id>\"` in jk.lock)");
            return 2;
        }
        String shell = System.getenv().getOrDefault("SHELL", "/bin/sh");
        ProcessBuilder pb = new ProcessBuilder(shell);
        pb.directory(dir.toFile());
        pb.inheritIO();
        var env = pb.environment();
        target.vars().forEach(env::put);
        // Strip well-known tool-options envs that would override jk's choice
        // (per PRD §23.7).
        env.remove("JAVA_TOOL_OPTIONS");
        env.remove("_JAVA_OPTIONS");
        env.remove("JDK_HOME");

        var javaHome = target.vars().get(JkEnv.JAVA_HOME);
        System.out.println("Entering jk shell with JAVA_HOME=" + javaHome);
        Process p = pb.start();
        return p.waitFor();
    }
}
