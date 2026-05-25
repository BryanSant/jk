// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.discovery;

import dev.jkbuild.jdk.IntellijJdkDir;
import dev.jkbuild.jdk.JdkHit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Surfaces JDKs under the IntelliJ JDK directory — the same location
 * {@code jk jdk install} writes to:
 *
 * <ul>
 *   <li>Linux / Windows: {@code ~/.jdks/<install-folder-name>/}</li>
 *   <li>macOS: {@code ~/Library/Java/JavaVirtualMachines/<install-folder-name>/Contents/Home/}</li>
 * </ul>
 *
 * <p>On macOS, JetBrains stores JDKs as {@code .jdk} bundles; the real
 * {@code JAVA_HOME} is under {@code Contents/Home}. This probe applies the
 * unwrap convention (see {@link IntellijJdkDir#javaHome}) before handing
 * the path to {@link ProbeSupport#discoverJdk}.
 */
public final class JetbrainsProbe implements LocalToolProbe {

    private final Path jdksRoot;

    public JetbrainsProbe() {
        this(IntellijJdkDir.root());
    }

    public JetbrainsProbe(Path jdksRoot) {
        this.jdksRoot = jdksRoot;
    }

    @Override
    public String name() { return "intellij"; }

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
        if (!Files.isDirectory(jdksRoot)) return List.of(); // fail fast
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
