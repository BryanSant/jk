// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import dev.jkbuild.util.JkDirs;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The directory jk installs JDKs into. Defaults to the IntelliJ neighbor
 * location so jk and IntelliJ transparently share downloads:
 *
 * <ul>
 *   <li>Linux / Windows: {@code ~/.jdks/}</li>
 *   <li>macOS: {@code ~/Library/Java/JavaVirtualMachines/}</li>
 * </ul>
 *
 * <p>Override via {@code JK_JDKS_DIR}. Resolution is delegated to
 * {@link JkDirs#jdks()}; this class is the home for the macOS
 * {@code Contents/Home} bundle-unwrap convention.
 */
public final class IntellijJdkDir {

    private IntellijJdkDir() {}

    public static Path root() {
        return JkDirs.jdks();
    }

    static Path rootFor(String os, String userHome) {
        return JkDirs.of(name -> null, userHome, os).jdksDir();
    }

    /**
     * Resolve the JAVA_HOME path within a given install directory. On
     * macOS the JDK ships as a {@code .jdk} bundle whose runtime lives at
     * {@code Contents/Home}; on Linux/Windows the install dir itself is
     * JAVA_HOME.
     */
    public static Path javaHome(Path installDir) {
        Path macHome = installDir.resolve("Contents").resolve("Home");
        return Files.isDirectory(macHome.resolve("bin")) ? macHome : installDir;
    }
}
