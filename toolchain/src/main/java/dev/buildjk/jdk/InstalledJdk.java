// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.jdk;

import java.nio.file.Path;
import java.util.Objects;

/** A JDK that {@link JdkInstaller} has placed under {@code ~/.jk/jdks/}. */
public record InstalledJdk(String identifier, Path home) {

    public InstalledJdk {
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(home, "home");
    }
}
