// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

/**
 * The host's OS / architecture in the vocabulary the JetBrains JDK feed
 * uses ({@code linux} / {@code macOS} / {@code windows}; {@code x86_64} /
 * {@code aarch64}). Separate from {@link Platform}, which encodes
 * foojay vocabulary for the dormant {@link DiscoClient} path.
 *
 * <p>Hosts the feed doesn't cover (AIX, FreeBSD, 32-bit x86, arm32,
 * Alpine/musl) return {@link #UNSUPPORTED} so callers can surface a clean
 * "set JAVA_HOME explicitly" message instead of silently downloading the
 * wrong binary.
 */
public final class HostPlatform {

    public static final String UNSUPPORTED = "unsupported";

    private HostPlatform() {}

    public static String currentOs() {
        return mapOs(System.getProperty("os.name"));
    }

    public static String currentArch() {
        return mapArch(System.getProperty("os.arch"));
    }

    public static boolean supported() {
        return !UNSUPPORTED.equals(currentOs()) && !UNSUPPORTED.equals(currentArch());
    }

    static String mapOs(String osName) {
        if (osName == null) return UNSUPPORTED;
        String lower = osName.toLowerCase();
        if (lower.contains("linux")) return "linux";
        if (lower.contains("mac") || lower.contains("darwin")) return "macOS";
        if (lower.contains("windows")) return "windows";
        return UNSUPPORTED;
    }

    static String mapArch(String osArch) {
        if (osArch == null) return UNSUPPORTED;
        return switch (osArch.toLowerCase()) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> UNSUPPORTED;
        };
    }
}
