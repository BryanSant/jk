// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.run.GoalConsole;
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
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.run.PhaseStatus;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code jk build} — compile sources, copy resources, package a jar at
 * {@code target/<artifact>-<version>.jar}.
 *
 * <p>Organised as a {@link Goal} with six phases:
 * <ol>
 *   <li>{@code parse-build} (SYNC) — load jk.toml + jk.lock, resolve
 *       classpath (incl. workspace siblings), gather sources, pick a
 *       profile + JDK.</li>
 *   <li>{@code compile-java} (CPU) — internally branches:
 *       freshness-stamp skip → action-cache hit → real javac. Real
 *       compiles run javac in a subprocess while a {@link
 *       dev.jkbuild.task.CasPrewriter} streams outputs into the CAS in
 *       parallel.</li>
 *   <li>{@code compile-kotlin} (CPU) — no-op when no {@code .kt}
 *       sources; otherwise kotlinc against classes/ + classpath.</li>
 *   <li>{@code copy-resources} (CPU) — mirror src/main/resources into
 *       classes/.</li>
 *   <li>{@code package-jar} (CPU) — assemble the final jar.</li>
 *   <li>{@code write-stamp} (SYNC) — refresh the FreshnessStamp inside
 *       classes/ unless we already short-circuited as up-to-date.</li>
 * </ol>
 */
@Command(name = "build", description = "Compile sources and package the project jar")
public final class BuildCommand implements Callable<Integer> {    @Option(names = "--profile", paramLabel = "<name>",
            description = "Build profile to apply. Default: auto (ci if CI=true, else none).")
    String profileName;

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
    Path cacheDir;

    @picocli.CommandLine.Mixin GlobalOptions global;

