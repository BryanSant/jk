// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;

import dev.jkbuild.cli.GlobalOptions;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.run.AggregateContext;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.tui.CommandManager;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.compile.ShadowPackager;
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
        GoalConsole.Mode mode = GoalConsole.modeFor(global);

        // --output json / --verbose keep per-member rendering (NDJSON streams,
        // verbose wants the full per-phase log). Banners separate the members.
        if (mode != GoalConsole.Mode.AUTO && mode != GoalConsole.Mode.QUIET) {
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

        // AUTO / QUIET: every member feeds ONE aggregate view (spinner header +
        // single bar + merged phase list). Settle it once after the last member.
        boolean animate = mode == GoalConsole.Mode.AUTO && GoalConsole.isInteractiveTerminal();
        CommandManager view = CommandManager.goal(System.out, "Building", animate);
        AggregateContext agg = new AggregateContext(view);
        int built = 0;
        for (Path memberDir : sorted) {
            String member = workspaceRoot.relativize(memberDir).toString();
            int exit;
            try {
                exit = runForDir(memberDir, agg);
            } catch (Exception e) {
                view.finishFailure("Build failed in " + member);
                throw e;
            }
            if (exit != 0) {
                view.finishFailure("Build failed in " + member);
                System.err.println("jk build: " + member + " failed (exit " + exit + ")");
                return exit;
            }
            built++;
        }
        view.finishSuccess("Built " + built + " member" + (built == 1 ? "" : "s"));
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
        return runForDir(dir, null);
    }

    /**
     * Build one project directory. When {@code agg} is non-null this is a
     * workspace member whose events feed the shared aggregate view rather than
     * a per-member progress display.
     */
    private int runForDir(Path dir, AggregateContext agg) throws Exception {
        Path cache  = cacheDir != null ? cacheDir : JkDirs.cache();
        Cas cas     = new Cas(cache);
        ActionCache actionCache = new ActionCache(cas, cache.resolve("actions"));
        Path buildFile = dir.resolve("jk.toml");
        int workerCount = workers != null && workers > 0 ? workers : 1;
        // Lexical pre-discovery so the runTests phase's scope is known
        // before any phase runs — see TestCommand.estimateTestCount.
        int estimatedTestCount = TestCommand.estimateTestCount(dir.resolve("src/test/java"));

        if (!Files.exists(buildFile)) {
            System.err.println("jk build: no jk.toml in " + dir);
            return 2;
        }

        // Workspace members share the root's jk.lock — there's exactly one
        // lock per workspace (PRD §13.2). A member-local jk.lock would be
        // resolved against a partial view of the dep graph and would race
        // with the root's. Detect the member case here and target the
        // root's lockfile for both reads and the first-run write.
        final Path lockFile = resolveLockFileForDir(dir, buildFile);
        if (lockFile == null) {
            // resolveLockFileForDir handles the error message internally.
            return 2;
        }
        // The directory where LockFlow.run should write a missing lock —
        // always the workspace root when we're a member, so the member
        // doesn't shadow the root with its own.
        final Path lockDir = lockFile.getParent();

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

                    // If no lockfile, resolve now. For workspace members
                    // this runs LockFlow against the workspace root so the
                    // single shared jk.lock gets written there.
                    if (!Files.exists(lockFile)) {
                        ctx.label("resolve deps (first run)");
                        var result = LockFlow.run(
                                lockDir, cache, List.of(), true, null, "jk build");
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
                    // Project-qualify so the tasks/ pointer is unique per module.
                    String taskId = ActionKey.qualifiedTaskId("compile-main", classes);
                    ctx.label("compiling " + sources.size() + " sources");
                    dev.jkbuild.task.IncrementalCompile.Result r =
                            dev.jkbuild.task.IncrementalCompile.run(
                                    taskId, request, Jk.VERSION, !noCache, cas, actionCache);
                    ctx.put(ACTION_KEY, r.actionKey());
                    for (CompileResult.Diagnostic d : r.diagnostics())
                        ctx.error("javac", d.render());
                    if (!r.success())
                        throw new RuntimeException("javac reported errors");
                    if (r.cacheHit())
                        ctx.label("cache hit " + r.actionKey().substring(0, 8));
                    ctx.put(BUILD_OUTCOME, r.outcome());
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
                    Path kotlinHome = CompileToolchain.resolveKotlinHome(cache,
                            CompileToolchain.kotlinVersionFor(ctx.require(LOCKFILE), ctx.require(PROJECT)));
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
                .scope(estimatedTestCount)
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

                    TestProgressListener listener =
                            TestCommand.bridgeListener(ctx, workerCount, global.verbose);
                    JUnitLauncher.Result result;
                    try {
                        result = new JUnitLauncher().run(
                                ctx.require(JAVA_HOME), ctx.require(TEST_CLASSES),
                                runtimeCp, cache, workerCount, listener);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        ctx.error("test", "interrupted");
                        throw new RuntimeException(e);
                    } catch (IOException e) {
                        ctx.error("test", e.getMessage());
                        throw e;
                    }
                    ctx.put(TEST_RESULT, result);
                    if (!result.allPassed()) {
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
                    if (!project.manifest().isEmpty())
                        jarRequest = jarRequest.withAttributes(project.manifest());
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

        // shadow = true → add a fat-jar phase after package-jar;
        // native = "always" → add native-image phase after package-jar.
        try {
            JkBuild project = JkBuildParser.parse(buildFile);
            if (project.project().shadow()) {
                goalBuilder.addPhase(buildShadowPhase(cache, lockFile));
            }
            if (project.project().nativeMode() == JkBuild.NativeMode.ALWAYS) {
                goalBuilder.addPhase(buildNativePhase(dir, cache, lockFile));
            }
        } catch (Exception ignored) {}

        Goal goal = goalBuilder.build();

        String target = buildTarget(buildFile, dir);
        GoalResult result;
        if (agg != null) {
            // Workspace member: feed the one shared aggregate view.
            result = GoalConsole.runGoalInto(goal, cache, target, agg);
        } else {
            ConsoleSpec spec = new ConsoleSpec("Building",
                    r -> successMessage(goal, r),
                    r -> failureMessage(goal, r));
            result = GoalConsole.runGoal(goal, GoalConsole.modeFor(global), cache, spec, target);
        }

        if (result.success()) {
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

    private static Phase buildShadowPhase(Path cache, Path lockFile) {
        return Phase.builder("package-shadow")
                .label("Shadow")
                .kind(PhaseKind.CPU)
                .requires("package-jar")
                .scope(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path classes = ctx.require(MAIN_CLASSES);
                    Path shadowJar = layout.shadowJar();
                    ctx.label("package " + shadowJar.getFileName());
                    List<Path> depJars = List.of();
                    if (Files.exists(lockFile)) {
                        Lockfile lock = LockfileReader.read(lockFile);
                        depJars = new ClasspathResolver(new Cas(cache))
                                .classpathFor(lock, ClasspathResolver.RUNTIME);
                    }
                    new ShadowPackager().packageShadow(new ShadowPackager.ShadowRequest(
                            classes, depJars, shadowJar,
                            project.project().main(), project.manifest(), 0L));
                    ctx.progress(1);
                })
                .build();
    }

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

    /** Header member label for the goal view: the project's {@code group:artifact}. */
    static String buildTarget(Path buildFile, Path dir) {
        try {
            var p = JkBuildParser.parse(buildFile).project();
            return p.group() + ":" + p.artifact();
        } catch (Exception e) {
            return dir.getFileName() == null ? "" : dir.getFileName().toString();
        }
    }

    /** Success result line (sans the leading ✔), e.g. "Built jktest-0.1.0.jar in 717ms". */
    private static String successMessage(Goal goal, GoalResult result) {
        String inTime = Theme.colorize(
                "in " + fmtDuration(result.duration()), Theme.active().darkGray());
        String outcome = goal.get(BUILD_OUTCOME).orElse("");
        if ("up-to-date".equals(outcome)) {
            return "Up to date " + inTime;
        }
        String built = Theme.colorize("Built", Theme.active().focused());
        if (outcome.startsWith("cache-hit:")) {
            String jarLabel = goal.get(JAR_PATH)
                    .map(p -> p.getFileName().toString()).orElse("");
            return built + (jarLabel.isEmpty() ? "" : " " + jarLabel) + " " + inTime;
        }
        return goal.get(JAR_PATH)
                .map(jar -> built + " " + jar.getFileName() + " " + inTime)
                .orElse(built + " " + inTime);
    }

    /** Failure result line (sans the leading ✗); test failures get a tailored message. */
    private static String failureMessage(Goal goal, GoalResult result) {
        var testResult = goal.get(TEST_RESULT).orElse(null);
        if (testResult != null && !testResult.allPassed()) {
            String jar = goal.get(JAR_PATH).map(p -> p.getFileName().toString()).orElse("");
            return jar.isEmpty() ? "Tests failed" : "Tests failed while building " + jar;
        }
        return "Build failed";
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

    /**
     * Resolve which {@code jk.lock} a member project should use. For a
     * workspace member, the workspace root's {@code jk.lock} is shared —
     * we never write a per-member lockfile. For a standalone project (or
     * the workspace root itself), the project's own directory.
     *
     * @return the lockfile path, or {@code null} if the parse failed
     *         (an error message was already printed to stderr).
     */
    private static Path resolveLockFileForDir(Path dir, Path buildFile) throws IOException {
        JkBuild peek;
        try {
            peek = JkBuildParser.parse(buildFile);
        } catch (RuntimeException e) {
            System.err.println("jk build: " + e.getMessage());
            return null;
        }
        if (!peek.isWorkspaceRoot()) {
            var workspaceRoot = WorkspaceLocator.findRoot(dir);
            if (workspaceRoot.isPresent()) {
                return workspaceRoot.get().resolve("jk.lock");
            }
        }
        return dir.resolve("jk.lock");
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
