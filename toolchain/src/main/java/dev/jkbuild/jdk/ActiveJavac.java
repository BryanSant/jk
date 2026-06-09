// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Locates the JDK that {@code javac} on the user's {@code PATH} actually
 * resolves to — the "current" JDK, i.e. what {@code which javac} would print.
 *
 * <p>This is deliberately independent of {@code $JAVA_HOME} and of jk's own
 * default pointer: it answers "what compiler does this shell run right now?",
 * which is the thing a developer most often wants confirmed.
 *
 * <p>The first {@code javac} found while walking {@code PATH} (in order) wins.
 * Its real path is resolved through symlinks, so a manager that exposes the
 * tool via a symlink (e.g. SDKMAN's {@code candidates/java/current/bin/javac})
 * resolves to the real install. The JDK home is the parent of {@code bin/}.
 *
 * <p><b>Limitation:</b> version managers that put a <em>shim script</em> on
 * {@code PATH} rather than a symlink to the real binary (jenv, asdf) resolve
 * to the shim's own directory, not a JDK home, and so won't be matched. Direct
 * {@code PATH} entries and symlink-based managers (SDKMAN, jk, Homebrew) work.
 */
public final class ActiveJavac {

    private ActiveJavac() {}

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

    /** Resolve the current JDK home from the process {@code PATH}. */
    public static Optional<Path> home() {
        return home(System::getenv);
    }

    /**
     * Test-friendly variant: resolves {@code javac} from the {@code PATH}
     * value returned by {@code env}. Returns the canonical JDK home, or empty
     * when no {@code javac} is found on the path (or none resolves to a
     * {@code bin/} under a home directory).
     */
    static Optional<Path> home(Function<String, String> env) {
        String path = env.apply("PATH");
        if (path == null || path.isBlank()) return Optional.empty();
        String exe = WINDOWS ? "javac.exe" : "javac";
        for (String dir : path.split(Pattern.quote(File.pathSeparator))) {
            if (dir.isBlank()) continue;
            Path candidate = Path.of(dir).resolve(exe);
            if (!Files.isRegularFile(candidate)) continue;
            if (!WINDOWS && !Files.isExecutable(candidate)) continue;
            try {
                Path real = candidate.toRealPath();       // follow symlinks (SDKMAN et al.)
                Path bin = real.getParent();               // <home>/bin
                Path jdkHome = bin != null ? bin.getParent() : null;
                if (jdkHome != null) return Optional.of(jdkHome);
            } catch (IOException ignored) {
                // Unreadable/broken symlink — keep walking the PATH.
            }
        }
        return Optional.empty();
    }
}