    // Keys phases share via the goal's typed state channel.
    private static final GoalKey<Lockfile> LOCKFILE = GoalKey.of("lockfile", Lockfile.class);
    private static final GoalKey<JkBuild> PROJECT = GoalKey.of("project", JkBuild.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> CLASSPATH = GoalKey.of("classpath", List.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> JAVA_SOURCES = GoalKey.of("java-sources", List.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> KOTLIN_SOURCES = GoalKey.of("kotlin-sources", List.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> JAVAC_ARGS = GoalKey.of("javac-args", List.class);
    private static final GoalKey<Path> JAVA_HOME = GoalKey.of("java-home", Path.class);
    private static final GoalKey<Integer> RELEASE = GoalKey.of("release", Integer.class);
    private static final GoalKey<String> ACTION_KEY = GoalKey.of("action-key", String.class);
    private static final GoalKey<String> BUILD_OUTCOME = GoalKey.of("build-outcome", String.class);
    private static final GoalKey<Path> JAR_PATH = GoalKey.of("jar-path", Path.class);

    @Override
    public Integer call() throws IOException {
        Path dir = global.workingDir();
        Path target = dir.resolve("target");
        Path classes = target.resolve("classes");
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Cas cas = new Cas(cache);
        ActionCache actionCache = new ActionCache(cas, cache.resolve("actions"));

        // Bail with the existing error shape for "no jk.toml" / "no jk.lock"
        // BEFORE we even build the goal — these aren't phase failures,
        // they're "you ran me in the wrong directory" misconfigurations
        // that don't need an event log trail.
        Path buildFile = dir.resolve("jk.toml");
        if (!Files.exists(buildFile)) {
            System.err.println("jk build: no jk.toml in " + dir);
            return 2;
        }
        Path lockFile = resolveLockFile(dir);
        if (lockFile == null) {
            System.err.println("jk build: no jk.lock in " + dir + " (run `jk lock` first)");
            return 2;
        }
        Path lockFileFinal = lockFile;

        Phase parseBuild = Phase.builder("parse-build")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml / jk.lock");
                    JkBuild project;
                    try {
                        project = JkBuildParser.parse(buildFile);
                    } catch (RuntimeException e) {
                        ctx.error("toml", e.getMessage());
                        throw e;
                    }
                    ctx.put(PROJECT, project);
                    ctx.put(LOCKFILE, LockfileReader.read(lockFileFinal));

                    ctx.label("resolve classpath");
                    Path srcMain = dir.resolve("src/main/java");
                    List<Path> sources = CompileCommand.collectJavaSources(srcMain);
                    List<Path> classpath = new ArrayList<>(new ClasspathResolver(cas)
                            .classpathFor(ctx.require(LOCKFILE), ClasspathResolver.COMPILE_MAIN));
                    WorkspaceClasspath.Result siblings =
                            WorkspaceClasspath.resolve(dir, project, Set.of(Scope.MAIN));
                    classpath.addAll(siblings.jars());
                    if (!siblings.missingSiblingJars().isEmpty()) {
                        for (String missing : siblings.missingSiblingJars()) {
                            ctx.error("workspace", "sibling not built — " + missing);
                        }
                        throw new RuntimeException("missing workspace siblings");
                    }

                    Profile profile =
                            CompileCommand.resolveProfile(project.profiles(), profileName);
                    ctx.put(JAVAC_ARGS, profile == null ? List.of() : profile.javacArgs());
                    ctx.put(CLASSPATH, classpath);
                    ctx.put(JAVA_SOURCES, sources);
                    ctx.put(KOTLIN_SOURCES, CompileCommand.collectKotlinSources(dir));
                    ctx.put(RELEASE, project.project().javaRelease());
                    ctx.put(JAVA_HOME, CompileToolchain.resolveJavaHome(dir));
                    ctx.progress(1);
                })
                .build();

        Phase compileJava = Phase.builder("compile-java")
                .kind(PhaseKind.CPU)
                .requires("parse-build")
                // Initial scope unknown — parse-build hasn't run when scope
                // suppliers fire. The phase calls updateScope once it
                // knows the source count.
                .scope(0)
                .execute(ctx -> {
                    List<Path> sources = javaSources(ctx);
                    if (sources.isEmpty()) {
                        ctx.label("no Java sources");
                        Files.createDirectories(classes);
                        ctx.put(BUILD_OUTCOME, "no-sources");
                        return;
                    }
                    ctx.updateScope(sources.size());
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);

                    // Layer 1: maven-style mtime skip.
                    if (dev.jkbuild.task.FreshnessStamp.isFresh(classes, sources, classpath)) {
                        ctx.label("up to date");
                        ctx.put(BUILD_OUTCOME, "up-to-date");
                        ctx.progress(sources.size());
                        return;
                    }

                    @SuppressWarnings("unchecked")
                    List<String> javacArgs = (List<String>) ctx.require(JAVAC_ARGS);
                    CompileRequest request = CompileRequest.builder()
                            .sources(sources)
                            .classpath(classpath)
                            .outputDir(classes)
                            .release(ctx.require(RELEASE))
                            .extraOptions(javacArgs)
                            .javaHome(ctx.require(JAVA_HOME))
                            .build();

                    String taskId = "compile-main";
                    String actionKey = ActionKey.forJavac(taskId, request, Jk.VERSION);
                    ctx.put(ACTION_KEY, actionKey);

                    // Layer 2: action cache lookup.
                    var cached = actionCache.lookup(actionKey);
                    if (cached.isPresent()) {
                        ctx.label("cache hit " + actionKey.substring(0, 8));
                        actionCache.restore(cached.get(), classes);
                        ctx.put(BUILD_OUTCOME, "cache-hit:" + actionKey.substring(0, 8));
                        ctx.progress(sources.size());
                        return;
                    }

                    // Layer 3: real compile with CasPrewriter streaming
                    // .class files into the CAS in parallel.
                    ctx.label("compiling " + sources.size() + " sources");
                    var prewriter = dev.jkbuild.task.CasPrewriter.watching(cas, classes);
                    CompileResult result;
                    Map<String, String> precomputedOutputs;
                    try {
                        result = new JavacDriver().compile(request);
                    } finally {
                        precomputedOutputs = prewriter.finish();
                    }
                    for (CompileResult.Diagnostic d : result.diagnostics()) {
                        // Surface each diagnostic both to the event log
                        // (structured) and stderr (the existing CLI shape).
                        ctx.error("javac", d.render());
                    }
                    if (!result.success() || result.hasErrors()) {
                        throw new RuntimeException("javac reported errors");
                    }
                    actionCache.storeWithOutputs(taskId, actionKey,
                            ActionKey.snapshotInputs(request), precomputedOutputs);
                    ctx.put(BUILD_OUTCOME, "compiled");
                    ctx.progress(sources.size());
                })
                .build();

        Phase compileKotlin = Phase.builder("compile-kotlin")
                .kind(PhaseKind.CPU)
                .requires("compile-java")
                .scope(0)
                .execute(ctx -> {
                    List<Path> ktSources = kotlinSources(ctx);
                    if (ktSources.isEmpty()) {
                        ctx.label("no Kotlin sources");
                        return;
                    }
                    ctx.updateScope(ktSources.size());
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    List<Path> kotlincCp = new ArrayList<>(classpath);
                    kotlincCp.add(classes);
                    ctx.label("compiling " + ktSources.size() + " Kotlin sources");
                    Path kotlinHome = CompileToolchain.resolveKotlinHome(cache);
                    KotlincResult ktResult = new KotlincDriver().compile(
                            KotlincRequest.builder()
                                    .sources(ktSources)
                                    .classpath(kotlincCp)
                                    .outputDir(classes)
                                    .jvmTarget(CompileCommand.kotlinJvmTarget(ctx.require(RELEASE)))
                                    .kotlinHome(kotlinHome)
                                    .build());
                    if (!ktResult.success()) {
                        ctx.error("kotlinc", ktResult.output());
                        throw new RuntimeException("kotlinc reported errors");
                    }
                    ctx.progress(ktSources.size());
                })
                .build();

        Phase copyResources = Phase.builder("copy-resources")
                .kind(PhaseKind.CPU)
                .requires("compile-kotlin")
                .scope(1)
                .execute(ctx -> {
                    Path resMain = dir.resolve("src/main/resources");
                    if (!Files.exists(resMain)) {
                        ctx.label("no resources");
                        return;
                    }
                    ctx.label("copy resources");
                    copyResources(resMain, classes);
                    ctx.progress(1);
                })
                .build();

        Phase packageJar = Phase.builder("package-jar")
                .kind(PhaseKind.CPU)
                .requires("copy-resources")
                .scope(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    Path jarPath = target.resolve(
                            project.project().artifact() + "-"
                                    + project.project().version() + ".jar");
                    ctx.label("package " + jarPath.getFileName());
                    new JarPackager().packageJar(JarPackager.JarRequest.of(classes, jarPath));
                    ctx.put(JAR_PATH, jarPath);
                    ctx.progress(1);
                })
                .build();

        Phase writeStamp = Phase.builder("write-stamp")
                .requires("compile-java")
                .scope(1)
                .execute(ctx -> {
                    String outcome = ctx.get(BUILD_OUTCOME).orElse("");
                    // Don't touch the stamp on the up-to-date / no-sources
                    // paths — the existing stamp is still valid; rewriting
                    // it would just bump mtimes for no reason.
                    if ("up-to-date".equals(outcome) || "no-sources".equals(outcome)) {
                        ctx.label("stamp unchanged");
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("write freshness stamp");
                    @SuppressWarnings("unchecked")
                    List<Path> sources = (List<Path>) ctx.require(JAVA_SOURCES);
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    String actionKey = ctx.get(ACTION_KEY).orElse("");
                    dev.jkbuild.task.FreshnessStamp.write(classes, "compile-main",
                            actionKey, sources, classpath);
                    ctx.progress(1);
                })
                .build();

        Goal goal = Goal.builder("build")
                .addPhase(parseBuild)
                .addPhase(compileJava)
                .addPhase(compileKotlin)
                .addPhase(copyResources)
                .addPhase(packageJar)
                .addPhase(writeStamp)
                .build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);

        if (result.success()) {
            printSuccessSummary(goal);
            // Opportunistic cache prune (no-op when auto-prune is off).
            try {
                var cacheConfig = dev.jkbuild.config.JkCacheConfig.fromToml(
                        dir.resolve("jk.toml"));
                dev.jkbuild.task.CachePruneScheduler.resolveJkExe().ifPresent(exe ->
                        dev.jkbuild.task.CachePruneScheduler.maybeRun(
                                cacheConfig, cache, exe));
            } catch (IOException ignored) {
                // Cache hygiene is never load-bearing.
            }
            return 0;
        }
        printFailureSummary(result, cache);
        return 1;
    }

