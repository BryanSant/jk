// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.discovery;

import build.jumpkick.jdk.IntellijJdkDir;
import build.jumpkick.jdk.JdkHit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Surfaces JDKs that IntelliJ (and other tools using its convention) placed in the
 * platform-standard location:
 *
 * <ul>
 *   <li>Linux / Windows: {@code ~/.jdks/<install-folder-name>/}
 *   <li>macOS: {@code ~/Library/Java/JavaVirtualMachines/<install-folder-name>/Contents/Home/}
 * </ul>
 *
 * <p>The root path is <strong>not</strong> affected by {@code JK_JDKS_DIR} / {@code JK_HOME} —
 * those move jk's own install dir, which is what {@link JkProbe} scans. This probe specifically
 * targets JDKs installed by external tools that we want to surface but never modify.
 *
 * <p>On macOS the JDK ships as a {@code .jdk} bundle whose real {@code JAVA_HOME} is under {@code
 * Contents/Home}; the {@link IntellijJdkDir#javaHome} unwrap normalises both layouts.
 */
public final class IntellijProbe implements LocalToolProbe {

    private final Path jdksRoot;

    public IntellijProbe() {
        this(defaultRoot(System.getProperty("os.name", ""), System.getProperty("user.home", "")));
    }

    public IntellijProbe(Path jdksRoot) {
        this.jdksRoot = jdksRoot;
    }

    /** Test seam: synthetic user.home + os.name pair. */
    public static IntellijProbe forPlatform(String osName, String userHome) {
        return new IntellijProbe(defaultRoot(osName, userHome));
    }

    static Path defaultRoot(String osName, String userHome) {
        String lower = osName.toLowerCase(Locale.ROOT);
        Path home = Path.of(userHome);
        if (lower.contains("mac") || lower.contains("darwin")) {
            return home.resolve("Library").resolve("Java").resolve("JavaVirtualMachines");
        }
        return home.resolve(".jdks");
    }

    @Override
    public String name() {
        return "intellij";
    }

    @Override
    public Optional<DiscoveredTool> find(ToolSpec spec) throws IOException {
        if (!"java".equals(spec.kind())) return Optional.empty();
        if (!Files.isDirectory(jdksRoot)) return Optional.empty();
        try (Stream<Path> entries = Files.list(jdksRoot)) {
            return entries.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .map(IntellijJdkDir::javaHome)
                    .filter(home -> ToolHealth.isHealthy(spec, home))
                    .findFirst()
                    .map(home -> new DiscoveredTool(home, spec.version(), name()));
        }
    }

    @Override
    public List<JdkHit> discoverAllJdks() throws IOException {
        if (!Files.isDirectory(jdksRoot)) return List.of();
        List<JdkHit> hits = new ArrayList<>();
        try (Stream<Path> entries = Files.list(jdksRoot)) {
            entries.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .map(IntellijJdkDir::javaHome)
                    .forEach(home -> ProbeSupport.discoverJdk(home, name()).ifPresent(hits::add));
        }
        return hits;
    }
}
