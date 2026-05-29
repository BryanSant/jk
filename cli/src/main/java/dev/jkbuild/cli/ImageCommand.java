// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.ImageConfigParser;
import dev.jkbuild.image.ImageBuilder;
import dev.jkbuild.image.ImageConfig;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.run.PhaseStatus;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jk image} — build an OCI image for the project (PRD §22).
 *
 * <p>Goal shape: {@code parse-build} (SYNC) reads jk.toml, picks
 * main class, locates main jar; {@code load-deps} (SYNC) reads the
 * lockfile and resolves CAS paths for every MAIN/RUNTIME dependency
 * jar; {@code write-image} (IO) either writes the OCI tarball or
 * pushes to the configured registry.
 *
 * <p>{@code --tarball <path>} writes an OCI archive locally instead of
 * pushing.
 */
@Command(name = "image", description = "Build and push an OCI image for the project")
public final class ImageCommand implements Callable<Integer> {

    @Option(names = "--main",
            description = "Main class to set as the image entrypoint. Default: image.main-class or project.main.")
    String mainClass;

    @Option(names = "--registry",
            description = "Override image.registry from jk.toml.")
    String registry;

    @Option(names = "--tag",
            description = "Override image.tag from jk.toml (default: project.version).")
    String tag;

    @Option(names = "--tarball", arity = "0..1", fallbackValue = "",
            description = "Write an OCI tarball instead of pushing. Optional <path>; "
                    + "defaults to target/<artifact>.oci.tar.")
    String tarballArg;

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
    Path cacheDirOverride;

    @picocli.CommandLine.Mixin GlobalOptions global;

    private static final GoalKey<JkBuild> PROJECT = GoalKey.of("project", JkBuild.class);
    private static final GoalKey<BuildLayout> LAYOUT = GoalKey.of("layout", BuildLayout.class);
    private static final GoalKey<ImageConfig> CONFIG = GoalKey.of("image-config", ImageConfig.class);
    private static final GoalKey<Path> MAIN_JAR = GoalKey.of("main-jar", Path.class);
    private static final GoalKey<Path> TARBALL_PATH = GoalKey.of("tarball-path", Path.class);
    private static final GoalKey<String> CHOSEN_MAIN = GoalKey.of("chosen-main", String.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> DEP_JARS = GoalKey.of("dep-jars", List.class);
    private static final GoalKey<ImageBuilder.Plan> PLAN = GoalKey.of("plan", ImageBuilder.Plan.class);

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path projectDir = global.workingDir();
        Path jkBuildPath = projectDir.resolve("jk.toml");
        if (!Files.exists(jkBuildPath)) {
            System.err.println("jk image: " + jkBuildPath + " not found.");
            return 66;
        }
        Path cache = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();

        Phase parseBuild = Phase.builder("parse-build")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml + image config");
                    JkBuild project = JkBuildParser.parse(jkBuildPath);
                    ctx.put(PROJECT, project);
                    BuildLayout layout = BuildLayout.of(projectDir, project);
                    ctx.put(LAYOUT, layout);
                    Path tarballPath = resolveTarballPath(layout);
                    if (tarballPath != null) ctx.put(TARBALL_PATH, tarballPath);
                    ImageConfig config = buildConfig(jkBuildPath);
                    ctx.put(CONFIG, config);

                    Path mainJar = layout.mainJar();
                    if (!Files.exists(mainJar)) {
                        ctx.error("missing-jar", "main jar not found at " + mainJar
                                + " — run `jk build` first.");
                        throw new RuntimeException("missing main jar");
                    }
                    ctx.put(MAIN_JAR, mainJar);

                    String chosen = mainClass != null ? mainClass : config.mainClass();
                    if (chosen == null || chosen.isBlank()) {
                        ctx.error("no-main", "no main class set — pass --main or "
                                + "set image.main-class.");
                        throw new RuntimeException("missing main class");
                    }
                    ctx.put(CHOSEN_MAIN, chosen);
                    ctx.progress(1);
                })
                .build();

        Phase loadDeps = Phase.builder("load-deps")
                .requires("parse-build")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("collect dep jars from CAS");
                    List<Path> depJars = loadDependencyJars(projectDir, cache);
                    ctx.put(DEP_JARS, depJars);

                    JkBuild project = ctx.require(PROJECT);
                    @SuppressWarnings("unchecked")
                    List<Path> jars = depJars;
                    ImageBuilder.Plan plan = new ImageBuilder.Plan(
                            ctx.require(CONFIG),
                            project.project().artifact(),
                            project.project().version(),
                            ctx.require(CHOSEN_MAIN),
                            ctx.require(MAIN_JAR),
                            jars);
                    ctx.put(PLAN, plan);
                    ctx.progress(1);
                })
                .build();

        Phase writeImage = Phase.builder("write-image")
                .kind(PhaseKind.IO)
                .requires("load-deps")
                .scope(1)
                .execute(ctx -> {
                    ImageBuilder.Plan plan = ctx.require(PLAN);
                    Path tarballPath = ctx.get(TARBALL_PATH).orElse(null);
                    if (tarballPath != null) {
                        ctx.label("write OCI tarball " + tarballPath.getFileName());
                        Files.createDirectories(tarballPath.getParent());
                        ImageBuilder.writeToTarball(plan, tarballPath);
                    } else {
                        ctx.label("push to " + plan.config().registry());
                        try {
                            ImageBuilder.pushToRegistry(plan);
                        } catch (Exception e) {
                            ctx.error("push", e.getMessage());
                            throw new RuntimeException(e);
                        }
                    }
                    ctx.progress(1);
                })
                .build();

        Goal goal = Goal.builder("image")
                .addPhase(parseBuild)
                .addPhase(loadDeps)
                .addPhase(writeImage)
                .build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);
        if (!result.success()) {
            for (GoalResult.Diagnostic d : result.errors()) {
                if ("missing-jar".equals(d.code())) return 66;
                if ("no-main".equals(d.code())) return 64;
            }
            return 1;
        }

        ImageBuilder.Plan plan = goal.get(PLAN).orElseThrow();
        JkBuild project = goal.get(PROJECT).orElseThrow();
        ImageConfig config = goal.get(CONFIG).orElseThrow();
        Path tarballPath = goal.get(TARBALL_PATH).orElse(null);
        if (!global.outputIsJson()) {
            if (tarballPath != null) {
                System.out.println("Wrote OCI tarball " + tarballPath
                        + " (" + plan.dependencyJars().size() + " dep layers, main jar layer)");
            } else {
                System.out.println("Pushed " + config.targetReference(
                        project.project().artifact(), project.project().version()));
            }
        }
        return 0;
    }

    /**
     * Map the {@code --tarball} CLI flag to an effective output path.
     *
     * <ul>
     *   <li>Flag absent → {@code null} (push mode).</li>
     *   <li>Flag with no path → {@code layout.ociImageTar()}.</li>
     *   <li>Flag with explicit path → that path verbatim.</li>
     * </ul>
     */
    private Path resolveTarballPath(BuildLayout layout) {
        if (tarballArg == null) return null;
        if (tarballArg.isBlank()) return layout.ociImageTar();
        return Path.of(tarballArg);
    }

    private ImageConfig buildConfig(Path jkBuild) throws IOException {
        ImageConfigParser.ImageConfigData parsed = ImageConfigParser.parse(jkBuild);
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
    private static List<Path> loadDependencyJars(Path projectDir, Path cache) throws IOException {
        Path lockPath = projectDir.resolve("jk.lock");
        if (!Files.exists(lockPath)) return List.of();
        Path casRoot = cache.resolve("sha256");
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
}
