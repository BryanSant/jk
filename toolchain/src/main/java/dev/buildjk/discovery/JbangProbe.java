// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.discovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * JBang caches downloaded JDKs under {@code ~/.jbang/cache/jdks/<major>/}
 * (major-version key, single distribution per major). JDKs only — JBang
 * doesn't manage Maven/Gradle/Kotlin.
 */
public final class JbangProbe implements LocalToolProbe {

    private final Path jbangRoot;

    public JbangProbe() {
        this(Path.of(System.getProperty("user.home"), ".jbang"));
    }

    JbangProbe(Path jbangRoot) {
        this.jbangRoot = jbangRoot;
    }

    @Override
    public String name() { return "jbang"; }

    @Override
    public Optional<DiscoveredTool> find(ToolSpec spec) throws IOException {
        if (!"java".equals(spec.kind())) return Optional.empty();
        Path jdksDir = jbangRoot.resolve("cache").resolve("jdks");
        if (!Files.isDirectory(jdksDir)) return Optional.empty();

        // JBang keys by major version (e.g. "21/"). Resolve and verify
        // the install matches the full requested version + distribution.
        String major = majorOf(spec.version());
        Path[] candidates = {
                jdksDir.resolve(spec.version()),
                jdksDir.resolve(major),
        };
        for (Path candidate : candidates) {
            if (!Files.isDirectory(candidate)) continue;
            Path resolved = candidate.toRealPath();
            if (ToolHealth.isHealthy(spec, resolved)) {
                return Optional.of(new DiscoveredTool(resolved, spec.version(), name()));
            }
        }
        // Last-ditch: scan the jdks dir for anything matching.
        try (Stream<Path> entries = Files.list(jdksDir)) {
            return entries.filter(Files::isDirectory)
                    .map(p -> {
                        try { return p.toRealPath(); } catch (IOException e) { return p; }
                    })
                    .filter(p -> ToolHealth.isHealthy(spec, p))
                    .findFirst()
                    .map(p -> new DiscoveredTool(p, spec.version(), name()));
        }
    }

    private static String majorOf(String version) {
        int dot = version.indexOf('.');
        return dot > 0 ? version.substring(0, dot) : version;
    }
}
