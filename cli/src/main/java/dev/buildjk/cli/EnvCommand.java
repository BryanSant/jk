// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.jdk.InstalledJdk;
import dev.buildjk.jdk.JdkRegistry;
import dev.buildjk.jdk.JdkResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk env} — print {@code export} lines for the project's pinned JDK,
 * suitable for {@code eval "$(jk env)"}.
 */
@Command(name = "env", description = "Print shell export lines for the project's pinned JDK")
public final class EnvCommand implements Callable<Integer> {

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: ~/.jk/jdks.")
    Path jdksDir;

    @Override
    public Integer call() throws IOException {
        Path dir = directory != null ? directory : Path.of(".").toAbsolutePath().normalize();
        Optional<InstalledJdk> jdk = resolvePinnedJdk(dir, jdksDir);
        if (jdk.isEmpty()) {
            System.err.println("jk env: no pinned JDK for " + dir
                    + " (write `.jk-version` via `jk jdk use <spec>`)");
            return 2;
        }
        Path home = jdk.get().home();
        // Posix shell only for now; %COMSPEC% on Windows is a follow-up.
        System.out.println("export JAVA_HOME=" + shellQuote(home.toString()));
        System.out.println("export PATH=" + shellQuote(home.resolve("bin").toString())
                + ":$PATH");
        return 0;
    }

    static Optional<InstalledJdk> resolvePinnedJdk(Path projectDir, Path jdksDir) throws IOException {
        Path jdksRoot = jdksDir != null
                ? jdksDir : Path.of(System.getProperty("user.home"), ".jk", "jdks");
        if (!Files.exists(jdksRoot)) return Optional.empty();
        return new JdkResolver(new JdkRegistry(jdksRoot)).resolve(projectDir);
    }

    /** Minimal POSIX shell quoting — wraps in single quotes, escapes embedded ones. */
    static String shellQuote(String value) {
        if (!needsQuoting(value)) return value;
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static boolean needsQuoting(String value) {
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
