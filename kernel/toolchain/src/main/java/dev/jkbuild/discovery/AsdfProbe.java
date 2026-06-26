// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.discovery;

import dev.jkbuild.jdk.JdkHit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * asdf-vm keeps installs under {@code ~/.asdf/installs/<kind>/<version>/}.
 * For JDKs, the {@code <version>} is asdf-plugin-specific
 * ({@code openjdk-21.0.5}, {@code temurin-21.0.5+11}); we scan for any
 * subdir whose {@code release} file matches the requested version +
 * distribution.
 */
public final class AsdfProbe implements LocalToolProbe {

    private final Path asdfRoot;

    public AsdfProbe() {
        this(Path.of(System.getProperty("user.home"), ".asdf"));
    }

    AsdfProbe(Path asdfRoot) {
        this.asdfRoot = asdfRoot;
    }

    @Override
    public String name() {
        return "asdf";
    }

    @Override
    public Optional<DiscoveredTool> find(ToolSpec spec) throws IOException {
        Path kindDir = asdfRoot.resolve("installs").resolve(spec.kind());
        if (!Files.isDirectory(kindDir)) return Optional.empty();
        try (Stream<Path> entries = Files.list(kindDir)) {
            return entries.filter(Files::isDirectory)
                    .map(path -> {
                        try {
                            return path.toRealPath();
                        } catch (IOException e) {
                            return path;
                        }
                    })
                    .filter(path -> ToolHealth.isHealthy(spec, path))
                    .findFirst()
                    .map(path -> new DiscoveredTool(path, spec.version(), name()));
        }
    }

    @Override
    public List<JdkHit> discoverAllJdks() throws IOException {
        Path javaDir = asdfRoot.resolve("installs").resolve("java");
        if (!Files.isDirectory(javaDir)) return List.of(); // fail fast
        List<JdkHit> hits = new ArrayList<>();
        try (Stream<Path> entries = Files.list(javaDir)) {
            entries.filter(Files::isDirectory)
                    .forEach(p -> ProbeSupport.discoverJdk(p, name()).ifPresent(hits::add));
        }
        return hits;
    }
}
