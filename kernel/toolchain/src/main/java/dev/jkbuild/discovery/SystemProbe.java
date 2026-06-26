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
 * Catch-all for OS package-manager JDK locations:
 *
 * <ul>
 *   <li>macOS: {@code /Library/Java/JavaVirtualMachines/<id>.jdk/Contents/Home/}</li>
 *   <li>Linux: {@code /usr/lib/jvm/<id>/} (Debian/Ubuntu, Fedora, Arch)</li>
 *   <li>Linux: {@code /usr/java/<id>/} (RHEL family)</li>
 * </ul>
 *
 * <p>JDKs only — system package managers rarely ship Maven/Gradle/Kotlin
 * in standardised paths.
 */
public final class SystemProbe implements LocalToolProbe {

    private final List<Path> roots;
    private final boolean macOs;

    public SystemProbe() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        this.macOs = os.contains("mac");
        this.roots = macOs
                ? List.of(Path.of("/Library/Java/JavaVirtualMachines"))
                : List.of(Path.of("/usr/lib/jvm"), Path.of("/usr/java"));
    }

    SystemProbe(List<Path> roots, boolean macOs) {
        this.roots = roots;
        this.macOs = macOs;
    }

    @Override
    public String name() {
        return "system";
    }

    @Override
    public Optional<DiscoveredTool> find(ToolSpec spec) throws IOException {
        if (!"java".equals(spec.kind())) return Optional.empty();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> entries = Files.list(root)) {
                Optional<DiscoveredTool> hit = entries.filter(Files::isDirectory)
                        .map(this::asJdkHome)
                        .filter(home -> ToolHealth.isHealthy(spec, home))
                        .findFirst()
                        .map(home -> new DiscoveredTool(home, spec.version(), name()));
                if (hit.isPresent()) return hit;
            }
        }
        return Optional.empty();
    }

    /** macOS JDK bundles wrap the real home in {@code Contents/Home}. */
    private Path asJdkHome(Path topLevel) {
        Path mac = topLevel.resolve("Contents").resolve("Home");
        if (macOs && Files.isDirectory(mac)) return mac;
        return topLevel;
    }

    @Override
    public List<JdkHit> discoverAllJdks() throws IOException {
        List<JdkHit> hits = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue; // fail fast per root
            try (Stream<Path> entries = Files.list(root)) {
                entries.filter(Files::isDirectory).map(this::asJdkHome).forEach(p -> ProbeSupport.discoverJdk(p, name())
                        .ifPresent(hits::add));
            }
        }
        return hits;
    }
}
