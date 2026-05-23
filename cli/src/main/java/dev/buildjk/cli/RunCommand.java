// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.cache.Cas;
import dev.buildjk.compile.ClasspathResolver;
import dev.buildjk.compile.CompileRequest;
import dev.buildjk.compile.CompileResult;
import dev.buildjk.compile.JavacDriver;
import dev.buildjk.config.BuildJkParser;
import dev.buildjk.config.WorkspaceClasspath;
import dev.buildjk.config.WorkspaceLocator;
import dev.buildjk.http.Http;
import dev.buildjk.lock.Lockfile;
import dev.buildjk.lock.LockfileReader;
import dev.buildjk.model.BuildJk;
import dev.buildjk.model.Coordinate;
import dev.buildjk.model.Dependency;
import dev.buildjk.model.RepositorySpec;
import dev.buildjk.model.Scope;
import dev.buildjk.repo.EffectivePomBuilder;
import dev.buildjk.repo.MavenRepo;
import dev.buildjk.repo.RepoGroup;
import dev.buildjk.resolver.NaiveResolver;
import dev.buildjk.resolver.Resolution;
import dev.buildjk.script.ScriptHeader;
import dev.buildjk.script.ScriptHeaderParser;
import dev.buildjk.util.Hashing;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code jk run [<script.java>] [-- <args>...]} — runs either a single-file
 * Java script (JBang-compat, PRD §19) or the current project's main class.
 *
 * <p>Modes:
 * <ul>
 *   <li><b>Script:</b> {@code jk run script.java [args...]} — compile the
 *       {@code .java} file with {@link JavacDriver}, cache classes, exec with
 *       the script-declared classpath.</li>
 *   <li><b>Project:</b> {@code jk run [args...]} with a {@code jk.toml} in
 *       cwd. Builds the project jar if missing, then exec'es the configured
 *       {@code project.main} class against the lockfile's runtime classpath.
 *       If a native binary is present at {@code target/<artifact>}, that wins
 *       and is exec'd directly.</li>
 * </ul>
 *
 * <p>Disambiguation: the first positional is treated as a script when it
 * names an existing {@code .java} file; otherwise all positionals forward
 * to the project's main class.
 */
@Command(name = "run", description = "Run a single-file Java script or the current project")
public final class RunCommand implements Callable<Integer> {

    @Parameters(arity = "0..*", paramLabel = "<args>",
            description = "Either a .java script path (then args after it) or "
                    + "arguments forwarded to the project's main class.")
    List<String> positional = new ArrayList<>();

    @Option(names = {"-C", "--directory"},
            description = "Project directory for project-mode runs. Default: current directory.")
    Path directory;

    @Option(names = "--home", hidden = true,
            description = "Override the jk home root. Default: ~/.jk.")
    Path home;

    @Option(names = "--repo-url", hidden = true,
            description = "Override the Maven repository URL (for tests).")
    URI repoUrl;

