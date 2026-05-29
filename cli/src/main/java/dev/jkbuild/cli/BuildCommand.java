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
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.http.Http;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Profile;
import dev.jkbuild.model.Scope;
import dev.jkbuild.resolver.CacheSync;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.run.PhaseStatus;
import dev.jkbuild.task.ActionCache;
import dev.jkbuild.task.ActionKey;
import dev.jkbuild.test.JUnitLauncher;
import dev.jkbuild.test.TestProgressListener;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code jk build} — smart meta-goal that orchestrates the full pipeline:
 *
 * <ol>
 *   <li>{@code parse-build} — load {@code jk.toml}; if {@code jk.lock} is
 *       absent, run the lock resolver inline (same as {@code jk lock}).</li>
 *   <li>{@code sync-deps} (IO) — ensure all locked artifacts are in the CAS;
 *       virtually a no-op when everything is already cached.</li>
 *   <li>{@code ensure-jdk} (IO, parallel with sync-deps) — install the
 *       pinned JDK when it is not yet on disk.</li>
 *   <li>{@code compile-java} (CPU) — javac, with action-cache + freshness
 *       stamp skip layers.</li>
 *   <li>{@code compile-kotlin} (CPU) — no-op when no {@code .kt} sources.</li>
 *   <li>{@code copy-resources} (CPU) — mirror {@code src/main/resources}.</li>
 *   <li>{@code compile-test} (CPU) — compile {@code src/test/java}.</li>
 *   <li>{@code run-tests} (IO) — fork JUnit Platform runner(s).</li>
 *   <li>{@code package-jar} (CPU) — assemble the project jar.</li>
 *   <li>{@code native-image} (IO, only when {@code native = "always"}) —
 *       GraalVM native-image compilation.</li>
 *   <li>{@code write-stamp} (SYNC) — refresh the freshness stamp.</li>
 * </ol>
 */
@Command(name = "build", description = "Compile, test, and package the project")
public final class BuildCommand implements Callable<Integer> {

    @Option(names = "--profile", paramLabel = "<name>",
            description = "Build profile to apply. Default: auto (ci if CI=true, else none).")
    String profileName;

    @Option(names = {"-w", "--workers"}, paramLabel = "<N>",
            description = "Number of test-runner JVMs to fork in parallel. Default 1.")
    Integer workers;

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the jk cache directory.")
    Path cacheDir;

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root.")
    Path jdksDir;

    @picocli.CommandLine.Mixin GlobalOptions global;

    // ---- GoalKeys -------------------------------------------------------

