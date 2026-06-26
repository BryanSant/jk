// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A JDK that {@link JdkInstaller} has placed under the IntelliJ JDK directory ({@code ~/.jdks} on
 * Linux/Windows, {@code ~/Library/Java/JavaVirtualMachines} on macOS), overridable via {@code
 * JK_JDKS_DIR}.
 */
public record InstalledJdk(String identifier, Path home) {

    public InstalledJdk {
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(home, "home");
    }
}
