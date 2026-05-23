// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.discovery;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Structural + version checks on a tool install root. Used by probes
 * (to validate candidates before reporting them) and by provisioners
 * (to detect broken symlinks before reusing a cached entry).
 *
 * <p>All checks are filesystem-only — no subprocesses. Reading a
 * {@code release} file or a jar's {@code MANIFEST.MF} is cheap and
 * doesn't depend on the tool being runnable on the current host.
 */
public final class ToolHealth {

    private ToolHealth() {}

    /**
     * Does {@code home} contain a working install matching {@code spec}?
     * Follows symlinks; a broken link returns false.
     */
    public static boolean isHealthy(ToolSpec spec, Path home) {
        if (!Files.isDirectory(home)) return false;
        if (!Files.exists(requiredBinary(spec, home))) return false;
        Optional<String> version = detectVersion(spec, home);
        if (version.isEmpty()) return false;
        if (!version.get().equals(spec.version())) return false;
        if (spec.distribution() != null) {
            Optional<String> dist = detectDistribution(spec, home);
            // If the install doesn't expose a distribution string at all
            // (most non-JDK tools), accept it. If it does, it must match.
            if (dist.isPresent() && !dist.get().equalsIgnoreCase(spec.distribution())) {
                return false;
            }
        }
        return true;
    }

    /** Read the version off disk for a candidate {@code home}. */
    public static Optional<String> detectVersion(ToolSpec spec, Path home) {
        return switch (spec.kind()) {
            case "java" -> jdkVersion(home);
            case "maven" -> mavenVersion(home);
            case "gradle" -> gradleVersion(home);
            case "kotlin" -> kotlinVersion(home);
            default -> Optional.empty();
        };
    }

    /** Read the distribution suffix for a JDK install, or empty for non-JDKs. */
    public static Optional<String> detectDistribution(ToolSpec spec, Path home) {
        if (!"java".equals(spec.kind())) return Optional.empty();
        return jdkImplementor(home);
    }

    // --- JDK ----------------------------------------------------------------

    private static Optional<String> jdkVersion(Path home) {
        return readReleaseProperty(home, "JAVA_VERSION");
    }

    private static Optional<String> jdkImplementor(Path home) {
        return readReleaseProperty(home, "IMPLEMENTOR").map(ToolHealth::mapImplementor);
    }

    private static Optional<String> readReleaseProperty(Path home, String key) {
        Path release = home.resolve("release");
        if (!Files.isRegularFile(release)) return Optional.empty();
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(release)) {
            props.load(in);
        } catch (IOException e) {
            return Optional.empty();
        }
        String raw = props.getProperty(key);
        if (raw == null) return Optional.empty();
        // release file values are double-quoted.
        return Optional.of(stripQuotes(raw));
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** Map {@code release}'s {@code IMPLEMENTOR} string to the SDKMAN distribution suffix. */
    private static String mapImplementor(String implementor) {
        String lower = implementor.toLowerCase(Locale.ROOT);
        if (lower.contains("temurin") || lower.contains("adoptium")) return "tem";
        if (lower.contains("graalvm community")) return "graalce";
        if (lower.contains("graalvm") || lower.contains("oracle graalvm")) return "graal";
        if (lower.contains("liberica") || lower.contains("bellsoft")) return "librca";
        if (lower.contains("zulu") || lower.contains("azul")) return "zulu";
        if (lower.contains("corretto") || lower.contains("amazon")) return "amzn";
        if (lower.contains("sapmachine")) return "sapmchn";
        if (lower.contains("semeru")) return "sem";
        if (lower.contains("microsoft")) return "ms";
        if (lower.contains("openjdk")) return "open";
        return lower.replace(' ', '-');
    }

    // --- Maven --------------------------------------------------------------

    private static final Pattern MAVEN_CORE = Pattern.compile("^maven-core-(?<v>.+)\\.jar$");

    private static Optional<String> mavenVersion(Path home) {
        return findVersionInLibByPattern(home.resolve("lib"), MAVEN_CORE);
    }

    // --- Gradle -------------------------------------------------------------

    private static final Pattern GRADLE_LAUNCHER = Pattern.compile("^gradle-launcher-(?<v>.+)\\.jar$");

    private static Optional<String> gradleVersion(Path home) {
        return findVersionInLibByPattern(home.resolve("lib"), GRADLE_LAUNCHER);
    }

    private static Optional<String> findVersionInLibByPattern(Path libDir, Pattern p) {
        if (!Files.isDirectory(libDir)) return Optional.empty();
        try (Stream<Path> stream = Files.list(libDir)) {
            return stream
                    .map(path -> path.getFileName().toString())
                    .map(p::matcher)
                    .filter(Matcher::matches)
                    .map(m -> m.group("v"))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    // --- Kotlin -------------------------------------------------------------

    private static Optional<String> kotlinVersion(Path home) {
        Path jar = home.resolve("lib").resolve("kotlin-compiler.jar");
        if (!Files.isRegularFile(jar)) return Optional.empty();
        try (InputStream in = Files.newInputStream(jar);
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")) continue;
                Manifest mf = new Manifest();
                mf.read(new ByteArrayInputStream(zis.readAllBytes()));
                Attributes attrs = mf.getMainAttributes();
                String v = attrs.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
                if (v == null) return Optional.empty();
                // Kotlin's MANIFEST reports `2.3.21-release-298`; the rest of
                // the world (SDKMAN, Maven Central, Homebrew, …) calls that
                // `2.3.21`. Trim the build-metadata suffix at the first `-`.
                String trimmed = v.trim();
                int dash = trimmed.indexOf('-');
                return Optional.of(dash > 0 ? trimmed.substring(0, dash) : trimmed);
            }
        } catch (IOException ignored) {
            // fall through
        }
        return Optional.empty();
    }

    // --- Required binary lookup --------------------------------------------

    /** {@code <home>/bin/<binary>} (or {@code .exe} / {@code .bat} on Windows). */
    public static Path requiredBinary(ToolSpec spec, Path home) {
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return home.resolve("bin").resolve(switch (spec.kind()) {
            case "java" -> win ? "java.exe" : "java";
            case "maven" -> win ? "mvn.cmd" : "mvn";
            case "gradle" -> win ? "gradle.bat" : "gradle";
            case "kotlin" -> win ? "kotlinc.bat" : "kotlinc";
            default -> throw new IllegalArgumentException("unknown tool kind: " + spec.kind());
        });
    }

    /** Required artifacts beyond the launcher — useful for cheap structural checks. */
    public static List<Path> requiredArtifacts(ToolSpec spec, Path home) {
        return switch (spec.kind()) {
            case "java" -> List.of(home.resolve("release"));
            case "maven", "gradle" -> List.of(home.resolve("lib"));
            case "kotlin" -> List.of(home.resolve("lib").resolve("kotlin-compiler.jar"));
            default -> List.of();
        };
    }

    /**
     * Read up to {@code limit} bytes from a UTF-8 file. Convenience for
     * probe implementations that want to grep version strings.
     */
    @SuppressWarnings("unused")
    static String readFirstBytes(Path file, int limit) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[limit];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, StandardCharsets.UTF_8);
        }
    }
}
