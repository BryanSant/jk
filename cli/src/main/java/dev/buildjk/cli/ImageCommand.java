// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.config.BuildJkParser;
import dev.buildjk.config.ImageConfigParser;
import dev.buildjk.image.ImageBuilder;
import dev.buildjk.image.ImageConfig;
import dev.buildjk.lock.Lockfile;
import dev.buildjk.lock.LockfileReader;
import dev.buildjk.model.BuildJk;
import dev.buildjk.model.Coordinate;
import dev.buildjk.repo.MavenLayout;
import dev.buildjk.util.Hashing;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jk image} — build an OCI image for the project (PRD §22).
 *
 * <p>Reads {@code build.jk} for project coords and the optional
 * {@code image { ... }} block. The main jar must already be built under
 * {@code target/<artifact>-<version>.jar}. Dependency jars come from the
 * CAS via {@code jk.lock}.
 *
 * <p>{@code --tarball <path>} writes an OCI archive locally instead of
 * pushing — useful for tests and for inspecting layers without a registry.
 */
@Command(name = "image", description = "Build and push an OCI image for the project")
public final class ImageCommand implements Callable<Integer> {

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Option(names = "--main",
            description = "Main class to set as the image entrypoint. Default: image.main-class or project.main.")
    String mainClass;

    @Option(names = "--registry",
            description = "Override image.registry from build.jk.")
    String registry;

    @Option(names = "--tag",
            description = "Override image.tag from build.jk (default: project.version).")
    String tag;

    @Option(names = "--tarball",
            description = "Write an OCI tarball to this path instead of pushing.")
    Path tarball;

    @Option(names = "--home", hidden = true,
            description = "Override the jk home root. Default: ~/.jk.")
    Path home;

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path projectDir = directory != null
                ? directory : Path.of(".").toAbsolutePath().normalize();
        Path buildJkPath = projectDir.resolve("jk.toml");
        if (!Files.exists(buildJkPath)) {
            System.err.println("jk image: " + buildJkPath + " not found.");
            return 66;
        }
        BuildJk project = BuildJkParser.parse(buildJkPath);

        ImageConfig config = buildConfig(buildJkPath);
        Path mainJar = projectDir.resolve("target").resolve(
                project.project().artifact() + "-" + project.project().version() + ".jar");
        if (!Files.exists(mainJar)) {
            System.err.println("jk image: main jar not found at " + mainJar
                    + " — run `jk build` first.");
            return 66;
        }

        String chosenMain = mainClass != null ? mainClass : config.mainClass();
        if (chosenMain == null || chosenMain.isBlank()) {
            System.err.println("jk image: no main class set — pass --main or set image.main-class.");
            return 64;
        }

        List<Path> depJars = loadDependencyJars(projectDir);

        ImageBuilder.Plan plan = new ImageBuilder.Plan(
                config, project.project().artifact(), project.project().version(),
                chosenMain, mainJar, depJars);

        if (tarball != null) {
            ImageBuilder.writeToTarball(plan, tarball);
            System.out.println("Wrote OCI tarball " + tarball
                    + " (" + plan.dependencyJars().size() + " dep layers, main jar layer)");
        } else {
            ImageBuilder.pushToRegistry(plan);
            System.out.println("Pushed " + config.targetReference(
                    project.project().artifact(), project.project().version()));
        }
        return 0;
    }

    private ImageConfig buildConfig(Path buildJk) throws IOException {
        ImageConfigParser.ImageConfigData parsed = ImageConfigParser.parse(buildJk);
        return new ImageConfig(
                parsed.base(), parsed.user(), parsed.ports(),
                parsed.env(), parsed.labels(),
                registry != null ? registry : parsed.registry(),
                tag != null ? tag : parsed.tag(),
                parsed.platforms(), parsed.mainClass());
    }

    /**
     * Resolve every {@code MAIN} / {@code RUNTIME} scope jar in the lockfile
     * into a path under the CAS. Missing entries are silently skipped — jk
     * sync should have populated them, but partial caches are recoverable.
     */
    private List<Path> loadDependencyJars(Path projectDir) throws IOException {
        Path lockPath = projectDir.resolve("jk.lock");
        if (!Files.exists(lockPath)) return List.of();
        Path jkHome = home != null
                ? home : Path.of(System.getProperty("user.home"), ".jk");
        Path casRoot = jkHome.resolve("cache").resolve("sha256");
        Lockfile lock = LockfileReader.read(lockPath);
        List<Path> result = new ArrayList<>();
        for (Lockfile.Package pkg : lock.packages()) {
            if (pkg.checksum() == null) continue;
            String hex = pkg.checksum().startsWith("sha256:")
                    ? pkg.checksum().substring("sha256:".length())
                    : pkg.checksum();
            Path candidate = casRoot.resolve(hex.substring(0, 2)).resolve(hex);
            if (Files.exists(candidate)) result.add(candidate);
        }
        return result;
    }

    @SuppressWarnings("unused")
    private static Coordinate coordOf(Lockfile.Package pkg) {
        int colon = pkg.name().indexOf(':');
        return Coordinate.of(pkg.name().substring(0, colon),
                pkg.name().substring(colon + 1), pkg.version());
    }

    @SuppressWarnings("unused")
    private static String hashSource(String url) {
        return Hashing.sha256Hex(url.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unused")
    private static String mavenPath(Coordinate c) {
        return MavenLayout.artifactPath(c);
    }
}
