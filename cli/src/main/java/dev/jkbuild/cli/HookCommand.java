// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * {@code jk hook <shell>} — print a shell snippet that wires jk into the
 * user's environment. The snippet sources {@code JAVA_HOME} from the
 * system-wide default-JDK symlink ({@link JkDirs#data()}/default-jdk,
 * written by {@code jk jdk install --make-default}) and prepends its
 * {@code bin} dir to {@code PATH}.
 *
 * <p>Typical install:
 * <pre>
 *   jk hook zsh >> ~/.zshenv
 *   jk hook bash >> ~/.bashrc
 *   jk hook fish > ~/.config/fish/conf.d/jk.fish
 * </pre>
 *
 * <p>The snippet is idempotent at the symlink check: when no default JDK
 * is set, {@code JAVA_HOME} is left untouched. {@code .jk-version}
 * per-project activation is a future enhancement (would need a {@code cd}
 * hook + shell-specific {@code jk env} output).
 */
@Command(name = "hook", description = "Print shell integration snippet (bash | zsh | fish)")
public final class HookCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<shell>",
            description = "Target shell: bash | zsh | fish.")
    String shell;

    @Override
    public Integer call() {
        Path defaultJdk = JkDirs.data().resolve("default-jdk");
        String s = shell.toLowerCase(Locale.ROOT);
        switch (s) {
            case "bash", "zsh" -> System.out.print(posixSnippet(defaultJdk));
            case "fish"        -> System.out.print(fishSnippet(defaultJdk));
            default -> {
                System.err.println("jk hook: unknown shell `" + shell
                        + "` (supported: bash, zsh, fish)");
                return 64; // EX_USAGE
            }
        }
        return 0;
    }

    static String posixSnippet(Path defaultJdk) {
        String path = defaultJdk.toString();
        return """
                # Added by `jk hook`
                if [ -L "%1$s" ] || [ -d "%1$s" ]; then
                    export JAVA_HOME="%1$s"
                    export PATH="$JAVA_HOME/bin:$PATH"
                fi
                """.formatted(path);
    }

    static String fishSnippet(Path defaultJdk) {
        String path = defaultJdk.toString();
        return """
                # Added by `jk hook`
                if test -L "%1$s" -o -d "%1$s"
                    set -gx JAVA_HOME "%1$s"
                    fish_add_path "$JAVA_HOME/bin"
                end
                """.formatted(path);
    }
}
