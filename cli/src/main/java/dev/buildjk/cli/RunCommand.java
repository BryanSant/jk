// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.cache.Cas;
import dev.buildjk.compile.CompileRequest;
import dev.buildjk.compile.CompileResult;
import dev.buildjk.compile.JavacDriver;
import dev.buildjk.http.Http;
import dev.buildjk.model.Coordinate;
import dev.buildjk.model.Dependency;
import dev.buildjk.model.RepositorySpec;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code jk run <script.java> [-- <args>...]} — JBang-compat single-file
 * script runner (PRD §19).
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Hash the script's bytes for the cache key (SHA-256).</li>
 *   <li>Parse {@code //jk dep}/{@code //jk jdk}/{@code //jk repo} +
 *       JBang {@code //DEPS}/{@code //JAVA}/etc. directives.</li>
 *   <li>Resolve declared deps transitively via {@link NaiveResolver};
 *       fetch jars into the shared CAS.</li>
 *   <li>Compile the script via {@link JavacDriver} into
 *       {@code ~/.jk/script-cache/<hash>/classes/} — skipped if the cache
 *       already has the classes.</li>
 *   <li>Exec a child {@code java} with the compiled class + resolved
 *       classpath + caller-provided {@code -- args}.</li>
 * </ol>
 */
@Command(name = "run", description = "Run a single-file Java script")
public final class RunCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<script>",
            description = "Path to a .java script.")
    Path script;

    @Parameters(index = "1..*", paramLabel = "<args>",
            description = "Arguments forwarded to the script.")
    List<String> args = new ArrayList<>();

    @Option(names = "--home", hidden = true,
            description = "Override the jk home root. Default: ~/.jk.")
    Path home;

    @Option(names = "--repo-url", hidden = true,
            description = "Override the Maven repository URL (for tests).")
    URI repoUrl;

    @Option(names = "--force-recompile",
            description = "Ignore cached classes and recompile.")
    boolean forceRecompile;

    @Override
    public Integer call() throws IOException, InterruptedException {
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
            CompileResult result = compile(header, classesDir, classpath);
            if (!result.success()) {
                for (var d : result.diagnostics()) {
                    System.err.println(d.severity() + " "
                            + (d.source() != null ? d.source().getFileName() : "<unknown>")
                            + ":" + d.line() + ": " + d.message());
                }
                return 1;
            }
        }

        return exec(header, classesDir, classpath);
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

    private CompileResult compile(ScriptHeader header, Path classesDir, List<Path> classpath)
            throws IOException {
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

    private int exec(ScriptHeader header, Path classesDir, List<Path> classpath)
            throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin",
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
        command.addAll(args);

        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    private static String mainClassName(Path script) {
        String name = script.getFileName().toString();
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
    }
}