    @Option(names = "--force-recompile",
            description = "Ignore cached classes and recompile (script mode).")
    boolean forceRecompile;

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path scriptPath = detectScript();
        if (scriptPath != null) {
            List<String> scriptArgs = positional.subList(1, positional.size());
            return runScript(scriptPath, scriptArgs);
        }
        Path projectDir = directory != null
                ? directory : Path.of(".").toAbsolutePath().normalize();
        Path manifest = projectDir.resolve("jk.toml");
        if (!Files.exists(manifest)) {
            System.err.println("jk run: no script given and no jk.toml in " + projectDir);
            return 64; // EX_USAGE
        }
        return runProject(projectDir, positional);
    }

    /**
     * If the first positional looks like a script ({@code .java} suffix),
     * treat as script mode — even if the file is missing, so the user gets
     * the "script not found" error instead of falling through to project mode.
     */
    private Path detectScript() {
        if (positional.isEmpty()) return null;
        String first = positional.getFirst();
        return first.endsWith(".java") ? Path.of(first) : null;
    }

    // --- project mode -----------------------------------------------------

    private int runProject(Path projectDir, List<String> appArgs)
            throws IOException, InterruptedException {
        BuildJk project = BuildJkParser.parse(projectDir.resolve("jk.toml"));
        if (project.project().main() == null) {
            System.err.println("jk run: no `main` class set in [project] for "
                    + projectDir + " — set `main = \"<fqcn>\"` or pass a script.");
            return 64;
        }
        String artifact = project.project().artifact();
        String version = project.project().version();
        Path target = projectDir.resolve("target");

        // Native binary wins if present.
        Path nativeBin = target.resolve(artifact);
        if (Files.isRegularFile(nativeBin) && Files.isExecutable(nativeBin)) {
            List<String> command = new ArrayList<>();
            command.add(nativeBin.toAbsolutePath().toString());
            command.addAll(appArgs);
            return new ProcessBuilder(command).inheritIO().start().waitFor();
        }

        // Build the jar if missing.
        Path jar = target.resolve(artifact + "-" + version + ".jar");
        if (!Files.exists(jar)) {
            int rc = Jk.execute("build", "-C", projectDir.toString());
            if (rc != 0) return rc;
        }
        if (!Files.exists(jar)) {
            System.err.println("jk run: expected jar at " + jar + " but build did not produce it.");
            return 70; // EX_SOFTWARE
        }

        List<Path> classpath = assembleRuntimeClasspath(projectDir, project, jar);

        Path java = CompileToolchain.runningJavaHome().resolve("bin").resolve(
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        StringBuilder cp = new StringBuilder();
        String sep = System.getProperty("path.separator");
        for (int i = 0; i < classpath.size(); i++) {
            if (i > 0) cp.append(sep);
            cp.append(classpath.get(i).toAbsolutePath());
        }
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-cp");
        command.add(cp.toString());
        command.add(project.project().main());
        command.addAll(appArgs);
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    private List<Path> assembleRuntimeClasspath(Path projectDir, BuildJk project, Path projectJar)
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
        Path jkHome = home != null
                ? home : Path.of(System.getProperty("user.home"), ".jk");
        Cas cas = new Cas(jkHome.resolve("cache"));
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

    // --- script mode ------------------------------------------------------

    private int runScript(Path script, List<String> scriptArgs)
            throws IOException, InterruptedException {
        if (!Files.isRegularFile(script)) {
            System.err.println("jk run: script not found: " + script);
            return 66; // EX_NOINPUT
        }
        byte[] bytes = Files.readAllBytes(script);
        String source = new String(bytes, StandardCharsets.UTF_8);
        ScriptHeader header = ScriptHeaderParser.parse(source);

        String hash = Hashing.sha256Hex(bytes);
        Path jkHome = home != null
                ? home : Path.of(System.getProperty("user.home"), ".jk");
        Path cacheDir = jkHome.resolve("cache");
        Path scriptCacheDir = jkHome.resolve("script-cache").resolve(hash);
        Path classesDir = scriptCacheDir.resolve("classes");
        Files.createDirectories(cacheDir);

        Cas cas = new Cas(cacheDir);
        Http http = new Http();
        RepoGroup repos = buildRepos(header, http, cas);

        List<Path> classpath = resolveClasspath(header.deps(), repos);

        if (forceRecompile || !Files.exists(classesDir.resolve(mainClassName(script) + ".class"))) {
            CompileResult result = compile(script, header, classesDir, classpath);
            if (!result.success()) {
                for (var d : result.diagnostics()) {
                    System.err.println(d.severity() + " "
                            + (d.source() != null ? d.source().getFileName() : "<unknown>")
                            + ":" + d.line() + ": " + d.message());
                }
                return 1;
            }
        }

        return execScript(script, header, classesDir, classpath, scriptArgs);
    }

    private RepoGroup buildRepos(ScriptHeader header, Http http, Cas cas) {
        List<MavenRepo> list = new ArrayList<>();
        if (repoUrl != null) {
            list.add(new MavenRepo("central", repoUrl, http, cas));
        } else {
            for (URI uri : header.repos()) {
                list.add(new MavenRepo("script-repo-" + list.size(), uri, http, cas));
            }
            if (list.isEmpty()) {
                list.add(new MavenRepo(RepositorySpec.MAVEN_CENTRAL.name(),
                        RepositorySpec.MAVEN_CENTRAL.url(), http, cas));
            }
        }
        return new RepoGroup(list);
    }

    private static List<Path> resolveClasspath(List<Dependency> deps, RepoGroup repos)
            throws IOException, InterruptedException {
        if (deps.isEmpty()) return List.of();
        Resolution resolution = new NaiveResolver(new EffectivePomBuilder(repos)).resolve(deps);
        // Preserve declaration order of roots first, then transitives.
        Set<String> ordered = new LinkedHashSet<>();
        for (Dependency d : deps) ordered.add(d.module());
        for (Resolution.ResolvedModule m : resolution.modules().values()) ordered.add(m.module());

        List<Path> jars = new ArrayList<>();
        for (String module : ordered) {
            Resolution.ResolvedModule m = resolution.modules().get(module);
            if (m == null) continue;
            int colon = m.module().indexOf(':');
            Coordinate coord = Coordinate.of(
                    m.module().substring(0, colon),
                    m.module().substring(colon + 1),
                    m.version());
            jars.add(repos.tryFetchArtifact(coord)
                    .orElseThrow(() -> new MavenRepo.ArtifactNotFoundException(
                            "jar not found in any declared repo: " + coord))
                    .fetched().cachePath());
        }
        return jars;
    }

    private CompileResult compile(Path script, ScriptHeader header,
                                  Path classesDir, List<Path> classpath) throws IOException {
        Files.createDirectories(classesDir);
        int release = header.release() != null
                ? header.release() : Runtime.version().feature();
        CompileRequest request = CompileRequest.builder()
                .sources(List.of(script.toAbsolutePath()))
                .classpath(classpath)
                .outputDir(classesDir)
                .release(release)
                .extraOptions(header.javacOptions())
                .javaHome(CompileToolchain.resolveJavaHome(script.toAbsolutePath().getParent()))
                .build();
        return new JavacDriver().compile(request);
    }

    private int execScript(Path script, ScriptHeader header, Path classesDir,
                           List<Path> classpath, List<String> scriptArgs)
            throws IOException, InterruptedException {
        Path java = CompileToolchain.runningJavaHome().resolve("bin").resolve(
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");

        StringBuilder cp = new StringBuilder(classesDir.toAbsolutePath().toString());
        String sep = System.getProperty("path.separator");
        for (Path jar : classpath) cp.append(sep).append(jar.toAbsolutePath());

        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.addAll(header.javaOptions());
        command.add("-cp");
        command.add(cp.toString());
        command.add(mainClassName(script));
        command.addAll(scriptArgs);

        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    private static String mainClassName(Path script) {
        String name = script.getFileName().toString();
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
    }
}
