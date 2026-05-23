// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.discovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * {@code ~/.jenv/versions/<version>/} (JDKs only). jenv typically
 * symlinks here from the original install location; we follow the
 * link via {@link Path#toRealPath()} before validating.
 */
public final class JenvProbe implements LocalToolProbe {

    private final Path jenvRoot;

    public JenvProbe() {
        this(Path.of(System.getProperty("user.home"), ".jenv"));
    }

    JenvProbe(Path jenvRoot) {
        this.jenvRoot = jenvRoot;
    }

    @Override
    public String name() { return "jenv"; }

    @Override
    public Optional<DiscoveredTool> find(ToolSpec spec) throws IOException {
        if (!"java".equals(spec.kind())) return Optional.empty();
        Path versionsDir = jenvRoot.resolve("versions");
        if (!Files.isDirectory(versionsDir)) return Optional.empty();
        try (Stream<Path> entries = Files.list(versionsDir)) {
            return entries.filter(Files::isDirectory)
                    .map(path -> {
                        try { return path.toRealPath(); } catch (IOException e) { return path; }
                    })
                    .filter(path -> ToolHealth.isHealthy(spec, path))
                    .findFirst()
                    .map(path -> new DiscoveredTool(path, spec.version(), name()));
        }
    }
}
