// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cache.Cas;
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

    @Option(names = {"-C", "--directory"},
            description = "Project directory for the no-arg case. Default: cwd.")
    Path directory;

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
        Path projectDir = directory != null
                ? directory : Path.of(".").toAbsolutePath().normalize();
        Path manifest = projectDir.resolve("jk.toml");
        if (!Files.exists(manifest)) {
            System.err.println("jk install: no jk.toml in " + projectDir);
            return 64; // EX_USAGE
        }
        return installProjectAt(projectDir);
    }

    // --- mode 2: Maven coord --------------------------------------------

    private int installFromMaven(String coord) throws IOException, InterruptedException {
        Coordinate primary;
        try {
            primary = Coordinate.parse(coord);
        } catch (IllegalArgumentException e) {
            System.err.println("jk install: " + e.getMessage());
            return 64;
        }
        String bin = binName != null && !binName.isBlank() ? binName : primary.artifact();

        Path cacheDir = cacheDir();
        Path envsRoot = stateDir().resolve("tools").resolve("envs");
        Path binDir = binDir();
        Files.createDirectories(cacheDir);

        Cas cas = new Cas(cacheDir);
        Http http = new Http();
        URI url = repoUrl != null ? repoUrl : RepositorySpec.MAVEN_CENTRAL.url();
        RepoGroup repos = RepoGroup.of(new MavenRepo("central", url, http, cas));
        ToolResolver toolResolver = new ToolResolver(repos);

        System.out.println("Resolving " + primary.toGav() + " ...");
        ToolEnv env = toolResolver.resolve(primary, bin, mainClass);
        Path javaHome = CompileToolchain.runningJavaHome();
        Path launcher = ToolLauncher.install(envsRoot, binDir, javaHome, env);

        announceInstall(primary.toGav(), launcher, binDir);
        return 0;
    }

    // --- mode 3: git URL ------------------------------------------------

    private int installFromGit(String input) throws IOException, InterruptedException {
        UrlAndRef split = splitUrlRef(input);
        String expanded = GitUrl.expand(split.url());
        String canonical = GitUrl.canonicalize(split.url());
        String refStr = split.ref() != null ? split.ref() : "main";

        GitFetcher fetcher = new GitFetcher(cacheDir().resolve("git"));
        GitFetcher.Fetched fetched = fetchTagOrBranch(fetcher, expanded, canonical, refStr);

        Path checkoutDir = fetched.checkoutPath();
        if (!Files.exists(checkoutDir.resolve("jk.toml"))) {
            System.err.println("jk install: " + expanded + " has no jk.toml at " + refStr);
            return 70; // EX_SOFTWARE
        }
        System.out.println("Fetched " + expanded + " @ " + refStr
                + " (" + fetched.sha().substring(0, Math.min(7, fetched.sha().length())) + ")");
        return installProjectAt(checkoutDir);
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

    // --- shared project-install path -------------------------------------

    private int installProjectAt(Path projectDir) throws IOException {
        JkBuild project = JkBuildParser.parse(projectDir.resolve("jk.toml"));
        if (project.project().main() == null) {
            System.err.println("jk install: project at " + projectDir
                    + " has no `main` class set in [project]");
            return 64;
        }
        String artifact = project.project().artifact();
        String version = project.project().version();
        Path jar = projectDir.resolve("target")
                .resolve(artifact + "-" + version + ".jar");

        if (!Files.exists(jar)) {
            int rc = Jk.execute("build", "-C", projectDir.toString());
            if (rc != 0) return rc;
        }
        if (!Files.exists(jar)) {
            System.err.println("jk install: expected jar at " + jar
                    + " but build did not produce it.");
            return 70;
        }

        List<Path> classpath = assembleRuntimeClasspath(projectDir, project, jar);
        String bin = binName != null && !binName.isBlank() ? binName : artifact;
        String mainCls = mainClass != null && !mainClass.isBlank()
                ? mainClass : project.project().main();

        Coordinate primary = Coordinate.of(project.project().group(), artifact, version);
        ToolEnv env = new ToolEnv(bin, primary, mainCls, classpath);

        Path envsRoot = stateDir().resolve("tools").resolve("envs");
        Path binDir = binDir();
        Path javaHome = CompileToolchain.runningJavaHome();
        Path launcher = ToolLauncher.install(envsRoot, binDir, javaHome, env);

        announceInstall(primary.toGav(), launcher, binDir);
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

    private Path cacheDir() {
        return cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
    }

    private Path stateDir() {
        return stateDirOverride != null ? stateDirOverride : JkDirs.state();
    }

    private Path binDir() {
        return binDirOverride != null ? binDirOverride : JkDirs.binDir();
    }

    private static void announceInstall(String coord, Path launcher, Path binDir) {
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
