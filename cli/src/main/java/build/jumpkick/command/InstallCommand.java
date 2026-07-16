// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.cli.CliOutput;
import build.jumpkick.cache.Cas;
import build.jumpkick.cache.Linking;
import build.jumpkick.cli.GlobalOptions;
import build.jumpkick.cli.PathDisplay;
import build.jumpkick.cli.run.PipelineConsole;
import build.jumpkick.cli.theme.Coords;
import build.jumpkick.jdk.JavaHomes;
import build.jumpkick.model.Coordinate;
import build.jumpkick.model.JkBuild;
import build.jumpkick.model.command.Arity;
import build.jumpkick.model.command.CliCommand;
import build.jumpkick.model.command.Exit;
import build.jumpkick.model.command.Invocation;
import build.jumpkick.model.command.Opt;
import build.jumpkick.model.command.Param;
import build.jumpkick.repo.MavenLayout;
import build.jumpkick.run.Pipeline;
import build.jumpkick.run.PipelineResult;
import build.jumpkick.run.TestSummary;
import build.jumpkick.tool.AppLauncher;
import build.jumpkick.tool.JarManifest;
import build.jumpkick.tool.ToolEnv;
import build.jumpkick.tool.ToolLauncher;
import build.jumpkick.util.GitUrl;
import build.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The app-install pipeline behind {@code jk tool install} (and its {@code jk install} command alias —
 * this class is no longer a registered top-level command; {@link ToolInstallCommand} routes to it
 * per tool-targets-plan §9). Three modes:
 *
 * <ul>
 *   <li><b>No source:</b> build the current project (per {@code jk.toml}) and install it. Always
 *       performs a <em>cache install</em> (jar + generated pom into jk's CAS + m2 local repo — the
 *       {@code mvn install} equivalent — so other local projects can depend on it; also into {@code
 *       ~/.m2} when {@code project.m2install} is set). When the project is an <em>application</em>
 *       ({@code project.application}, default true when a {@code main} is set) it additionally does
 *       a <em>make install</em>: a native binary into {@code ~/.jk/bin}; a shadow jar into {@code
 *       ~/.jk/lib} with a launcher; or a normal jar into {@code ~/.jk/lib} alongside its
 *       hard-linked runtime deps with a launcher in {@code ~/.jk/bin}.
 *   <li><b>Maven coord (g:a:v):</b> download the pre-built jar from declared Maven repositories and
 *       install a launcher (the legacy {@code jk tool install} behavior).
 *   <li><b>Git / HTTPS URL:</b> clone, build, install. The URL may carry an optional {@code @<ref>}
 *       or {@code #<ref>} suffix selecting a tag or branch (default: {@code main}). Host shorthands
 *       {@code gh:owner/repo} etc. are accepted.
 * </ul>
 *
 * <p>Each mode is its own Pipeline. Like {@code jk run}, the work that "shells out" to a nested {@code
 * jk build} happens inside the {@code ensure-built} step; the nested progress widget overlaps
 * briefly with the outer one, a known UX wrinkle.
 */
public final class InstallCommand {

    String source;
    String groupFlag;
    String nameFlag;
    String verFlag;
    String binName;
    String mainClass;
    Path cacheDirOverride;
    Path stateDirOverride;
    Path binDirOverride;
    Path libDirOverride;
    Path m2DirOverride;
    URI repoUrl;
    build.jumpkick.cli.BuildOptions buildOpts;
    GlobalOptions global;

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()} for the full rationale. A real {@code jk install} of a
     * project (current dir or git checkout) hosts its build + cache-install on the engine
     * (slim-client Wave 3), and a Maven coordinate hosts its resolve+fetch (Wave 4); the
     * launcher-writing "make install" half always runs here.
     */
    private static boolean engineDisabledForTests() {
        // Also bypass inside a jk-forked test worker (jk.plugin.class=TestRunner) — see BuildCommand.
        return Boolean.getBoolean("jk.test.noEngine")
                || "build.jumpkick.testrunner.TestRunner".equals(System.getProperty("jk.plugin.class"));
    }

    // --- mode 1: current project -----------------------------------------

    private int installCurrentProject() throws IOException {
        Path projectDir = global.workingDir();
        Path manifest = projectDir.resolve("jk.toml");
        if (!Files.exists(manifest)) {
            CliOutput.err("jk install: no jk.toml in " + build.jumpkick.cli.PathDisplay.styledRaw(projectDir));
            return Exit.CONFIG;
        }
        return runProjectInstallPipeline(projectDir, "install");
    }

    // --- mode 2: local file ----------------------------------------------

    /**
     * Store a local file in the CAS and mirror it into the m2 local repo under its Maven coordinate
     * (the {@code mvn install} equivalent for pre-built artifacts). The coordinate is auto-detected
     * from {@code META-INF/maven/.../pom.properties} for {@code .jar} files; {@code --group}, {@code
     * --name}, and {@code --ver} override or supply missing fields.
     */
    /** Package-private: `jk tool install <file> --group/--name/--ver` delegates here. */
    int installFromFile(Path filePath) throws IOException {
        if (!Files.exists(filePath)) {
            CliOutput.err("jk install: " + PathDisplay.styled(filePath) + ": no such file");
            return Exit.CONFIG;
        }

        boolean isJar = filePath.getFileName().toString().toLowerCase().endsWith(".jar");
        java.util.Optional<Coordinate> detected = java.util.Optional.empty();
        if (isJar) {
            try {
                detected = JarManifest.coordinateFrom(filePath);
            } catch (IOException ignored) {
            }
        }

        String group = coalesce(groupFlag, detected.map(Coordinate::group).orElse(null));
        String artifact = coalesce(nameFlag, detected.map(Coordinate::artifact).orElse(null));
        String version = coalesce(verFlag, detected.map(Coordinate::version).orElse(null));

        if (group == null || artifact == null || version == null) {
            if (!isJar) {
                CliOutput.err("jk install: --group, --name, and --ver are required for non-JAR files");
            } else {
                StringBuilder msg = new StringBuilder("jk install: could not detect");
                if (group == null) msg.append(" group");
                if (artifact == null) msg.append(" name");
                if (version == null) msg.append(" version");
                msg.append(" from JAR metadata");
                if (group == null) msg.append("; supply --group");
                if (artifact == null) msg.append("; supply --name");
                if (version == null) msg.append("; supply --ver");
                CliOutput.err(msg.toString());
            }
            return Exit.USAGE;
        }

        Path cache = cacheDir();
        Files.createDirectories(cache);
        Coordinate coord = Coordinate.of(group, artifact, version);
        // File-install writes directly to repos/local/ (the JAR is already on disk, no project
        // metadata for a POM, so ~/.m2 write is not appropriate here).
        build.jumpkick.repo.RepoArtifactStore.writeToLocalStore(cache, MavenLayout.artifactPath(coord), filePath);

        if (!global.outputIsJson()) {
            CliOutput.out("Installed " + build.jumpkick.cli.theme.Coords.gav(coord) + " to the local cache");
        }
        return 0;
    }

    private static String coalesce(String flag, String detected) {
        return (flag != null && !flag.isBlank()) ? flag : detected;
    }

    // --- mode 3: Maven coord ---------------------------------------------

    /**
     * The legacy {@code jk tool install} behavior: resolve+fetch the published tool (engine-hosted
     * — Wave 4, riding the same {@code tool-resolve-request} as {@code jk tool install/run}), then
     * write the launcher client-side.
     */
    private int installFromMaven(String coord) throws IOException, InterruptedException {
        ToolTargets.Resolved resolved;
        try {
            resolved = ToolTargets.resolve(coord);
        } catch (ToolTargets.TargetException e) {
            CliOutput.err(e.getMessage());
            return Exit.USAGE;
        }
        String bin = binName != null && !binName.isBlank() ? binName : resolved.defaultBin();
        Path cacheDir = cacheDir();
        Path envsRoot = stateDir().resolve("tools").resolve("envs");
        Path binDir = binDir();
        Files.createDirectories(cacheDir);
        PipelineConsole.Mode mode = PipelineConsole.modeFor(global);

        ToolEnv env;
        if (engineDisabledForTests()) {
            var o = build.jumpkick.cli.engine.InProcessEngine.require()
                    .toolResolvePipeline(
                            build.jumpkick.model.ToolCoordSpec.parse(resolved.coordSpec()),
                            java.util.List.of(),
                            bin, mainClass, repoUrl, cacheDir, resolved.coordSpec(), mode);
            if (o.env() == null) return failureExit(o.result(), "jk install", cacheDir);
            env = o.env();
        } else {
            build.jumpkick.cli.engine.EngineClient.ToolResolveOutcome outcome;
            try {
                outcome = build.jumpkick.cli.engine.EngineClient.runToolResolve(
                        build.jumpkick.engine.EnginePaths.current(),
                        new build.jumpkick.cli.engine.EngineClient.ToolResolveRequest(
                                resolved.coordSpec(), java.util.List.of(), bin, mainClass, repoUrl, cacheDir),
                        steps -> PipelineConsole.chooseConsoleListener("install-maven", steps, mode));
            } catch (IOException e) {
                CliOutput.err("jk install: " + e.getMessage());
                return Exit.SOFTWARE;
            }
            if (!outcome.result().success() || outcome.mainClass() == null || outcome.coord() == null) {
                return failureExit(outcome.result(), "jk install", cacheDir);
            }
            env = new ToolEnv(bin, Coordinate.parse(outcome.coord()), outcome.mainClass(), outcome.classpath());
        }

        Path launcher = ToolLauncher.install(
                envsRoot,
                binDir,
                JavaHomes.runningJavaHome(),
                env,
                new build.jumpkick.tool.ToolProvenance("gav", coord, env.primary().toGav()),
                java.util.List.of());
        announceInstall(Coords.gav(env.primary()), launcher, binDir);
        return 0;
    }

    // --- mode 4: git URL -------------------------------------------------

    /** Package-private: `jk tool install <git-url>` delegates here (plan §9 convergence). */
    int installFromGit(String input) throws IOException, InterruptedException {
        UrlAndRef split = splitUrlRef(input);
        String expanded = GitUrl.expand(split.url());
        String canonical = GitUrl.canonicalize(split.url());
        String refStr = split.ref() != null ? split.ref() : "main";
        Path cacheDir = cacheDir();
        Files.createDirectories(cacheDir);
        boolean refresh = build.jumpkick.config.SessionContext.current().config().forceOr(false);
        PipelineConsole.Mode mode = PipelineConsole.modeFor(global);

        PipelineResult fetchResult;
        Path checkout;
        String sha;
        if (engineDisabledForTests()) {
            var o = build.jumpkick.cli.engine.InProcessEngine.require()
                    .gitFetchPipeline(expanded, canonical, refStr, cacheDir, refresh, /* requireJkToml */ true, mode);
            fetchResult = o.result();
            checkout = o.checkout();
            sha = o.sha();
        } else {
            // Engine-hosted clone (slim-client Wave 3): git runs in-process in the engine;
            // the checkout path + sha ride the terminal pipeline-finish.
            build.jumpkick.cli.engine.EngineClient.GitFetchOutcome outcome;
            try {
                outcome = build.jumpkick.cli.engine.EngineClient.runGitFetch(
                        build.jumpkick.engine.EnginePaths.current(),
                        new build.jumpkick.cli.engine.EngineClient.GitFetchRequest(
                                expanded, canonical, refStr, cacheDir, refresh),
                        steps -> PipelineConsole.chooseConsoleListener("install-git-fetch", steps, mode));
            } catch (IOException e) {
                CliOutput.err("jk install: " + e.getMessage());
                return Exit.SOFTWARE;
            }
            fetchResult = outcome.result();
            checkout = outcome.checkout();
            sha = outcome.sha();
        }
        if (!fetchResult.success() || checkout == null || sha == null) {
            for (PipelineResult.Diagnostic d : fetchResult.errors()) {
                if ("no-jk-toml".equals(d.code())) return Exit.SOFTWARE;
            }
            return failureExit(fetchResult, "jk install", cacheDir);
        }
        if (!global.outputIsJson()) {
            CliOutput.out(
                    "Fetched " + expanded + " @ " + refStr + " (" + sha.substring(0, Math.min(7, sha.length())) + ")");
        }

        // After fetch, hand off to the same project-install pipeline used by
        // mode 1, but with the checkout dir instead of the user's CWD.
        return runProjectInstallPipeline(checkout, "install-git");
    }

    // --- shared project-install pipeline ---------------------------------

    /** Package-private: `jk tool install <project-dir>` delegates here (plan §9 convergence). */
    int runProjectInstallPipeline(Path projectDir, String pipelineName) throws IOException {
        Path cacheDir = cacheDir();
        Path binDir = binDir();
        Path libDir = libDir();

        // Validate up front: a non-native application needs a main class for its
        // launcher. (Done here, not in a step, so we fail before building.) Spring Boot
        // projects are exempt — the boot jar carries Start-Class in its manifest (resolved
        // by scan at package time) and the launcher runs it with -jar. Thin client: the
        // parsed summary comes from the engine, never a client-side parse.
        build.jumpkick.engine.protocol.ProjectInfo proj = projectInfo(projectDir);
        if (proj.error() != null) {
            CliOutput.err("jk install: " + proj.error());
            return Exit.CONFIG;
        }
        if (proj.application()
                && "DISABLED".equals(proj.nativeMode())
                && proj.mainClass().isEmpty()
                && !proj.springBoot()) {
            CliOutput.err("jk install: application project at "
                    + build.jumpkick.cli.PathDisplay.styledRaw(projectDir)
                    + " has no `main` class set in [application]");
            return Exit.USAGE;
        }
        // ALWAYS: native is part of the standard build and install produces a native binary.
        // SUPPORTED: user runs `jk native` explicitly; install deploys the jar.
        boolean isNative = proj.application() && "ALWAYS".equals(proj.nativeMode());

        // `jk build` no longer auto-builds native (that's `jk native`), so an installed native
        // application builds its binary here. Resolve the GraalVM up front — before any progress
        // UI opens, and before the request ships (a prompt/install owns this terminal and must
        // never run inside the engine).
        Path graalHome = null;
        if (isNative) {
            java.util.Optional<Path> resolved =
                    new build.jumpkick.cli.GraalResolver(null, false).resolve(projectDir, proj.graal());
            if (resolved.isEmpty()) return 1; // GraalResolver already printed why
            graalHome = resolved.get();
        }

        // Build + cache-install through the shared InstallPipelines pipeline (jar always; shadow/native
        // per jk.toml; jar + generated pom into ~/.m2 / repos/local) — engine-hosted for a real
        // invocation, in-process for the test-only bypass. The make-install half runs below,
        // client-side either way: it writes the user-home launcher/binary this process owns.
        PipelineConsole.Mode mode = PipelineConsole.modeFor(global);
        PipelineResult result;
        TestSummary testResult;
        if (engineDisabledForTests()) {
            var o = build.jumpkick.cli.engine.InProcessEngine.require()
                    .installProjectPipeline(projectDir, cacheDir, m2Dir(), buildOpts.skipTests, global.verbose,
                            graalHome, mode);
            result = o.result();
            testResult = o.testResult();
        } else {
            var session = build.jumpkick.config.SessionContext.current();
            TestSummary[] testResultHolder = new TestSummary[1];
            try {
                result = build.jumpkick.cli.engine.EngineClient.runInstall(
                        build.jumpkick.engine.EnginePaths.current(),
                        new build.jumpkick.cli.engine.EngineClient.InstallRequest(
                                projectDir,
                                cacheDir,
                                m2Dir(),
                                graalHome,
                                buildOpts.skipTests,
                                session.offline(),
                                session.force(),
                                global.verbose),
                        steps -> PipelineConsole.chooseConsoleListener(pipelineName, steps, mode),
                        testResultHolder);
            } catch (IOException e) {
                CliOutput.err("jk install: " + e.getMessage());
                return Exit.SOFTWARE;
            }
            testResult = testResultHolder[0];
        }
        if (!result.success()) {
            if (testResult != null && !testResult.allPassed()) return 4;
            return failureExit(result, "jk install", cacheDir);
        }

        // Only for applications: place a runnable artifact under ~/.jk (the "make install").
        // The engine computes the plan (gates, link set, launcher script); this process — which
        // owns the user's home — applies it.
        Coordinate coord = Coordinate.of(proj.group(), proj.name(), proj.version());
        Path launcher = null;
        if (proj.application()) {
            try {
                launcher = applyInstallPlan(projectDir, cacheDir);
            } catch (IOException e) {
                CliOutput.err("jk install: make install failed: " + e.getMessage());
                return 1;
            }
        }
        announceProjectInstall(Coords.gav(coord), launcher, binDir);
        return 0;
    }

    /** The engine's parsed-project summary (in-process twin under jk.test.noEngine). */
    private build.jumpkick.engine.protocol.ProjectInfo projectInfo(Path projectDir) throws IOException {
        return engineDisabledForTests()
                ? build.jumpkick.cli.engine.InProcessEngine.require().projectInfo(projectDir)
                : build.jumpkick.cli.engine.EngineClient.projectInfo(
                        build.jumpkick.engine.EnginePaths.current(), projectDir);
    }

    /**
     * The {@code make install} step for applications, thin-client style: the engine computes the
     * plan (link set + launcher script or a direct native-binary link); this process applies it —
     * hard-link/copy each pair, write the launcher, mark executables. Returns the launcher path.
     */
    private Path applyInstallPlan(Path projectDir, Path cacheDir) throws IOException {
        build.jumpkick.engine.protocol.ExecPlan plan = engineDisabledForTests()
                ? build.jumpkick.cli.engine.InProcessEngine.require()
                        .execPlan(projectDir, cacheDir, "install", mainClass, binName, binDirOverride,
                                libDirOverride)
                : build.jumpkick.cli.engine.EngineClient.execPlan(
                        build.jumpkick.engine.EnginePaths.current(), projectDir, cacheDir, "install", mainClass,
                        binName, binDirOverride, libDirOverride);
        if (plan.error() != null) {
            throw new IOException(plan.error());
        }
        for (int i = 0; i < plan.linkSrcs().size(); i++) {
            Path src = Path.of(plan.linkSrcs().get(i));
            Path dest = Path.of(plan.linkDests().get(i));
            Files.createDirectories(dest.getParent());
            // COPY, never link: src is a target/ artifact the next build rewrites in place —
            // an installed tool must be a stable snapshot, not an alias of the build tree.
            Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        if (!plan.launcherScript().isEmpty()) {
            Path launcher = Path.of(plan.launcherPath());
            Files.createDirectories(launcher.getParent());
            Files.writeString(launcher, plan.launcherScript());
            markExecutable(launcher);
            return launcher;
        }
        // Native binary linked directly into bin — no script, just the exec bit.
        Path bin = Path.of(plan.binPath());
        markExecutable(bin);
        return bin;
    }

    private static void markExecutable(Path file) {
        try {
            Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(file));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem.
        }
    }

    // --- helpers ---------------------------------------------------------

    /**
     * Maps a failed install pipeline to exit code 1. Kept as a named helper so the various call sites
     * read uniformly; the listener already printed the "✗ Error" diagnostic so we don't repeat
     * ourselves.
     */
    private static int failureExit(PipelineResult result, String label, Path cache) {
        return 1;
    }

    private Path cacheDir() {
        return cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
    }

    private Path stateDir() {
        return stateDirOverride != null ? stateDirOverride : JkDirs.state();
    }

    private Path binDir() {
        return binDirOverride != null ? binDirOverride : JkDirs.binDir();
    }

    private Path libDir() {
        return libDirOverride != null ? libDirOverride : JkDirs.lib();
    }

    private Path m2Dir() {
        if (m2DirOverride != null) return m2DirOverride;
        return Path.of(System.getProperty("user.home", "."), ".m2");
    }

    private void announceInstall(String coord, Path launcher, Path binDir) {
        if (global.outputIsJson()) return;
        CliOutput.out("Installed " + coord + " → " + launcher);
        CliOutput.out("Add to PATH if needed:");
        CliOutput.out("  export PATH=\"" + binDir + ":$PATH\"");
    }

    /** Announce a project install: launcher path for an app, cache-only for a library. */
    private void announceProjectInstall(String coord, Path launcher, Path binDir) {
        if (global.outputIsJson()) return;
        if (launcher == null) {
            CliOutput.out("Installed " + coord + " to the local cache");
            return;
        }
        CliOutput.out("Installed " + coord + " → " + launcher);
        CliOutput.out("Add to PATH if needed:");
        CliOutput.out("  export PATH=\"" + binDir + ":$PATH\"");
    }

    /**
     * True when {@code source} resolves to an existing regular file on disk. Checked after git-URL
     * detection so {@code file://...} URIs are handled by the git path. Uses filesystem existence as
     * the discriminator: Maven coords ({@code g:a:v}) and bare names never match an actual file.
     */
    private static boolean looksLikeFilePath(String source) {
        try {
            return Files.isRegularFile(Path.of(source));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean looksLikeGitUrl(String input) {
        return input.startsWith("git@")
                || input.startsWith("http://")
                || input.startsWith("https://")
                || input.startsWith("ssh://")
                || input.startsWith("git://")
                || input.startsWith("file://")
                || input.startsWith("gh:")
                || input.startsWith("gl:")
                || input.startsWith("bb:")
                || input.startsWith("sr:");
    }

    /** {@code <url>} or {@code <url>@<ref>} or {@code <url>#<ref>}. */
    record UrlAndRef(String url, String ref) {}

    static UrlAndRef splitUrlRef(String input) {
        int hash = input.lastIndexOf('#');
        if (hash >= 0) {
            return new UrlAndRef(input.substring(0, hash), input.substring(hash + 1));
        }
        // For `@`, only treat as ref-separator when the suffix doesn't look
        // like part of a URL (no `/`, `:`, or another `@`). This avoids
        // misreading `git@github.com:foo/bar` as a ref-suffix.
        int at = input.lastIndexOf('@');
        if (at > 0) {
            String suffix = input.substring(at + 1);
            if (!suffix.isEmpty() && suffix.indexOf('/') < 0 && suffix.indexOf(':') < 0 && suffix.indexOf('@') < 0) {
                return new UrlAndRef(input.substring(0, at), suffix);
            }
        }
        return new UrlAndRef(input, null);
    }
}
