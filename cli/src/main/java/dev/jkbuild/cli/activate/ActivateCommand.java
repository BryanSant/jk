// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.activate;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code jk activate <shell>} — print the shell-integration script that wires
 * jk into the user's shell.
 *
 * <p>Typical usage:
 * <pre>
 *   echo 'eval "$(jk activate zsh)"'   &gt;&gt; ~/.zshrc
 *   echo 'eval "$(jk activate bash)"'  &gt;&gt; ~/.bashrc
 *   echo 'jk activate fish | source'   &gt;&gt; ~/.config/fish/config.fish
 *   echo '(&amp; jk activate pwsh) | Out-String | Invoke-Expression' &gt;&gt; $PROFILE
 * </pre>
 *
 * <p>The emitted script installs precmd/chpwd hooks that re-run
 * {@code jk hook-env} on every directory change, so {@code JAVA_HOME} and
 * {@code PATH} track the project's pinned JDK without explicit user action.
 */
@Command(name = "activate",
        description = "Print shell integration script (bash | zsh | fish | pwsh)")
public final class ActivateCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<shell>",
            description = "Target shell: bash, zsh, fish, pwsh.")
    String shellName;

    @Override
    public Integer call() {
        var shell = Shell.byName(shellName);
        if (shell.isEmpty()) {
            System.err.println("jk activate: unsupported shell `" + shellName
                    + "` (supported: bash, zsh, fish, pwsh)");
            return 64; // EX_USAGE
        }
        var exe = resolveJkExe();
        System.out.print(shell.get().activateScript(exe));
        return 0;
    }

    /**
     * Best-effort resolution of the absolute path to the running {@code jk}
     * binary, so the activation script can invoke us directly without
     * relying on PATH lookup. Falls back to {@code "jk"} when we can't
     * figure it out (e.g., reflection-launched).
     */
    private static String resolveJkExe() {
        var argv0 = System.getProperty("sun.java.command");
        // Honour an explicit override, useful for tests and reproducible installs.
        var envOverride = System.getenv("JK_EXE");
        if (envOverride != null && !envOverride.isBlank()) return envOverride;

        // ProcessHandle gives us the JVM's launcher command — typically the
        // native-image binary path in distributed builds.
        try {
            var info = ProcessHandle.current().info();
            var cmd = info.command();
            if (cmd.isPresent()) {
                var path = Path.of(cmd.get());
                if (path.getFileName() != null
                        && path.getFileName().toString().contains("jk")) {
                    return path.toAbsolutePath().toString();
                }
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        // Last resort: trust PATH to find `jk`. The activation script will
        // resolve it on first call.
        return argv0 != null && argv0.startsWith("/") ? argv0 : "jk";
    }
}
