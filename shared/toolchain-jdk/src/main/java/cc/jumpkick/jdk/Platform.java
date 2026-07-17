// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Maps the current JVM's {@code os.arch} / {@code os.name} properties onto the strings the foojay
 * Disco API uses. Encapsulated so the JDK subsystem can be tested with synthetic platforms.
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

    /**
     * The host's C runtime, in foojay's {@code lib_c_type} vocabulary ({@code glibc} / {@code musl} /
     * {@code libc} / {@code c_std_lib}). Without this filter, foojay can return a musl-linked JDK to
     * a glibc host (or vice versa); the binary then fails to exec with "no such file or directory"
     * because the dynamic linker doesn't exist.
     *
     * <p>Detection is filesystem-based: on Linux, the presence of {@code /lib/ld-musl-*} marks the
     * host as musl (Alpine and a few derivatives). Everything else under Linux is treated as glibc.
     * macOS reports {@code libc}, Windows reports {@code c_std_lib}.
     */
    public static String currentLibCType() {
        return libCTypeFor(currentOperatingSystem(), Path.of("/"));
    }

    static String libCTypeFor(String os, Path rootFs) {
        return switch (os) {
            case "linux" -> hasMuslLoader(rootFs) ? "musl" : "glibc";
            case "macos" -> "libc";
            case "windows" -> "c_std_lib";
            default -> null;
        };
    }

    private static boolean hasMuslLoader(Path rootFs) {
        Path lib = rootFs.resolve("lib");
        if (!Files.isDirectory(lib)) return false;
        try (var stream = Files.list(lib)) {
            return stream.anyMatch(p -> p.getFileName().toString().startsWith("ld-musl-"));
        } catch (IOException e) {
            return false;
        }
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
