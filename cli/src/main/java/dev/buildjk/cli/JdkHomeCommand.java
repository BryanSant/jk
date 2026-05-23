// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.jdk.InstalledJdk;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk jdk home} — print a single {@code export JAVA_HOME=…} line
 * for the project's pinned JDK. Equivalent to the first line of
 * {@code jk env} but without the {@code PATH} adjustment, suitable for
 * shell {@code eval} or sourcing into a {@code .envrc}.
 *
 * <p>Example:
 * <pre>
 *   eval "$(jk jdk home)"
 * </pre>
 */
@Command(name = "home", description = "Print the pinned JDK's JAVA_HOME export line")
public final class JdkHomeCommand implements Callable<Integer> {

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: ~/.jk/jdks.")
    Path jdksDir;

    @Override
    public Integer call() throws IOException {
        Path dir = directory != null ? directory : Path.of(".").toAbsolutePath().normalize();
        Optional<InstalledJdk> jdk = EnvCommand.resolvePinnedJdk(dir, jdksDir);
        if (jdk.isEmpty()) {
            System.err.println("jk jdk home: no pinned JDK for " + dir
                    + " (write `.jk-version` via `jk jdk use <spec>`)");
            return 2;
        }
        Path home = jdk.get().home();
        System.out.println("export JAVA_HOME=" + EnvCommand.shellQuote(home.toString()));
        return 0;
    }
}
