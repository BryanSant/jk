// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Helpers for the macOS {@code .jdk/Contents/Home} bundle layout that
 * JDK distributions ship with on macOS. Used by every JDK discovery probe
 * to normalise install paths into {@code JAVA_HOME} paths and back.
 *
 * <p>The class is named after IntelliJ because IntelliJ codified this
 * convention for the JVM ecosystem, but the helpers apply equally to
 * jk-installed bundles, system installs at
 * {@code /Library/Java/JavaVirtualMachines}, and any other macOS JDK.
 */
public final class IntellijJdkDir {

    private IntellijJdkDir() {}

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
