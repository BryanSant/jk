// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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
public final class ShellCommand implements CliCommand {

    @Override
    public String name() {
        return "shell";
    }

    @Override
    public String description() {
        return "Spawn a subshell with the project's pinned JDK on PATH";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Override the JDK install root. Default: the IntelliJ JDK directory.", "--jdks-dir")
                        .hide());
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        // jk shell hands control to an interactive subshell. Without a terminal
        // to attach it to (piped, scripted, CI, or running inside a jk worker
        // whose stdin is a control pipe) the spawned shell would sit at its
        // prompt forever and waitFor() would block. Fail fast instead.
        if (System.console() == null) {
            System.err.println("jk shell: requires an interactive terminal "
                    + "(run it directly from your shell, not piped or scripted)");
            return 2;
        }
        Path jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        Path dir = new GlobalOptions().workingDir();
        var origPath = System.getenv().getOrDefault("PATH", "");
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        var target = new JkEnv(registry, origPath).resolve(dir);
        if (!target.isActive()) {
            System.err.println("jk shell: no pinned JDK for " + dev.jkbuild.cli.PathDisplay.styledRaw(dir)
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
