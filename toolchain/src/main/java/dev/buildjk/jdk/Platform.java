// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.jdk;

/**
 * Maps the current JVM's {@code os.arch} / {@code os.name} properties onto
 * the strings the foojay Disco API uses. Encapsulated so the JDK
 * subsystem can be tested with synthetic platforms.
 */
public final class Platform {

    private Platform() {}

    public static String currentArchitecture() {
        return mapArchitecture(System.getProperty("os.arch"));
    }

    public static String currentOperatingSystem() {
        return mapOperatingSystem(System.getProperty("os.name"));
    }

    public static String currentArchiveType() {
        return "windows".equals(currentOperatingSystem()) ? "zip" : "tar.gz";
    }

    static String mapArchitecture(String osArch) {
        if (osArch == null) return "x64";
        return switch (osArch.toLowerCase()) {
            case "amd64", "x86_64" -> "x64";
            case "aarch64", "arm64" -> "aarch64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            case "arm" -> "arm32";
            default -> osArch.toLowerCase();
        };
    }

    static String mapOperatingSystem(String osName) {
        if (osName == null) return "linux";
        String lower = osName.toLowerCase();
        if (lower.contains("linux")) return "linux";
        if (lower.contains("mac") || lower.contains("darwin")) return "macos";
        if (lower.contains("windows")) return "windows";
        if (lower.contains("aix")) return "aix";
        if (lower.contains("freebsd")) return "freebsd";
        return lower;
    }
}
