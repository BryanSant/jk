// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.discovery;

import dev.jkbuild.jdk.JdkHit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Homebrew on macOS keeps real installs under
 * {@code /opt/homebrew/Cellar/<formula>/<version>/} (Apple Silicon)
 * or {@code /usr/local/Cellar/<formula>/<version>/} (Intel). The Cellar
 * version dir contains a {@code libexec/} child that's the actual
 * {@code <home>} for Maven/Gradle/Kotlin; for OpenJDK it's
 * {@code libexec/openjdk.jdk/Contents/Home}.
 *
 * <p>JDK formulae use {@code openjdk@21} naming; build-tool formulae
 * are {@code maven} / {@code gradle} / {@code kotlin}.
 *
 * <p>Skipped silently on non-macOS hosts.
 */
public final class HomebrewProbe implements LocalToolProbe {

    private final List<Path> cellars;
    private final String osName;

    public HomebrewProbe() {
        this(List.of(Path.of("/opt/homebrew/Cellar"), Path.of("/usr/local/Cellar")), System.getProperty("os.name", ""));
    }

    HomebrewProbe(List<Path> cellars, String osName) {
        this.cellars = cellars;
        this.osName = osName;
    }

    @Override
    public String name() {
        return "homebrew";
    }

    @Override
    public Optional<DiscoveredTool> find(ToolSpec spec) throws IOException {
        if (!osName.toLowerCase(Locale.ROOT).contains("mac")) return Optional.empty();
        for (String formula : formulaeFor(spec)) {
            for (Path cellar : cellars) {
                Path formulaDir = cellar.resolve(formula);
                if (!Files.isDirectory(formulaDir)) continue;
                try (Stream<Path> versionDirs = Files.list(formulaDir)) {
                    Optional<DiscoveredTool> hit = versionDirs
                            .filter(Files::isDirectory)
                            .flatMap(v -> candidateHomes(spec, v).stream())
                            .filter(home -> ToolHealth.isHealthy(spec, home))
                            .findFirst()
                            .map(home -> new DiscoveredTool(home, spec.version(), name() + ":" + formula));
                    if (hit.isPresent()) return hit;
                }
            }
        }
        return Optional.empty();
    }

    private static List<String> formulaeFor(ToolSpec spec) {
        return switch (spec.kind()) {
            case "java" -> {
                int dot = spec.version().indexOf('.');
                String major = dot > 0 ? spec.version().substring(0, dot) : spec.version();
                yield List.of("openjdk@" + major, "openjdk");
            }
            case "maven" -> List.of("maven");
            case "gradle" -> List.of("gradle");
            case "kotlin" -> List.of("kotlin");
            default -> List.of();
        };
    }

    private static List<Path> candidateHomes(ToolSpec spec, Path cellarVersionDir) {
        // Possible <home> locations under a Cellar version dir:
        //   libexec/                            (maven, gradle, kotlin)
        //   libexec/openjdk.jdk/Contents/Home   (openjdk on macOS)
        //   .                                   (rare; some formulae)
        return List.of(
                cellarVersionDir
                        .resolve("libexec")
                        .resolve("openjdk.jdk")
                        .resolve("Contents")
                        .resolve("Home"),
                cellarVersionDir.resolve("libexec"),
                cellarVersionDir);
    }

    @Override
    public List<JdkHit> discoverAllJdks() throws IOException {
        if (!osName.toLowerCase(Locale.ROOT).contains("mac")) return List.of();
        // Cellar dir absent on a machine without Homebrew → fail fast on the lookup loop.
        List<JdkHit> hits = new ArrayList<>();
        for (Path cellar : cellars) {
            if (!Files.isDirectory(cellar)) continue;
            try (Stream<Path> formulae = Files.list(cellar)) {
                formulae.filter(Files::isDirectory)
                        .filter(p -> {
                            String n = p.getFileName().toString();
                            return n.equals("openjdk") || n.startsWith("openjdk@");
                        })
                        .forEach(formulaDir -> collectFormulaInstalls(formulaDir, hits));
            }
        }
        return hits;
    }

    private void collectFormulaInstalls(Path formulaDir, List<JdkHit> out) {
        try (Stream<Path> versionDirs = Files.list(formulaDir)) {
            versionDirs.filter(Files::isDirectory).forEach(versionDir -> {
                Path macHome = versionDir
                        .resolve("libexec")
                        .resolve("openjdk.jdk")
                        .resolve("Contents")
                        .resolve("Home");
                Path candidate = Files.isDirectory(macHome) ? macHome : versionDir.resolve("libexec");
                ProbeSupport.discoverJdk(candidate, name()).ifPresent(out::add);
            });
        } catch (IOException ignored) {
            // skip this formula
        }
    }
}
