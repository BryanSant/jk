// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.image;

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
            List<Path> dependencyJars,
            List<Path> snapshotJars,
            Path classesDir) {

        public Plan {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(mainClass, "mainClass");
            Objects.requireNonNull(mainJar, "mainJar");
            dependencyJars = List.copyOf(dependencyJars);
            snapshotJars = snapshotJars == null ? List.of() : List.copyOf(snapshotJars);
            // classesDir nullable: set = classes-dir layout (Spring Boot layer mapping),
            // null = classic jar-on-classpath layout.
        }

        /** Back-compat constructor: classic layout (main jar + one dependency layer). */
        public Plan(
                ImageConfig config,
                String artifact,
                String version,
                String mainClass,
                Path mainJar,
                List<Path> dependencyJars) {
            this(config, artifact, version, mainClass, mainJar, dependencyJars, List.of(), null);
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

        // Layer 1 — release dependency jars (change least often).
        if (!plan.dependencyJars().isEmpty()) {
            builder = builder.addLayer(plan.dependencyJars(), AbsoluteUnixPath.get("/app/libs"));
        }
        // Layer 2 — SNAPSHOT dependency jars (their own layer: they churn while releases don't,
        // so a snapshot bump never invalidates the big release-deps layer). Boot layer mapping.
        if (!plan.snapshotJars().isEmpty()) {
            builder = builder.addLayer(plan.snapshotJars(), AbsoluteUnixPath.get("/app/libs"));
        }
        // Layer 3 — the application: either exploded classes (Boot layer mapping — the
        // most-frequently-changing bytes ride the smallest layer) or the classic main jar.
        String appClasspath;
        if (plan.classesDir() != null) {
            builder = builder.addFileEntriesLayer(classesLayer(plan.classesDir()));
            appClasspath = "/app/classes:/app/libs/*";
        } else {
            builder = builder.addLayer(List.of(plan.mainJar()), AbsoluteUnixPath.get("/app/classpath"));
            appClasspath = "/app/classpath/*:/app/libs/*";
        }

        // Entrypoint: java -cp <app classpath> <main>
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
        entrypoint.add(appClasspath);
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
        // Creation time = mtime of the primary payload jar. If the jar was restored
        // from the CAS (cache hit), its mtime is preserved by hard-link / COPY_ATTRIBUTES,
        // so the timestamp reflects "when this content was first produced" — stable across
        // repeated builds of unchanged code, accurate when code changes, and git-independent.
        Instant creationTime;
        try {
            creationTime = java.nio.file.Files.getLastModifiedTime(plan.mainJar()).toInstant();
        } catch (IOException ignored) {
            creationTime = Instant.now();
        }
        builder = builder.setCreationTime(creationTime);

        try {
            return builder.containerize(containerizer);
        } catch (RegistryException | ExecutionException | CacheDirectoryCreationException e) {
            throw new IOException("image build failed: " + e.getMessage(), e);
        }
    }

    /**
     * The exploded app-classes layer at {@code /app/classes}, with jk's freshness stamps
     * ({@code .jstamp}/{@code .kstamp}/{@code .test-stamp}) filtered out — build-host metadata,
     * never image content. Files sorted for deterministic layer bytes.
     */
    private static com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer classesLayer(Path classesDir)
            throws IOException {
        var layer = com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer.builder().setName("classes");
        AbsoluteUnixPath target = AbsoluteUnixPath.get("/app/classes");
        List<Path> files = new ArrayList<>();
        try (var stream = java.nio.file.Files.walk(classesDir)) {
            stream.filter(java.nio.file.Files::isRegularFile).forEach(files::add);
        }
        files.sort(java.util.Comparator.comparing(p -> classesDir.relativize(p).toString()));
        for (Path file : files) {
            String rel = classesDir.relativize(file).toString().replace(java.io.File.separatorChar, '/');
            if (rel.endsWith(".jstamp") || rel.endsWith(".kstamp") || rel.endsWith(".test-stamp")) continue;
            layer.addEntry(file, target.resolve(rel));
        }
        return layer.build();
    }

    private static RegistryImage registryTarget(Plan plan) throws InvalidImageReferenceException {
        return RegistryImage.named(plan.config().targetReference(plan.artifact(), plan.version()));
    }

    /** Convert parsed HOCON data into an {@link ImageConfig}. */
    public static ImageConfig fromParsed(Map<String, Object> envMap, ImageConfig defaults) {
        return defaults; // placeholder — see ImageCommand for the bridge.
    }
}
