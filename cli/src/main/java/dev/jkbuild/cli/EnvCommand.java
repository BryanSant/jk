// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.activate.JkEnv;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk env} — print POSIX {@code export} lines for the project's
 * pinned JDK ({@code eval "$(jk env)"}).
 *
 * <p>Resolves the JDK the same way {@link dev.jkbuild.cli.activate.HookEnvCommand}
 * does — walking up to find {@code jk.toml}, reading {@code jk.lock} for the
 * resolved install identifier, and looking it up via {@link JdkRegistry}. The
 * {@link #resolvePinnedJdk} static helper retains the legacy
 * {@code .jk-version} resolution for callers that haven't migrated yet (mvn,
 * gradle, native, compile, jdk-home).
 */
@Command(name = "env", description = "Print shell export lines for the project's pinned JDK")
public final class EnvCommand implements Callable<Integer> {    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException {
        Path dir = global.workingDir();
        var origPath = System.getenv("__JK_ORIG_PATH");
        if (origPath == null) origPath = System.getenv().getOrDefault("PATH", "");
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        var target = new JkEnv(registry, origPath).resolve(dir);
        if (!target.isActive()) {
            System.err.println("jk env: no pinned JDK for " + dir
                    + " (run `jk new` to scaffold, or stamp `jdk = \"<id>\"` in jk.lock)");
            return 2;
        }
        // POSIX shells only. For shell-specific syntax, see `jk hook-env -s <shell>`.
        target.vars().forEach((k, v) ->
                System.out.println("export " + k + "=" + shellQuote(v)));
        return 0;
    }

    /**
     * Legacy {@code .jk-version} resolution. Still used by {@code mvn},
     * {@code gradle}, {@code native}, {@code compile}, and {@code jdk home}
     * while those commands haven't migrated to the {@code jk.lock} flow.
     */
    static Optional<InstalledJdk> resolvePinnedJdk(Path projectDir, Path jdksDir) throws IOException {
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        return new JdkResolver(registry).resolve(projectDir);
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
