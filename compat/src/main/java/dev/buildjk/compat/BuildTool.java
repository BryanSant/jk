// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.compat;

import java.util.Locale;

/**
 * The build tools jk knows how to download and exec on the user's behalf
 * (PRD §24.1). Each tool carries its display name and the POSIX/Windows
 * binary names under {@code <home>/bin/}.
 */
public enum BuildTool {
    MAVEN("maven", "mvn", "mvn.cmd"),
    GRADLE("gradle", "gradle", "gradle.bat");

    private final String slug;
    private final String posixBinary;
    private final String windowsBinary;

    BuildTool(String slug, String posixBinary, String windowsBinary) {
        this.slug = slug;
        this.posixBinary = posixBinary;
        this.windowsBinary = windowsBinary;
    }

    /** Directory name under {@code ~/.jk/tools/}. */
    public String slug() {
        return slug;
    }

    /** Binary name under {@code <home>/bin/} on the current OS. */
    public String binaryName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? windowsBinary : posixBinary;
    }
}
