// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.cache.Cas;
import dev.buildjk.compile.ClasspathResolver;
import dev.buildjk.compile.CompileRequest;
import dev.buildjk.compile.CompileResult;
import dev.buildjk.compile.JarPackager;
import dev.buildjk.compile.JavacDriver;
import dev.buildjk.compile.KotlincDriver;
import dev.buildjk.compile.KotlincRequest;
import dev.buildjk.compile.KotlincResult;
import dev.buildjk.config.BuildJkParser;
import dev.buildjk.config.WorkspaceClasspath;
import dev.buildjk.config.WorkspaceLocator;
import dev.buildjk.lock.Lockfile;
import dev.buildjk.lock.LockfileReader;
import dev.buildjk.model.BuildJk;
import dev.buildjk.model.Profile;
import dev.buildjk.model.Scope;
import dev.buildjk.task.ActionCache;
import dev.buildjk.task.ActionKey;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code jk build} — compile sources, copy resources, package a jar at
 * {@code target/&lt;artifact&gt;-&lt;version&gt;.jar}.
 *
 * <p>v0.2 first iteration: full recompile every run. Incremental
 * compilation and the action cache land in slice C.
 */
@Command(name = "build", description = "Compile sources and package the project jar")
public final class BuildCommand implements Callable<Integer> {

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Option(names = "--profile", paramLabel = "<name>",
            description = "Build profile to apply. Default: auto (ci if CI=true, else none).")
    String profileName;

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the CAS cache directory. Default: ~/.jk/cache.")
    Path cacheDir;

    @Override
    public Integer call() throws IOException {
        Path dir = directory != null ? directory : Path.of(".").toAbsolutePath().normalize();
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(buildFile)) {
            System.err.println("jk build: no build.jk in " + dir);
            return 2;
        }
        if (!Files.exists(lockFile)) {
            // Workspace member: fall back to the root's jk.lock (PRD §13).
            var workspaceRoot = WorkspaceLocator.findRoot(dir);
            if (workspaceRoot.isPresent()) {
                Path candidate = workspaceRoot.get().resolve("jk.lock");
                if (Files.exists(candidate)) {
                    lockFile = candidate;
                }
            }
        }
        if (!Files.exists(lockFile)) {
            System.err.println("jk build: no jk.lock in " + dir + " (run `jk lock` first)");
            return 2;
        }

        BuildJk project;
        try {
            project = BuildJkParser.parse(buildFile);
        } catch (RuntimeException e) {
            System.err.println("jk build: " + e.getMessage());
            return 2;
        }
        Lockfile lock = LockfileReader.read(lockFile);

        Path srcMain = dir.resolve("src/main/java");
        Path resMain = dir.resolve("src/main/resources");
        Path target = dir.resolve("target");
        Path classes = target.resolve("classes");

        List<Path> sources = CompileCommand.collectJavaSources(srcMain);
        Path cache = cacheDir != null
                ? cacheDir
                : Path.of(System.getProperty("user.home"), ".jk", "cache");
        Cas cas = new Cas(cache);
        List<Path> classpath = new ArrayList<>(new ClasspathResolver(cas)
                .classpathFor(lock, ClasspathResolver.COMPILE_MAIN));
        WorkspaceClasspath.Result siblings = WorkspaceClasspath.resolve(dir, project,
                Set.of(Scope.MAIN));
        classpath.addAll(siblings.jars());
        if (!siblings.missingSiblingJars().isEmpty()) {
            for (String missing : siblings.missingSiblingJars()) {
                System.err.println("jk build: workspace sibling not built — " + missing);
            }
            return 2;
        }
        int release = CompileCommand.parseReleaseFromJdk(project.project().jdk());

        Profile profile = CompileCommand.resolveProfile(project.profiles(), profileName);
        List<String> javacArgs = profile == null ? List.of() : profile.javacArgs();

        List<Path> ktSources = CompileCommand.collectKotlinSources(dir);

        Path javaHome = CompileToolchain.resolveJavaHome(dir);

        if (!sources.isEmpty()) {
            CompileRequest request = CompileRequest.builder()
                    .sources(sources)
                    .classpath(classpath)
                    .outputDir(classes)
                    .release(release)
                    .extraOptions(javacArgs)
                    .javaHome(javaHome)
                    .build();
            String taskId = "compile-main";
            String actionKey = ActionKey.forJavac(taskId, request, Jk.VERSION);
            ActionCache actionCache = new ActionCache(cas, cache.resolve("actions"));

            var cached = actionCache.lookup(actionKey);
            if (cached.isPresent()) {
                actionCache.restore(cached.get(), classes);
                System.out.println("Cache hit: " + taskId + " (" + actionKey.substring(0, 8) + ")");
            } else {
                CompileResult result = new JavacDriver().compile(request);
                for (CompileResult.Diagnostic d : result.diagnostics()) {
                    System.err.println(d.render());
                }
                if (!result.success() || result.hasErrors()) {
                    return 1;
                }
                actionCache.store(taskId, actionKey, ActionKey.snapshotInputs(request), classes);
            }
        } else {
            Files.createDirectories(classes);
        }

        // Kotlin: second pass. Caching for the Kotlin step lands in a follow-up;
        // for now kotlinc runs every build when .kt sources exist.
        if (!ktSources.isEmpty()) {
            List<Path> kotlincCp = new ArrayList<>(classpath);
            kotlincCp.add(classes);
            Path kotlinHome = CompileToolchain.resolveKotlinHome(cache);
            KotlincResult ktResult = new KotlincDriver().compile(
                    KotlincRequest.builder()
                            .sources(ktSources)
                            .classpath(kotlincCp)
                            .outputDir(classes)
                            .jvmTarget(CompileCommand.kotlinJvmTarget(release))
                            .kotlinHome(kotlinHome)
                            .build());
            if (!ktResult.success()) {
                System.err.print(ktResult.output());
                return 1;
            }
        }

        copyResources(resMain, classes);

        Path jarPath = target.resolve(
                project.project().artifact() + "-" + project.project().version() + ".jar");
        new JarPackager().packageJar(JarPackager.JarRequest.of(classes, jarPath));

        int totalSources = sources.size() + ktSources.size();
        System.out.println("Built " + jarPath + " (" + totalSources + " source"
                + (totalSources == 1 ? "" : "s") + ")");
        return 0;
    }

    private static void copyResources(Path resourceDir, Path classes) throws IOException {
        if (!Files.exists(resourceDir)) return;
        try (Stream<Path> stream = Files.walk(resourceDir)) {
            for (Path source : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(source)) continue;
                Path relative = resourceDir.relativize(source);
                Path target = classes.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