    /**
     * Walk up the workspace looking for a jk.lock — same fallback the
     * old monolithic command did, kept outside the goal because "no
     * lockfile anywhere" is a misuse error, not a phase failure.
     */
    private static Path resolveLockFile(Path dir) throws IOException {
        Path local = dir.resolve("jk.lock");
        if (Files.exists(local)) return local;
        var workspaceRoot = WorkspaceLocator.findRoot(dir);
        if (workspaceRoot.isPresent()) {
            Path candidate = workspaceRoot.get().resolve("jk.lock");
            if (Files.exists(candidate)) return candidate;
        }
        return null;
    }

    private void printSuccessSummary(Goal goal) {
        // Match the existing CLI shape for grep-ability:
        //   Up to date: compile-main
        //   Cache hit: compile-main (12345678)
        //   Built /path/widget-0.1.0.jar (N sources)
        String outcome = goal.get(BUILD_OUTCOME).orElse("");
        if ("up-to-date".equals(outcome)) {
            System.out.println("Up to date: compile-main");
        } else if (outcome.startsWith("cache-hit:")) {
            System.out.println("Cache hit: compile-main (" + outcome.substring("cache-hit:".length()) + ")");
        }

        goal.get(JAR_PATH).ifPresent(jar -> {
            @SuppressWarnings("unchecked")
            int n = ((List<Path>) goal.get(JAVA_SOURCES).orElse(List.of())).size()
                    + ((List<Path>) goal.get(KOTLIN_SOURCES).orElse(List.of())).size();
            System.out.println("Built " + jar + " (" + n + " source"
                    + (n == 1 ? "" : "s") + ")");
        });
    }

    private void printFailureSummary(GoalResult result, Path cache) {
        String failedPhase = result.phases().stream()
                .filter(p -> p.status() == PhaseStatus.FAIL)
                .map(GoalResult.PhaseReport::name)
                .findFirst().orElse("?");
        System.err.println("jk build failed: " + failedPhase);
        if (!result.errors().isEmpty()) {
            // Compile errors from javac come through here verbatim — preserve
            // their original formatting (file:line: message) by printing
            // each on its own line.
            for (GoalResult.Diagnostic d : result.errors()) {
                System.err.println("  " + d.message());
            }
        }
        System.err.println("Run log: " + cache.resolve("runs"));
    }

    @SuppressWarnings("unchecked")
    private static List<Path> javaSources(dev.jkbuild.run.PhaseContext ctx) {
        return (List<Path>) ctx.get(JAVA_SOURCES).orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Path> kotlinSources(dev.jkbuild.run.PhaseContext ctx) {
        return (List<Path>) ctx.get(KOTLIN_SOURCES).orElse(List.of());
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
