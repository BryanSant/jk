// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.image;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code image { ... }} block from {@code jk.toml} (PRD §22.2).
 *
 * <p>Sensible defaults: distroless Java 21 base, nonroot user, no exposed
 * ports, single {@code linux/amd64} platform. Override anything by
 * setting the matching field in jk.toml.
 */
public record ImageConfig(
        String base,
        String user,
        List<Integer> ports,
        Map<String, String> env,
        Map<String, String> labels,
        String registry,
        String tag,
        List<String> platforms,
        String mainClass) {

    public static final String DEFAULT_BASE = "gcr.io/distroless/java21-debian12:nonroot";

    public ImageConfig {
        if (base == null || base.isBlank()) base = DEFAULT_BASE;
        ports = ports == null ? List.of() : List.copyOf(ports);
        env = env == null ? Map.of() : Map.copyOf(env);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        platforms = (platforms == null || platforms.isEmpty())
                ? List.of("linux/amd64") : List.copyOf(platforms);
        // user, registry, tag, mainClass may be null
    }

    public static ImageConfig defaults() {
        return new ImageConfig(DEFAULT_BASE, "nonroot", List.of(),
                Map.of(), Map.of(), null, null, List.of("linux/amd64"), null);
    }

    /** Resolve the final {@code <registry>/<image>:<tag>} target. */
    public String targetReference(String artifact, String version) {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(version, "version");
        String image = (registry != null && !registry.isBlank())
                ? registry + "/" + artifact
                : artifact;
        String t = (tag != null && !tag.isBlank()) ? tag : version;
        return image + ":" + t;
    }
}
