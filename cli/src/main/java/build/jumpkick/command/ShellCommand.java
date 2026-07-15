// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.cli.CliOutput;
import build.jumpkick.cli.GlobalOptions;
import build.jumpkick.jdk.JdkRegistry;
import build.jumpkick.model.command.CliCommand;
import build.jumpkick.model.command.Exit;
import build.jumpkick.model.command.Invocation;
import build.jumpkick.model.command.Opt;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk shell} — spawn a subshell with the env the project would have under {@code jk
 * activate}. Resolves the JDK via {@link JkEnv} (the {@code jk.toml} + {@code jk.lock} flow), so
 * {@code jk shell} and {@code jk activate} can never disagree on which JDK is "current".
 *
 * <p>POSIX shells only for this iteration — picks {@code $SHELL} (or {@code /bin/sh}) and inherits
 * stdio. Native cmd / PowerShell wiring lands in a follow-up.
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
        // to attach it to (scripted, CI, or running inside a jk worker whose stdin
        // is a control pipe) the spawned shell would sit at its prompt forever and
        // waitFor() would block. Fail fast instead. Keyed on the controlling
        // terminal, so `jk shell` under `curl | bash` (piped stdin) still works.
        if (!build.jumpkick.cli.tui.Interactivity.canPrompt()) {
            CliOutput.err("jk shell: requires an interactive terminal "
                    + "(run it directly from your shell, not piped or scripted)");
            return Exit.CONFIG;
        }
        Path jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        Path dir = new GlobalOptions().workingDir();
        var origPath = System.getenv().getOrDefault("PATH", "");
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        var target = new JkEnv(registry, origPath).resolve(dir);
        if (!target.isActive()) {
            CliOutput.err("jk shell: no pinned JDK for "
                    + build.jumpkick.cli.PathDisplay.styledRaw(dir)
                    + " (run `jk new` to scaffold, or stamp `jdk = \"<id>\"` in jk.lock)");
            return Exit.CONFIG;
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
        CliOutput.out("Entering jk shell with JAVA_HOME=" + javaHome);
        Process p = pb.start();
        return p.waitFor();
    }
}
