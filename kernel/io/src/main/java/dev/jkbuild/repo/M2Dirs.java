// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import java.nio.file.Path;

/**
 * Resolves the Maven local repository root ({@code ~/.m2/repository} by default). jk uses this as
 * the primary store for downloaded Maven artifacts so that Maven, Gradle, and IDEs can share the
 * same artifact cache without a separate download step.
 *
 * <p>Override via the {@code JK_M2_LOCAL} environment variable when the machine's Maven local repo
 * lives at a non-default path (e.g. a corporate IT policy, a shared NFS mount, or a CI agent that
 * pre-populates a custom directory).
 */
public final class M2Dirs {

    private M2Dirs() {}

    /**
     * The Maven local repository root. Resolution order:
     * <ol>
     *   <li>{@code JK_M2_LOCAL} environment variable (absolute path)
     *   <li>{@code ~/.m2/repository} (Maven's own default)
     * </ol>
     */
    public static Path localRepository() {
        String override = System.getenv("JK_M2_LOCAL");
        if (override != null && !override.isBlank()) return Path.of(override.trim());
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
    }
}
