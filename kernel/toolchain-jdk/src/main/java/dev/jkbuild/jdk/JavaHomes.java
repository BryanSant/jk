// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import dev.jkbuild.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves which JDK home hosts a Java launch — the client-retained half of what used to be the
 * engine's {@code CompileToolchain} (slim-client Stage 5): the CLI's exec paths ({@code jk run},
 * {@code jk tool run}, launcher shims) and the engine's compile strategies share this one lookup
 * order so they always agree.
 *
 * <p>Java home order:
 *
 * <ol>
 *   <li>{@code JdkResolution.resolveForHook(projectDir …)} — the project's pinned JDK, via the
 *       canonical non-installing resolution walk ({@link JdkEnsure} already installed any pin
 *       during sync; this just locates it).
 *   <li>{@link #runningJavaHome()} — the JVM running jk, or {@code $JAVA_HOME} under native-image.
 * </ol>
 */
public final class JavaHomes {

    private JavaHomes() {}

    public static Path resolveJavaHome(Path projectDir) {
        try {
            dev.jkbuild.lock.Lockfile lock = readLockSoft(projectDir);
            JkBuild build = readBuildSoft(projectDir);
            JdkResolution.Request req = new JdkResolution.Request(
                    projectDir,
                    dev.jkbuild.config.SessionContext.current().jdkSpec(),
                    System.getenv("JK_JDK"),
                    lock != null ? lock.jdk() : null,
                    (build != null && build.project() != null) ? build.project().jdk() : null,
                    (build != null && build.project() != null) ? build.project().javaRelease() : 0,
                    System::getenv);
            // Non-installing walk of the canonical order — JdkEnsure already
            // installed any pin during sync, so this just locates it. Falls back
            // to the running JVM when nothing resolves.
            JdkResolution.Resolved r = JdkResolution.resolveForHook(req, new JdkRegistry(), GlobalDefaultJdk.current());
            if (r.jdk().isPresent()) return r.jdk().get().home();
        } catch (RuntimeException ignored) {
            // fall through to the running JVM
        }
        return runningJavaHome();
    }

    private static dev.jkbuild.lock.Lockfile readLockSoft(Path projectDir) {
        try {
            Path lock = projectDir.resolve("jk.lock");
            return Files.isRegularFile(lock) ? dev.jkbuild.lock.LockfileReader.read(lock) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static JkBuild readBuildSoft(Path projectDir) {
        try {
            Path toml = projectDir.resolve("jk.toml");
            return Files.isRegularFile(toml) ? dev.jkbuild.config.JkBuildParser.parse(toml) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The JDK that's hosting the current jk process, or {@code $JAVA_HOME} when running under GraalVM
     * native-image (which doesn't expose {@code java.home}). Used as the last-resort fallback when no
     * project JDK is pinned; throws an explanatory error if neither is available.
     */
    public static Path runningJavaHome() {
        String home = System.getProperty("java.home");
        if (home == null || home.isBlank()) home = System.getenv("JAVA_HOME");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("Cannot resolve a JDK: no project pin (`.jdk-version` or `.sdkmanrc`), "
                    + "no `java.home` (running under native-image?), and `JAVA_HOME` is unset. "
                    + "Pin a JDK with a `.jdk-version` file, set `JAVA_HOME`, or run jk on a JVM.");
        }
        return Path.of(home);
    }
}
