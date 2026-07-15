// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.jdk;

import java.util.Locale;

/**
 * The host's OS / architecture in the vocabulary the JetBrains JDK feed uses ({@code linux} /
 * {@code macOS} / {@code windows}; {@code x86_64} / {@code aarch64}). Separate from {@link
 * Platform}, which encodes foojay vocabulary for the dormant {@link DiscoClient} path.
 *
 * <p>Hosts the feed doesn't cover (AIX, FreeBSD, 32-bit x86, arm32, Alpine/musl) return {@link
 * #UNSUPPORTED} so callers can surface a clean "set JAVA_HOME explicitly" message instead of
 * silently downloading the wrong binary.
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

    /**
     * Returns {@code true} when the current JVM is running on Windows.
     *
     * <p>Callers should use this rather than inlining {@code System.getProperty("os.name",
     * "").toLowerCase(Locale.ROOT).contains("win")} which appears in many places and is inconsistent
     * about locale.
     */
    public static boolean isWindows() {
        return "windows".equals(currentOs());
    }

    /** Friendly display name for an OS (feed vocabulary → user-facing). */
    public static String displayOs(String os) {
        return switch (os) {
            case "linux" -> "Linux";
            case "macOS" -> "macOS";
            case "windows" -> "Windows";
            default -> os;
        };
    }

    /** Friendly display name for an architecture. */
    public static String displayArch(String arch) {
        return switch (arch) {
            case "x86_64" -> "64-bit";
            case "aarch64" -> "ARM 64-bit";
            default -> arch;
        };
    }

    static String mapOs(String osName) {
        if (osName == null) return UNSUPPORTED;
        String lower = osName.toLowerCase(Locale.ROOT);
        if (lower.contains("linux")) return "linux";
        if (lower.contains("mac") || lower.contains("darwin")) return "macOS";
        if (lower.contains("windows")) return "windows";
        return UNSUPPORTED;
    }

    static String mapArch(String osArch) {
        if (osArch == null) return UNSUPPORTED;
        return switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> UNSUPPORTED;
        };
    }
}
