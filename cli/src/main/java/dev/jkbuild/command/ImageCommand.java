// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.config.ImageConfigParser;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.image.ImageConfig;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.runtime.BuildPipeline;
import dev.jkbuild.runtime.CompileToolchain;
import dev.jkbuild.util.JkDirs;
import dev.jkbuild.worker.WorkerJar;
import dev.jkbuild.worker.WorkerProcess;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code jk image} — build an OCI image for the project (PRD §22). Runs the full build pipeline
 * (compile → test → package) in-process, then forks the {@code jk-image-runner} worker for the
 * Jib-backed image step so Jib, Guava, and the Google HTTP stack never load in the main jk process.
 */
public final class ImageCommand implements CliCommand {

    @Override
    public String name() {
        return "image";
    }

    @Override
    public String description() {
        return "Bundle this project into an OCI image";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<class>", "Main class to set as the image entrypoint.", "--main"),
                Opt.value("<registry>", "Override image.registry from jk.toml.", "--registry"),
                Opt.value("<tag>", "Override image.tag from jk.toml.", "--tag"),
                Opt.value("<path>", "Write an OCI tarball instead of pushing.", "--tarball")
                        .withFallback(""),
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                        .hide(),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir")
                        .hide(),
                Opt.flag("Skip compiling and running tests.", "--skip-tests"));
    }

    String mainClass;
    String registry;
    String tag;
    String tarballArg;
    Path cacheDirOverride;
    Path jdksDir;
    dev.jkbuild.cli.BuildOptions buildOpts;
    GlobalOptions global;

    private static final GoalKey<ImageConfig> CONFIG = GoalKey.of("image-config", ImageConfig.class);
    private static final GoalKey<Path> TARBALL_PATH = GoalKey.of("tarball-path", Path.class);

    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> DEP_JARS = GoalKey.of("dep-jars", List.class);

    private static final GoalKey<String> IMAGE_REF = GoalKey.of("image-ref", String.class);

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.mainClass = in.value("main").orElse(null);
        this.registry = in.value("registry").orElse(null);
        this.tag = in.value("tag").orElse(null);
        this.tarballArg = in.value("tarball").orElse(null);
        this.cacheDirOverride = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.buildOpts = new dev.jkbuild.cli.BuildOptions();
        this.buildOpts.skipTests = in.isSet("skip-tests");
        this.global = GlobalOptions.from(in);
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
                projectDir,
                cache,
                jkBuildPath,
                lockFile,
                projectDir,
                1,
                estimatedTestCount,
                null,
                jdksDir,
                buildOpts.skipTests,
                global.verbose);

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
                    String chosen = resolveMainClass(mainClass, config, project, projectDir);
                    if (chosen == null || chosen.isBlank()) {
                        ctx.error("no-main",
                                "no main class — pass --main, set image.main, or set project.main.");
                        throw new RuntimeException("missing main class");
                    }
                    ctx.put(DEP_JARS, loadDependencyJars(projectDir, cache));
                    ctx.progress(1);
                })
                .build();

        Phase writeImage = Phase.builder("write-image")
                .kind(PhaseKind.IO)
                .requires("image-plan")
                .weight(() -> dev.jkbuild.runtime.EffortWeights.ociWeight(projectDir))
                .scope(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(BuildPipeline.PROJECT);
                    BuildLayout layout = ctx.require(BuildPipeline.LAYOUT);
                    ImageConfig config = ctx.require(CONFIG);
                    Path tarballPath = ctx.get(TARBALL_PATH).orElse(null);
                    @SuppressWarnings("unchecked")
                    List<Path> depJars = (List<Path>) ctx.require(DEP_JARS);

                    String chosen = resolveMainClass(mainClass, config, project, projectDir);

                    // Packaging cache — tarball only. A registry push is a network
                    // side-effect (the remote's state is unknown), so it's never skipped.
                    // The tarball is a pure function of the main jar, the dependency jars,
                    // the main class, the image config, and the image-builder worker version.
                    dev.jkbuild.task.ActionCache ac =
                            new dev.jkbuild.task.ActionCache(new Cas(cache), cache.resolve("actions"));
                    boolean useCache = tarballPath != null
                            && !dev.jkbuild.config.ActiveConfig.get().rerunOr(false);
                    String imgTask = null, imgKey = null;
                    if (tarballPath != null) {
                        List<String> tokens = List.of(
                                "mainjar:" + dev.jkbuild.task.ClasspathFingerprint.entry(layout.mainJar()),
                                "deps:" + dev.jkbuild.task.ClasspathFingerprint.of(depJars),
                                "main:" + chosen,
                                "cfg:" + imageConfigToken(config),
                                "worker:" + WorkerJar.IMAGE_BUILDER.artifactId() + ":"
                                        + dev.jkbuild.util.JkVersion.VERSION);
                        imgTask = dev.jkbuild.task.ActionKey.qualifiedTaskId("write-image", tarballPath);
                        imgKey = dev.jkbuild.task.ActionKey.forArtifact(
                                imgTask, dev.jkbuild.util.JkVersion.VERSION, tokens);
                        if (useCache) {
                            var hit = ac.lookup(imgKey);
                            if (hit.isPresent() && ac.restoreArtifacts(hit.get(), tarballPath.getParent())) {
                                ctx.put(IMAGE_REF, "");
                                ctx.label(tarballPath.getFileName() + " up-to-date");
                                ctx.progress(1);
                                return;
                            }
                        }
                    }
                    ctx.label(
                            tarballPath != null
                                    ? "write OCI tarball " + tarballPath.getFileName()
                                    : "push to "
                                            + config.targetReference(
                                                    project.project().name(),
                                                    project.project().version()));
                    try {
                        String ref = runImageWorker(cache, project, layout, config, chosen, depJars, tarballPath);
                        ctx.put(IMAGE_REF, ref);
                    } catch (RuntimeException e) {
                        ctx.error("image", e.getMessage());
                        throw e;
                    }
                    if (useCache) {
                        ac.storeArtifacts(imgTask, imgKey, Map.of(), tarballPath.getParent(), List.of(tarballPath));
                    }
                    ctx.progress(1);
                })
                .build();

        Goal.Builder builder = BuildPipeline.coreBuilder(inputs);
        builder.addPhase(imagePlan).addPhase(writeImage);
        Goal goal = builder.build();

        ConsoleSpec spec = new ConsoleSpec("Image", r -> "Built image", r -> "Image build failed");
        GoalResult result = GoalConsole.runGoal(
                goal, GoalConsole.modeFor(global), cache, spec, BuildCommand.buildTarget(jkBuildPath, projectDir));

        if (!result.success()) {
            for (GoalResult.Diagnostic d : result.errors()) {
                if ("no-main".equals(d.code())) return 64;
            }
            var testResult = goal.get(BuildPipeline.TEST_RESULT).orElse(null);
            if (testResult != null && !testResult.allPassed()) return 4;
            return 1;
        }

        if (!global.outputIsJson()) {
            JkBuild project = goal.get(BuildPipeline.PROJECT).orElseThrow();
            Path tarballPath = goal.get(TARBALL_PATH).orElse(null);
            if (tarballPath != null) {
                System.out.println("Wrote OCI tarball " + tarballPath);
            } else {
                String ref = goal.get(IMAGE_REF)
                        .orElse(goal.get(CONFIG)
                                .map(c -> c.targetReference(
                                        project.project().name(),
                                        project.project().version()))
                                .orElse(""));
                System.out.println("Pushed " + ref);
            }
        }
        return 0;
    }

    /** Stable serialization of the image config for the packaging cache key. */
    private static String imageConfigToken(ImageConfig c) {
        StringBuilder sb = new StringBuilder();
        sb.append("base=").append(c.base()).append(';');
        sb.append("user=").append(c.user()).append(';');
        sb.append("registry=").append(c.registry()).append(';');
        sb.append("tag=").append(c.tag()).append(';');
        sb.append("ports=").append(new java.util.TreeSet<>(c.ports())).append(';');
        sb.append("env=").append(new java.util.TreeMap<>(c.env())).append(';');
        sb.append("labels=").append(new java.util.TreeMap<>(c.labels())).append(';');
        sb.append("platforms=").append(new java.util.ArrayList<>(c.platforms())).append(';');
        return sb.toString();
    }

    private String runImageWorker(
            Path cache,
            JkBuild project,
            BuildLayout layout,
            ImageConfig config,
            String chosen,
            List<Path> depJars,
            Path tarballPath)
            throws IOException, InterruptedException {
        Path workerJar = WorkerJar.IMAGE_BUILDER.locate(new Cas(cache));
        List<String> lines = new ArrayList<>();
        lines.add("MAIN_JAR " + layout.mainJar().toAbsolutePath());
        lines.add("ARTIFACT " + project.project().name());
        lines.add("VERSION " + project.project().version());
        lines.add("MAIN_CLASS " + chosen);
        if (config.base() != null) lines.add("BASE " + config.base());
        if (config.user() != null) lines.add("USER " + config.user());
        if (config.registry() != null) lines.add("REGISTRY " + config.registry());
        if (config.tag() != null) lines.add("TAG " + config.tag());
        if (tarballPath != null) lines.add("TARBALL " + tarballPath.toAbsolutePath());
        for (int p : config.ports()) lines.add("PORT " + p);
        for (var e : config.env().entrySet()) lines.add("ENV " + e.getKey() + "=" + e.getValue());
        for (var e : config.labels().entrySet()) lines.add("LABEL " + e.getKey() + "=" + e.getValue());
        for (String plat : config.platforms()) lines.add("PLATFORM " + plat);
        for (Path dep : depJars) lines.add("DEP_JAR " + dep.toAbsolutePath());

        Path spec = Files.createTempFile("jk-image-", ".spec");
        try {
            Files.write(spec, lines, StandardCharsets.UTF_8);
            Path javaExe = CompileToolchain.runningJavaHome()
                    .resolve("bin")
                    .resolve(HostPlatform.isWindows() ? "java.exe" : "java");
            List<String> cmd = dev.jkbuild.worker.JvmOptions.javaCommand(
                    javaExe.toString(),
                    1,
                    List.of("-jar", workerJar.toString(), spec.toAbsolutePath().toString()));
            String[] ref = {null};
            String[] workerError = {null};
            StringBuilder diag = new StringBuilder();
            int exit = WorkerProcess.run(
                    cmd,
                    "##JKIM:",
                    json -> {
                        if ("result".equals(Ndjson.str(json, "t"))) {
                            ref[0] = Ndjson.str(json, "ref");
                            String err = Ndjson.str(json, "error");
                            if (err != null) workerError[0] = err;
                        }
                    },
                    ln -> diag.append(ln).append('\n'));
            if (workerError[0] != null) throw new RuntimeException("image worker: " + workerError[0]);
            if (exit != 0) {
                String d = diag.length() > 0 ? diag.toString().trim() : null;
                throw new RuntimeException("image worker failed" + (d != null ? ": " + d : " (exit " + exit + ")"));
            }
            return ref[0] != null ? ref[0] : "";
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    private Path resolveTarballPath(BuildLayout layout) {
        if (tarballArg == null) return null;
        if (tarballArg.isBlank()) return layout.ociImageTar();
        return Path.of(tarballArg);
    }

    private ImageConfig buildConfig(Path jkBuild) throws IOException {
        ImageConfigParser.ImageConfigData parsed = ImageConfigParser.parse(jkBuild);
        return new ImageConfig(
                parsed.base(),
                parsed.user(),
                parsed.ports(),
                parsed.env(),
                parsed.labels(),
                registry != null ? registry : parsed.registry(),
                tag != null ? tag : parsed.tag(),
                parsed.platforms(),
                parsed.main());
    }

    /**
     * Resolve the main class to use for the OCI image entrypoint. Priority:
     *
     * <ol>
     *   <li>{@code --main} CLI flag
     *   <li>{@code image.main} in jk.toml
     *   <li>{@code project.main} in jk.toml
     *   <li>The sole {@code project.main} across all workspace modules (fails if more than one)
     * </ol>
     *
     * Returns {@code null} when no main class can be determined.
     */
    private static String resolveMainClass(
            String cliMain, ImageConfig config, JkBuild project, Path projectDir) {
        if (cliMain != null && !cliMain.isBlank()) return cliMain;
        if (config.main() != null && !config.main().isBlank()) return config.main();
        if (project.project().main() != null && !project.project().main().isBlank()) {
            return project.project().main();
        }
        // Workspace fallback: scan modules for a project.main.
        if (!project.isWorkspaceRoot()) return null;
        try {
            var modules = WorkspaceLoader.loadModules(projectDir, project);
            List<String> mains = modules.values().stream()
                    .map(m -> m.project().main())
                    .filter(m -> m != null && !m.isBlank())
                    .distinct()
                    .toList();
            if (mains.size() == 1) return mains.get(0);
            if (mains.size() > 1) {
                throw new RuntimeException("Multiple main classes discovered. Set "
                        + dev.jkbuild.cli.theme.Theme.colorize(
                                "image.main", dev.jkbuild.cli.theme.Theme.active().focused())
                        + " in jk.toml.");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception ignored) {
        }
        return null;
    }

    private static List<Path> loadDependencyJars(Path projectDir, Path cache) throws IOException {
        Path lockPath = projectDir.resolve("jk.lock");
        if (!Files.exists(lockPath)) return List.of();
        Lockfile lock = LockfileReader.read(lockPath);
        List<Path> result = new ArrayList<>();
        Cas cas = new Cas(cache);
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            if (pkg.checksum() == null) continue;
            String hex = pkg.checksum().startsWith("sha256:")
                    ? pkg.checksum().substring("sha256:".length())
                    : pkg.checksum();
            Path candidate = cas.pathFor(hex);
            if (Files.exists(candidate)) result.add(candidate);
        }
        return result;
    }
}
