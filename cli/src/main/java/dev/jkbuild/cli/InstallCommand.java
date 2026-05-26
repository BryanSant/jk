// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceClasspath;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.git.GitFetcher;
import dev.jkbuild.http.Http;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.GitSource;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.model.Scope;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.run.PhaseStatus;
import dev.jkbuild.tool.ToolEnv;
import dev.jkbuild.tool.ToolLauncher;
import dev.jkbuild.tool.ToolResolver;
import dev.jkbuild.util.GitUrl;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jk install [<source>]} — install a runnable jk artifact as a
 * launcher under {@code $JK_BIN_DIR}. Three modes:
 *
 * <ul>
 *   <li><b>No source:</b> build the current project (per {@code jk.toml})
 *       and install its main jar.</li>
 *   <li><b>Maven coord (g:a:v):</b> download the pre-built jar from declared
 *       Maven repositories and install (the legacy {@code jk tool install}
 *       behavior).</li>
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
        description = "Install the current project, a Maven coord, or a git URL")
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

    @Option(names = "--repo-url", hidden = true,
            description = "Override the Maven repository URL (for tests).")
    URI repoUrl;

    @picocli.CommandLine.Mixin GlobalOptions global;

    // Cross-phase keys.
    private static final GoalKey<JkBuild> PROJECT = GoalKey.of("project", JkBuild.class);
    private static final GoalKey<Path> JAR = GoalKey.of("jar", Path.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> CLASSPATH = GoalKey.of("classpath", List.class);
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
                    GitFetcher fetcher = new GitFetcher(cacheDir.resolve("git"));
                    GitFetcher.Fetched fetched;
                    try {
                        fetched = fetchTagOrBranch(fetcher, expanded, canonical, refStr);
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
                if ("no-jk-toml".equals(d.code())) {
                    printFailure(fetchResult, "jk install", cacheDir);
                    return 70;
                }
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
            GitFetcher fetcher, String expanded, String canonical, String refStr) throws IOException {
        IOException tagFailure;
        try {
            GitSource asTag = new GitSource(
                    canonical, expanded, new GitRefSpec.Tag(refStr), null, true, false);
            return fetcher.fetch(asTag);
        } catch (IOException e) {
            tagFailure = e;
        }
        try {
            GitSource asBranch = new GitSource(
                    canonical, expanded, new GitRefSpec.Branch(refStr), null, true, false);
            return fetcher.fetch(asBranch);
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
        Path envsRoot = stateDir().resolve("tools").resolve("envs");
        Path binDir = binDir();

        Phase parseBuild = Phase.builder("parse-build")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml");
                    JkBuild project = JkBuildParser.parse(projectDir.resolve("jk.toml"));
                    if (project.project().main() == null) {
                        ctx.error("no-main", "project at " + projectDir
                                + " has no `main` class set in [project]");
                        throw new RuntimeException("no main");
                    }
                    ctx.put(PROJECT, project);
                    ctx.put(JAR, projectDir.resolve("target")
                            .resolve(project.project().artifact() + "-"
                                    + project.project().version() + ".jar"));
                    ctx.progress(1);
                })
                .build();

        Phase ensureBuilt = Phase.builder("ensure-built")
                .kind(PhaseKind.CPU)
                .requires("parse-build")
                .scope(1)
                .execute(ctx -> {
                    Path jar = ctx.require(JAR);
                    if (Files.exists(jar)) {
                        ctx.label("jar present");
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("jar missing — running jk build");
                    int rc = Jk.execute("build", "-C", projectDir.toString());
                    if (rc != 0) {
                        ctx.error("nested-build", "jk build exited " + rc);
                        throw new RuntimeException("nested build failed");
                    }
                    if (!Files.exists(jar)) {
                        ctx.error("missing-jar", "expected jar at " + jar
                                + " but build did not produce it.");
                        throw new RuntimeException("jar not produced");
                    }
                    ctx.progress(1);
                })
                .build();

        Phase assembleClasspath = Phase.builder("assemble-classpath")
                .requires("ensure-built")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("collect runtime classpath");
                    List<Path> classpath = assembleRuntimeClasspath(projectDir,
                            ctx.require(PROJECT), ctx.require(JAR));
                    ctx.put(CLASSPATH, classpath);
                    ctx.progress(1);
                })
                .build();

        Phase installLauncher = Phase.builder("install-launcher")
                .requires("assemble-classpath")
                .scope(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    String artifact = project.project().artifact();
                    String version = project.project().version();
                    String bin = binName != null && !binName.isBlank() ? binName : artifact;
                    String mainCls = mainClass != null && !mainClass.isBlank()
                            ? mainClass : project.project().main();

                    Coordinate primary = Coordinate.of(project.project().group(), artifact, version);
                    ToolEnv env = new ToolEnv(bin, primary, mainCls, classpath);
                    ctx.label("write launcher to " + binDir);
                    Path javaHome = CompileToolchain.runningJavaHome();
                    Path launcher = ToolLauncher.install(envsRoot, binDir, javaHome, env);
                    ctx.put(PRIMARY, primary);
                    ctx.put(LAUNCHER, launcher);
                    ctx.progress(1);
                })
                .build();

        Goal goal = Goal.builder(goalName)
                .addPhase(parseBuild)
                .addPhase(ensureBuilt)
                .addPhase(assembleClasspath)
                .addPhase(installLauncher)
                .build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cacheDir);
        if (!result.success()) {
            for (GoalResult.Diagnostic d : result.errors()) {
                if ("no-main".equals(d.code())) {
                    printFailure(result, "jk install", cacheDir);
                    return 64;
                }
                if ("missing-jar".equals(d.code())) {
                    printFailure(result, "jk install", cacheDir);
                    return 70;
                }
            }
            return failureExit(result, "jk install", cacheDir);
        }

        announceInstall(goal.get(PRIMARY).orElseThrow().toGav(),
                goal.get(LAUNCHER).orElseThrow(), binDir);
        return 0;
    }

    private List<Path> assembleRuntimeClasspath(Path projectDir, JkBuild project, Path projectJar)
            throws IOException {
        List<Path> classpath = new ArrayList<>();
        classpath.add(projectJar);

        Path lockFile = projectDir.resolve("jk.lock");
        if (!Files.exists(lockFile)) {
            var rootOpt = WorkspaceLocator.findRoot(projectDir);
            if (rootOpt.isPresent()) {
                Path candidate = rootOpt.get().resolve("jk.lock");
                if (Files.exists(candidate)) lockFile = candidate;
            }
        }
        Cas cas = new Cas(cacheDir());
        if (Files.exists(lockFile)) {
            Lockfile lock = LockfileReader.read(lockFile);
            classpath.addAll(new ClasspathResolver(cas).classpathFor(lock,
                    EnumSet.of(Scope.MAIN, Scope.RUNTIME)));
        }
        WorkspaceClasspath.Result siblings = WorkspaceClasspath.resolve(projectDir, project,
                EnumSet.of(Scope.MAIN, Scope.RUNTIME));
        classpath.addAll(siblings.jars());
        return classpath;
    }

    // --- helpers ---------------------------------------------------------

    private static int failureExit(GoalResult result, String label, Path cache) {
        printFailure(result, label, cache);
        return 1;
    }

    private static void printFailure(GoalResult result, String label, Path cache) {
        String failed = result.phases().stream()
                .filter(p -> p.status() == PhaseStatus.FAIL)
                .map(GoalResult.PhaseReport::name).findFirst().orElse("?");
        System.err.println(label + " failed: " + failed);
        for (GoalResult.Diagnostic d : result.errors()) {
            System.err.println("  " + d.code() + ": " + d.message());
        }
        System.err.println("Run log: " + cache.resolve("runs"));
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

    private void announceInstall(String coord, Path launcher, Path binDir) {
        if (global.outputIsJson()) return;
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
