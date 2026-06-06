// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;



import dev.jkbuild.cache.Cas;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.compile.CompileRequest;
import dev.jkbuild.compile.CompileResult;
import dev.jkbuild.compile.JarPackager;
import dev.jkbuild.compile.KotlincRequest;
import dev.jkbuild.compile.ShadowPackager;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceClasspath;
import dev.jkbuild.http.Http;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Profile;
import dev.jkbuild.model.Scope;
import dev.jkbuild.resolver.CacheSync;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseContext;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.task.ActionCache;
import dev.jkbuild.task.ActionKey;
import dev.jkbuild.test.JUnitLauncher;
import dev.jkbuild.test.TestProgressListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The single build pipeline shared by every build-family command
 * ({@code build}, {@code native}, {@code image}, {@code run}, {@code install}).
 * It owns the cross-phase {@link GoalKey}s and the phase factories; each command
 * composes the {@linkplain #coreBuilder core phases} (always the same DAG —
 * sequential with parallelism where deps allow) and appends its own target tail
 * (native-image, OCI image, install launcher, …) into <em>one</em> {@link Goal}.
 *
 * <p>This is why commands never shell out to a nested {@code jk} process: the
 * phases are assembled and run in-process, which keeps progress/aggregation
 * coherent and lets embedders (e.g. an IntelliJ plugin) drive jk as pure Java.
 */
public final class BuildPipeline {

    private BuildPipeline() {}

    // ---- shared cross-phase keys ---------------------------------------
    public static final GoalKey<JkBuild>   PROJECT       = GoalKey.of("project",        JkBuild.class);
    public static final GoalKey<Lockfile>  LOCKFILE      = GoalKey.of("lockfile",       Lockfile.class);
    public static final GoalKey<Path>      JAVA_HOME     = GoalKey.of("java-home",      Path.class);
    public static final GoalKey<Integer>   RELEASE       = GoalKey.of("release",        Integer.class);
    @SuppressWarnings("rawtypes")
    public static final GoalKey<List> CLASSPATH       = GoalKey.of("classpath",      List.class);
    @SuppressWarnings("rawtypes")
    public static final GoalKey<List> JAVA_SOURCES    = GoalKey.of("java-sources",   List.class);
    @SuppressWarnings("rawtypes")
    public static final GoalKey<List> KOTLIN_SOURCES  = GoalKey.of("kotlin-sources", List.class);
    @SuppressWarnings("rawtypes")
    public static final GoalKey<List> JAVAC_ARGS      = GoalKey.of("javac-args",     List.class);
    @SuppressWarnings("rawtypes")
    public static final GoalKey<List> PROCESSOR_CP    = GoalKey.of("processor-cp",   List.class);
    @SuppressWarnings("rawtypes")
    public static final GoalKey<List> COMPILE_TEST_CP = GoalKey.of("cp-test",        List.class);
    @SuppressWarnings("rawtypes")
    public static final GoalKey<List> TEST_RUNTIME_CP = GoalKey.of("cp-runtime",     List.class);
    public static final GoalKey<String>    ACTION_KEY    = GoalKey.of("action-key",     String.class);
    public static final GoalKey<String>    BUILD_OUTCOME = GoalKey.of("build-outcome",  String.class);
    public static final GoalKey<String>    KOTLIN_OUTCOME = GoalKey.of("kotlin-outcome", String.class);
    public static final GoalKey<Path>      JAR_PATH      = GoalKey.of("jar-path",       Path.class);
    public static final GoalKey<Path>      MAIN_CLASSES  = GoalKey.of("main-classes",   Path.class);
    public static final GoalKey<Path>      TEST_CLASSES  = GoalKey.of("test-classes",   Path.class);
    public static final GoalKey<BuildLayout> LAYOUT      = GoalKey.of("layout",         BuildLayout.class);
    public static final GoalKey<JUnitLauncher.Result> TEST_RESULT =
            GoalKey.of("test-result", JUnitLauncher.Result.class);
    public static final GoalKey<Boolean>   NO_TEST_SOURCES = GoalKey.of("no-test-sources", Boolean.class);

