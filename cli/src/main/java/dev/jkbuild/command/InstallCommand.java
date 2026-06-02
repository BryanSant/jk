// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;

import dev.jkbuild.cli.GlobalOptions;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cache.Journal;
import dev.jkbuild.cache.Linking;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceClasspath;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.git.GitFetcher;
import dev.jkbuild.http.Http;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.GitSource;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.model.Scope;
import dev.jkbuild.publish.PublishablePom;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.run.PhaseStatus;
import dev.jkbuild.tool.AppLauncher;
import dev.jkbuild.tool.ToolEnv;
import dev.jkbuild.tool.ToolLauncher;
import dev.jkbuild.tool.ToolResolver;
import dev.jkbuild.util.GitUrl;
import dev.jkbuild.util.Hashing;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code jk install [<source>]} — install a jk artifact. Three modes:
 *
 * <ul>
 *   <li><b>No source:</b> build the current project (per {@code jk.toml}) and
 *       install it. Always performs a <em>cache install</em> (jar + generated
 *       pom into jk's CAS + journal — the {@code mvn install} equivalent — so
 *       other local projects can depend on it; also into {@code ~/.m2} when
 *       {@code project.m2install} is set). When the project is an
 *       <em>application</em> ({@code project.application}, default true when a
 *       {@code main} is set) it additionally does a <em>make install</em>: a
 *       native binary into {@code ~/.jk/bin}; a shadow jar into
 *       {@code ~/.jk/libexec} with a launcher; or a normal jar into
 *       {@code ~/.jk/libexec} alongside its hard-linked runtime deps with a
 *       launcher in {@code ~/.jk/bin}.</li>
 *   <li><b>Maven coord (g:a:v):</b> download the pre-built jar from declared
 *       Maven repositories and install a launcher (the legacy
 *       {@code jk tool install} behavior).</li>
 *   <li><b>Git / HTTPS URL:</b> clone, build, install. The URL may carry an
 *       optional {@code @<ref>} or {@code #<ref>} suffix selecting a tag or
 *       branch (default: {@code main}). Host shorthands {@code gh:owner/repo}
 *       etc. are accepted.</li>
 * </ul>
 *
 * <p>Each mode is its own Goal. Like {@code jk run}, the work that
 * "shells out" to a nested {@code jk build} happens inside the
 * {@code ensure-built} phase; the nested progress widget overlaps
 * briefly with the outer one, a known UX wrinkle.
 */
@Command(name = "install",
        description = "Install the current project or a specified target")
public final class InstallCommand implements Callable<Integer> {

    @Parameters(arity = "0..1", paramLabel = "<source>",
            description = "Maven coord (group:artifact:version) OR a git URL "
                    + "(optionally suffixed with @<tag-or-branch> or #<tag-or-branch>). "
                    + "Omit to install the current jk.toml project.")
    String source;

    @Option(names = "--bin",
            description = "Launcher name under $JK_BIN_DIR. Default: the artifact id.")
    String binName;

    @Option(names = "--main",
            description = "Override the Main-Class to exec.")
    String mainClass;

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
    Path cacheDirOverride;

    @Option(names = "--state-dir", hidden = true,
            description = "Override the tool state directory. Default: $JK_STATE_DIR.")
    Path stateDirOverride;

    @Option(names = "--bin-dir", hidden = true,
            description = "Override the bin directory. Default: $JK_BIN_DIR or ~/.local/bin.")
    Path binDirOverride;

    @Option(names = "--libexec-dir", hidden = true,
            description = "Override the libexec directory. Default: $JK_LIBEXEC_DIR or ~/.jk/libexec.")
    Path libexecDirOverride;

    @Option(names = "--m2-dir", hidden = true,
            description = "Override the local Maven repo root (~/.m2) for m2install.")
    Path m2DirOverride;

    @Option(names = "--repo-url", hidden = true,
            description = "Override the Maven repository URL (for tests).")
    URI repoUrl;

    @picocli.CommandLine.Mixin GlobalOptions global;

    // Cross-phase keys.
    private static final GoalKey<JkBuild> PROJECT = GoalKey.of("project", JkBuild.class);
    private static final GoalKey<BuildLayout> LAYOUT = GoalKey.of("layout", BuildLayout.class);
    private static final GoalKey<Path> JAR = GoalKey.of("jar", Path.class);
    private static final GoalKey<ToolEnv> TOOL_ENV = GoalKey.of("tool-env", ToolEnv.class);
    private static final GoalKey<Coordinate> PRIMARY = GoalKey.of("primary-coord", Coordinate.class);
    private static final GoalKey<Path> LAUNCHER = GoalKey.of("launcher", Path.class);
    private static final GoalKey<Path> CHECKOUT = GoalKey.of("checkout-dir", Path.class);
    private static final GoalKey<String> FETCHED_SHA = GoalKey.of("fetched-sha", String.class);

    @Override
    public Integer call() throws IOException, InterruptedException {
        if (source == null || source.isBlank()) {
            return installCurrentProject();
        }
        if (looksLikeGitUrl(source)) {
            return installFromGit(source);
        }
        return installFromMaven(source);
    }

    // --- mode 1: current project -----------------------------------------

    private int installCurrentProject() throws IOException {
        Path projectDir = global.workingDir();
        Path manifest = projectDir.resolve("jk.toml");
        if (!Files.exists(manifest)) {
            System.err.println("jk install: no jk.toml in " + projectDir);
            return 64;
        }
        return runProjectInstallGoal(projectDir, "install");
    }

    // --- mode 2: Maven coord ---------------------------------------------

    private int installFromMaven(String coord) throws IOException, InterruptedException {
        Coordinate parsed;
        try {
            parsed = Coordinate.parse(coord);
        } catch (IllegalArgumentException e) {
            System.err.println("jk install: " + e.getMessage());
            return 64;
        }
        String bin = binName != null && !binName.isBlank() ? binName : parsed.artifact();
        Path cacheDir = cacheDir();
        Path envsRoot = stateDir().resolve("tools").resolve("envs");
        Path binDir = binDir();
        Files.createDirectories(cacheDir);

        Phase resolveCoord = Phase.builder("resolve-coord")
                .kind(PhaseKind.IO)
                .scope(1)
                .execute(ctx -> {
                    ctx.label("fetch " + parsed.toGav());
                    Cas cas = new Cas(cacheDir);
                    Http http = new Http();
                    URI url = repoUrl != null ? repoUrl : RepositorySpec.MAVEN_CENTRAL.url();
                    RepoGroup repos = RepoGroup.of(new MavenRepo("central", url, http, cas));
                    ToolResolver toolResolver = new ToolResolver(repos);
                    try {
                        ToolEnv env = toolResolver.resolve(parsed, bin, mainClass);
                        ctx.put(TOOL_ENV, env);
                        ctx.put(PRIMARY, parsed);
                    } catch (RuntimeException | IOException e) {
                        ctx.error("resolve", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    ctx.progress(1);
                })
                .build();

        Phase installLauncher = Phase.builder("install-launcher")
                .requires("resolve-coord")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("write launcher to " + binDir);
                    Path javaHome = CompileToolchain.runningJavaHome();
                    Path launcher = ToolLauncher.install(
                            envsRoot, binDir, javaHome, ctx.require(TOOL_ENV));
                    ctx.put(LAUNCHER, launcher);
                    ctx.progress(1);
                })
                .build();

        Goal goal = Goal.builder("install-maven")
                .addPhase(resolveCoord)
                .addPhase(installLauncher)
                .build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cacheDir);
        if (!result.success()) return failureExit(result, "jk install", cacheDir);

        announceInstall(goal.get(PRIMARY).orElseThrow().toGav(),
                goal.get(LAUNCHER).orElseThrow(), binDir);
        return 0;
    }

    // --- mode 3: git URL -------------------------------------------------

    private int installFromGit(String input) throws IOException, InterruptedException {
        UrlAndRef split = splitUrlRef(input);
        String expanded = GitUrl.expand(split.url());
        String canonical = GitUrl.canonicalize(split.url());
        String refStr = split.ref() != null ? split.ref() : "main";
        Path cacheDir = cacheDir();
        Files.createDirectories(cacheDir);

        Phase fetch = Phase.builder("fetch-git")
                .kind(PhaseKind.IO)
                .scope(1)
                .execute(ctx -> {
                    ctx.label("git fetch " + expanded + " @ " + refStr);
                    boolean noCache = dev.jkbuild.config.ActiveConfig.get().noCacheOr(false);
                    GitFetcher fetcher = new GitFetcher(cacheDir.resolve("git"));
                    GitFetcher.Fetched fetched;
                    try {
                        fetched = fetchTagOrBranch(fetcher, expanded, canonical, refStr, noCache);
                    } catch (IOException e) {
                        ctx.error("fetch", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    Path checkout = fetched.checkoutPath();
                    if (!Files.exists(checkout.resolve("jk.toml"))) {
                        ctx.error("no-jk-toml",
                                expanded + " has no jk.toml at " + refStr);
                        throw new RuntimeException("no jk.toml in checkout");
                    }
                    ctx.put(CHECKOUT, checkout);
                    ctx.put(FETCHED_SHA, fetched.sha());
                    ctx.progress(1);
                })
                .build();

        // After fetch, hand off to the same project-install pipeline used by
        // mode 1, but with the checkout dir instead of the user's CWD.
        Goal fetchGoal = Goal.builder("install-git-fetch")
                .addPhase(fetch)
                .build();

        GoalResult fetchResult = GoalConsole.run(
                fetchGoal, GoalConsole.modeFor(global), cacheDir);
        if (!fetchResult.success()) {
            for (GoalResult.Diagnostic d : fetchResult.errors()) {
                if ("no-jk-toml".equals(d.code())) return 70;
            }
            return failureExit(fetchResult, "jk install", cacheDir);
        }

        Path checkout = fetchGoal.get(CHECKOUT).orElseThrow();
        String sha = fetchGoal.get(FETCHED_SHA).orElseThrow();
        if (!global.outputIsJson()) {
            System.out.println("Fetched " + expanded + " @ " + refStr
                    + " (" + sha.substring(0, Math.min(7, sha.length())) + ")");
        }

        return runProjectInstallGoal(checkout, "install-git");
    }

    /** Try the user's ref as a tag first, then a branch. */
    private static GitFetcher.Fetched fetchTagOrBranch(
            GitFetcher fetcher, String expanded, String canonical, String refStr,
            boolean noCache) throws IOException {
        IOException tagFailure;
        try {
            GitSource asTag = new GitSource(
                    canonical, expanded, new GitRefSpec.Tag(refStr), null, true, false);
            return fetcher.fetch(asTag, noCache);
        } catch (IOException e) {
            tagFailure = e;
        }
        try {
            GitSource asBranch = new GitSource(
                    canonical, expanded, new GitRefSpec.Branch(refStr), null, true, false);
            return fetcher.fetch(asBranch, noCache);
        } catch (IOException branchFailure) {
            IOException wrapped = new IOException(
                    "ref `" + refStr + "` not found as tag or branch in " + expanded);
            wrapped.addSuppressed(tagFailure);
            wrapped.addSuppressed(branchFailure);
            throw wrapped;
        }
    }

    // --- shared project-install pipeline ---------------------------------

    private int runProjectInstallGoal(Path projectDir, String goalName) throws IOException {
        Path cacheDir = cacheDir();
        Path binDir = binDir();
        Path libexecDir = libexecDir();

        Phase parseBuild = Phase.builder("parse-build")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml");
                    JkBuild project = JkBuildParser.parse(projectDir.resolve("jk.toml"));
                    var p = project.project();
                    // A non-native application needs a main class for its launcher.
                    if (p.isApplication() && !p.nativeMode().isEnabled() && p.main() == null) {
                        ctx.error("no-main", "application project at " + projectDir
                                + " has no `main` class set in [project]");
                        throw new RuntimeException("no main");
                    }
                    ctx.put(PROJECT, project);
                    ctx.put(LAYOUT, BuildLayout.of(projectDir, project));
                    ctx.put(JAR, BuildLayout.of(projectDir, project).mainJar());
                    ctx.progress(1);
                })
                .build();

        Phase ensureBuilt = Phase.builder("ensure-built")
                .kind(PhaseKind.CPU)
                .requires("parse-build")
                .scope(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    var p = project.project();
                    BuildLayout layout = ctx.require(LAYOUT);
                    boolean isNative = p.isApplication() && p.nativeMode().isEnabled();
                    boolean needShadow = p.isApplication() && p.shadow() && !isNative;

                    Path jar = layout.mainJar();
                    if (!Files.exists(jar) || (needShadow && !Files.exists(layout.shadowJar()))) {
                        ctx.label("running jk build");
                        // Share install's cache so the build's deps land where
                        // cache-install / make-install read them from.
                        int rc = Jk.execute("build", "-C", projectDir.toString(),
                                "--cache-dir", cacheDir.toString());
                        if (rc != 0) {
                            ctx.error("nested-build", "jk build exited " + rc);
                            throw new RuntimeException("nested build failed");
                        }
                    }
                    if (!Files.exists(jar)) {
                        ctx.error("missing-jar", "expected jar at " + jar
                                + " but build did not produce it.");
                        throw new RuntimeException("jar not produced");
                    }
                    if (isNative && !Files.exists(layout.nativeBinary())) {
                        ctx.label("running jk native");
                        int rc = Jk.execute("native", "-C", projectDir.toString(),
                                "--cache-dir", cacheDir.toString());
                        if (rc != 0 || !Files.exists(layout.nativeBinary())) {
                            ctx.error("missing-native", "expected native binary at "
                                    + layout.nativeBinary() + " but it was not produced.");
                            throw new RuntimeException("native binary not produced");
                        }
                    }
                    ctx.progress(1);
                })
                .build();

        // Always: install the jar + pom into jk's CAS + journal (the
        // `mvn install` equivalent), so other local projects can resolve it.
        Phase cacheInstall = Phase.builder("cache-install")
                .requires("ensure-built")
                .scope(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    var p = project.project();
                    Coordinate coord = Coordinate.of(p.group(), p.artifact(), p.version());
                    ctx.label("install " + coord.toGav() + " to cache");
                    try {
                        cacheInstallArtifact(project, layout, cacheDir);
                    } catch (IOException e) {
                        ctx.error("cache-install", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    ctx.put(PRIMARY, coord);
                    ctx.progress(1);
                })
                .build();

        // Only for applications: place a runnable artifact under ~/.jk.
        Phase makeInstall = Phase.builder("make-install")
                .requires("cache-install")
                .scope(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    if (!project.project().isApplication()) {
                        ctx.label("library — cache install only");
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("make install to " + binDir);
                    try {
                        Path launcher = makeInstallApp(projectDir, project, layout, cacheDir,
                                binDir, libexecDir);
                        ctx.put(LAUNCHER, launcher);
                    } catch (IOException e) {
                        ctx.error("make-install", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    ctx.progress(1);
                })
                .build();

        Goal goal = Goal.builder(goalName)
                .addPhase(parseBuild)
                .addPhase(ensureBuilt)
                .addPhase(cacheInstall)
                .addPhase(makeInstall)
                .build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cacheDir);
        if (!result.success()) {
            for (GoalResult.Diagnostic d : result.errors()) {
                if ("no-main".equals(d.code())) return 64;
                if ("missing-jar".equals(d.code()) || "missing-native".equals(d.code())) return 70;
            }
            return failureExit(result, "jk install", cacheDir);
        }

        announceProjectInstall(goal.get(PRIMARY).orElseThrow().toGav(),
                goal.get(LAUNCHER).orElse(null), binDir);
        return 0;
    }

    /**
     * The {@code mvn install} equivalent: hash the built jar and a generated
     * pom into jk's CAS and record them in the journal as a {@code local}
     * source so other local jk projects resolve them. When {@code m2install}
     * is set, also materialise jar+pom into {@code ~/.m2/repository}.
     */
    private void cacheInstallArtifact(JkBuild project, BuildLayout layout, Path cacheDir)
            throws IOException {
        var p = project.project();
        Coordinate coord = Coordinate.of(p.group(), p.artifact(), p.version());
        Cas cas = new Cas(cacheDir);
        Journal journal = new Journal(cacheDir);

        Path jar = layout.mainJar();
        byte[] jarBytes = Files.readAllBytes(jar);
        String jarHex = Hashing.sha256Hex(jarBytes);
        cas.putByLink(jar, jarHex);
        journal.record(coord, "jar", jarHex, jarBytes.length, "local", null);

        String pomXml = PublishablePom.render(project, null).xml();
        byte[] pomBytes = pomXml.getBytes(StandardCharsets.UTF_8);
        cas.put(pomBytes);
        journal.record(coord, "pom", Hashing.sha256Hex(pomBytes), pomBytes.length, "local", null);

        if (p.m2install()) {
            Path dir = m2Dir().resolve("repository");
            for (String seg : p.group().split("\\.")) dir = dir.resolve(seg);
            dir = dir.resolve(p.artifact()).resolve(p.version());
            Files.createDirectories(dir);
            String base = p.artifact() + "-" + p.version();
            Linking.linkOrCopy(jar, dir.resolve(base + ".jar"));
            Files.writeString(dir.resolve(base + ".pom"), pomXml, StandardCharsets.UTF_8);
        }
    }

    /**
     * The {@code make install} step for applications. Dispatches by artifact
     * type: a native binary goes straight into {@code ~/.jk/bin}; a shadow jar
     * goes into {@code ~/.jk/libexec} with a launcher; a normal jar goes into
     * {@code libexec} alongside its hard-linked runtime dependency jars, with a
     * launcher whose classpath lists exactly those jars. Returns the launcher
     * (or the binary) path.
     */
    private Path makeInstallApp(Path projectDir, JkBuild project, BuildLayout layout,
                                Path cacheDir, Path binDir, Path libexecDir) throws IOException {
        var p = project.project();
        String bin = binName != null && !binName.isBlank() ? binName : p.artifact();
        String mainCls = mainClass != null && !mainClass.isBlank() ? mainClass : p.main();
        Path javaHome = CompileToolchain.runningJavaHome();

        // Native binary → ~/.jk/bin/<bin>.
        if (p.nativeMode().isEnabled()) {
            Files.createDirectories(binDir);
            Path dest = binDir.resolve(bin);
            Linking.linkOrCopy(layout.nativeBinary(), dest);
            markExecutable(dest);
            return dest;
        }

        Files.createDirectories(libexecDir);

        // Shadow/fat jar → a single self-contained jar in libexec.
        if (p.shadow()) {
            Path dest = libexecDir.resolve(layout.shadowJar().getFileName().toString());
            Linking.linkOrCopy(layout.shadowJar(), dest);
            return AppLauncher.install(binDir, javaHome, bin, mainCls, List.of(dest));
        }

        // Normal jar → app jar + hard-linked runtime dependency jars in libexec.
        List<Path> classpath = new ArrayList<>();
        Path appDest = libexecDir.resolve(layout.mainJar().getFileName().toString());
        Linking.linkOrCopy(layout.mainJar(), appDest);
        classpath.add(appDest);

        Path lockFile = resolveLockFile(projectDir);
        if (Files.exists(lockFile)) {
            Cas cas = new Cas(cacheDir);
            Lockfile lock = LockfileReader.read(lockFile);
            for (Lockfile.Package pkg : lock.packages()) {
                if (pkg.checksum() == null) continue;
                if (!(pkg.scopes().contains(Scope.MAIN) || pkg.scopes().contains(Scope.RUNTIME))) continue;
                String hex = pkg.checksum().startsWith("sha256:")
                        ? pkg.checksum().substring("sha256:".length()) : pkg.checksum();
                Path blob = cas.pathFor(hex);
                if (!Files.exists(blob)) continue;
                String artifactId = pkg.name().substring(pkg.name().indexOf(':') + 1);
                Path dest = libexecDir.resolve(artifactId + "-" + pkg.version() + ".jar");
                Linking.linkOrCopy(blob, dest);
                classpath.add(dest);
            }
        }
        // Workspace sibling jars (built locally) also belong on the classpath.
        WorkspaceClasspath.Result siblings = WorkspaceClasspath.resolve(projectDir, project,
                EnumSet.of(Scope.MAIN, Scope.RUNTIME));
        for (Path sib : siblings.jars()) {
            Path dest = libexecDir.resolve(sib.getFileName().toString());
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
     * Maps a failed install goal to exit code 1. Kept as a named helper so
     * the various call sites read uniformly; the listener already printed
     * the "✗ Error" diagnostic so we don't repeat ourselves.
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

    private Path libexecDir() {
        return libexecDirOverride != null ? libexecDirOverride : JkDirs.libexec();
    }

    private Path m2Dir() {
        if (m2DirOverride != null) return m2DirOverride;
        return Path.of(System.getProperty("user.home", "."), ".m2");
    }

    private void announceInstall(String coord, Path launcher, Path binDir) {
        if (global.outputIsJson()) return;
        System.out.println("Installed " + coord + " → " + launcher);
        System.out.println("Add to PATH if needed:");
        System.out.println("  export PATH=\"" + binDir + ":$PATH\"");
    }

    /** Announce a project install: launcher path for an app, cache-only for a library. */
    private void announceProjectInstall(String coord, Path launcher, Path binDir) {
        if (global.outputIsJson()) return;
        if (launcher == null) {
            System.out.println("Installed " + coord + " to the local cache");
            return;
        }
        System.out.println("Installed " + coord + " → " + launcher);
        System.out.println("Add to PATH if needed:");
        System.out.println("  export PATH=\"" + binDir + ":$PATH\"");
    }

    private static boolean looksLikeGitUrl(String input) {
        return input.startsWith("git@")
                || input.startsWith("http://") || input.startsWith("https://")
                || input.startsWith("ssh://") || input.startsWith("git://")
                || input.startsWith("file://")
                || input.startsWith("gh:") || input.startsWith("gl:")
                || input.startsWith("bb:") || input.startsWith("sr:");
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
            if (!suffix.isEmpty()
                    && suffix.indexOf('/') < 0
                    && suffix.indexOf(':') < 0
                    && suffix.indexOf('@') < 0) {
                return new UrlAndRef(input.substring(0, at), suffix);
            }
        }
        return new UrlAndRef(input, null);
    }
}
