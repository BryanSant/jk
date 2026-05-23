// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compat;

import java.util.Locale;

/**
 * External tools jk knows how to download and exec on the user's behalf:
 * Maven / Gradle for the {@code jk mvn} / {@code jk gradle} passthroughs
 * (PRD §24.1), and the Kotlin compiler distribution that
 * {@code SubprocessKotlincStrategy} drives. Each tool carries its slug
 * (the directory under {@code $JK_CACHE_DIR/tools/}) and the
 * POSIX/Windows binary names under {@code <home>/bin/}.
 */
public enum BuildTool {
    MAVEN("maven", "mvn", "mvn.cmd"),
    GRADLE("gradle", "gradle", "gradle.bat"),
    KOTLIN("kotlin", "kotlinc", "kotlinc.bat");

    private final String slug;
    private final String posixBinary;
    private final String windowsBinary;

    BuildTool(String slug, String posixBinary, String windowsBinary) {
        this.slug = slug;
        this.posixBinary = posixBinary;
        this.windowsBinary = windowsBinary;
    }

    /** Directory name under {@code $JK_CACHE_DIR/tools/}. */
    public String slug() {
        return slug;
    }

    /** Binary name under {@code <home>/bin/} on the current OS. */
    public String binaryName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? windowsBinary : posixBinary;
    }
}
