// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.discovery;

import dev.jkbuild.jdk.IntellijJdkDir;
import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Surfaces JDKs that {@code jk jdk install} placed under
 * {@link JkDirs#jdks()} (default {@code ~/.jk/jdks/}; overridable via
 * {@code JK_JDKS_DIR} or {@code JK_HOME}).
 *
 * <p>The macOS {@code Contents/Home} bundle unwrap is applied via
 * {@link IntellijJdkDir#javaHome} before the path is handed to
 * {@link ProbeSupport#discoverJdk}, so jk-installed macOS tarballs that
 * ship as {@code .jdk} bundles work the same as flat Linux/Windows
 * installs.
 */
public final class JkProbe implements LocalToolProbe {

    private final Path jdksRoot;

    public JkProbe() {
        this(JkDirs.jdks());
    }

    public JkProbe(Path jdksRoot) {
        this.jdksRoot = jdksRoot;
    }

    @Override
    public String name() {
        return "jk";
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
