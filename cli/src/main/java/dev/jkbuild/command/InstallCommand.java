// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cache.Cas;
import dev.jkbuild.cache.Linking;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.PathDisplay;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceClasspath;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.jdk.JavaHomes;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;
import dev.jkbuild.repo.MavenLayout;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.TestSummary;
import dev.jkbuild.tool.AppLauncher;
import dev.jkbuild.tool.JarManifest;
import dev.jkbuild.tool.ToolEnv;
import dev.jkbuild.tool.ToolLauncher;
import dev.jkbuild.util.GitUrl;
import dev.jkbuild.util.JkDirs;
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
 * {@code jk install [<source>]} — install a jk artifact. Three modes:
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
 * <p>Each mode is its own Goal. Like {@code jk run}, the work that "shells out" to a nested {@code
 * jk build} happens inside the {@code ensure-built} phase; the nested progress widget overlaps
 * briefly with the outer one, a known UX wrinkle.
 */
public final class InstallCommand implements CliCommand {

    @Override
    public String name() {
        return "install";
    }

    @Override
    public String description() {
        return "Install the current project or a specified target";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<group>", "Maven groupId for a local file install.", "--group"),
                Opt.value("<name>", "Maven artifactId for a local file install.", "--name"),
                Opt.value("<ver>", "Version for a local file install.", "--ver"),
                Opt.value("<name>", "Launcher name in $JK_BIN_DIR. Default: artifact id.", "--bin"),
                Opt.value("<class>", "Override the Main-Class to exec.", "--main"),
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                        .hide(),
                Opt.value("<dir>", "Override the tool state directory.", "--state-dir")
                        .hide(),
                Opt.value("<dir>", "Override the bin directory.", "--bin-dir").hide(),
                Opt.value("<dir>", "Override the lib directory.", "--lib-dir").hide(),
                Opt.value("<dir>", "Override the local Maven repo root (~/.m2) for m2install.", "--m2-dir")
                        .hide(),
                Opt.value("<url>", "Override the Maven repository URL (for tests).", "--repo-url")
                        .hide(),
                Opt.flag("Skip compiling and running tests.", "--skip-tests"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "source",
                Arity.ZERO_OR_ONE,
                "Maven coord, git URL, or local file path. Omit to\ninstall the current jk.toml project."));
    }

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
    dev.jkbuild.cli.BuildOptions buildOpts;
    GlobalOptions global;

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()} for the full rationale. A real {@code jk install} of a
     * project (current dir or git checkout) hosts its build + cache-install on the engine
     * (slim-client Wave 3), and a Maven coordinate hosts its resolve+fetch (Wave 4); the
     * launcher-writing "make install" half always runs here.
     */
    private static boolean engineDisabledForTests() {
        // Also bypass inside a jk-forked test worker (jk.plugin.class=JkRunner) — see BuildCommand.
        return Boolean.getBoolean("jk.test.noEngine")
                || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"));
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.source = in.positionals().isEmpty() ? null : in.positionals().get(0);
        this.groupFlag = in.value("group").orElse(null);
        this.nameFlag = in.value("name").orElse(null);
        this.verFlag = in.value("ver").orElse(null);
        this.binName = in.value("bin").orElse(null);
        this.mainClass = in.value("main").orElse(null);
        this.cacheDirOverride = in.value("cache-dir").map(Path::of).orElse(null);
        this.stateDirOverride = in.value("state-dir").map(Path::of).orElse(null);
        this.binDirOverride = in.value("bin-dir").map(Path::of).orElse(null);
        this.libDirOverride = in.value("lib-dir").map(Path::of).orElse(null);
        this.m2DirOverride = in.value("m2-dir").map(Path::of).orElse(null);
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.buildOpts = new dev.jkbuild.cli.BuildOptions();
        this.buildOpts.skipTests = in.isSet("skip-tests");
        this.global = GlobalOptions.from(in);

        if (source == null || source.isBlank()) {
            return installCurrentProject();
        }
        if (looksLikeGitUrl(source)) {
            return installFromGit(source);
        }
        if (looksLikeFilePath(source)) {
            return installFromFile(Path.of(source).toAbsolutePath().normalize());
        }
        return installFromMaven(source);
    }

    // --- mode 1: current project -----------------------------------------

    private int installCurrentProject() throws IOException {
        Path projectDir = global.workingDir();
        Path manifest = projectDir.resolve("jk.toml");
        if (!Files.exists(manifest)) {
            CliOutput.err("jk install: no jk.toml in " + dev.jkbuild.cli.PathDisplay.styledRaw(projectDir));
            return Exit.USAGE;
        }
        return runProjectInstallGoal(projectDir, "install");
    }

    // --- mode 2: local file ----------------------------------------------

    /**
     * Store a local file in the CAS and mirror it into the m2 local repo under its Maven coordinate
     * (the {@code mvn install} equivalent for pre-built artifacts). The coordinate is auto-detected
     * from {@code META-INF/maven/.../pom.properties} for {@code .jar} files; {@code --group}, {@code
     * --name}, and {@code --ver} override or supply missing fields.
     */
    private int installFromFile(Path filePath) throws IOException {
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
        dev.jkbuild.repo.RepoArtifactStore.writeToLocalStore(cache, MavenLayout.artifactPath(coord), filePath);

        if (!global.outputIsJson()) {
            CliOutput.out("Installed " + dev.jkbuild.cli.theme.Coords.gav(coord) + " to the local cache");
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
        Coordinate parsed;
        try {
            parsed = Coordinate.parse(coord);
        } catch (IllegalArgumentException e) {
            CliOutput.err("jk install: " + e.getMessage());
            return Exit.USAGE;
        }
        String bin = binName != null && !binName.isBlank() ? binName : parsed.artifact();
        Path cacheDir = cacheDir();
        Path envsRoot = stateDir().resolve("tools").resolve("envs");
        Path binDir = binDir();
        Files.createDirectories(cacheDir);
        GoalConsole.Mode mode = GoalConsole.modeFor(global);

        ToolEnv env;
        if (engineDisabledForTests()) {
            var o = dev.jkbuild.cli.engine.InProcessEngine.require()
                    .toolResolveGoal(parsed, bin, mainClass, repoUrl, cacheDir, Coords.gav(parsed), mode);
            if (o.env() == null) return failureExit(o.result(), "jk install", cacheDir);
            env = o.env();
        } else {
            dev.jkbuild.cli.engine.EngineClient.ToolResolveOutcome outcome;
            try {
                outcome = dev.jkbuild.cli.engine.EngineClient.runToolResolve(
                        dev.jkbuild.engine.EnginePaths.current(),
                        new dev.jkbuild.cli.engine.EngineClient.ToolResolveRequest(
                                coord, bin, mainClass, repoUrl, cacheDir),
                        phases -> GoalConsole.chooseConsoleListener("install-maven", phases, mode));
            } catch (IOException e) {
                CliOutput.err("jk install: " + e.getMessage());
                return Exit.SOFTWARE;
            }
            if (!outcome.result().success() || outcome.mainClass() == null) {
                return failureExit(outcome.result(), "jk install", cacheDir);
            }
            env = new ToolEnv(bin, parsed, outcome.mainClass(), outcome.classpath());
        }

        Path launcher = ToolLauncher.install(envsRoot, binDir, JavaHomes.runningJavaHome(), env);
        announceInstall(Coords.gav(parsed), launcher, binDir);
        return 0;
    }

    // --- mode 4: git URL -------------------------------------------------

    private int installFromGit(String input) throws IOException, InterruptedException {
        UrlAndRef split = splitUrlRef(input);
        String expanded = GitUrl.expand(split.url());
        String canonical = GitUrl.canonicalize(split.url());
        String refStr = split.ref() != null ? split.ref() : "main";
        Path cacheDir = cacheDir();
        Files.createDirectories(cacheDir);
        boolean refresh = dev.jkbuild.config.SessionContext.current().config().refreshOr(false);
        GoalConsole.Mode mode = GoalConsole.modeFor(global);

        GoalResult fetchResult;
        Path checkout;
        String sha;
        if (engineDisabledForTests()) {
            var o = dev.jkbuild.cli.engine.InProcessEngine.require()
                    .gitFetchGoal(expanded, canonical, refStr, cacheDir, refresh, mode);
            fetchResult = o.result();
            checkout = o.checkout();
            sha = o.sha();
        } else {
            // Engine-hosted clone (slim-client Wave 3): the git-client worker forks engine-side;
            // the checkout path + sha ride the terminal goal-finish.
            dev.jkbuild.cli.engine.EngineClient.GitFetchOutcome outcome;
            try {
                outcome = dev.jkbuild.cli.engine.EngineClient.runGitFetch(
                        dev.jkbuild.engine.EnginePaths.current(),
                        new dev.jkbuild.cli.engine.EngineClient.GitFetchRequest(
                                expanded, canonical, refStr, cacheDir, refresh),
                        phases -> GoalConsole.chooseConsoleListener("install-git-fetch", phases, mode));
            } catch (IOException e) {
                CliOutput.err("jk install: " + e.getMessage());
                return Exit.SOFTWARE;
            }
            fetchResult = outcome.result();
            checkout = outcome.checkout();
            sha = outcome.sha();
        }
        if (!fetchResult.success() || checkout == null || sha == null) {
            for (GoalResult.Diagnostic d : fetchResult.errors()) {
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
        return runProjectInstallGoal(checkout, "install-git");
    }

    // --- shared project-install pipeline ---------------------------------

    private int runProjectInstallGoal(Path projectDir, String goalName) throws IOException {
        Path cacheDir = cacheDir();
        Path binDir = binDir();
        Path libDir = libDir();

        // Validate up front: a non-native application needs a main class for its
        // launcher. (Done here, not in a phase, so we fail before building.)
        JkBuild proj = JkBuildParser.parse(projectDir.resolve("jk.toml"));
        var pj = proj.project();
        if (proj.isApplication() && proj.nativeMode() == JkBuild.NativeMode.DISABLED && proj.mainClass() == null) {
            CliOutput.err("jk install: application project at "
                    + dev.jkbuild.cli.PathDisplay.styledRaw(projectDir)
                    + " has no `main` class set in [application]");
            return Exit.USAGE;
        }
        // ALWAYS: native is part of the standard build and install produces a native binary.
        // SUPPORTED: user runs `jk native` explicitly; install deploys the jar.
        boolean isNative = proj.isApplication() && proj.nativeMode() == JkBuild.NativeMode.ALWAYS;

        // `jk build` no longer auto-builds native (that's `jk native`), so an installed native
        // application builds its binary here. Resolve the GraalVM up front — before any progress
        // UI opens, and before the request ships (a prompt/install owns this terminal and must
        // never run inside the engine).
        Path graalHome = null;
        if (isNative) {
            java.util.Optional<Path> resolved =
                    new dev.jkbuild.cli.GraalResolver(null, false).resolve(projectDir, proj.graal());
            if (resolved.isEmpty()) return 1; // GraalResolver already printed why
            graalHome = resolved.get();
        }

        // Build + cache-install through the shared InstallGoals goal (jar always; shadow/native
        // per jk.toml; jar + generated pom into ~/.m2 / repos/local) — engine-hosted for a real
        // invocation, in-process for the test-only bypass. The make-install half runs below,
        // client-side either way: it writes the user-home launcher/binary this process owns.
        GoalConsole.Mode mode = GoalConsole.modeFor(global);
        GoalResult result;
        TestSummary testResult;
        if (engineDisabledForTests()) {
            var o = dev.jkbuild.cli.engine.InProcessEngine.require()
                    .installProjectGoal(projectDir, cacheDir, m2Dir(), buildOpts.skipTests, global.verbose,
                            graalHome, mode);
            result = o.result();
            testResult = o.testResult();
        } else {
            var session = dev.jkbuild.config.SessionContext.current();
            TestSummary[] testResultHolder = new TestSummary[1];
            try {
                result = dev.jkbuild.cli.engine.EngineClient.runInstall(
                        dev.jkbuild.engine.EnginePaths.current(),
                        new dev.jkbuild.cli.engine.EngineClient.InstallRequest(
                                projectDir,
                                cacheDir,
                                m2Dir(),
                                graalHome,
                                buildOpts.skipTests,
                                session.offline(),
                                session.force(),
                                global.verbose),
                        phases -> GoalConsole.chooseConsoleListener(goalName, phases, mode),
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
        Coordinate coord = Coordinate.of(pj.group(), pj.name(), pj.version());
        Path launcher = null;
        if (proj.isApplication()) {
            try {
                launcher = makeInstallApp(projectDir, proj, BuildLayout.of(projectDir, proj), cacheDir, binDir,
                        libDir);
            } catch (IOException e) {
                CliOutput.err("jk install: make install failed: " + e.getMessage());
                return 1;
            }
        }
        announceProjectInstall(Coords.gav(coord), launcher, binDir);
        return 0;
    }

    /**
     * The {@code make install} step for applications. Dispatches by artifact type: a native binary
     * goes straight into {@code ~/.jk/bin}; a shadow jar goes into {@code ~/.jk/lib} with a
     * launcher; a normal jar goes into {@code ~/.jk/lib} alongside its hard-linked runtime
     * dependency jars, with a launcher whose classpath lists exactly those jars. Returns the
     * launcher (or the binary) path.
     */
    private Path makeInstallApp(
            Path projectDir, JkBuild project, BuildLayout layout, Path cacheDir, Path binDir, Path libDir)
            throws IOException {
        var p = project.project();
        String bin = binName != null && !binName.isBlank() ? binName : p.name();
        String mainCls = mainClass != null && !mainClass.isBlank() ? mainClass : project.mainClass();
        Path javaHome = JavaHomes.runningJavaHome();

        // Native binary → ~/.jk/bin/<bin>. Only for ALWAYS (auto-build) mode.
        if (project.nativeMode() == JkBuild.NativeMode.ALWAYS) {
            Files.createDirectories(binDir);
            Path dest = binDir.resolve(bin);
            Linking.linkOrCopy(layout.nativeBinary(), dest);
            markExecutable(dest);
            return dest;
        }

        Files.createDirectories(libDir);

        // Shadow/fat jar → a single self-contained jar in lib.
        if (project.shadowJar()) {
            Path dest = libDir.resolve(layout.shadowJar().getFileName().toString());
            Linking.linkOrCopy(layout.shadowJar(), dest);
            return AppLauncher.install(binDir, javaHome, bin, mainCls, List.of(dest));
        }

        // Normal jar → app jar + hard-linked runtime dependency jars in lib.
        List<Path> classpath = new ArrayList<>();
        Path appDest = libDir.resolve(layout.mainJar().getFileName().toString());
        Linking.linkOrCopy(layout.mainJar(), appDest);
        classpath.add(appDest);

        Path lockFile = resolveLockFile(projectDir);
        if (Files.exists(lockFile)) {
            Cas cas = new Cas(cacheDir);
            Lockfile lock = LockfileReader.read(lockFile);
            for (Lockfile.Artifact pkg : lock.artifacts()) {
                if (pkg.checksum() == null) continue;
                if (!pkg.inAnyScope(ClasspathResolver.RUNTIME)) continue; // EXPORT + MAIN + RUNTIME
                String hex = pkg.checksum().startsWith("sha256:")
                        ? pkg.checksum().substring("sha256:".length())
                        : pkg.checksum();
                Path blob = cas.pathFor(hex);
                if (!Files.exists(blob)) continue;
                String artifactId = pkg.moduleArtifact();
                Path dest = libDir.resolve(artifactId + "-" + pkg.version() + ".jar");
                Linking.linkOrCopy(blob, dest);
                classpath.add(dest);
            }
        }
        // Workspace sibling jars (built locally) also belong on the classpath.
        WorkspaceClasspath.Result siblings = WorkspaceClasspath.resolve(projectDir, project, ClasspathResolver.RUNTIME);
        for (Path sib : siblings.jars()) {
            Path dest = libDir.resolve(sib.getFileName().toString());
            Linking.linkOrCopy(sib, dest);
            classpath.add(dest);
        }
        return AppLauncher.install(binDir, javaHome, bin, mainCls, classpath);
    }

    private static Path resolveLockFile(Path projectDir) throws IOException {
        Path lockFile = projectDir.resolve("jk.lock");
        if (!Files.exists(lockFile)) {
            var rootOpt = WorkspaceLocator.findRoot(projectDir);
            if (rootOpt.isPresent()) {
                Path candidate = rootOpt.get().resolve("jk.lock");
                if (Files.exists(candidate)) return candidate;
            }
        }
        return lockFile;
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
     * Maps a failed install goal to exit code 1. Kept as a named helper so the various call sites
     * read uniformly; the listener already printed the "✗ Error" diagnostic so we don't repeat
     * ourselves.
     */
    private static int failureExit(GoalResult result, String label, Path cache) {
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
