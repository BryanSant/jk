// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.config.ImageConfigParser;
import dev.jkbuild.config.SessionContext;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.image.ImageConfig;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseContext;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.task.ActionCache;
import dev.jkbuild.task.ActionKey;
import dev.jkbuild.task.ClasspathFingerprint;
import dev.jkbuild.util.JkDirs;
import dev.jkbuild.util.JkVersion;
import dev.jkbuild.worker.WorkerClient;
import dev.jkbuild.worker.WorkerJar;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The shared {@code jk image} goal — the full build pipeline (compile → test → package) plus the
 * OCI tail: an image-plan phase that resolves the merged {@code [image]} config, and a write-image
 * phase that either forks the {@code jk-image-builder} (Jib) worker or, in Dockerfile mode, shells
 * out to {@code docker}/{@code podman} — hoisted out of the CLI so the resident engine can host the
 * verb (Wave 2 of {@code docs/architecture/slim-client.md}) while the command's test-only
 * in-process path builds the exact same goal.
 *
 * <p>Everything here reads the same machine state the CLI fork read (project {@code jk.toml}, the
 * user-global {@code ~/.jk/config.toml} image layer, the local container runtime) — the engine is
 * always a same-user, same-host process. All strings put into the goal are plain; the client themes
 * the success tail from the structured keys ({@link #IMAGE_REF}/{@link #TARBALL_PATH}/…).
 */
public final class ImageGoals {

    private ImageGoals() {}

    public static final GoalKey<ImageConfig> CONFIG = GoalKey.of("image-config", ImageConfig.class);
    public static final GoalKey<Path> TARBALL_PATH = GoalKey.of("tarball-path", Path.class);

    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> DEP_JARS = GoalKey.of("dep-jars", List.class);

    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> SNAPSHOT_JARS = GoalKey.of("snapshot-jars", List.class);

    public static final GoalKey<String> IMAGE_REF = GoalKey.of("image-ref", String.class);

    /**
     * The base image template used when no {@code image.base} is set in the project or global
     * config. {@code {java-major-version}} is replaced with the project's resolved JDK major.
     */
    public static final String DEFAULT_BASE_TEMPLATE =
            "bellsoft/liberica-runtime-container:jre-{java-major-version}-slim-glibc";

    /**
     * Build the image goal for {@code projectDir}: the core pipeline (via {@link
     * BuildPipeline#coreBuilder}) plus the image-plan/write-image tail. {@code tarballArg} is
     * tri-state exactly like {@code --tarball}'s optional value: {@code null} (no tarball), {@code
     * ""} (default layout path), or an explicit path.
     */
    public static Goal imageGoal(
            Path projectDir,
            Path cache,
            Path jdksDir,
            boolean skipTests,
            boolean verbose,
            String mainClass,
            String registry,
            String tag,
            String tarballArg,
            String dockerExecutableArg) {
        Path jkBuildPath = projectDir.resolve("jk.toml");
        Path lockFile = projectDir.resolve("jk.lock");
        int estimatedTestCount = TestSupport.estimateTestCount(projectDir.resolve("src/test/java"));
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
                skipTests,
                verbose);

        Phase imagePlan = Phase.builder("image-plan")
                .requires("package-jar")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("resolve image config");
                    JkBuild project = ctx.require(BuildPipeline.PROJECT);
                    BuildLayout layout = ctx.require(BuildPipeline.LAYOUT);
                    Path tarballPath = resolveTarballPath(tarballArg, layout);
                    if (tarballPath != null) ctx.put(TARBALL_PATH, tarballPath);
                    ImageConfig config = buildConfig(jkBuildPath, project, registry, tag, dockerExecutableArg);
                    ctx.put(CONFIG, config);
                    boolean dockerfileMode = config.dockerFile() != null && !config.dockerFile().isBlank();
                    if (!dockerfileMode) {
                        // Jib mode: main class required; load dep jars for classpath layering.
                        String chosen = resolveMainClass(mainClass, config, project, projectDir);
                        if (chosen == null || chosen.isBlank()) {
                            ctx.error("no-main", "no main class — pass --main, set image.main, or set project.main.");
                            throw new RuntimeException("missing main class");
                        }
                        if (project.isSpringBoot()) {
                            // Boot layer mapping (spring-boot plan §3.6): production RUNTIME
                            // deps only, snapshots split into their own layer, app classes
                            // exploded — the layer cadence matches how the bytes actually change.
                            List<Path> releases = new ArrayList<>();
                            List<Path> snapshots = new ArrayList<>();
                            splitBootDependencyJars(projectDir, cache, releases, snapshots);
                            ctx.put(DEP_JARS, releases);
                            ctx.put(SNAPSHOT_JARS, snapshots);
                        } else {
                            ctx.put(DEP_JARS, loadDependencyJars(projectDir, cache));
                            ctx.put(SNAPSHOT_JARS, List.of());
                        }
                    }
                    ctx.progress(1);
                })
                .build();

        Phase writeImage = Phase.builder("write-image")
                .kind(PhaseKind.IO)
                .requires("image-plan")
                .weight(() -> EffortWeights.ociWeight(projectDir))
                .scope(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(BuildPipeline.PROJECT);
                    BuildLayout layout = ctx.require(BuildPipeline.LAYOUT);
                    ImageConfig config = ctx.require(CONFIG);
                    Path tarballPath = ctx.get(TARBALL_PATH).orElse(null);

                    // Dockerfile mode: shell out to docker/podman build; skip Jib entirely.
                    if (config.dockerFile() != null && !config.dockerFile().isBlank()) {
                        boolean daemonMode = tarballPath == null
                                && (config.registry() == null || config.registry().isBlank());
                        String exe = config.dockerExecutable() != null ? config.dockerExecutable() : "docker";
                        ctx.label((daemonMode ? "build into " : "build + push via ") + exe
                                + " (" + config.dockerFile() + ")");
                        try {
                            String ref = runDockerfileBuild(ctx, config, projectDir, tarballPath, project);
                            ctx.put(IMAGE_REF, ref);
                        } catch (RuntimeException e) {
                            ctx.error("image", e.getMessage());
                            throw e;
                        }
                        ctx.progress(1);
                        return;
                    }

                    @SuppressWarnings("unchecked")
                    List<Path> depJars = (List<Path>) ctx.require(DEP_JARS);
                    @SuppressWarnings("unchecked")
                    List<Path> snapshotJars =
                            (List<Path>) ctx.get(SNAPSHOT_JARS).orElse(List.of());
                    Path classesDir = project.isSpringBoot() ? layout.classesDir() : null;

                    String chosen = resolveMainClass(mainClass, config, project, projectDir);

                    // Packaging cache — tarball only. A registry push is a network
                    // side-effect (the remote's state is unknown), so it's never skipped.
                    // The tarball is a pure function of the main jar, the dependency jars,
                    // the main class, the image config, and the image-builder worker version.
                    ActionCache ac = new ActionCache(new Cas(cache), cache.resolve("actions"));
                    boolean useCache = tarballPath != null
                            && !SessionContext.current().config().rerunOr(false);
                    String imgTask = null, imgKey = null;
                    if (tarballPath != null) {
                        List<String> tokens = List.of(
                                "mainjar:" + ClasspathFingerprint.entry(layout.mainJar()),
                                "deps:" + ClasspathFingerprint.of(depJars),
                                "snapshots:" + ClasspathFingerprint.of(snapshotJars),
                                "classes:" + (classesDir == null ? "" : ClasspathFingerprint.entry(classesDir)),
                                "main:" + chosen,
                                "cfg:" + imageConfigToken(config),
                                "worker:" + WorkerJar.IMAGE_BUILDER.artifactId() + ":" + JkVersion.VERSION);
                        imgTask = ActionKey.qualifiedTaskId("write-image", tarballPath);
                        imgKey = ActionKey.forArtifact(imgTask, JkVersion.VERSION, tokens);
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
                    boolean daemonMode = tarballPath == null
                            && (config.registry() == null || config.registry().isBlank());
                    if (tarballPath != null) {
                        ctx.label("write OCI tarball " + tarballPath.getFileName());
                    } else if (daemonMode) {
                        ctx.label("load into local daemon ("
                                + (config.dockerExecutable() != null ? config.dockerExecutable() : "docker/podman")
                                + ")");
                    } else {
                        ctx.label("push to " + config.targetReference(
                                project.project().name(), project.project().version()));
                    }
                    try {
                        String ref = runImageWorker(
                                cache, project, layout, config, chosen, depJars, snapshotJars, classesDir,
                                tarballPath);
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
        return builder.build();
    }

    private static Path resolveTarballPath(String tarballArg, BuildLayout layout) {
        if (tarballArg == null) return null;
        if (tarballArg.isBlank()) return layout.ociImageTar();
        return Path.of(tarballArg);
    }

    /**
     * Build the resolved {@link ImageConfig} for this invocation.
     *
     * <ol>
     *   <li>Parse project-local {@code [image]} from {@code jk.toml}.
     *   <li>Parse user-global {@code [image]} from {@code ~/.jk/config.toml} (if present).
     *   <li>Merge: project values win over global values.
     *   <li>Substitute {@code {java-major-version}} in {@code image.base} with the project's JDK
     *       major (falling back to 21 when undeclared).
     *   <li>Apply {@link #DEFAULT_BASE_TEMPLATE} when no base is set after all layers.
     * </ol>
     */
    private static ImageConfig buildConfig(
            Path jkBuild, JkBuild project, String registry, String tag, String dockerExecutableArg)
            throws IOException {
        ImageConfigParser.ImageConfigData data = ImageConfigParser.parse(jkBuild);

        // Merge user-global [image] from ~/.jk/config.toml underneath the project layer.
        Path globalConfig = JkDirs.userConfigFile();
        if (Files.isRegularFile(globalConfig)) {
            try {
                ImageConfigParser.ImageConfigData global = ImageConfigParser.parse(globalConfig);
                data = ImageConfigParser.merge(data, global);
            } catch (Exception ignored) {
                // malformed global config → proceed with project layer only
            }
        }

        // Resolve the java major version for template substitution.
        int javaMajor = project.project().javaRelease();
        if (javaMajor <= 0) javaMajor = 21; // LTS fallback

        // Apply {java-major-version} substitution and default base.
        String base = data.base();
        if (base == null || base.isBlank()) base = DEFAULT_BASE_TEMPLATE;
        base = base.replace("{java-major-version}", String.valueOf(javaMajor));

        // Docker/Podman executable: CLI flag > image.docker-executable > auto-detect.
        String dockerExe = dockerExecutableArg != null ? dockerExecutableArg : data.dockerExecutable();
        if (dockerExe == null || dockerExe.isBlank()) dockerExe = detectDockerExecutable();

        return new ImageConfig(
                base,
                data.user(),
                data.ports(),
                data.env(),
                data.labels(),
                registry != null ? registry : data.registry(),
                tag != null ? tag : data.tag(),
                data.platforms(),
                data.main(),
                dockerExe,
                data.dockerFile());
    }

    private static String runImageWorker(
            Path cache,
            JkBuild project,
            BuildLayout layout,
            ImageConfig config,
            String chosen,
            List<Path> depJars,
            List<Path> snapshotJars,
            Path classesDir,
            Path tarballPath) {
        try {
            Path workerJar = WorkerJar.IMAGE_BUILDER.locate(new Cas(cache));
            boolean daemonMode = tarballPath == null
                    && (config.registry() == null || config.registry().isBlank());
            List<String> lines = new ArrayList<>();
            lines.add("MAIN_JAR " + layout.mainJar().toAbsolutePath());
            lines.add("ARTIFACT " + project.project().name());
            lines.add("VERSION " + project.project().version());
            lines.add("MAIN_CLASS " + chosen);
            lines.add("MODE " + (tarballPath != null ? "tarball" : daemonMode ? "daemon" : "push"));
            if (config.base() != null) lines.add("BASE " + config.base());
            if (config.user() != null) lines.add("USER " + config.user());
            if (config.registry() != null) lines.add("REGISTRY " + config.registry());
            if (config.tag() != null) lines.add("TAG " + config.tag());
            if (tarballPath != null) lines.add("TARBALL " + tarballPath.toAbsolutePath());
            if (config.dockerExecutable() != null) lines.add("DOCKER_EXECUTABLE " + config.dockerExecutable());
            for (int p : config.ports()) lines.add("PORT " + p);
            for (var e : config.env().entrySet()) lines.add("ENV " + e.getKey() + "=" + e.getValue());
            for (var e : config.labels().entrySet()) lines.add("LABEL " + e.getKey() + "=" + e.getValue());
            for (String plat : config.platforms()) lines.add("PLATFORM " + plat);
            for (Path dep : depJars) lines.add("DEP_JAR " + dep.toAbsolutePath());
            for (Path dep : snapshotJars) lines.add("SNAPSHOT_DEP_JAR " + dep.toAbsolutePath());
            if (classesDir != null) lines.add("CLASSES_DIR " + classesDir.toAbsolutePath());

            Path spec = Files.createTempFile("jk-image-", ".spec");
            try {
                Files.write(spec, lines, StandardCharsets.UTF_8);
                String[] ref = {null};
                String[] workerError = {null};
                StringBuilder diag = new StringBuilder();
                int exit = new WorkerClient("##JKIM:")
                        .on("result", json -> {
                            ref[0] = Ndjson.str(json, "ref");
                            String err = Ndjson.str(json, "error");
                            if (err != null) workerError[0] = err;
                        })
                        .passthrough(ln -> diag.append(ln).append('\n'))
                        .run(WorkerCommands.javaCommand(workerJar, spec));
                if (workerError[0] != null) throw new RuntimeException("image worker: " + workerError[0]);
                if (exit != 0) {
                    String d = diag.length() > 0 ? diag.toString().trim() : null;
                    throw new RuntimeException("image worker failed" + (d != null ? ": " + d : " (exit " + exit + ")"));
                }
                return ref[0] != null ? ref[0] : "";
            } finally {
                Files.deleteIfExists(spec);
            }
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("image worker interrupted", e);
        }
    }

    /**
     * Build an OCI image using the project's Dockerfile. Runs {@code docker build -f <file> -t
     * <ref> [--platform <p>]* <projectDir>}, then (for tarball mode) {@code docker save}, or (for
     * push mode) {@code docker push}. Daemon mode requires no extra step — the build loads directly
     * into the local runtime. Build output streams via {@code ctx.output()} (over the wire, in the
     * engine-hosted case).
     */
    private static String runDockerfileBuild(
            PhaseContext ctx, ImageConfig config, Path projectDir, Path tarballPath, JkBuild project)
            throws IOException, InterruptedException {
        String exe = config.dockerExecutable() != null ? config.dockerExecutable() : "docker";
        Path dockerfile = projectDir.resolve(config.dockerFile()).normalize();
        String name = project.project().name();
        String version = project.project().version();
        String ref = config.targetReference(name, version);

        // docker build -f <dockerfile> -t <ref> [--platform <p>]* <context>
        List<String> buildCmd = new ArrayList<>();
        buildCmd.add(exe);
        buildCmd.add("build");
        buildCmd.add("-f");
        buildCmd.add(dockerfile.toString());
        buildCmd.add("-t");
        buildCmd.add(ref);
        for (String platform : config.platforms()) {
            buildCmd.add("--platform");
            buildCmd.add(platform);
        }
        buildCmd.add(projectDir.toString());

        runSubprocess(ctx, buildCmd, projectDir);

        if (tarballPath != null) {
            Files.createDirectories(tarballPath.getParent());
            runSubprocess(ctx, List.of(exe, "save", "-o", tarballPath.toString(), ref), projectDir);
        } else if (config.registry() != null && !config.registry().isBlank()) {
            runSubprocess(ctx, List.of(exe, "push", ref), projectDir);
        }
        return ref;
    }

    /** Run a subprocess, streaming each output line via {@code ctx.output()}. */
    private static void runSubprocess(PhaseContext ctx, List<String> cmd, Path cwd)
            throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                ctx.output(line);
            }
        }
        int exit = p.waitFor();
        if (exit != 0) {
            throw new RuntimeException(cmd.get(0) + " " + cmd.get(1) + " failed (exit " + exit + ")");
        }
    }

    /**
     * Auto-detect the local container runtime by probing {@code docker} then {@code podman} on
     * {@code PATH}. Falls back to {@code "docker"} if neither responds — the worker will fail with a
     * clear error in that case rather than silently choosing wrong.
     */
    private static String detectDockerExecutable() {
        for (String candidate : new String[] {"docker", "podman"}) {
            try {
                Process p = new ProcessBuilder(candidate, "--version")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return candidate;
                }
            } catch (Exception ignored) {
            }
        }
        return "docker";
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
        sb.append("platforms=").append(new ArrayList<>(c.platforms())).append(';');
        return sb.toString();
    }

    /**
     * Resolve the main class to use for the OCI image entrypoint. Priority:
     *
     * <ol>
     *   <li>{@code --main} CLI flag
     *   <li>{@code image.main} in jk.toml
     *   <li>{@code [application].main} in jk.toml
     *   <li>The sole {@code [application].main} across all workspace modules (fails if more than one)
     * </ol>
     *
     * Returns {@code null} when no main class can be determined. Error text is plain — the client
     * renders (and themes) it.
     */
    private static String resolveMainClass(String cliMain, ImageConfig config, JkBuild project, Path projectDir) {
        if (cliMain != null && !cliMain.isBlank()) return cliMain;
        if (config.main() != null && !config.main().isBlank()) return config.main();
        if (project.mainClass() != null) {
            return project.mainClass();
        }
        // Boot fallback: the compiled classes carry exactly one main (same scan the boot-jar
        // packager uses for Start-Class) — [application].main stays optional.
        if (project.isSpringBoot()) {
            try {
                return dev.jkbuild.layout.MainClassScanner.scanUnique(
                        BuildLayout.of(projectDir, project).classesDir());
            } catch (IOException ignored) {
                // fall through to the workspace scan / null
            }
        }
        // Workspace fallback: scan modules for an [application].main.
        if (!project.isWorkspaceRoot()) return null;
        try {
            var modules = WorkspaceLoader.loadModules(projectDir, project);
            List<String> mains = modules.values().stream()
                    .map(JkBuild::mainClass)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            if (mains.size() == 1) return mains.get(0);
            if (mains.size() > 1) {
                throw new RuntimeException("Multiple main classes discovered. Set image.main in jk.toml.");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Boot layer mapping: production RUNTIME deps only (dev/test-dev never enter the image),
     * SNAPSHOT versions split into their own layer, paths from the named-repo store when
     * available so entries keep readable names.
     */
    private static void splitBootDependencyJars(Path projectDir, Path cache, List<Path> releases, List<Path> snapshots)
            throws IOException {
        Path lockPath = projectDir.resolve("jk.lock");
        if (!Files.exists(lockPath)) return;
        Lockfile lock = LockfileReader.read(lockPath);
        ClasspathResolver resolver = new ClasspathResolver(new Cas(cache));
        for (ClasspathResolver.Entry entry : resolver.entriesFor(lock, ClasspathResolver.RUNTIME)) {
            if (!Files.exists(entry.jar())) continue;
            (entry.artifact().version().contains("SNAPSHOT") ? snapshots : releases).add(entry.jar());
        }
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
