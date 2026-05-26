// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.compile.CompileRequest;
import dev.jkbuild.compile.CompileResult;
import dev.jkbuild.compile.JarPackager;
import dev.jkbuild.compile.JavacDriver;
import dev.jkbuild.compile.KotlincDriver;
import dev.jkbuild.compile.KotlincRequest;
import dev.jkbuild.compile.KotlincResult;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceClasspath;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Profile;
import dev.jkbuild.model.Scope;
import dev.jkbuild.task.ActionCache;
import dev.jkbuild.task.ActionKey;
import dev.jkbuild.util.JkDirs;
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
public final class BuildCommand implements Callable<Integer> {    @Option(names = "--profile", paramLabel = "<name>",
            description = "Build profile to apply. Default: auto (ci if CI=true, else none).")
    String profileName;

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
    Path cacheDir;

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException {
        Path dir = global.workingDir();
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(buildFile)) {
            System.err.println("jk build: no jk.toml in " + dir);
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

        JkBuild project;
        try {
            project = JkBuildParser.parse(buildFile);
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
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
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
        int release = project.project().javaRelease();

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
            ActionCache actionCache = new ActionCache(cas, cache.resolve("actions"));

            // Layer 1: maven-style mtime check. Cheap stat-per-input — if
            // nothing has moved since the last stamp, skip the action-key
            // hash entirely.
            if (dev.jkbuild.task.FreshnessStamp.isFresh(classes, sources, classpath)) {
                System.out.println("Up to date: " + taskId);
            } else {
                // Layer 2: action cache lookup. Hashes content; survives
                // mtime jiggles and works across machines via shared CAS.
                String actionKey = ActionKey.forJavac(taskId, request, Jk.VERSION);
                var cached = actionCache.lookup(actionKey);
                if (cached.isPresent()) {
                    actionCache.restore(cached.get(), classes);
                    System.out.println("Cache hit: " + taskId + " (" + actionKey.substring(0, 8) + ")");
                } else {
                    // Layer 3: full compile, with CAS prewriting in the
                    // background. The prewriter watches `classes/` while
                    // javac runs and hard-links each settled .class into
                    // the CAS as it appears; by the time javac exits,
                    // most outputs are already cached.
                    var prewriter = dev.jkbuild.task.CasPrewriter.watching(cas, classes);
                    CompileResult result;
                    java.util.Map<String, String> precomputedOutputs;
                    try {
                        result = new JavacDriver().compile(request);
                    } finally {
                        // finish() always runs — stops the poller and
                        // performs the final correctness sweep.
                        precomputedOutputs = prewriter.finish();
                    }
                    for (CompileResult.Diagnostic d : result.diagnostics()) {
                        System.err.println(d.render());
                    }
                    if (!result.success() || result.hasErrors()) {
                        return 1;
                    }
                    actionCache.storeWithOutputs(taskId, actionKey,
                            ActionKey.snapshotInputs(request), precomputedOutputs);
                }
                // Stamp lives inside classes/, so wiping the output tree
                // automatically invalidates the stamp.
                dev.jkbuild.task.FreshnessStamp.write(classes, taskId, actionKey,
                        sources, classpath);
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

        // Opportunistic cache prune (no-op when [cache].auto-prune is off).
        try {
            var cacheConfig = dev.jkbuild.config.JkCacheConfig.fromToml(dir.resolve("jk.toml"));
            dev.jkbuild.task.CachePruneScheduler.resolveJkExe().ifPresent(exe ->
                    dev.jkbuild.task.CachePruneScheduler.maybeRun(cacheConfig, cache, exe));
        } catch (IOException ignored) {
            // Cache hygiene is never load-bearing.
        }
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
