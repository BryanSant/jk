// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;

import dev.jkbuild.jdk.JdkResolver;
import dev.jkbuild.jdk.InstalledJdk;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk jdk home} — print a single {@code export JAVA_HOME=…} line
 * for the project's pinned JDK. Suitable for shell {@code eval} or
 * sourcing into a {@code .envrc}.
 *
 * <p>Example:
 * <pre>
 *   eval "$(jk jdk home)"
 * </pre>
 */
@Command(name = "home", description = "Print the pinned JDK's JAVA_HOME export line")
public final class JdkHomeCommand implements Callable<Integer> {    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException {
        Path dir = global.workingDir();
        Optional<InstalledJdk> jdk = JdkResolver.forProject(dir, jdksDir);
        if (jdk.isEmpty()) {
            System.err.println("jk jdk home: no pinned JDK for " + dir
                    + " (write `.jdk-version` via `jk jdk use <spec>`)");
            return 2;
        }
        Path home = jdk.get().home();
        System.out.println("export JAVA_HOME=" + shellQuote(home.toString()));
        return 0;
    }

    /** Minimal POSIX shell quoting — wraps in single quotes, escapes embedded ones. */
    static String shellQuote(String value) {
        if (!needsQuoting(value)) return value;
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static boolean needsQuoting(String value) {
        if (value.isEmpty()) return true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(Character.isLetterOrDigit(c)
                    || c == '_' || c == '-' || c == '.' || c == '/' || c == ':')) {
                return true;
            }
        }
        return false;
    }
}
