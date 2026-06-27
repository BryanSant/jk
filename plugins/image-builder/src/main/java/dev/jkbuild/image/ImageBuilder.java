// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.image;

import com.google.cloud.tools.jib.api.CacheDirectoryCreationException;
import com.google.cloud.tools.jib.api.Containerizer;
import com.google.cloud.tools.jib.api.DockerDaemonImage;
import com.google.cloud.tools.jib.api.InvalidImageReferenceException;
import com.google.cloud.tools.jib.api.Jib;
import com.google.cloud.tools.jib.api.JibContainer;
import com.google.cloud.tools.jib.api.JibContainerBuilder;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.api.RegistryImage;
import com.google.cloud.tools.jib.api.TarImage;
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath;
import com.google.cloud.tools.jib.api.buildplan.Platform;
import com.google.cloud.tools.jib.api.buildplan.Port;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Jib-core-backed OCI image builder (PRD §22). Wraps {@link Jib} sufficiently to:
 *
 * <ul>
 *   <li>Pull a base image from a registry (PRD §22.1 default is distroless java21).
 *   <li>Layer dependency jars under {@code /app/libs/}, the project jar under {@code
 *       /app/classpath/}, classpath ordering preserved.
 *   <li>Apply {@link ImageConfig} entrypoint / user / env / labels / ports / platforms.
 *   <li>Push to a registry, or stream to a local tarball ({@code --tarball} mode for hermetic
 *       tests).
 *   <li>Stamp deterministic timestamps so two runs produce byte-identical layers.
 * </ul>
 */
public final class ImageBuilder {

    private ImageBuilder() {}

    public record Plan(
            ImageConfig config,
            String artifact,
            String version,
            String mainClass,
            Path mainJar,
            List<Path> dependencyJars) {

        public Plan {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(mainClass, "mainClass");
            Objects.requireNonNull(mainJar, "mainJar");
            dependencyJars = List.copyOf(dependencyJars);
        }
    }

    public record Result(String imageReference, String digest) {}

    /** Push to a registry. */
    public static Result pushToRegistry(Plan plan) throws IOException, InterruptedException {
        try {
            JibContainer container = run(plan, Containerizer.to(registryTarget(plan)));
            return new Result(
                    plan.config().targetReference(plan.artifact(), plan.version()),
                    container.getDigest().toString());
        } catch (InvalidImageReferenceException e) {
            throw new IOException("invalid target image reference: " + e.getMessage(), e);
        }
    }

    /**
     * Load the image directly into the local Docker/Podman daemon. {@code dockerExecutable} is the
     * resolved CLI path (e.g. {@code "docker"} or {@code "podman"}, or an absolute path); pass
     * {@code null} to let Jib auto-detect via {@code PATH}.
     */
    public static Result loadToLocalDaemon(Plan plan, Path dockerExecutable)
            throws IOException, InterruptedException {
        try {
            DockerDaemonImage target = DockerDaemonImage.named(
                    plan.config().targetReference(plan.artifact(), plan.version()));
            if (dockerExecutable != null) target = target.setDockerExecutable(dockerExecutable);
            JibContainer container = run(plan, Containerizer.to(target));
            return new Result(
                    plan.config().targetReference(plan.artifact(), plan.version()),
                    container.getDigest().toString());
        } catch (InvalidImageReferenceException e) {
            throw new IOException("invalid target image reference: " + e.getMessage(), e);
        }
    }

    /** Build to a local OCI tarball ({@code --tarball} mode). */
    public static Result writeToTarball(Plan plan, Path tarball) throws IOException, InterruptedException {
        try {
            JibContainer container = run(
                    plan,
                    Containerizer.to(TarImage.at(tarball)
                            .named(plan.config().targetReference(plan.artifact(), plan.version()))));
            return new Result(
                    plan.config().targetReference(plan.artifact(), plan.version()),
                    container.getDigest().toString());
        } catch (InvalidImageReferenceException e) {
            throw new IOException("invalid target image reference: " + e.getMessage(), e);
        }
    }

    private static JibContainer run(Plan plan, Containerizer containerizer)
            throws IOException, InterruptedException, InvalidImageReferenceException {
        ImageConfig cfg = plan.config();
        JibContainerBuilder builder;
        try {
            builder = Jib.from(RegistryImage.named(cfg.base()));
        } catch (InvalidImageReferenceException e) {
            throw new IOException("invalid base image: " + cfg.base(), e);
        }

        // Layer 1 — dependency jars (rarely change).
        if (!plan.dependencyJars().isEmpty()) {
            builder = builder.addLayer(plan.dependencyJars(), AbsoluteUnixPath.get("/app/libs"));
        }
        // Layer 2 — main jar.
        builder = builder.addLayer(List.of(plan.mainJar()), AbsoluteUnixPath.get("/app/classpath"));

        // Entrypoint: java -cp /app/classpath/*:/app/libs/* <main>
        List<String> entrypoint = new ArrayList<>();
        entrypoint.add("java");
        if (!cfg.env().isEmpty()) {
            // JAVA_OPTS is the conventional hook; values are joined with spaces.
            String javaOpts = cfg.env().get("JAVA_OPTS");
            if (javaOpts != null && !javaOpts.isBlank()) {
                for (String token : javaOpts.trim().split("\\s+")) entrypoint.add(token);
            }
        }
        entrypoint.add("-cp");
        entrypoint.add("/app/classpath/*:/app/libs/*");
        entrypoint.add(plan.mainClass());
        builder = builder.setEntrypoint(entrypoint);

        if (cfg.user() != null && !cfg.user().isBlank()) {
            builder = builder.setUser(cfg.user());
        }
        if (!cfg.ports().isEmpty()) {
            Set<Port> ports = new HashSet<>();
            for (int p : cfg.ports()) ports.add(Port.tcp(p));
            builder = builder.setExposedPorts(ports);
        }
        if (!cfg.env().isEmpty()) {
            builder = builder.setEnvironment(cfg.env());
        }
        if (!cfg.labels().isEmpty()) {
            builder = builder.setLabels(cfg.labels());
        }
        if (!cfg.platforms().isEmpty()) {
            Set<Platform> platforms = new HashSet<>();
            for (String p : cfg.platforms()) {
                int slash = p.indexOf('/');
                if (slash <= 0 || slash >= p.length() - 1) {
                    throw new IOException("invalid platform `" + p + "` (expect os/arch)");
                }
                platforms.add(new Platform(p.substring(slash + 1), p.substring(0, slash)));
            }
            builder = builder.setPlatforms(platforms);
        }
        // Reproducible timestamps (PRD §22.1).
        builder = builder.setCreationTime(Instant.EPOCH);

        try {
            return builder.containerize(containerizer);
        } catch (RegistryException | ExecutionException | CacheDirectoryCreationException e) {
            throw new IOException("image build failed: " + e.getMessage(), e);
        }
    }

    private static RegistryImage registryTarget(Plan plan) throws InvalidImageReferenceException {
        return RegistryImage.named(plan.config().targetReference(plan.artifact(), plan.version()));
    }

    /** Convert parsed HOCON data into an {@link ImageConfig}. */
    public static ImageConfig fromParsed(Map<String, Object> envMap, ImageConfig defaults) {
        return defaults; // placeholder — see ImageCommand for the bridge.
    }
}
