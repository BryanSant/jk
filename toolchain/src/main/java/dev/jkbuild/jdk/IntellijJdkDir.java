// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import dev.jkbuild.util.JkDirs;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Helpers for handling the macOS {@code .jdk/Contents/Home} bundle layout
 * that JDK distributions ship with on macOS. Originally this class also
 * decided where jk installs JDKs (defaulting to the IntelliJ neighbor
 * location for shared downloads); installs now live under
 * {@code ~/.jk/jdks/} on every platform — see {@link JkDirs#jdks()}.
 *
 * <p>The bundle-unwrap helpers stay because external JDKs discovered by
 * the probe chain (IntelliJ's {@code ~/.jdks}, system installs under
 * {@code ~/Library/Java/JavaVirtualMachines}, SDKMAN/mise/asdf) still
 * follow the macOS {@code Contents/Home} convention and need normalising
 * before {@code JAVA_HOME} is exported.
 */
public final class IntellijJdkDir {

    private IntellijJdkDir() {}

    public static Path root() {
        return JkDirs.jdks();
    }

    static Path rootFor(String userHome) {
        return JkDirs.of(name -> null, userHome).jdksDir();
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

    /**
     * Inverse of {@link #javaHome}: given a JAVA_HOME path, return the
     * containing install directory. On macOS this strips the
     * {@code /Contents/Home} suffix to recover the {@code .jdk} bundle dir;
     * elsewhere it's identity.
     */
    public static Path installDirOf(Path javaHome) {
        Path fileName = javaHome.getFileName();
        Path parent = javaHome.getParent();
        if (fileName != null && parent != null
                && "Home".equals(fileName.toString())
                && parent.getFileName() != null
                && "Contents".equals(parent.getFileName().toString())) {
            return parent.getParent();
        }
        return javaHome;
    }
}
