// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.runtime.BuildPipeline;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
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
 * {@code jk image} — build an OCI image for the project, from source (PRD §22).
 *
 * <p>Runs the shared {@linkplain BuildPipeline build pipeline} (compile → test →
 * package) and then composes its own tail onto the same goal: {@code image-plan}
 * (requires package-jar) picks the main class, resolves the dependency jars from
 * the CAS and assembles the {@link ImageBuilder.Plan}; {@code write-image} either
 * writes the OCI tarball or pushes to the configured registry. No prior
 * {@code jk build} and no nested {@code jk} process.
 *
 * <p>{@code --tarball <path>} writes an OCI archive locally instead of pushing.
 */
@Command(name = "image", description = "Bundle this project into an OCI image")
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

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root.")
    Path jdksDir;

    @Option(names = "--skip-tests",
            description = "Skip compiling and running tests before building the image.")
    boolean skipTests;

    @picocli.CommandLine.Mixin GlobalOptions global;

    // Image-specific cross-phase keys (the core build's keys live on BuildPipeline).
    private static final GoalKey<ImageConfig> CONFIG = GoalKey.of("image-config", ImageConfig.class);
    private static final GoalKey<Path> TARBALL_PATH = GoalKey.of("tarball-path", Path.class);
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
        Path lockFile = projectDir.resolve("jk.lock");
        int estimatedTestCount = TestCommand.estimateTestCount(projectDir.resolve("src/test/java"));

        BuildPipeline.Inputs inputs = new BuildPipeline.Inputs(
                projectDir, cache, jkBuildPath, lockFile, projectDir,
                1, estimatedTestCount, null, jdksDir, skipTests, global.verbose);

        // image-plan reads the freshly-built jar + project off the core goal,
        // then assembles the OCI plan; write-image emits it.
        Phase imagePlan = Phase.builder("image-plan")
                .requires("package-jar")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("resolve image config");
                    JkBuild project = ctx.require(BuildPipeline.PROJECT);
                    BuildLayout layout = ctx.require(BuildPipeline.LAYOUT);
                    Path tarballPath = resolveTarballPath(layout);
                    if (tarballPath != null) ctx.put(TARBALL_PATH, tarballPath);
                    ImageConfig config = buildConfig(jkBuildPath);
                    ctx.put(CONFIG, config);

                    String chosen = mainClass != null ? mainClass : config.mainClass();
                    if (chosen == null || chosen.isBlank()) {
                        ctx.error("no-main", "no main class set — pass --main or "
                                + "set image.main-class.");
                        throw new RuntimeException("missing main class");
                    }

                    Path mainJar = layout.mainJar();
                    List<Path> depJars = loadDependencyJars(projectDir, cache);
                    ImageBuilder.Plan plan = new ImageBuilder.Plan(
                            config,
                            project.project().artifact(),
                            project.project().version(),
                            chosen,
                            mainJar,
                            depJars);
                    ctx.put(PLAN, plan);
                    ctx.progress(1);
                })
                .build();

        Phase writeImage = Phase.builder("write-image")
                .kind(PhaseKind.IO)
                .requires("image-plan")
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

        Goal.Builder builder = BuildPipeline.coreBuilder(inputs);
        builder.addPhase(imagePlan).addPhase(writeImage);
        Goal goal = builder.build();

        ConsoleSpec spec = new ConsoleSpec("Image Build",
                r -> "Built image " + BuildCommand.inTime(r),
                r -> "Image build failed " + BuildCommand.inTime(r));
        GoalResult result = GoalConsole.runGoal(goal, GoalConsole.modeFor(global), cache, spec,
                BuildCommand.buildTarget(jkBuildPath, projectDir));

        if (!result.success()) {
            for (GoalResult.Diagnostic d : result.errors()) {
                if ("no-main".equals(d.code())) return 64;
            }
            var testResult = goal.get(BuildPipeline.TEST_RESULT).orElse(null);
            if (testResult != null && !testResult.allPassed()) return 4;
            return 1;
        }

        ImageBuilder.Plan plan = goal.get(PLAN).orElseThrow();
        JkBuild project = goal.get(BuildPipeline.PROJECT).orElseThrow();
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
