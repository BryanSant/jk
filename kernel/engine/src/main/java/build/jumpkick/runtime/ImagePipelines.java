// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.run.StepNames;
import build.jumpkick.plugin.build.Phase;

import build.jumpkick.cache.Cas;
import build.jumpkick.compile.ClasspathResolver;
import build.jumpkick.config.ImageConfigParser;
import build.jumpkick.config.SessionContext;
import build.jumpkick.config.WorkspaceLoader;
import build.jumpkick.image.ImageConfig;
import build.jumpkick.layout.BuildLayout;
import build.jumpkick.lock.Lockfile;
import build.jumpkick.lock.LockfileReader;
import build.jumpkick.model.JkBuild;
import build.jumpkick.plugin.protocol.Ndjson;
import build.jumpkick.plugin.protocol.PluginProtocol;
import build.jumpkick.plugin.protocol.SpecWriter;
import build.jumpkick.run.Pipeline;
import build.jumpkick.run.PipelineKey;
import build.jumpkick.run.Step;
import build.jumpkick.run.StepContext;
import build.jumpkick.run.StepKind;
import build.jumpkick.task.ActionCache;
import build.jumpkick.task.ActionKey;
import build.jumpkick.task.ClasspathFingerprint;
import build.jumpkick.util.JkDirs;
import build.jumpkick.model.BuildIdentity;
import build.jumpkick.model.JkVersion;
import build.jumpkick.engine.plugin.PluginClient;
import build.jumpkick.engine.plugin.PluginJar;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The shared {@code jk image} pipeline — the full build pipeline (compile → test → package) plus the
 * OCI tail: an image-plan step that resolves the merged {@code [image]} config, and a write-image
 * step that either forks the {@code jk-image-builder} (Jib) worker or, in Dockerfile mode, shells
 * out to {@code docker}/{@code podman} — hoisted out of the CLI so the resident engine can host the
 * command (Wave 2 of {@code docs/architecture/slim-client.md}) while the command's test-only
 * in-process path builds the exact same pipeline.
 *
 * <p>Everything here reads the same machine state the CLI fork read (project {@code jk.toml}, the
 * user-global {@code ~/.jk/config.toml} image layer, the local container runtime) — the engine is
 * always a same-user, same-host process. All strings put into the pipeline are plain; the client themes
 * the success tail from the structured keys ({@link #IMAGE_REF}/{@link #TARBALL_PATH}/…).
 */
public final class ImagePipelines {

    private ImagePipelines() {}

    public static final PipelineKey<ImageConfig> CONFIG = PipelineKey.of("image-config", ImageConfig.class);
    public static final PipelineKey<Path> TARBALL_PATH = PipelineKey.of("tarball-path", Path.class);

    @SuppressWarnings("rawtypes")
    private static final PipelineKey<List> DEP_JARS = PipelineKey.of("dep-jars", List.class);

    @SuppressWarnings("rawtypes")
    private static final PipelineKey<List> SNAPSHOT_JARS = PipelineKey.of("snapshot-jars", List.class);

    public static final PipelineKey<String> IMAGE_REF = PipelineKey.of("image-ref", String.class);

    /**
     * The base image template used when no {@code image.base} is set in the project or global
     * config. {@code {java-major-version}} is replaced with the project's resolved JDK major.
     */
    public static final String DEFAULT_BASE_TEMPLATE =
            "bellsoft/liberica-runtime-container:jre-{java-major-version}-slim-glibc";

    /**
     * Build the image pipeline for {@code projectDir}: the core pipeline (via {@link
     * BuildPipelines#coreBuilder}) plus the image-plan/write-image tail. {@code tarballArg} is
     * tri-state exactly like {@code --tarball}'s optional value: {@code null} (no tarball), {@code
     * ""} (default layout path), or an explicit path.
     */
    public static Pipeline imagePipeline(
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
        BuildPipelines.Inputs inputs = new BuildPipelines.Inputs(
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
                verbose,
                false,
                false,
                java.util.Set.of(),
                build.jumpkick.config.SessionContext.current());

        Step imagePlan = Step.builder(StepNames.IMAGE_PLAN).phase(Phase.IMAGE)
                .requires(StepNames.PACKAGE_JAR)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("resolve image config");
                    JkBuild project = ctx.require(BuildPipelines.PROJECT);
                    BuildLayout layout = ctx.require(BuildPipelines.LAYOUT);
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
                        if (PluginBuild.shape(project, projectDir).map(sh -> sh.layeredImage()).orElse(false)) {
                            // Layered-image packagers (spring-boot plan §3.6): production RUNTIME
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

        Step writeImage = Step.builder(StepNames.WRITE_IMAGE).phase(Phase.IMAGE)
                .kind(StepKind.IO)
                .requires(StepNames.IMAGE_PLAN)
                .weight(() -> EffortWeights.ociWeight(projectDir))
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(BuildPipelines.PROJECT);
                    BuildLayout layout = ctx.require(BuildPipelines.LAYOUT);
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
                    Path classesDir = PluginBuild.shape(project, projectDir).map(sh -> sh.layeredImage()).orElse(false)
                            ? layout.classesDir()
                            : null;

                    String chosen = resolveMainClass(mainClass, config, project, projectDir);

                    // Packaging cache — tarball only. A registry push is a network
                    // side-effect (the remote's state is unknown), so it's never skipped.
                    // The tarball is a pure function of the main jar, the dependency jars,
                    // the main class, the image config, and the image-builder worker version.
                    ActionCache ac = new ActionCache(new Cas(cache), cache.resolve("actions"));
                    boolean useCache = tarballPath != null
                            && !SessionContext.current().config().rebuildOr(false);
                    String imgTask = null, imgKey = null;
                    if (tarballPath != null) {
                        List<String> tokens = List.of(
                                "mainjar:" + ClasspathFingerprint.entry(layout.mainJar()),
                                "deps:" + ClasspathFingerprint.of(depJars),
                                "snapshots:" + ClasspathFingerprint.of(snapshotJars),
                                "classes:" + (classesDir == null ? "" : ClasspathFingerprint.entry(classesDir)),
                                "main:" + chosen,
                                "cfg:" + imageConfigToken(config),
                                "worker:" + PluginJar.IMAGE_BUILDER.artifactId() + ":" + BuildIdentity.cacheKeyVersion());
                        imgTask = ActionKey.qualifiedTaskId(StepNames.WRITE_IMAGE, tarballPath);
                        imgKey = ActionKey.forArtifact(imgTask, BuildIdentity.cacheKeyVersion(), tokens);
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

        Pipeline.Builder builder = BuildPipelines.coreBuilder(inputs);
        builder.addStep(imagePlan).addStep(writeImage);
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
            Path workerJar = PluginJar.IMAGE_BUILDER.locate(new Cas(cache));
            boolean daemonMode = tarballPath == null
                    && (config.registry() == null || config.registry().isBlank());
            SpecWriter sw = new SpecWriter()
                    .op(PluginProtocol.OP_IMAGE, null, "jk-image-builder")
                    .configString("artifact", project.project().name())
                    .configString("version", project.project().version())
                    .configString("mainClass", chosen)
                    .configString("mode", tarballPath != null ? "tarball" : daemonMode ? "daemon" : "push");
            if (config.base() != null) sw.configString("base", config.base());
            if (config.user() != null) sw.configString("user", config.user());
            if (config.registry() != null) sw.configString("registry", config.registry());
            if (config.tag() != null) sw.configString("tag", config.tag());
            if (tarballPath != null) sw.configString("tarball", tarballPath.toAbsolutePath().toString());
            if (config.dockerExecutable() != null) sw.configString("dockerExecutable", config.dockerExecutable());
            if (!config.ports().isEmpty()) {
                sw.configList("ports", config.ports().stream().map(String::valueOf).toList());
            }
            if (!config.env().isEmpty()) {
                sw.configList("env", config.env().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
            }
            if (!config.labels().isEmpty()) {
                sw.configList(
                        "labels", config.labels().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
            }
            if (!config.platforms().isEmpty()) sw.configList("platforms", config.platforms());
            sw.artifact(layout.mainJar());
            for (Path dep : depJars) sw.entry(dep.getFileName().toString(), dep, false, null);
            for (Path dep : snapshotJars) sw.entry(dep.getFileName().toString(), dep, true, null);
            if (classesDir != null) sw.layout(java.util.Map.of("classesDir", classesDir));

            Path spec = Files.createTempFile("jk-image-", ".spec");
            try {
                Files.write(spec, sw.lines(), StandardCharsets.UTF_8);
                String[] ref = {null};
                String[] workerError = {null};
                StringBuilder diag = new StringBuilder();
                int exit = new PluginClient("##JKIM:")
                        .on(PluginProtocol.RESULT, json -> ref[0] = Ndjson.str(json, "ref"))
                        .on(PluginProtocol.ERROR, json -> workerError[0] = Ndjson.str(json, PluginProtocol.MESSAGE))
                        .passthrough(ln -> diag.append(ln).append('\n'))
                        .run(PluginLaunch.javaCommand(workerJar, spec));
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
            StepContext ctx, ImageConfig config, Path projectDir, Path tarballPath, JkBuild project)
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
    private static void runSubprocess(StepContext ctx, List<String> cmd, Path cwd)
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
        // main-scan fallback: the compiled classes carry exactly one main (same scan the
        // packager uses for its entry attribute) — [application].main stays optional.
        if (PluginBuild.shape(project, projectDir).map(sh -> sh.mainScan()).orElse(false)) {
            try {
                return build.jumpkick.layout.MainClassScanner.scanUnique(
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