    /** Everything a build needs that isn't carried through the goal's state. */
    public record Inputs(
            Path dir,
            Path cache,
            Path buildFile,
            Path lockFile,
            Path lockDir,
            int workerCount,
            int estimatedTestCount,
            String profileName,
            Path jdksDir,
            boolean skipTests,
            boolean verbose,
            boolean testOnly) {

        /** Back-compat: a full build (not test-only). */
        public Inputs(Path dir, Path cache, Path buildFile, Path lockFile, Path lockDir,
                      int workerCount, int estimatedTestCount, String profileName, Path jdksDir,
                      boolean skipTests, boolean verbose) {
            this(dir, cache, buildFile, lockFile, lockDir, workerCount, estimatedTestCount,
                    profileName, jdksDir, skipTests, verbose, false);
        }
    }

    /**
     * Assemble the goal builder with the core build phases (parse → sync → jdk →
     * compile → resources → [test] → package → stamp) plus the shadow/native
     * tails the project's {@code jk.toml} requests. Callers may append further
     * target-specific phases before {@code build()}.
     */
    public static Goal.Builder coreBuilder(Inputs in) {
        Cas cas = new Cas(in.cache());
        ActionCache actionCache = new ActionCache(cas, in.cache().resolve("actions"));

        // Compose only the language steps the project uses, so a single-language
        // project never shows a no-op step for the other. Explicit jk.toml
        // opt-ins (java/kotlin) win; otherwise the languages are inferred from
        // the source tree (see CompileSupport.resolveLanguages).
        boolean useKotlin = false;
        boolean useJava = true;
        boolean compactLayout = false;
        try {
            var project = JkBuildParser.parse(in.buildFile()).project();
            CompileSupport.Languages langs = CompileSupport.resolveLanguages(project, in.dir());
            useJava = langs.java();
            useKotlin = langs.kotlin();
            compactLayout = CompileSupport.isCompact(project, in.dir());
        } catch (Exception ignored) {
            // Unparseable/missing jk.toml — parse-build will surface the real error.
        }
        // Effectively-final copies for the phase lambdas.
        final boolean mixedWithJava = useJava;
        final boolean compact = compactLayout;
        // Mixed module: Kotlin compiles first (it reads Java *declarations* from
        // source; the Kotlin compiler never emits Java bytecode), then javac
        // against the Kotlin output, then `assemble-classes` merges both into the
        // shared classes dir — so Java↔Kotlin references resolve in both
        // directions. Each compiler owns a private output dir; they can't share
        // one, because javac's content-hash action cache snapshots its whole
        // output dir and would cache (then on restore, clobber) the other's
        // classes. The terminal phase downstream steps wait on is the assembler
        // when mixed, else whichever single compiler ran.
        final boolean mixed = useJava && useKotlin;
        final boolean kotlinModule = useKotlin;   // effectively-final copy for lambdas
        String mainCompile = mixed ? "assemble-classes"
                : (useKotlin ? "compile-kotlin" : "compile-java");

        // ---- parse-build ------------------------------------------------
        Phase parseBuild = Phase.builder("parse-build")
                .label("Parsing")
                .scope(() -> {
                    if (Files.exists(in.lockFile())) {
                        try { return LockfileReader.read(in.lockFile()).packages().size() + 5; }
                        catch (Exception ignored) {}
                    }
                    return 10;
                })
                .execute(ctx -> {
                    ctx.label("parse jk.toml");
                    JkBuild project;
                    try {
                        project = JkBuildParser.parse(in.buildFile());
                    } catch (RuntimeException e) {
                        ctx.error("toml", e.getMessage());
                        throw e;
                    }
                    ctx.put(PROJECT, project);
                    BuildLayout layout = BuildLayout.of(in.dir(), project);
                    ctx.put(LAYOUT, layout);

                    if (!Files.exists(in.lockFile())) {
                        ctx.label("resolve deps (first run)");
                        var result = LockFlow.run(
                                in.lockDir(), in.cache(), List.of(), true, null);
                        if (result.status() != 0) {
                            ctx.error("lock", result.error() != null
                                    ? result.error() : "dependency resolution failed");
                            throw new RuntimeException("lock failed");
                        }
                        ctx.put(LOCKFILE, result.lockfile());
                    } else {
                        ctx.put(LOCKFILE, LockfileReader.read(in.lockFile()));
                    }

                    Lockfile lock = ctx.require(LOCKFILE);
                    ctx.label("resolve classpath");
                    ClasspathResolver resolver = new ClasspathResolver(cas);

                    List<Path> mainCp = new ArrayList<>(
                            resolver.classpathFor(lock, ClasspathResolver.COMPILE_MAIN));
                    WorkspaceClasspath.Result mainSiblings =
                            WorkspaceClasspath.resolve(in.dir(), project, Set.of(Scope.MAIN));
                    mainCp.addAll(mainSiblings.jars());
                    if (!mainSiblings.missingSiblingJars().isEmpty()) {
                        for (String missing : mainSiblings.missingSiblingJars())
                            ctx.error("workspace", "sibling not built — " + missing);
                        throw new RuntimeException("missing workspace siblings");
                    }

                    Profile profile = CompileSupport.resolveProfile(project.profiles(), in.profileName());
                    ctx.put(JAVAC_ARGS, profile == null ? List.of() : profile.javacArgs());
                    ctx.put(CLASSPATH, mainCp);

                    // Annotation processors live in their own scope (kept off the
                    // compile classpath); javac discovers them via -processorpath.
                    ctx.put(PROCESSOR_CP,
                            new ArrayList<>(resolver.classpathFor(lock, Set.of(Scope.PROCESSOR))));

                    WorkspaceClasspath.Result testSiblings =
                            WorkspaceClasspath.resolve(in.dir(), project, Set.of(Scope.MAIN, Scope.TEST));
                    List<Path> compileTestCp = new ArrayList<>(
                            resolver.classpathFor(lock, ClasspathResolver.COMPILE_TEST));
                    compileTestCp.addAll(testSiblings.jars());
                    List<Path> testRuntimeCp = new ArrayList<>(
                            resolver.classpathFor(lock, ClasspathResolver.TEST));
                    testRuntimeCp.addAll(testSiblings.jars());
                    ctx.put(COMPILE_TEST_CP, compileTestCp);
                    ctx.put(TEST_RUNTIME_CP, testRuntimeCp);
                    Path javaMainSrc = compact ? in.dir().resolve("src") : in.dir().resolve("src/main/java");
                    ctx.put(JAVA_SOURCES, CompileSupport.collectJavaSources(javaMainSrc));
                    ctx.put(KOTLIN_SOURCES, CompileSupport.collectKotlinSources(in.dir(), compact));
                    ctx.put(RELEASE, project.project().javaRelease());
                    ctx.put(JAVA_HOME, CompileToolchain.resolveJavaHome(in.dir()));
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
                    try { return LockfileReader.read(in.lockFile()).packages().size(); }
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
                        JdkEnsure.ensure(in.dir(), in.jdksDir(), project, lock,
                                m -> ctx.warn("jdk", m));
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
                .requires(mixed
                        ? new String[]{"parse-build", "sync-deps", "ensure-jdk", "compile-kotlin"}
                        : new String[]{"parse-build", "sync-deps", "ensure-jdk"})
                .scope(0)
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    // javac always writes to the canonical classes dir (java/main/).
                    // The Kotlin incremental compiler gets its own dir (kotlin/main/)
                    // so it cannot prune Java's output; the assembler merges both.
                    Path javaOut = classes;
                    List<Path> sources = javaSources(ctx);
                    if (sources.isEmpty()) {
                        ctx.label("no Java sources");
                        Files.createDirectories(javaOut);
                        ctx.put(BUILD_OUTCOME, "no-sources");
                        return;
                    }
                    ctx.updateScope(sources.size());
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    if (mixed) {
                        // See Kotlin's output so Java can reference Kotlin types.
                        classpath = new ArrayList<>(classpath);
                        classpath.add(ctx.require(LAYOUT).kotlinClassesDir());
                    }
                    @SuppressWarnings("unchecked")
                    List<Path> processorCp = (List<Path>) ctx.require(PROCESSOR_CP);
                    boolean noCache = dev.jkbuild.config.ActiveConfig.get().noCacheOr(false);
                    // Fold the processor path into the freshness inputs so a processor
                    // bump busts the stamp (it isn't on the compile classpath).
                    List<Path> stampInputs = classpath;
                    if (!processorCp.isEmpty()) {
                        stampInputs = new ArrayList<>(classpath);
                        stampInputs.addAll(processorCp);
                    }
                    if (!noCache && dev.jkbuild.task.FreshnessStamp.isFresh(
                            javaOut, dev.jkbuild.task.FreshnessStamp.JAVA_STAMP, sources, stampInputs)) {
                        ctx.label("up to date");
                        ctx.put(BUILD_OUTCOME, "up-to-date");
                        ctx.progress(sources.size());
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    List<String> javacArgs = (List<String>) ctx.require(JAVAC_ARGS);
                    CompileRequest request = CompileRequest.builder()
                            .sources(sources).classpath(classpath)
                            .outputDir(javaOut).release(ctx.require(RELEASE))
                            .extraOptions(javacArgs).javaHome(ctx.require(JAVA_HOME))
                            .processorPath(processorCp)
                            .build();
                    String taskId = ActionKey.qualifiedTaskId("compile-main", javaOut);
                    Path javaStateDir = in.cache().resolve("actions")
                            .resolve("incremental-java").resolve(taskId);
                    // With processors declared, hand the incremental compiler an AP setup:
                    // a located worker jar (null-safe → plain javac fallback) + a stable
                    // generated-sources dir. The engine only routes through the worker once
                    // it has *detected* source-generating processors (the orphan signal), so
                    // bytecode-only processors (e.g. Lombok) never need the worker.
                    dev.jkbuild.task.JavaIncrementalCompile.ApSetup ap = null;
                    if (!processorCp.isEmpty()) {
                        Path genDir = ctx.require(LAYOUT).generatedSourcesDir("annotations");
                        Files.createDirectories(genDir);
                        ap = new dev.jkbuild.task.JavaIncrementalCompile.ApSetup(
                                JavaWorkerSetup.locateWorkerJar(cas), genDir);
                    }
                    ctx.label("compiling " + sources.size() + " sources");
                    dev.jkbuild.task.JavaIncrementalCompile.Result r =
                            dev.jkbuild.task.JavaIncrementalCompile.run(
                                    taskId, request, dev.jkbuild.util.JkVersion.VERSION,
                                    !noCache, cas, actionCache, javaStateDir, ap);
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
                // Kotlin compiles first (reads Java declarations from source), so it
                // only needs the base phases — javac runs after it in a mixed module.
                .requires("parse-build", "sync-deps", "ensure-jdk")
                .scope(0)
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    Files.createDirectories(classes);   // compile-java may be skipped
                    List<Path> ktSources = kotlinSources(ctx);
                    if (ktSources.isEmpty()) {
                        ctx.label("no Kotlin sources");
                        ctx.put(KOTLIN_OUTCOME, "no-sources");
                        return;
                    }
                    ctx.updateScope(ktSources.size());
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    // Freshness inputs: Kotlin sources plus — in a mixed module —
                    // the Java sources, since kotlinc compiles against the Java
                    // output (kotlincCp includes `classes`) and a Java edit can
                    // make our .class files stale. Unlike compile-java there is
                    // no content-hash action cache behind this stamp, so the
                    // check must err conservative: any Java change forces a
                    // Kotlin recompile. The output dir itself is deliberately not
                    // an input (directory mtimes don't track in-place .class
                    // rewrites, and copy-resources churns it).
                    List<Path> freshInputs = new ArrayList<>(ktSources);
                    if (mixedWithJava) freshInputs.addAll(javaSources(ctx));
                    boolean noCache = dev.jkbuild.config.ActiveConfig.get().noCacheOr(false);
                    if (!noCache && dev.jkbuild.task.FreshnessStamp.isFresh(
                            classes, dev.jkbuild.task.FreshnessStamp.KOTLIN_STAMP, freshInputs, classpath)) {
                        ctx.label("up to date");
                        ctx.put(KOTLIN_OUTCOME, "up-to-date");
                        ctx.progress(ktSources.size());
                        return;
                    }
                    ctx.label("compiling " + ktSources.size() + " Kotlin sources");
                    // Kotlin compiles into its own dir, then we merge into the
                    // shared classes dir. The incremental compiler owns its output
                    // dir and prunes files it didn't produce — so it can't share a
                    // dir with javac's output (it would delete the .class files).
                    Path ktOut = ctx.require(LAYOUT).kotlinClassesDir();
                    String taskId = ActionKey.qualifiedTaskId("compile-kotlin", classes);
                    Path workingDir = in.cache().resolve("actions").resolve("incremental-kotlin")
                            .resolve(taskId);
                    // Mixed module: Kotlin reads the Java declarations from source
                    // (analysis only — it emits no Java bytecode; javac does next).
                    dev.jkbuild.task.KotlinCompile.Result kr = compileKotlinSources(
                            ctx, in, cas, actionCache, ktSources, classpath, ktOut, taskId, workingDir,
                            mixedWithJava ? (compact ? in.dir().resolve("src") : in.dir().resolve("src/main/java")) : null);
                    if (!kr.success()) {
                        ctx.error("kotlinc", kr.output());
                        throw new RuntimeException("kotlinc reported errors");
                    }
                    if (kr.cacheHit()) ctx.label("cache hit " + kr.actionKey().substring(0, 8));
                    // Kotlin-only: publish straight into the classes dir. Mixed:
                    // leave it in ktOut for `assemble-classes` to merge after javac.
                    if (!mixedWithJava) copyResources(ktOut, classes);
                    ctx.put(KOTLIN_OUTCOME, "compiled");
                    ctx.progress(ktSources.size());
                })
                .build();

        // ---- copy-resources ---------------------------------------------
        Phase copyResources = Phase.builder("copy-resources")
                .label("Resources")
                .kind(PhaseKind.CPU)
                .requires(mainCompile)
                .scope(1)
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    Path resMain = in.dir().resolve("src/main/resources");
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
                .requires(mainCompile, "sync-deps")
                .scope(1)
                .execute(ctx -> {
                    Path javaTestSrc = compact ? in.dir().resolve("test") : in.dir().resolve("src/test/java");
                    List<Path> javaTest = CompileSupport.collectJavaSources(javaTestSrc);
                    List<Path> ktTest = CompileSupport.collectKotlinTestSources(in.dir(), compact);
                    if (javaTest.isEmpty() && ktTest.isEmpty()) {
                        ctx.label("no test sources");
                        ctx.put(NO_TEST_SOURCES, true);
                        ctx.progress(1);
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    List<Path> compileCp = (List<Path>) ctx.require(COMPILE_TEST_CP);
                    List<Path> baseCp = new ArrayList<>();
                    baseCp.add(ctx.require(MAIN_CLASSES));
                    baseCp.addAll(compileCp);
                    Path testClasses = ctx.require(TEST_CLASSES);
                    boolean mixedTest = !javaTest.isEmpty() && !ktTest.isEmpty();

                    // Kotlin test sources first, so Java tests can reference Kotlin
                    // test types (mirrors the main mixed-module ordering). In a mixed
                    // test module each language gets its own output dir, merged below.
                    Path ktTestOut = mixedTest
                            ? ctx.require(LAYOUT).kotlinTestClassesDir()
                            : testClasses;
                    if (!ktTest.isEmpty()) {
                        ctx.label("compiling " + ktTest.size() + " Kotlin test sources");
                        String ktTaskId = ActionKey.qualifiedTaskId("compile-test-kotlin", testClasses);
                        Path ktWorkingDir = in.cache().resolve("actions")
                                .resolve("incremental-kotlin").resolve(ktTaskId);
                        dev.jkbuild.task.KotlinCompile.Result kr = compileKotlinSources(
                                ctx, in, cas, actionCache, ktTest, baseCp, ktTestOut,
                                ktTaskId, ktWorkingDir, mixedTest ? javaTestSrc : null);
                        if (!kr.success()) {
                            ctx.error("kotlinc", kr.output());
                            throw new RuntimeException("test kotlinc reported errors");
                        }
                    }

                    // Java test sources, against the Kotlin test output in a mixed module.
                    if (!javaTest.isEmpty()) {
                        Path javaTestOut = testClasses;  // javac always writes to java/test/
                        List<Path> javaCp = baseCp;
                        if (mixedTest) {
                            javaCp = new ArrayList<>(baseCp);
                            javaCp.add(ktTestOut);
                        }
                        @SuppressWarnings("unchecked")
                        List<String> javacArgs = (List<String>) ctx.require(JAVAC_ARGS);
                        boolean ok = TestSupport.compileWithCache(
                                ctx, "compile-test", javaTestSrc, javaTestOut, javaCp,
                                ctx.require(RELEASE), javacArgs, ctx.require(JAVA_HOME), cas, in.cache());
                        if (!ok) throw new RuntimeException("test compile failed");
                    }

                    // In mixed test mode, kotlin output needs to be merged into testClasses
                    // (java/test/). Java test output already went there directly.
                    if (mixedTest && !ktTest.isEmpty()) {
                        Files.createDirectories(testClasses);
                        copyResources(ktTestOut, testClasses);
                    }
                    ctx.progress(1);
                })
                .build();

        // ---- run-tests --------------------------------------------------
        Phase runTests = Phase.builder("run-tests")
                .label("Testing")
                .kind(PhaseKind.IO)
                .requires("compile-test", "copy-resources")
                .scope(in.estimatedTestCount())
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
                    // Kotlin output (main or test) needs the stdlib at runtime.
                    if (kotlinModule || !CompileSupport.collectKotlinTestSources(in.dir(), compact).isEmpty()) {
                        runtimeCp.add(kotlinStdlib(ctx, cas));
                    }

                    TestProgressListener listener =
                            TestSupport.bridgeListener(ctx, in.workerCount(), in.verbose());
                    JUnitLauncher.Result result;
                    try {
                        result = new JUnitLauncher().run(
                                ctx.require(JAVA_HOME), ctx.require(TEST_CLASSES),
                                runtimeCp, in.cache(), in.workerCount(), listener);
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
                .requires(in.skipTests()
                        ? new String[]{"copy-resources"}
                        : new String[]{"copy-resources", "run-tests"})
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
                    Path javaOut = classes;  // javac always writes to java/main/
                    @SuppressWarnings("unchecked")
                    List<Path> sources = (List<Path>) ctx.require(JAVA_SOURCES);
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    if (mixed) {   // match compile-java's freshness inputs
                        classpath = new ArrayList<>(classpath);
                        classpath.add(ctx.require(LAYOUT).kotlinClassesDir());
                    }
                    String actionKey = ctx.get(ACTION_KEY).orElse("");
                    dev.jkbuild.task.FreshnessStamp.write(
                            javaOut, dev.jkbuild.task.FreshnessStamp.JAVA_STAMP,
                            "compile-main", actionKey, sources, classpath);
                    ctx.progress(1);
                })
                .build();

        // ---- write-stamp-kotlin -----------------------------------------
        // Kotlin's freshness companion (cf. write-stamp for Java). Mirrors the
        // input set compile-kotlin checked: Kotlin sources, plus Java sources in
        // a mixed module. No action-cache key exists yet — the direct kotlinc
        // path leaves it empty until incremental Kotlin lands.
        Phase writeStampKotlin = Phase.builder("write-stamp-kotlin")
                .requires("compile-kotlin")
                .scope(1)
                .execute(ctx -> {
                    String outcome = ctx.get(KOTLIN_OUTCOME).orElse("");
                    if ("up-to-date".equals(outcome) || "no-sources".equals(outcome)) {
                        ctx.label("stamp unchanged");
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("write freshness stamp");
                    Path classes = ctx.require(MAIN_CLASSES);
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    List<Path> freshInputs = new ArrayList<>(kotlinSources(ctx));
                    if (mixedWithJava) freshInputs.addAll(javaSources(ctx));
                    dev.jkbuild.task.FreshnessStamp.write(
                            classes, dev.jkbuild.task.FreshnessStamp.KOTLIN_STAMP,
                            "compile-kotlin", "", freshInputs, classpath);
                    ctx.progress(1);
                })
                .build();

        // ---- assemble-classes (mixed modules only) ----------------------
        // Merge the per-language output dirs into the shared classes dir that
        // packaging, tests, and the run/native tails all read.
        Phase assembleClasses = Phase.builder("assemble-classes")
                .label("Assembling")
                .kind(PhaseKind.CPU)
                .requires("compile-java", "compile-kotlin")
                .scope(1)
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    String jOutcome = ctx.get(BUILD_OUTCOME).orElse("");
                    String kOutcome = ctx.get(KOTLIN_OUTCOME).orElse("");
                    boolean settled = (jOutcome.equals("up-to-date") || jOutcome.equals("no-sources"))
                            && (kOutcome.equals("up-to-date") || kOutcome.equals("no-sources"));
                    if (settled) {   // both unchanged → classes already holds both
                        ctx.label("up to date");
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("assemble classes");
                    Files.createDirectories(classes);
                    // Java output already lives in classes (java/main/); merge kotlin.
                    copyResources(ctx.require(LAYOUT).kotlinClassesDir(), classes);
                    ctx.progress(1);
                })
                .build();

        Goal.Builder b = Goal.builder("build")
                .addPhase(parseBuild)
                .addPhase(syncDeps)
                .addPhase(ensureJdk);
        if (useJava) {
            b.addPhase(compileJava);
        }
        if (useKotlin) {
            b.addPhase(compileKotlin);
        }
        if (mixed) {
            b.addPhase(assembleClasses);
        }
        b.addPhase(copyResources);
        if (in.testOnly() || !in.skipTests()) {
            b.addPhase(compileTest).addPhase(runTests);
        }
        // `jk test` stops at run-tests — it never packages a jar.
        if (!in.testOnly()) {
            b.addPhase(packageJar);
        }
        // write-stamp is the Java-compile freshness companion; only when Java ran.
        if (useJava) {
            b.addPhase(writeStamp);
        }
        // write-stamp-kotlin is the Kotlin-compile freshness companion.
        if (useKotlin) {
            b.addPhase(writeStampKotlin);
        }
        return b;
    }

    /**
     * Append the artifact tails the project's {@code jk.toml} declares:
     * {@code shadow = true} → fat-jar phase, {@code native = "always"} →
     * native-image phase. {@code jk build} / {@code jk run} / {@code jk install}
     * call this after {@link #coreBuilder}; {@code jk native} composes the
     * native tail explicitly instead.
     */
    public static void appendDeclaredTails(Goal.Builder b, Inputs in) {
        try {
            JkBuild project = JkBuildParser.parse(in.buildFile());
            if (project.project().shadow()) {
                b.addPhase(shadowPhase(in.cache(), in.lockFile()));
            }
            if (project.project().nativeMode() == JkBuild.NativeMode.ALWAYS) {
                b.addPhase(nativePhase(in.dir(), in.cache(), in.lockFile(), in.jdksDir()));
            }
        } catch (Exception ignored) {}
    }

    // ---- tail phases ----------------------------------------------------

    /** Fat-jar (shadow) packaging — requires package-jar. */
    public static Phase shadowPhase(Path cache, Path lockFile) {
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

    /** GraalVM native-image, run directly as a subprocess — requires package-jar. */
    public static Phase nativePhase(Path dir, Path cache, Path lockFile, Path jdksDir) {
        return nativePhase(dir, cache, lockFile, jdksDir, null, List.of());
    }

    /**
     * GraalVM native-image phase with an explicit main-class override and extra
     * native-image arguments — what {@code jk native} composes onto the core
     * build. A null/blank {@code mainOverride} falls back to {@code [project] main}.
     * Run directly as a subprocess; requires package-jar.
     */
    public static Phase nativePhase(Path dir, Path cache, Path lockFile, Path jdksDir,
                                    String mainOverride, List<String> extraArgs) {
        List<String> extra = extraArgs == null ? List.of() : extraArgs;
        return Phase.builder("native-image")
                .label("Native")
                .kind(PhaseKind.IO)
                .requires("package-jar")
                .scope(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path mainJar = layout.mainJar();
                    if (!Files.exists(mainJar)) {
                        ctx.error("native", "jar not found at " + mainJar);
                        throw new RuntimeException("missing main jar for native-image");
                    }
                    String mainClass = (mainOverride != null && !mainOverride.isBlank())
                            ? mainOverride : project.project().main();
                    if (mainClass == null || mainClass.isBlank()) {
                        ctx.error("native", "no main class — set [project] main "
                                + "or pass --main / [image] main-class.");
                        throw new RuntimeException("missing main class");
                    }
                    Path out = layout.nativeBinary();
                    Files.createDirectories(out.getParent());

                    Path javaHome = dev.jkbuild.jdk.JdkResolver.forProject(dir, jdksDir)
                            .map(dev.jkbuild.jdk.InstalledJdk::home)
                            .orElseGet(CompileToolchain::runningJavaHome);

                    List<Path> classpath = new ArrayList<>();
                    classpath.add(mainJar);
                    if (Files.exists(lockFile)) {
                        Lockfile lock = LockfileReader.read(lockFile);
                        classpath.addAll(new ClasspathResolver(new Cas(cache))
                                .classpathFor(lock, ClasspathResolver.RUNTIME));
                    }

                    ctx.label("native-image " + out.getFileName());
                    int exit = dev.jkbuild.tool.NativeImageDriver.run(
                            new dev.jkbuild.tool.NativeImageDriver.Request(
                                    javaHome, classpath, mainClass, out, extra));
                    if (exit != 0) {
                        ctx.error("native", "native-image exited " + exit);
                        throw new RuntimeException("native-image failed (exit " + exit + ")");
                    }
                    ctx.progress(1);
                })
                .build();
    }

    // ---- helpers --------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Path> javaSources(PhaseContext ctx) {
        return (List<Path>) ctx.get(JAVA_SOURCES).orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Path> kotlinSources(PhaseContext ctx) {
        return (List<Path>) ctx.get(KOTLIN_SOURCES).orElse(List.of());
    }

    /**
     * Compile Kotlin {@code sources} into {@code outputDir} via the worker
     * (action-cached: restores from the CAS on an exact-input hit without
     * launching the worker, else compiles incrementally). Shared by the main
     * {@code compile-kotlin} and {@code compile-test} phases. The caller owns
     * freshness stamps, output assembly, and outcome reporting.
     *
     * @param javaSourceRoot when non-null, passed as {@code -Xjava-source-roots}
     *     so a mixed module's Kotlin can read Java declarations from source
     */
    private static dev.jkbuild.task.KotlinCompile.Result compileKotlinSources(
            PhaseContext ctx, Inputs in, Cas cas, ActionCache actionCache,
            List<Path> sources, List<Path> classpath, Path outputDir,
            String taskId, Path workingDir, Path javaSourceRoot) throws IOException {
        String kotlinVersion = CompileToolchain.kotlinVersionFor(
                ctx.require(LOCKFILE), ctx.require(PROJECT));
        KotlinWorkerSetup.Prepared kt;
        try {
            dev.jkbuild.repo.RepoGroup repos =
                    RepoGroupBuilder.buildFor(ctx.require(PROJECT), null, cas);
            kt = KotlinWorkerSetup.prepare(repos, cas, kotlinVersion);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted resolving the Kotlin compiler", e);
        }
        // Compilation classpath: project deps + the version-matched stdlib (the
        // in-process worker has no kotlin-home to auto-supply it; -no-stdlib).
        List<Path> compileCp = new ArrayList<>(classpath);
        compileCp.add(kt.stdlib());
        List<String> ktArgs = new ArrayList<>();
        ktArgs.add("-no-stdlib");
        if (javaSourceRoot != null) {
            ktArgs.add("-Xjava-source-roots=" + javaSourceRoot.toAbsolutePath());
        }
        Files.createDirectories(outputDir);
        KotlincRequest req = KotlincRequest.builder()
                .sources(sources).classpath(compileCp)
                .outputDir(outputDir)
                .jvmTarget(CompileSupport.kotlinJvmTarget(ctx.require(RELEASE)))
                .workerClasspath(kt.workerClasspath())
                .javaHome(ctx.require(JAVA_HOME))
                .workingDir(workingDir)
                .snapshotDir(in.cache().resolve("kotlin-cp-snapshots"))
                .extraArgs(ktArgs)
                .build();
        boolean noCache = dev.jkbuild.config.ActiveConfig.get().noCacheOr(false);
        return dev.jkbuild.task.KotlinCompile.run(
                taskId, req, dev.jkbuild.util.JkVersion.VERSION, !noCache, cas, actionCache);
    }

    /**
     * The version-matched {@code kotlin-stdlib} path (already in the CAS from the
     * worker closure). Kotlin output needs it on the <em>runtime</em> classpath
     * too — compilation pairs the stdlib with {@code -no-stdlib}, but the JVM
     * still needs {@code kotlin.jvm.internal.*} etc. when the code runs.
     */
    private static Path kotlinStdlib(PhaseContext ctx, Cas cas) throws IOException {
        String kotlinVersion = CompileToolchain.kotlinVersionFor(
                ctx.require(LOCKFILE), ctx.require(PROJECT));
        try {
            dev.jkbuild.repo.RepoGroup repos =
                    RepoGroupBuilder.buildFor(ctx.require(PROJECT), null, cas);
            return KotlinWorkerSetup.prepare(repos, cas, kotlinVersion).stdlib();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted resolving the Kotlin stdlib", e);
        }
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
