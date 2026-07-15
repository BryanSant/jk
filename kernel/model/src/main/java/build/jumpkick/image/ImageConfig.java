// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.image;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The resolved {@code [image]} configuration for {@code jk image}. Merged from the project's
 * {@code jk.toml} and the user-global {@code ~/.jk/config.toml} by {@code ImageCommand} before
 * construction, with {@code {java-major-version}} substituted in {@code base}.
 *
 * <p>{@code base} is always non-null by the time this record reaches the image worker — the CLI
 * resolves it to {@code "bellsoft/hardened-liberica-runtime-container:jre-<N>-slim-glibc"} when
 * no base is declared in either config layer.
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
        String main,
        /** Docker/Podman executable override; {@code null} → auto-detect at build time. */
        String dockerExecutable,
        /**
         * Relative path to a {@code Dockerfile} (from the project root). When set, {@code jk image}
         * shells out to {@code docker build -f <file> -t <ref> <projectDir>} instead of using Jib.
         * The compiled jar is available in the build context so the Dockerfile can {@code COPY} it.
         * {@code null} → Jib-based build (default).
         */
        String dockerFile) {

    public ImageConfig {
        ports = ports == null ? List.of() : List.copyOf(ports);
        env = env == null ? Map.of() : Map.copyOf(env);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        platforms = (platforms == null || platforms.isEmpty()) ? List.of("linux/amd64") : List.copyOf(platforms);
        // base, user, registry, tag, main, dockerExecutable, dockerFile may be null
    }

    /** Resolve the final {@code <registry>/<image>:<tag>} target. */
    public String targetReference(String artifact, String version) {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(version, "version");
        String image = (registry != null && !registry.isBlank()) ? registry + "/" + artifact : artifact;
        String t = (tag != null && !tag.isBlank()) ? tag : version;
        return image + ":" + t;
    }
}