    private static final GoalKey<JkBuild>   PROJECT         = GoalKey.of("project",       JkBuild.class);
    private static final GoalKey<Lockfile>  LOCKFILE        = GoalKey.of("lockfile",       Lockfile.class);
    private static final GoalKey<Path>      JAVA_HOME       = GoalKey.of("java-home",      Path.class);
    private static final GoalKey<Integer>   RELEASE         = GoalKey.of("release",        Integer.class);

    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> CLASSPATH       = GoalKey.of("classpath",      List.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> JAVA_SOURCES    = GoalKey.of("java-sources",   List.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> KOTLIN_SOURCES  = GoalKey.of("kotlin-sources", List.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> JAVAC_ARGS      = GoalKey.of("javac-args",     List.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> COMPILE_TEST_CP = GoalKey.of("cp-test",        List.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> TEST_RUNTIME_CP = GoalKey.of("cp-runtime",     List.class);

    private static final GoalKey<String>    ACTION_KEY      = GoalKey.of("action-key",     String.class);
    private static final GoalKey<String>    BUILD_OUTCOME   = GoalKey.of("build-outcome",  String.class);
    private static final GoalKey<Path>      JAR_PATH        = GoalKey.of("jar-path",       Path.class);
    private static final GoalKey<Path>      MAIN_CLASSES    = GoalKey.of("main-classes",   Path.class);
    private static final GoalKey<Path>      TEST_CLASSES    = GoalKey.of("test-classes",   Path.class);
    private static final GoalKey<BuildLayout> LAYOUT        = GoalKey.of("layout",         BuildLayout.class);
    private static final GoalKey<JUnitLauncher.Result> TEST_RESULT =
            GoalKey.of("test-result", JUnitLauncher.Result.class);
    private static final GoalKey<Boolean>   NO_TEST_SOURCES = GoalKey.of("no-test-sources", Boolean.class);

    // ---- Entry point ----------------------------------------------------

    @Override
    public Integer call() throws Exception {
        Path startDir = global.workingDir();
        Path buildFile = startDir.resolve("jk.toml");
        if (!Files.exists(buildFile)) {
            System.err.println("jk build: no jk.toml in " + startDir);
            return 2;
        }
        // Peek at the manifest before committing to a per-dir build. A
        // workspace root dispatches to runWorkspaceBuild, which iterates
        // members in topological order and calls runForDir per member.
        JkBuild peek;
        try {
            peek = JkBuildParser.parse(buildFile);
        } catch (RuntimeException e) {
            System.err.println("jk build: " + e.getMessage());
            return 2;
        }
        if (peek.isWorkspaceRoot()) {
            return runWorkspaceBuild(startDir, peek);
        }
        return runForDir(startDir);
    }

    /**
     * Build every member of the workspace whose root is {@code workspaceRoot}.
     * Members compile in topological order computed from each member's
     * inter-sibling deps (a sibling listed as a regular Maven coord whose
     * group+artifact match another member's {@code [project]}). Each
     * member's jar lands at {@code <workspaceRoot>/target/} per the
     * {@link BuildLayout} contract.
     *
     * <p>If the root manifest also declares its own {@code [project]} with
     * source files, that build is skipped — the workspace root is
     * coordinator-only here. (We may revisit this once virtual workspaces
     * land; for now the assumption matches every multi-module JVM project
     * we've seen.)
     */
    private int runWorkspaceBuild(Path workspaceRoot, JkBuild root) throws Exception {
        Map<Path, JkBuild> membersByDir;
        try {
            membersByDir = WorkspaceLoader.loadMembers(workspaceRoot, root);
        } catch (RuntimeException e) {
            System.err.println("jk build: " + e.getMessage());
            return 2;
        }
        if (membersByDir.isEmpty()) {
            System.out.println("(workspace declares no members)");
            return 0;
        }
        List<Path> sorted = topoSortMembers(membersByDir);
        for (int i = 0; i < sorted.size(); i++) {
            Path memberDir = sorted.get(i);
            System.out.println();
            System.out.println("══ " + workspaceRoot.relativize(memberDir)
                    + " (" + (i + 1) + "/" + sorted.size() + ") ══");
            int exit = runForDir(memberDir);
            if (exit != 0) {
                System.err.println("jk build: " + workspaceRoot.relativize(memberDir)
                        + " failed (exit " + exit + ")");
                return exit;
            }
        }
        return 0;
    }

    /**
     * Order workspace members so each builds after its sibling deps.
     * Kahn's algorithm against the in-workspace dep graph. Sibling
     * matches are by full Maven coord ({@code group:artifact}) — members
     * declare sibling deps explicitly with inline coords, no
     * {@code .workspace = true} shorthand needed.
     *
     * <p>Cycles (which the workspace's
     * {@link dev.jkbuild.config.WorkspaceLoader} doesn't currently
     * detect) result in any unsorted members being appended in
     * declaration order so the build still attempts to make progress.
     */
    private static List<Path> topoSortMembers(Map<Path, JkBuild> membersByDir) {
        Map<String, Path> dirByCoord = new HashMap<>();
        for (var e : membersByDir.entrySet()) {
            String coord = e.getValue().project().group()
                    + ":" + e.getValue().project().artifact();
            dirByCoord.put(coord, e.getKey());
        }
        Map<Path, Set<Path>> requires = new LinkedHashMap<>();
        for (var e : membersByDir.entrySet()) {
            Set<Path> prereqs = new LinkedHashSet<>();
            for (Scope scope : Scope.values()) {
                for (Dependency d : e.getValue().dependencies().of(scope)) {
                    Path depDir = dirByCoord.get(d.module());
                    if (depDir != null && !depDir.equals(e.getKey())) {
                        prereqs.add(depDir);
                    }
                }
            }
            requires.put(e.getKey(), prereqs);
        }
        Map<Path, Integer> remainingPrereqs = new HashMap<>();
        for (var e : requires.entrySet()) {
            remainingPrereqs.put(e.getKey(), e.getValue().size());
        }
        java.util.Deque<Path> queue = new java.util.ArrayDeque<>();
        for (var e : remainingPrereqs.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }
        List<Path> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            Path next = queue.removeFirst();
            sorted.add(next);
            for (var e : requires.entrySet()) {
                if (e.getValue().contains(next)) {
                    int rem = remainingPrereqs.merge(e.getKey(), -1, Integer::sum);
                    if (rem == 0) queue.add(e.getKey());
                }
            }
        }
        if (sorted.size() != membersByDir.size()) {
            // Cycle. Fall back to declaration order for the stragglers
            // so the build still tries to make progress.
            for (Path p : membersByDir.keySet()) {
                if (!sorted.contains(p)) sorted.add(p);
            }
        }
        return sorted;
    }

    private int runForDir(Path dir) throws Exception {
        Path cache  = cacheDir != null ? cacheDir : JkDirs.cache();
        Cas cas     = new Cas(cache);
        ActionCache actionCache = new ActionCache(cas, cache.resolve("actions"));
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile  = dir.resolve("jk.lock");
        int workerCount = workers != null && workers > 0 ? workers : 1;

        if (!Files.exists(buildFile)) {
            System.err.println("jk build: no jk.toml in " + dir);
            return 2;
        }

        // ---- parse-build ------------------------------------------------
        // Enhanced: if jk.lock is absent it runs the lock resolver inline
        // before reading the lockfile — same path SyncCommand takes.
        Phase parseBuild = Phase.builder("parse-build")
                .label("Parsing")
                .scope(() -> {
                    // Cheap scope estimate; see LockCommand for rationale.
                    if (Files.exists(lockFile)) {
                        try { return LockfileReader.read(lockFile).packages().size() + 5; }
                        catch (Exception ignored) {}
                    }
                    return 10;
                })
                .execute(ctx -> {
                    ctx.label("parse jk.toml");
                    JkBuild project;
                    try {
                        project = JkBuildParser.parse(buildFile);
                    } catch (RuntimeException e) {
                        ctx.error("toml", e.getMessage());
                        throw e;
                    }
                    ctx.put(PROJECT, project);
                    BuildLayout layout = BuildLayout.of(dir, project);
                    ctx.put(LAYOUT, layout);

                    // If no lockfile, resolve now.
                    if (!Files.exists(lockFile)) {
                        ctx.label("resolve deps (first run)");
                        var result = LockFlow.run(
                                dir, cache, List.of(), true, null, "jk build");
                        if (result.status() != 0) {
                            ctx.error("lock", "dependency resolution failed");
                            throw new RuntimeException("lock failed");
                        }
                        ctx.put(LOCKFILE, result.lockfile());
                    } else {
                        ctx.put(LOCKFILE, LockfileReader.read(lockFile));
                    }

                    Lockfile lock = ctx.require(LOCKFILE);
                    ctx.label("resolve classpath");
                    ClasspathResolver resolver = new ClasspathResolver(cas);

                    List<Path> mainCp = new ArrayList<>(
                            resolver.classpathFor(lock, ClasspathResolver.COMPILE_MAIN));
                    WorkspaceClasspath.Result mainSiblings =
                            WorkspaceClasspath.resolve(dir, project, Set.of(Scope.MAIN));
                    mainCp.addAll(mainSiblings.jars());
                    if (!mainSiblings.missingSiblingJars().isEmpty()) {
                        for (String missing : mainSiblings.missingSiblingJars())
                            ctx.error("workspace", "sibling not built — " + missing);
                        throw new RuntimeException("missing workspace siblings");
                    }

                    Profile profile = CompileCommand.resolveProfile(project.profiles(), profileName);
                    ctx.put(JAVAC_ARGS, profile == null ? List.of() : profile.javacArgs());
                    ctx.put(CLASSPATH, mainCp);

                    // Tests link against MAIN siblings (a member's tests use its
                    // main-scope deps) plus any TEST-only siblings the member
                    // declared. Resolve both, then merge into the test
                    // classpaths read from the lockfile.
                    WorkspaceClasspath.Result testSiblings =
                            WorkspaceClasspath.resolve(dir, project, Set.of(Scope.MAIN, Scope.TEST));
                    List<Path> compileTestCp = new ArrayList<>(
                            resolver.classpathFor(lock, ClasspathResolver.COMPILE_TEST));
                    compileTestCp.addAll(testSiblings.jars());
                    List<Path> testRuntimeCp = new ArrayList<>(
                            resolver.classpathFor(lock, ClasspathResolver.TEST));
                    testRuntimeCp.addAll(testSiblings.jars());
                    ctx.put(COMPILE_TEST_CP, compileTestCp);
                    ctx.put(TEST_RUNTIME_CP, testRuntimeCp);
                    ctx.put(JAVA_SOURCES, CompileCommand.collectJavaSources(dir.resolve("src/main/java")));
                    ctx.put(KOTLIN_SOURCES, CompileCommand.collectKotlinSources(dir));
                    ctx.put(RELEASE, project.project().javaRelease());
                    ctx.put(JAVA_HOME, CompileToolchain.resolveJavaHome(dir));
                    ctx.put(MAIN_CLASSES, layout.classesDir());
                    ctx.put(TEST_CLASSES, layout.testClassesDir());
                    ctx.progress(1);
                })
                .build();

        // ---- sync-deps --------------------------------------------------
        Phase syncDeps = Phase.builder("sync-deps")
                .label("Syncing")
                .kind(PhaseKind.IO)
                .requires("parse-build")
                .scope(() -> {
                    try { return LockfileReader.read(lockFile).packages().size(); }
                    catch (Exception ignored) { return 10; }
                })
                .execute(ctx -> {
                    Lockfile lock = ctx.require(LOCKFILE);
                    int packages = lock.packages().size();
                    if (packages > 0) ctx.updateScope(packages);
                    var observer = new CacheSync.ProgressObserver() {
                        @Override public void fetched(Lockfile.Package pkg) {
                            ctx.label("fetched " + pkg.name());
                            ctx.progress(1);
                        }
                        @Override public void upToDate(Lockfile.Package pkg) { ctx.progress(1); }
                        @Override public void skipped(Lockfile.Package pkg)  { ctx.progress(1); }
                        @Override public void failed(Lockfile.Package pkg, String err) {
                            ctx.error("dep", pkg.name() + " — " + err);
                            ctx.progress(1);
                        }
                    };
                    boolean noCache = dev.jkbuild.config.ActiveConfig.get().noCacheOr(false);
                    var report = new CacheSync(cas, new Http()).sync(lock, observer, noCache);
                    if (report.hasErrors()) throw new RuntimeException("dep sync had errors");
                })
                .build();

        // ---- ensure-jdk -------------------------------------------------
        Phase ensureJdk = Phase.builder("ensure-jdk")
                .label("JDK")
                .kind(PhaseKind.IO)
                .requires("parse-build")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("resolve JDK");
                    Lockfile lock = ctx.require(LOCKFILE);
                    JkBuild project = ctx.require(PROJECT);
                    try {
                        JdkEnsure.ensure(dir, jdksDir, project, lock);
                    } catch (Exception e) {
                        ctx.error("jdk", e.getMessage() == null
                                ? e.getClass().getSimpleName() : e.getMessage());
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();

        // ---- compile-java -----------------------------------------------
        Phase compileJava = Phase.builder("compile-java")
                .label("Compiling")
                .kind(PhaseKind.CPU)
                .requires("parse-build", "sync-deps", "ensure-jdk")
                .scope(0)
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
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
                    boolean noCache = dev.jkbuild.config.ActiveConfig.get().noCacheOr(false);
                    if (!noCache && dev.jkbuild.task.FreshnessStamp.isFresh(
                            classes, sources, classpath)) {
                        ctx.label("up to date");
                        ctx.put(BUILD_OUTCOME, "up-to-date");
                        ctx.progress(sources.size());
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    List<String> javacArgs = (List<String>) ctx.require(JAVAC_ARGS);
                    CompileRequest request = CompileRequest.builder()
                            .sources(sources).classpath(classpath)
                            .outputDir(classes).release(ctx.require(RELEASE))
                            .extraOptions(javacArgs).javaHome(ctx.require(JAVA_HOME))
                            .build();
                    String taskId = "compile-main";
                    String actionKey = ActionKey.forJavac(taskId, request, Jk.VERSION);
                    ctx.put(ACTION_KEY, actionKey);
                    java.util.Optional<ActionCache.ActionRecord> cached =
                            noCache ? java.util.Optional.empty() : actionCache.lookup(actionKey);
                    if (cached.isPresent()) {
                        ctx.label("cache hit " + actionKey.substring(0, 8));
                        actionCache.restore(cached.get(), classes);
                        ctx.put(BUILD_OUTCOME, "cache-hit:" + actionKey.substring(0, 8));
                        ctx.progress(sources.size());
                        return;
                    }
                    ctx.label("compiling " + sources.size() + " sources");
                    var prewriter = dev.jkbuild.task.CasPrewriter.watching(cas, classes);
                    CompileResult result;
                    Map<String, String> precomputedOutputs;
                    try {
                        result = new JavacDriver().compile(request);
                    } finally {
                        precomputedOutputs = prewriter.finish();
                    }
                    for (CompileResult.Diagnostic d : result.diagnostics())
                        ctx.error("javac", d.render());
                    if (!result.success() || result.hasErrors())
                        throw new RuntimeException("javac reported errors");
                    actionCache.storeWithOutputs(taskId, actionKey,
                            ActionKey.snapshotInputs(request), precomputedOutputs);
                    ctx.put(BUILD_OUTCOME, "compiled");
                    ctx.progress(sources.size());
                })
                .build();

        // ---- compile-kotlin ---------------------------------------------
        Phase compileKotlin = Phase.builder("compile-kotlin")
                .label("Kotlin")
                .kind(PhaseKind.CPU)
                .requires("compile-java")
                .scope(0)
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    List<Path> ktSources = kotlinSources(ctx);
                    if (ktSources.isEmpty()) { ctx.label("no Kotlin sources"); return; }
                    ctx.updateScope(ktSources.size());
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    List<Path> kotlincCp = new ArrayList<>(classpath);
                    kotlincCp.add(classes);
                    ctx.label("compiling " + ktSources.size() + " Kotlin sources");
                    Path kotlinHome = CompileToolchain.resolveKotlinHome(cache);
                    KotlincResult ktResult = new KotlincDriver().compile(
                            KotlincRequest.builder()
                                    .sources(ktSources).classpath(kotlincCp)
                                    .outputDir(classes)
                                    .jvmTarget(CompileCommand.kotlinJvmTarget(ctx.require(RELEASE)))
                                    .kotlinHome(kotlinHome).build());
                    if (!ktResult.success()) {
                        ctx.error("kotlinc", ktResult.output());
                        throw new RuntimeException("kotlinc reported errors");
                    }
                    ctx.progress(ktSources.size());
                })
                .build();

        // ---- copy-resources ---------------------------------------------
        Phase copyResources = Phase.builder("copy-resources")
                .label("Resources")
                .kind(PhaseKind.CPU)
                .requires("compile-kotlin")
                .scope(1)
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    Path resMain = dir.resolve("src/main/resources");
                    if (!Files.exists(resMain)) { ctx.label("no resources"); return; }
                    ctx.label("copy resources");
                    copyResources(resMain, classes);
                    ctx.progress(1);
                })
                .build();

        // ---- compile-test -----------------------------------------------
        Phase compileTest = Phase.builder("compile-test")
                .label("Test compile")
                .kind(PhaseKind.CPU)
                .requires("compile-java", "sync-deps")
                .scope(1)
                .execute(ctx -> {
                    Path srcTest = dir.resolve("src/test/java");
                    if (CompileCommand.collectJavaSources(srcTest).isEmpty()) {
                        ctx.label("no test sources");
                        ctx.put(NO_TEST_SOURCES, true);
                        ctx.progress(1);
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    List<Path> compileCp = (List<Path>) ctx.require(COMPILE_TEST_CP);
                    List<Path> fullCp = new ArrayList<>();
                    fullCp.add(ctx.require(MAIN_CLASSES));
                    fullCp.addAll(compileCp);
                    @SuppressWarnings("unchecked")
                    List<String> javacArgs = (List<String>) ctx.require(JAVAC_ARGS);
                    boolean ok = TestCommand.compileWithCache(
                            ctx, "compile-test", srcTest,
                            ctx.require(TEST_CLASSES), fullCp,
                            ctx.require(RELEASE), javacArgs,
                            ctx.require(JAVA_HOME), cas, cache);
                    if (!ok) throw new RuntimeException("test compile failed");
                    ctx.progress(1);
                })
                .build();

        // ---- run-tests --------------------------------------------------
        Phase runTests = Phase.builder("run-tests")
                .label("Testing")
                .kind(PhaseKind.IO)
                .requires("compile-test", "copy-resources")
                .scope(0)
                .execute(ctx -> {
                    if (ctx.get(NO_TEST_SOURCES).orElse(false)) {
                        ctx.label("no tests to run");
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    List<Path> testRtCp = (List<Path>) ctx.require(TEST_RUNTIME_CP);
                    List<Path> runtimeCp = new ArrayList<>();
                    runtimeCp.add(ctx.require(MAIN_CLASSES));
                    runtimeCp.addAll(testRtCp);

                    var progress = dev.jkbuild.cli.tui.TestProgress.start(System.out);
                    TestProgressListener listener = TestCommand.bridgeListener(ctx, progress, workerCount);
                    JUnitLauncher.Result result;
                    try {
                        result = new JUnitLauncher().run(
                                ctx.require(JAVA_HOME), ctx.require(TEST_CLASSES),
                                runtimeCp, cache, workerCount, listener);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        progress.writeAbove("jk build: interrupted");
                        progress.close();
                        ctx.error("test", "interrupted");
                        throw new RuntimeException(e);
                    } catch (IOException e) {
                        progress.writeAbove("jk build: " + e.getMessage());
                        progress.close();
                        ctx.error("test", e.getMessage());
                        throw e;
                    }
                    ctx.put(TEST_RESULT, result);
                    if (result.allPassed()) {
                        progress.finishSuccess();
                    } else {
                        progress.finishFailure(result.failed());
                        throw new RuntimeException(result.failed() + " test failure"
                                + (result.failed() == 1 ? "" : "s"));
                    }
                })
                .build();

        // ---- package-jar ------------------------------------------------
        Phase packageJar = Phase.builder("package-jar")
                .label("Packaging")
                .kind(PhaseKind.CPU)
                .requires("copy-resources", "run-tests")
                .scope(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path classes = ctx.require(MAIN_CLASSES);
                    Path jarPath = layout.mainJar();
                    Files.createDirectories(jarPath.getParent());
                    ctx.label("package " + jarPath.getFileName());
                    JarPackager.JarRequest jarRequest =
                            JarPackager.JarRequest.of(classes, jarPath);
                    String mainClass = project.project().main();
                    if (mainClass != null && !mainClass.isBlank())
                        jarRequest = jarRequest.withMainClass(mainClass);
                    new JarPackager().packageJar(jarRequest);
                    ctx.put(JAR_PATH, jarPath);
                    ctx.progress(1);
                })
                .build();

        // ---- write-stamp ------------------------------------------------
        Phase writeStamp = Phase.builder("write-stamp")
                .requires("compile-java")
                .scope(1)
                .execute(ctx -> {
                    String outcome = ctx.get(BUILD_OUTCOME).orElse("");
                    if ("up-to-date".equals(outcome) || "no-sources".equals(outcome)) {
                        ctx.label("stamp unchanged");
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("write freshness stamp");
                    Path classes = ctx.require(MAIN_CLASSES);
                    @SuppressWarnings("unchecked")
                    List<Path> sources = (List<Path>) ctx.require(JAVA_SOURCES);
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    String actionKey = ctx.get(ACTION_KEY).orElse("");
                    dev.jkbuild.task.FreshnessStamp.write(
                            classes, "compile-main", actionKey, sources, classpath);
                    ctx.progress(1);
                })
                .build();

        // ---- assemble goal ----------------------------------------------
        Goal.Builder goalBuilder = Goal.builder("build")
                .addPhase(parseBuild)
                .addPhase(syncDeps)
                .addPhase(ensureJdk)
                .addPhase(compileJava)
                .addPhase(compileKotlin)
                .addPhase(copyResources)
                .addPhase(compileTest)
                .addPhase(runTests)
                .addPhase(packageJar)
                .addPhase(writeStamp);

        // native = "always" → add native-image phase after package-jar
        try {
            JkBuild project = JkBuildParser.parse(buildFile);
            if (project.project().nativeMode() == JkBuild.NativeMode.ALWAYS) {
                goalBuilder.addPhase(buildNativePhase(dir, cache, lockFile));
            }
        } catch (Exception ignored) {}

        Goal goal = goalBuilder.build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);

        if (result.success()) {
            printSuccessSummary(goal, result);
            try {
                var cacheConfig = dev.jkbuild.config.JkCacheConfig.fromToml(buildFile);
                dev.jkbuild.task.CachePruneScheduler.resolveJkExe().ifPresent(exe ->
                        dev.jkbuild.task.CachePruneScheduler.maybeRun(cacheConfig, cache, exe));
            } catch (IOException ignored) {}
            return 0;
        }
        // Test failures get exit 4; other failures exit 1.
        var testResult = goal.get(TEST_RESULT).orElse(null);
        if (testResult != null && !testResult.allPassed()) return 4;
        return 1;
    }

    // ---- native-image phase factory ------------------------------------

    private static Phase buildNativePhase(Path dir, Path cache, Path lockFile) {
        return Phase.builder("native-image")
                .label("Native")
                .kind(PhaseKind.IO)
                .requires("package-jar")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("native-image compilation");
                    // Delegate to NativeCommand logic via a subprocess-style call.
                    // NativeCommand reads jk.toml + the existing jar and runs
                    // GraalVM native-image. We wire progress via ctx.label.
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path mainJar = layout.mainJar();
                    if (!Files.exists(mainJar)) {
                        ctx.error("native", "jar not found at " + mainJar);
                        throw new RuntimeException("missing main jar for native-image");
                    }
                    String mainClass = project.project().main();
                    if (mainClass == null || mainClass.isBlank()) {
                        ctx.error("native", "native = \"always\" requires [project] main to be set");
                        throw new RuntimeException("missing main class");
                    }
                    ctx.label("running native-image for " + project.project().artifact());
                    // Re-use NativeCommand.runNativeImage if accessible, or
                    // invoke the same GraalVM driver inline here.
                    // For now, delegate to a fresh NativeCommand invocation via
                    // Jk.execute so we don't duplicate the driver logic.
                    int rc = Jk.execute("native", "-C", dir.toString(),
                            "--cache-dir", cache.toString());
                    if (rc != 0) {
                        ctx.error("native", "native-image exited with code " + rc);
                        throw new RuntimeException("native-image failed");
                    }
                    ctx.progress(1);
                })
                .build();
    }

    // ---- success summary -----------------------------------------------

    private void printSuccessSummary(Goal goal, GoalResult result) {
        if (global.outputIsJson()) return;
        String check  = dev.jkbuild.cli.tui.Theme.colorize(
                "✓", dev.jkbuild.cli.tui.Theme.brightGreen().bold());
        String inTime = dev.jkbuild.cli.tui.Theme.colorize(
                "in " + fmtDuration(result.duration()),
                dev.jkbuild.cli.tui.Theme.darkGray());

        String outcome = goal.get(BUILD_OUTCOME).orElse("");
        if ("up-to-date".equals(outcome)) {
            System.out.println(check + " Up to date " + inTime);
            return;
        }

        String built = dev.jkbuild.cli.tui.Theme.colorize(
                "Built", dev.jkbuild.cli.tui.Theme.focused());

        if (outcome.startsWith("cache-hit:")) {
            String jarLabel = goal.get(JAR_PATH)
                    .map(p -> p.getFileName().toString()).orElse("");
            String suffix = jarLabel.isEmpty() ? "" : " " + jarLabel;
            System.out.println(check + " " + built + suffix + " " + inTime);
            return;
        }

        goal.get(JAR_PATH).ifPresentOrElse(
                jar -> System.out.println(check + " " + built + " " + jar.getFileName()
                        + " " + inTime),
                ()  -> System.out.println(check + " " + built + " " + inTime));
    }

    static String fmtDuration(java.time.Duration d) {
        long ms = d.toMillis();
        if (ms < 1000) return ms + "ms";
        long totalSec = d.toSeconds();
        if (totalSec < 60) return String.format("%.1fs", ms / 1000.0);
        long hours   = totalSec / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;
        if (hours == 0) return minutes + "m " + seconds + "s";
        return hours + "h " + minutes + "m " + seconds + "s";
    }

    // ---- helpers -------------------------------------------------------

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

    @SuppressWarnings("unchecked")
    private static List<Path> javaSources(dev.jkbuild.run.PhaseContext ctx) {
        return (List<Path>) ctx.get(JAVA_SOURCES).orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Path> kotlinSources(dev.jkbuild.run.PhaseContext ctx) {
        return (List<Path>) ctx.get(KOTLIN_SOURCES).orElse(List.of());
    }

    private static void copyResources(Path resourceDir, Path classesDir) throws IOException {
        if (!Files.exists(resourceDir)) return;
        try (Stream<Path> stream = Files.walk(resourceDir)) {
            for (Path source : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(source)) continue;
                Path relative = resourceDir.relativize(source);
                Path target   = classesDir.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
