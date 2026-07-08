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
import dev.jkbuild.jdk.JavaHomes;
import dev.jkbuild.jdk.JdkEnsure;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Profile;
import dev.jkbuild.model.Scope;
import dev.jkbuild.resolver.CacheSync;
import dev.jkbuild.resolver.pubgrub.UnsatisfiableException;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseContext;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.run.TestSummary;
import dev.jkbuild.task.ActionCache;
import dev.jkbuild.task.ActionKey;
import dev.jkbuild.test.JUnitLauncher;
import dev.jkbuild.test.TestProgressListener;
import dev.jkbuild.worker.WorkerJar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The single build pipeline shared by every build-family command ({@code build}, {@code native},
 * {@code image}, {@code run}, {@code install}). It owns the cross-phase {@link GoalKey}s and the
 * phase factories; each command composes the {@linkplain #coreBuilder core phases} (always the same
 * DAG — sequential with parallelism where deps allow) and appends its own target tail
 * (native-image, OCI image, install launcher, …) into <em>one</em> {@link Goal}.
 *
 * <p>This is why commands never shell out to a nested {@code jk} process: the phases are assembled
 * and run in-process, which keeps progress/aggregation coherent and lets embedders (e.g. an
 * IntelliJ plugin) drive jk as pure Java.
 */
public final class BuildPipeline {

    static {
        // Bridge the per-session cooperative cancel token into the engine's EXISTING per-goal
        // cancellation poll (PhaseContext.cancelled()) without an upward :model → :core edge:
        // :model exposes the SessionCancel seam and the engine (which sees :core) binds a probe
        // reading the current session's token. Runs once when this class loads — before any of
        // its phase factories produce phases that poll cancelled(). The session is resolved
        // lazily at poll time, so a later install()/where() binding is picked up.
        //
        // Session context (and thus this cancel token) also reaches tasks on the shared
        // JkThreads.io()/cpu() pools: those pools wrap every task via ContextPropagator, which
        // SessionContext binds to capture the submitting thread's session and rebind it on the
        // worker. So a scoped (multi-tenant) session's cancel is observed by async worker-pool
        // tasks too, not just phases polling on the binding thread.
        dev.jkbuild.run.SessionCancel.bind(
                () -> dev.jkbuild.config.SessionContext.current().cancelled());
    }

    private BuildPipeline() {}

    // ---- shared cross-phase keys ---------------------------------------
    public static final GoalKey<JkBuild> PROJECT = GoalKey.of("project", JkBuild.class);
    public static final GoalKey<Lockfile> LOCKFILE = GoalKey.of("lockfile", Lockfile.class);
    public static final GoalKey<Path> JAVA_HOME = GoalKey.of("java-home", Path.class);
    public static final GoalKey<Integer> RELEASE = GoalKey.of("release", Integer.class);

    @SuppressWarnings("rawtypes")
    public static final GoalKey<List> CLASSPATH = GoalKey.of("classpath", List.class);

    @SuppressWarnings("rawtypes")
    public static final GoalKey<List> JAVA_SOURCES = GoalKey.of("java-sources", List.class);

    @SuppressWarnings("rawtypes")
    public static final GoalKey<List> KOTLIN_SOURCES = GoalKey.of("kotlin-sources", List.class);

    @SuppressWarnings("rawtypes")
    public static final GoalKey<List> JAVAC_ARGS = GoalKey.of("javac-args", List.class);

    @SuppressWarnings("rawtypes")
    public static final GoalKey<List> PROCESSOR_CP = GoalKey.of("processor-cp", List.class);

    @SuppressWarnings("rawtypes")
    public static final GoalKey<List> COMPILE_TEST_CP = GoalKey.of("cp-test", List.class);

    @SuppressWarnings("rawtypes")
    public static final GoalKey<List> TEST_RUNTIME_CP = GoalKey.of("cp-runtime", List.class);

    public static final GoalKey<String> ACTION_KEY = GoalKey.of("action-key", String.class);

    @SuppressWarnings("rawtypes")
    public static final GoalKey<List> TEST_SOURCES = GoalKey.of("test-sources", List.class);

    public static final GoalKey<String> BUILD_OUTCOME = GoalKey.of("build-outcome", String.class);
    public static final GoalKey<String> KOTLIN_OUTCOME = GoalKey.of("kotlin-outcome", String.class);
    public static final GoalKey<Path> JAR_PATH = GoalKey.of("jar-path", Path.class);
    public static final GoalKey<Path> MAIN_CLASSES = GoalKey.of("main-classes", Path.class);
    public static final GoalKey<Path> TEST_CLASSES = GoalKey.of("test-classes", Path.class);
    public static final GoalKey<BuildLayout> LAYOUT = GoalKey.of("layout", BuildLayout.class);
    public static final GoalKey<TestSummary> TEST_RESULT =
            GoalKey.of("test-result", TestSummary.class);
    public static final GoalKey<Boolean> NO_TEST_SOURCES = GoalKey.of("no-test-sources", Boolean.class);

    /**
     * Process-wide gate that serializes the {@code run-tests} phase across concurrently-built units
     * (parallel workspace module builds). Tests commonly contend on shared resources — ports, lock
     * files, fixtures — so they run one at a time by default; the request's {@link
     * dev.jkbuild.config.Session#parallelTests()} ({@code --parallel-tests}) lifts the gate.
     *
     * <p>The gate itself remains a per-invocation shared primitive (one process, one build at a
     * time in the CLI); a per-session gate is part of the M1c server-hardening remainder.
     */
    private static final java.util.concurrent.Semaphore TEST_GATE = new java.util.concurrent.Semaphore(1);

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
            boolean testOnly,
            boolean compileOnly,
            // Dirs of the other modules in this build graph / workspace. Used ONLY to compute a
            // project-tier learned rate (EffortWeights.learned): a not-yet-built module borrows the
            // median rate of its sibling modules — same frameworks/fixtures, a closer prior than the
            // whole-host median — before falling back to that host median. Empty (the default) leaves
            // the fallback chain as module → host-median → static, exactly as before.
            Set<Path> projectModules,
            // The request-scoped session (config incl. --force/--refresh, working dir, cache/JDK
            // roots). Threaded so the engine reads request state explicitly instead of the ambient
            // global. Delegating ctors default it from SessionContext.current() at construction.
            dev.jkbuild.config.Session session) {

        /** Back-compat: a full build (not test-only, not compile-only), no project context. */
        public Inputs(
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
                boolean verbose) {
            this(
                    dir,
                    cache,
                    buildFile,
                    lockFile,
                    lockDir,
                    workerCount,
                    estimatedTestCount,
                    profileName,
                    jdksDir,
                    skipTests,
                    verbose,
                    false,
                    false,
                    Set.of(),
                    dev.jkbuild.config.SessionContext.current());
        }

        /** A test-only or full build (not compile-only), no project context. */
        public Inputs(
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
            this(
                    dir,
                    cache,
                    buildFile,
                    lockFile,
                    lockDir,
                    workerCount,
                    estimatedTestCount,
                    profileName,
                    jdksDir,
                    skipTests,
                    verbose,
                    testOnly,
                    false,
                    Set.of(),
                    dev.jkbuild.config.SessionContext.current());
        }

        /** Former canonical arity (testOnly + compileOnly), no project context. */
        public Inputs(
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
                boolean testOnly,
                boolean compileOnly) {
            this(
                    dir,
                    cache,
                    buildFile,
                    lockFile,
                    lockDir,
                    workerCount,
                    estimatedTestCount,
                    profileName,
                    jdksDir,
                    skipTests,
                    verbose,
                    testOnly,
                    compileOnly,
                    Set.of(),
                    dev.jkbuild.config.SessionContext.current());
        }

        /** Copy carrying the project/workspace module set — set by the estimate paths (explain/build). */
        public Inputs withProjectModules(Set<Path> modules) {
            return new Inputs(
                    dir,
                    cache,
                    buildFile,
                    lockFile,
                    lockDir,
                    workerCount,
                    estimatedTestCount,
                    profileName,
                    jdksDir,
                    skipTests,
                    verbose,
                    testOnly,
                    compileOnly,
                    modules == null ? Set.of() : modules,
                    session);
        }
    }

    // ---- progress-bar weights -------------------------------------------
    //
    // Each phase's share of the progress bar, as a rough *time* budget rather
    // than a unit count — so a 300-source compile and a 5-test run each occupy
    // their expected slice instead of the compile (scoped by file count) dwarfing
    // everything. These are relative: the bar normalises against their sum. Tuned
    // for the common warm build (JDK installed, deps cached); the three CPU/IO
    // heavyweights — compile, test-run, test-compile — own the bulk. Phases that
    // report fine-grained progress (sync per-artifact, run-tests per-test) advance
    // smoothly within their slice; opaque ones (javac) fill theirs on completion.
    static final int W_PARSE = 4;
    static final int W_SYNC = 6;
    static final int W_JDK = 3;
    static final int W_COMPILE = 30;
    static final int W_COMPILE_KT = 30;
    static final int W_ASSEMBLE = 2;
    static final int W_RESOURCES = 1;
    static final int W_COMPILE_TEST = 12;
    static final int W_RUN_TESTS = 30;
    static final int W_PACKAGE = 5;
    static final int W_STAMP = 1;
    static final int W_SHADOW = 10; // fat/shadow jar (vs 5 for a plain jar)
    static final int W_SOURCES = 3; // sources jar is lightweight IO
    // A fully-cached module's whole always-run tail (parse + resources + stamps +
    // assemble) collapses to this single touch: it only re-parses the build file and
    // re-checks stamps (~30 ms), so reserving the full static tail (~10 weight ≈ 1.5 s)
    // per cached module piled up phantom wall-clock in the workspace estimate.
    static final int W_CACHED_TOUCH = 1;
    static final int W_NATIVE = 90; // native-image build ≈ 9 steps × 10

    /**
     * Assemble the goal builder with the core build phases (parse → sync → jdk → compile → resources
     * → [test] → package → stamp) plus the shadow/native tails the project's {@code jk.toml}
     * requests. Callers may append further target-specific phases before {@code build()}.
     */
    public static Goal.Builder coreBuilder(Inputs in) {
        return coreBuilder(in, false);
    }

    /**
     * As {@link #coreBuilder(Inputs)} but with a {@code forceRebuild} hint passed to the
     * effort-weight prediction: the workspace pre-scan sets it when the module will rebuild because
     * of an upstream sibling, so the progress bar reserves its real slice up front instead of
     * discovering it mid-build (see {@link EffortWeights#predict(Inputs, Cas, boolean, boolean,
     * boolean, boolean)}).
     */
    public static Goal.Builder coreBuilder(Inputs in, boolean forceRebuild) {
        Cas cas = new Cas(in.cache());
        ActionCache actionCache = new ActionCache(cas, in.cache().resolve("actions"));

        // Compose only the language steps the project uses, so a single-language
        // project never shows a no-op step for the other. Explicit jk.toml
        // opt-ins (java/kotlin) win; otherwise the languages are inferred from
        // the source tree (see CompileSupport.resolveLanguages).
        boolean useKotlin = false;
        boolean useJava = true;
        boolean compactLayout = false;
        boolean workspaceNoSources = false;
        try {
            var jkBuild = JkBuildParser.parse(in.buildFile());
            var project = jkBuild.project();
            CompileSupport.Languages langs = CompileSupport.resolveLanguages(project, in.dir());
            useJava = langs.java();
            useKotlin = langs.kotlin();
            compactLayout = CompileSupport.isSimpleLayout(project, in.dir());
            // Workspace root with no source tree: nothing to compile or package.
            if (jkBuild.isWorkspaceRoot() && !CompileSupport.hasSources(in.dir())) {
                useJava = false;
                useKotlin = false;
                workspaceNoSources = true;
            }
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
        final boolean kotlinModule = useKotlin; // effectively-final copy for lambdas
        String mainCompile = mixed ? "assemble-classes" : (useKotlin ? "compile-kotlin" : "compile-java");

        // Predict each phase's bar weight from the work it will actually do this
        // run (skipped/cached phases collapse to ~1; real work dominates). Computed
        // once, lazily, when the first weight supplier fires during goal-start
        // estimation — so the prediction (stamps/lock/CAS) is read off disk once.
        final java.util.concurrent.atomic.AtomicReference<EffortWeights.Plan> planRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.function.Supplier<EffortWeights.Plan> plan = () -> {
            EffortWeights.Plan p = planRef.get();
            if (p == null) {
                planRef.compareAndSet(
                        null, EffortWeights.predict(in, cas, compact, mixedWithJava, kotlinModule, forceRebuild));
                p = planRef.get();
            }
            return p;
        };

        // Source-list caches shared between the scope suppliers (estimate phase)
        // and the parse-build execute (authoritative collection). The scope fires
        // first (goal-start estimation); parse-build execute reuses the result
        // instead of walking the same directories again. Using AtomicReference
        // with lazy init: whichever side fires first populates the cache; the
        // other side finds the value already set.
        final java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        final Path javaMainSrcDir = compact ? in.dir().resolve("src") : in.dir().resolve("src/main/java");

        // ---- parse-build ------------------------------------------------
        StepContext cx = new StepContext(
                in, cas, actionCache, plan, javaMainSrcRef, kotlinMainSrcRef,
                javaMainSrcDir, compact, mixed, kotlinModule, mixedWithJava, mainCompile);

        Phase parseBuild = parseBuildPhase(cx);

        // ---- sync-deps --------------------------------------------------
        Phase syncDeps = syncDepsPhase(cx);

        // ---- ensure-jdk -------------------------------------------------
        Phase ensureJdk = ensureJdkPhase(cx);

        // ---- compile-java -----------------------------------------------
        Phase compileJava = compileJavaPhase(cx);

        // ---- compile-kotlin ---------------------------------------------
        Phase compileKotlin = compileKotlinPhase(cx);

        // ---- copy-resources ---------------------------------------------
        Phase copyResources = copyResourcesPhase(cx);

        // ---- compile-test -----------------------------------------------
        Phase compileTest = compileTestPhase(cx);

        // ---- run-tests --------------------------------------------------
        Phase runTests = runTestsPhase(cx);

        // ---- package-jar ------------------------------------------------
        Phase packageJar = packageJarPhase(cx);

        // ---- write-stamp ------------------------------------------------
        Phase writeStamp = writeStampPhase(cx);

        // ---- write-stamp-kotlin -----------------------------------------
        // Kotlin's freshness companion (cf. write-stamp for Java). Mirrors the
        // input set compile-kotlin checked: Kotlin sources, plus Java sources in
        // a mixed module. No action-cache key exists yet — the direct kotlinc
        // path leaves it empty until incremental Kotlin lands.
        Phase writeStampKotlin = writeStampKotlinPhase(cx);

        // ---- assemble-classes (mixed modules only) ----------------------
        // Merge the per-language output dirs into the shared classes dir that
        // packaging, tests, and the run/native tails all read.
        Phase assembleClasses = assembleClassesPhase(cx);

        Goal.Builder b =
                Goal.builder("build").addPhase(parseBuild).addPhase(syncDeps).addPhase(ensureJdk);
        // Workspace root with no sources: validate jk.toml + sync deps, nothing more.
        if (workspaceNoSources) return b;
        if (useJava) {
            b.addPhase(compileJava);
        }
        if (useKotlin) {
            b.addPhase(compileKotlin);
        }
        if (mixed) {
            b.addPhase(assembleClasses);
        }
        // `jk compile` stops here: lock → sync → compile (+ freshness stamps),
        // no resources/test/package. Everything later depends on these phases.
        if (in.compileOnly()) {
            if (useJava) {
                b.addPhase(writeStamp);
            }
            if (useKotlin) {
                b.addPhase(writeStampKotlin);
            }
            return b;
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
     * The build-scoped services, estimation state, and layout flags shared by every core phase —
     * the explicit replacement for the effectively-final locals the phase lambdas used to capture.
     */
    private record StepContext(
            Inputs in,
            Cas cas,
            ActionCache actionCache,
            java.util.function.Supplier<EffortWeights.Plan> plan,
            java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef,
            java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef,
            Path javaMainSrcDir,
            boolean compact,
            boolean mixed,
            boolean kotlinModule,
            boolean mixedWithJava,
            String mainCompile) {}

    private static Phase parseBuildPhase(StepContext cx) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Phase.builder("parse-build")
                .label("Parsing")
                .weight(() -> plan.get().fullyCached() ? W_CACHED_TOUCH : W_PARSE)
                .scope(() -> {
                    if (Files.exists(in.lockFile())) {
                        try {
                            return LockfileReader.read(in.lockFile())
                                            .artifacts()
                                            .size()
                                    + 5;
                        } catch (Exception ignored) {
                        }
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
                        LockFlow.Result result;
                        try {
                            result = LockFlow.run(in.lockDir(), in.cache(), List.of(), true, null);
                        } catch (UnsatisfiableException e) {
                            ctx.error("verbatim", e.getMessage());
                            throw new RuntimeException("dependency resolution failed");
                        }
                        if (result.status() != 0) {
                            ctx.error("verbatim", result.error() != null ? result.error() : "dependency resolution failed");
                            throw new RuntimeException("lock failed");
                        }
                        ctx.put(LOCKFILE, result.lockfile());
                    } else if (AutoLock.isStale(in.dir(), in.lockFile())) {
                        ctx.label("jk.toml changed — updating lock");
                        Lockfile existing = LockfileReader.read(in.lockFile());
                        Lockfile updated = AutoLock.maybeReLock(
                                in.dir(),
                                existing,
                                in.lockFile(),
                                in.cache(),
                                null,
                                dev.jkbuild.util.JkVersion.VERSION,
                                List.of(),
                                true,
                                dev.jkbuild.resolver.ResolveObserver.NOOP,
                                ctx::output);
                        ctx.put(LOCKFILE, updated != null ? updated : existing);
                    } else {
                        ctx.put(LOCKFILE, LockfileReader.read(in.lockFile()));
                    }

                    Lockfile lock = ctx.require(LOCKFILE);
                    // Reading the lock keeps its deps fresh against the 90-day cache GC.
                    dev.jkbuild.task.AccessLedger.atDefaultPath().touchLock(lock);
                    ctx.label("resolve classpath");
                    ClasspathResolver resolver = new ClasspathResolver(cas);

                    WorkspaceClasspath.Result mainSiblings =
                            WorkspaceClasspath.resolve(in.dir(), project, Set.of(Scope.EXPORT, Scope.MAIN));
                    if (!mainSiblings.missingSiblingJars().isEmpty()) {
                        for (String missing : mainSiblings.missingSiblingJars())
                            ctx.error("workspace", "sibling not built — " + missing);
                        throw new RuntimeException("missing workspace siblings");
                    }
                    // Lockfile + sibling jars + siblings' transitive lockfile deps — the
                    // exact classpath `jk explain` re-derives, so the action keys match.
                    List<Path> mainCp = mainCompileClasspath(lock, resolver, mainSiblings);

                    Profile profile = CompileSupport.resolveProfile(project.profiles(), in.profileName());
                    // Default lint (deprecation/unchecked) unless [build] lint = false;
                    // the profile's own javac args win (appended after). Shared by the
                    // main- and test-compile phases (both read JAVAC_ARGS).
                    ctx.put(
                            JAVAC_ARGS,
                            dev.jkbuild.compile.JavacLint.effectiveArgs(
                                    project.build().lint(), profile == null ? List.of() : profile.javacArgs()));
                    ctx.put(CLASSPATH, mainCp);

                    // Annotation processors live in their own scope (kept off the
                    // compile classpath); javac discovers them via -processorpath.
                    ctx.put(PROCESSOR_CP, new ArrayList<>(resolver.classpathFor(lock, Set.of(Scope.PROCESSOR))));

                    WorkspaceClasspath.Result testSiblings =
                            WorkspaceClasspath.resolve(in.dir(), project, Set.of(Scope.EXPORT, Scope.MAIN, Scope.TEST));
                    List<Path> compileTestCp =
                            new ArrayList<>(resolver.classpathFor(lock, ClasspathResolver.COMPILE_TEST));
                    compileTestCp.addAll(testSiblings.jars());
                    List<Path> testRuntimeCp = new ArrayList<>(resolver.classpathFor(lock, ClasspathResolver.TEST));
                    testRuntimeCp.addAll(testSiblings.jars());
                    // A sibling's own external deps (e.g. resolver's maven-artifact) must
                    // also reach the test classpath, or tests exercising sibling code hit
                    // NoClassDefFoundError. Mirrors the main-cp sibling-lockfile loop above.
                    for (java.nio.file.Path sibLock : testSiblings.siblingLockfiles()) {
                        try {
                            dev.jkbuild.lock.Lockfile sl = dev.jkbuild.lock.LockfileReader.read(sibLock);
                            for (Path p : resolver.classpathFor(sl, ClasspathResolver.COMPILE_MAIN)) {
                                if (!compileTestCp.contains(p)) compileTestCp.add(p);
                            }
                            for (Path p : resolver.classpathFor(sl, ClasspathResolver.RUNTIME)) {
                                if (!testRuntimeCp.contains(p)) testRuntimeCp.add(p);
                            }
                        } catch (Exception ignored) {
                            /* best-effort */
                        }
                    }
                    ctx.put(COMPILE_TEST_CP, compileTestCp);
                    ctx.put(TEST_RUNTIME_CP, testRuntimeCp);
                    // Reuse source lists that the scope suppliers may have already walked.
                    // If the scope hasn't fired yet (unusual ordering), populate and cache now.
                    List<Path> javaMainSrcs = javaMainSrcRef.get();
                    if (javaMainSrcs == null) {
                        javaMainSrcs = CompileSupport.collectJavaSources(javaMainSrcDir);
                        javaMainSrcRef.compareAndSet(null, javaMainSrcs);
                        javaMainSrcs = javaMainSrcRef.get();
                    }
                    ctx.put(JAVA_SOURCES, javaMainSrcs);
                    List<Path> kotlinMainSrcs = kotlinMainSrcRef.get();
                    if (kotlinMainSrcs == null) {
                        kotlinMainSrcs = CompileSupport.collectKotlinSources(in.dir(), compact);
                        kotlinMainSrcRef.compareAndSet(null, kotlinMainSrcs);
                        kotlinMainSrcs = kotlinMainSrcRef.get();
                    }
                    ctx.put(KOTLIN_SOURCES, kotlinMainSrcs);
                    ctx.put(RELEASE, project.project().javaRelease());
                    ctx.put(JAVA_HOME, JavaHomes.resolveJavaHome(in.dir()));
                    ctx.put(MAIN_CLASSES, layout.classesDir());
                    ctx.put(TEST_CLASSES, layout.testClassesDir());
                    ctx.progress(1);
                })
                .build();
    }

    private static Phase syncDepsPhase(StepContext cx) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Phase.builder("sync-deps")
                .label("Syncing")
                .kind(PhaseKind.IO)
                .requires("parse-build")
                .weight(() -> plan.get().sync())
                .scope(() -> {
                    try {
                        return LockfileReader.read(in.lockFile()).artifacts().size();
                    } catch (Exception ignored) {
                        return 10;
                    }
                })
                .execute(ctx -> {
                    Lockfile lock = ctx.require(LOCKFILE);
                    // Scope is already counted up front by estimateScope() (artifact
                    // count); progress(1)-per-artifact below fills it.
                    var observer = new CacheSync.ProgressObserver() {
                        @Override
                        public void fetched(Lockfile.Artifact pkg) {
                            ctx.label("fetched " + pkg.name());
                            ctx.progress(1);
                        }

                        @Override
                        public void upToDate(Lockfile.Artifact pkg) {
                            ctx.progress(1);
                        }

                        @Override
                        public void skipped(Lockfile.Artifact pkg) {
                            ctx.progress(1);
                        }

                        @Override
                        public void failed(Lockfile.Artifact pkg, String err) {
                            ctx.error("dep", pkg.name() + " — " + err);
                            ctx.progress(1);
                        }
                    };
                    boolean refresh = in.session().config().refreshOr(false);
                    var report = new CacheSync(cas, new Http()).sync(lock, observer, refresh);
                    if (report.hasErrors()) throw new RuntimeException("dep sync had errors");
                })
                .build();
    }

    private static Phase ensureJdkPhase(StepContext cx) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Phase.builder("ensure-jdk")
                .label("JDK")
                .kind(PhaseKind.IO)
                .requires("parse-build")
                .weight(() -> EffortWeights.jdkWeight(in.dir(), in.jdksDir()))
                .scope(1)
                .execute(ctx -> {
                    ctx.label("resolve JDK");
                    Lockfile lock = ctx.require(LOCKFILE);
                    JkBuild project = ctx.require(PROJECT);
                    try {
                        JdkEnsure.ensure(in.dir(), in.jdksDir(), project, lock, m -> ctx.warn("jdk", m));
                    } catch (Exception e) {
                        ctx.error("jdk", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();
    }

    private static Phase compileJavaPhase(StepContext cx) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Phase.builder("compile-java")
                .label("Compiling")
                .kind(PhaseKind.CPU)
                .requires(
                        mixed
                                ? new String[] {"parse-build", "sync-deps", "ensure-jdk", "compile-kotlin"}
                                : new String[] {"parse-build", "sync-deps", "ensure-jdk"})
                // Scope counts sources (granularity); weight is the bar share. javac
                // is opaque — one progress(sources.size()) on completion — so ease the
                // slice forward over time while it runs instead of sitting flat.
                .weight(() -> plan.get().compileJava())
                .interpolated()
                .scope(() -> {
                    // Populate the cache if not yet done (may have been filled by
                    // parse-build execute or by EffortWeights.predict via collectJavaSources).
                    List<Path> srcs = javaMainSrcRef.get();
                    if (srcs == null) {
                        try {
                            srcs = CompileSupport.collectJavaSources(javaMainSrcDir);
                        } catch (Exception ignored) {
                            srcs = List.of();
                        }
                        javaMainSrcRef.compareAndSet(null, srcs);
                        srcs = javaMainSrcRef.get();
                    }
                    return srcs.size();
                })
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
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    if (mixed) {
                        // See Kotlin's output so Java can reference Kotlin types.
                        classpath = new ArrayList<>(classpath);
                        classpath.add(ctx.require(LAYOUT).kotlinClassesDir());
                    }
                    @SuppressWarnings("unchecked")
                    List<Path> processorCp = (List<Path>) ctx.require(PROCESSOR_CP);
                    boolean rerun = in.session().config().rerunOr(false);
                    // Fold the processor path into the freshness inputs so a processor
                    // bump busts the stamp (it isn't on the compile classpath).
                    List<Path> stampInputs = classpath;
                    if (!processorCp.isEmpty()) {
                        stampInputs = new ArrayList<>(classpath);
                        stampInputs.addAll(processorCp);
                    }
                    if (!rerun
                            && dev.jkbuild.task.FreshnessStamp.isFresh(
                                    javaOut,
                                    dev.jkbuild.task.FreshnessStamp.JAVA_STAMP,
                                    sources,
                                    stampInputs,
                                    ctx.require(RELEASE))) {
                        ctx.reweight(EffortWeights.SKIP); // nothing to compile this run
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
                            .outputDir(javaOut)
                            .release(ctx.require(RELEASE))
                            .extraOptions(javacArgs)
                            .javaHome(ctx.require(JAVA_HOME))
                            .processorPath(processorCp)
                            .build();
                    String taskId = ActionKey.qualifiedTaskId("compile-main", javaOut);
                    Path javaStateDir = in.cache()
                            .resolve("actions")
                            .resolve("incremental-java")
                            .resolve(taskId);
                    // Reweight the bar slice now that the real request is known: a CAS
                    // action-cache hit means a cheap hard-link restore (3), not a full
                    // javac (ceil(sources × 0.1)). Uses the exact key
                    // JavaIncrementalCompile will look up, so the estimate matches what
                    // actually happens — no goal-start reconstruction divergence.
                    if (!rerun) {
                        try {
                            boolean restores = actionCache
                                    .lookup(ActionKey.forJavac(taskId, request, dev.jkbuild.util.JkVersion.VERSION))
                                    .isPresent();
                            ctx.reweight(
                                    restores ? EffortWeights.RESTORE : EffortWeights.compileWeight(sources.size()));
                        } catch (Exception ignored) {
                            /* keep the up-front estimate */
                        }
                    }
                    // With processors declared, hand the incremental compiler an AP setup:
                    // a *lazy* worker-jar resolver + a stable generated-sources dir. The
                    // engine routes through the worker only once it has detected
                    // source-generating processors, so bytecode-only processors (e.g.
                    // Lombok) and first builds never resolve it — which matters because
                    // a jk build that didn't bundle the worker (or its sha resource)
                    // would otherwise fail here even though the worker isn't needed.
                    // When it *is* needed but unavailable, warn once and fall back to
                    // plain javac (correct, just without incremental AP provenance).
                    dev.jkbuild.task.JavaIncrementalCompile.ApSetup ap = null;
                    if (!processorCp.isEmpty()) {
                        Path genDir = ctx.require(LAYOUT).generatedSourcesDir("annotations");
                        Files.createDirectories(genDir);
                        ap = new dev.jkbuild.task.JavaIncrementalCompile.ApSetup(
                                () -> {
                                    try {
                                        return WorkerJar.JAVA_COMPILER.locate(cas);
                                    } catch (RuntimeException e) {
                                        ctx.warn(
                                                "javac",
                                                "java-compiler worker unavailable ("
                                                        + e.getMessage()
                                                        + "); compiling with plain javac"
                                                        + " (no incremental annotation-processing provenance)");
                                        return null;
                                    }
                                },
                                genDir);
                    }
                    ctx.label("compiling " + sources.size() + " sources");
                    dev.jkbuild.task.JavaIncrementalCompile.Result r = dev.jkbuild.task.JavaIncrementalCompile.run(
                            taskId,
                            request,
                            dev.jkbuild.util.JkVersion.VERSION,
                            !rerun,
                            cas,
                            actionCache,
                            javaStateDir,
                            ap);
                    ctx.put(ACTION_KEY, r.actionKey());
                    // Forward every javac diagnostic to the terminal, by severity:
                    // errors fail the build, warnings/notes (e.g. deprecation) are
                    // surfaced but don't. Strip the leading severity word — the
                    // console renderer adds its own ✗/⚠ marker.
                    boolean errored = false;
                    for (CompileResult.Diagnostic d : r.diagnostics()) {
                        if (d.severity() == CompileResult.Severity.ERROR) {
                            ctx.error("javac", d.describe());
                            errored = true;
                        } else {
                            ctx.warn("javac", d.describe());
                        }
                    }
                    if (!r.success()) {
                        // Never fail silently: if no ERROR diagnostic surfaced (crash,
                        // swallowed output), say so explicitly.
                        if (!errored) {
                            ctx.error("javac", "compile failed without compiler diagnostics (outcome: " + r.outcome() + ")");
                        }
                        throw new RuntimeException("javac reported errors");
                    }
                    if (r.cacheHit()) ctx.label("cache hit " + r.actionKey().substring(0, 8));
                    ctx.put(BUILD_OUTCOME, r.outcome());
                    ctx.progress(sources.size());
                })
                .build();
    }

    private static Phase compileKotlinPhase(StepContext cx) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Phase.builder("compile-kotlin")
                .label("Kotlin")
                .kind(PhaseKind.CPU)
                // Kotlin compiles first (reads Java declarations from source), so it
                // only needs the base phases — javac runs after it in a mixed module.
                .requires("parse-build", "sync-deps", "ensure-jdk")
                // Scope counts Kotlin sources (granularity); weight is the bar share
                // (see compile-java). kotlinc is opaque too, so ease it over time.
                .weight(() -> plan.get().compileKotlin())
                .interpolated()
                .scope(() -> {
                    List<Path> srcs = kotlinMainSrcRef.get();
                    if (srcs == null) {
                        try {
                            srcs = CompileSupport.collectKotlinSources(in.dir(), compact);
                        } catch (Exception ignored) {
                            srcs = List.of();
                        }
                        kotlinMainSrcRef.compareAndSet(null, srcs);
                        srcs = kotlinMainSrcRef.get();
                    }
                    return srcs.size();
                })
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    Files.createDirectories(classes); // compile-java may be skipped
                    List<Path> ktSources = kotlinSources(ctx);
                    if (ktSources.isEmpty()) {
                        ctx.label("no Kotlin sources");
                        ctx.put(KOTLIN_OUTCOME, "no-sources");
                        return;
                    }
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
                    boolean rerun = in.session().config().rerunOr(false);
                    if (!rerun
                            && dev.jkbuild.task.FreshnessStamp.isFresh(
                                    classes,
                                    dev.jkbuild.task.FreshnessStamp.KOTLIN_STAMP,
                                    freshInputs,
                                    classpath,
                                    ctx.require(RELEASE))) {
                        ctx.reweight(EffortWeights.SKIP); // nothing to compile this run
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
                    Path workingDir = in.cache()
                            .resolve("actions")
                            .resolve("incremental-kotlin")
                            .resolve(taskId);
                    // Mixed module: Kotlin reads the Java declarations from source
                    // (analysis only — it emits no Java bytecode; javac does next).
                    dev.jkbuild.task.KotlinCompile.Result kr = compileKotlinSources(
                            ctx,
                            in,
                            cas,
                            actionCache,
                            ktSources,
                            classpath,
                            ktOut,
                            taskId,
                            workingDir,
                            mixedWithJava
                                    ? (compact
                                            ? in.dir().resolve("src")
                                            : in.dir().resolve("src/main/java"))
                                    : null);
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
    }

    private static Phase copyResourcesPhase(StepContext cx) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Phase.builder("copy-resources")
                .label("Resources")
                .kind(PhaseKind.CPU)
                .requires(mainCompile)
                .weight(() -> plan.get().fullyCached() ? 0 : W_RESOURCES)
                .scope(1)
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    Path resMain = in.dir().resolve("src/main/resources");
                    if (!Files.exists(resMain)) {
                        ctx.label("no resources");
                        return;
                    }
                    ctx.label("copy resources");
                    copyResources(resMain, classes);
                    ctx.progress(1);
                })
                .build();
    }

    private static Phase compileTestPhase(StepContext cx) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Phase.builder("compile-test")
                .label("Test Compile")
                .kind(PhaseKind.CPU)
                .requires(mainCompile, "sync-deps")
                .weight(() -> plan.get().compileTest())
                .interpolated() // opaque javac/kotlinc call — ease it over time
                .scope(1)
                .execute(ctx -> {
                    Path javaTestSrc =
                            compact ? in.dir().resolve("test") : in.dir().resolve("src/test/java");
                    List<Path> javaTest = CompileSupport.collectJavaSources(javaTestSrc);
                    List<Path> ktTest = CompileSupport.collectKotlinTestSources(in.dir(), compact);
                    if (javaTest.isEmpty() && ktTest.isEmpty()) {
                        ctx.label("no test sources");
                        ctx.put(NO_TEST_SOURCES, true);
                        ctx.progress(1);
                        return;
                    }
                    // Store combined test sources for the TestStamp in run-tests.
                    List<Path> allTestSources = new ArrayList<>();
                    allTestSources.addAll(javaTest);
                    allTestSources.addAll(ktTest);
                    ctx.put(TEST_SOURCES, allTestSources);
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
                    Path ktTestOut = mixedTest ? ctx.require(LAYOUT).kotlinTestClassesDir() : testClasses;
                    if (!ktTest.isEmpty()) {
                        ctx.label("compiling " + ktTest.size() + " Kotlin test sources");
                        String ktTaskId = ActionKey.qualifiedTaskId("compile-test-kotlin", testClasses);
                        Path ktWorkingDir = in.cache()
                                .resolve("actions")
                                .resolve("incremental-kotlin")
                                .resolve(ktTaskId);
                        dev.jkbuild.task.KotlinCompile.Result kr = compileKotlinSources(
                                ctx,
                                in,
                                cas,
                                actionCache,
                                ktTest,
                                baseCp,
                                ktTestOut,
                                ktTaskId,
                                ktWorkingDir,
                                mixedTest ? javaTestSrc : null);
                        if (!kr.success()) {
                            ctx.error("kotlinc", kr.output());
                            throw new RuntimeException("test kotlinc reported errors");
                        }
                    }

                    // Java test sources, against the Kotlin test output in a mixed module.
                    if (!javaTest.isEmpty()) {
                        Path javaTestOut = testClasses; // javac always writes to java/test/
                        List<Path> javaCp = baseCp;
                        if (mixedTest) {
                            javaCp = new ArrayList<>(baseCp);
                            javaCp.add(ktTestOut);
                        }
                        @SuppressWarnings("unchecked")
                        List<String> javacArgs = (List<String>) ctx.require(JAVAC_ARGS);
                        // Run the same declared annotation processors over test sources:
                        // modern javac only honors processors named by -processorpath, so
                        // without this a Lombok-using test wouldn't see its generated modules.
                        @SuppressWarnings("unchecked")
                        List<Path> processorCp = (List<Path>) ctx.require(PROCESSOR_CP);
                        dev.jkbuild.task.JavaIncrementalCompile.ApSetup ap = null;
                        if (!processorCp.isEmpty()) {
                            Path genDir = ctx.require(LAYOUT).generatedSourcesDir("annotations", "test");
                            Files.createDirectories(genDir);
                            ap = new dev.jkbuild.task.JavaIncrementalCompile.ApSetup(
                                    () -> {
                                        try {
                                            return WorkerJar.JAVA_COMPILER.locate(cas);
                                        } catch (RuntimeException e) {
                                            ctx.warn(
                                                    "javac",
                                                    "java-compiler worker unavailable ("
                                                            + e.getMessage()
                                                            + "); compiling tests with plain javac"
                                                            + " (no incremental annotation-processing provenance)");
                                            return null;
                                        }
                                    },
                                    genDir);
                        }
                        boolean ok = TestSupport.compileWithCache(
                                ctx,
                                "compile-test",
                                javaTestSrc,
                                javaTestOut,
                                javaCp,
                                processorCp,
                                ctx.require(RELEASE),
                                javacArgs,
                                ctx.require(JAVA_HOME),
                                ap,
                                cas,
                                in.cache());
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
    }

    private static Phase runTestsPhase(StepContext cx) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Phase.builder("run-tests")
                .label("Testing")
                .kind(PhaseKind.IO)
                .requires("compile-test", "copy-resources")
                .weight(() -> plan.get().runTests())
                .scope(in.estimatedTestCount())
                .execute(ctx -> {
                    if (ctx.get(NO_TEST_SOURCES).orElse(false)) {
                        ctx.label("no tests to run");
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    List<Path> testRtCp = (List<Path>) ctx.require(TEST_RUNTIME_CP);
                    Path testClassesForStamp = ctx.require(TEST_CLASSES);
                    @SuppressWarnings("unchecked")
                    List<Path> testSrcs = ctx.get(TEST_SOURCES).orElse(java.util.List.of());
                    // Worker jars handed to the test JVM ([build.test-worker-jars]) —
                    // worker-forking tests' behavior depends on their content, so resolve
                    // them up front so they also feed the freshness key below.
                    Map<String, String> workerJars = workerJarProps(
                            in.dir(), ctx.require(PROJECT).build().testWorkerJars());

                    // Incremental test skip: a content key over every input that affects
                    // the outcome — own main output, test sources, the *content* of the
                    // runtime classpath (sibling modules included), the lock, and the
                    // toolchain/runner/worker identity. Unchanged → skip the runner.
                    String stampKey = dev.jkbuild.task.TestStamp.computeKey(
                            testSrcs, ctx.require(MAIN_CLASSES), in.lockFile(), testRtCp, testStampExtras(workerJars));
                    String testTaskId = ActionKey.qualifiedTaskId("run-tests", testClassesForStamp);
                    // --force forces a real test run, matching the compile/package
                    // freshness checks above (which all guard on !rerun). Without
                    // this guard the action record would skip the runner even when
                    // the user explicitly asked to bypass build caches.
                    boolean rerun = in.session().config().rerunOr(false);
                    // The "tests passed for this input" marker lives in the CAS (keyed by
                    // the content key), NOT in target/ — so it survives `jk clean`: a later
                    // build that restores byte-identical classes recomputes the same key and
                    // skips the runner, mirroring how the compile cache survives clean.
                    // stampKey is null only when computeKey failed open (unreadable
                    // input) — treat that as "not cached" and run the tests.
                    if (!rerun
                            && stampKey != null
                            && actionCache.lookup(stampKey).isPresent()) {
                        ctx.reweight(EffortWeights.SKIP);
                        ctx.label("tests up-to-date");
                        return; // skip — nothing changed since last green run
                    }
                    // Tests are actually running: claim the real test slice, symmetric
                    // to how compile reweights itself up. Without this a phase the
                    // forecast under-sized (predicted SKIP, but the CAS marker was
                    // missing so we run) stays pinned near-zero while the slow,
                    // serialized run executes — the "stuck near 100% during tests" bug.
                    // Reweight through the SAME learned ledger predict() used, NOT the
                    // raw static per-method floor: the static constant is ~10× hot for a
                    // fast suite, so reweighting to it ballooned the denominator (574
                    // tests × 8 → ~11 min) the instant testing began, then collapsed as
                    // the quick tests flew by — the wildly-jumping ETA. Learned == the
                    // up-front estimate, so a correctly-forecast phase reweights to the
                    // same value (a no-op) and the countdown stays steady.
                    ctx.reweight(EffortWeights.learned(
                            PhaseTimings.load(in.cache()),
                            in.dir().toString(),
                            "run-tests",
                            in.estimatedTestCount(),
                            EffortWeights.runTestsWeight(in.estimatedTestCount()),
                            in.projectModules().stream().map(Path::toString).toList()));
                    List<Path> runtimeCp = new ArrayList<>();
                    runtimeCp.add(ctx.require(MAIN_CLASSES));
                    runtimeCp.addAll(testRtCp);
                    // Kotlin output (main or test) needs the stdlib at runtime.
                    if (kotlinModule
                            || !CompileSupport.collectKotlinTestSources(in.dir(), compact)
                                    .isEmpty()) {
                        runtimeCp.add(kotlinStdlib(ctx, cas));
                    }

                    TestProgressListener listener = TestSupport.bridgeListener(ctx, in.workerCount(), in.verbose());
                    TestSummary result;
                    // Serialize test execution across concurrently-built units unless the
                    // user opted into parallel tests — shared ports/locks/fixtures.
                    boolean gated = !in.session().parallelTests();
                    if (gated) TEST_GATE.acquireUninterruptibly();
                    try {
                        result = new JUnitLauncher()
                                .run(
                                        ctx.require(JAVA_HOME),
                                        ctx.require(TEST_CLASSES),
                                        runtimeCp,
                                        in.cache(),
                                        in.workerCount(),
                                        workerJars,
                                        listener,
                                        ctx.require(LAYOUT).testResultsDir());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        ctx.error("test", "interrupted");
                        throw new RuntimeException(e);
                    } catch (IOException e) {
                        ctx.error("test", e.getMessage());
                        throw e;
                    } finally {
                        if (gated) TEST_GATE.release();
                    }
                    ctx.put(TEST_RESULT, result);
                    if (!result.allPassed()) {
                        // No marker on failure — the next build re-runs (the absence
                        // of a record for this key is the "not yet green" signal).
                        // Surface each failure (name + stack trace) above the bar —
                        // not just the count — like Maven/Gradle.
                        for (String line : TestSupport.renderFailures(result)) ctx.output(line);
                        throw new RuntimeException(
                                result.failed() + " test failure" + (result.failed() == 1 ? "" : "s"));
                    }
                    // All tests passed — record a CAS marker keyed by the content key so a
                    // later build skips the runner when inputs are unchanged. It lives in the
                    // CAS (not target/), so it survives `jk clean`: after clean+build the
                    // compile cache restores byte-identical classes, the key recomputes the
                    // same, and the marker is found. Output-less — the key's presence is the
                    // result. (Skip if the key failed open — nothing to key the marker on.)
                    if (stampKey != null) {
                        actionCache.storeWithOutputs(testTaskId, stampKey, java.util.Map.of(), java.util.Map.of());
                    }
                })
                .build();
    }

    private static Phase packageJarPhase(StepContext cx) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Phase.builder("package-jar")
                .label("Packaging")
                .kind(PhaseKind.CPU)
                .requires(
                        in.skipTests() ? new String[] {"copy-resources"} : new String[] {"copy-resources", "run-tests"})
                .weight(() -> plan.get().pkg())
                .scope(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path classes = ctx.require(MAIN_CLASSES);
                    Path jarPath = layout.mainJar();
                    Files.createDirectories(jarPath.getParent());
                    String mainClass = project.project().main();
                    // Packaging cache: the jar is a pure function of the main classes
                    // (resources already copied in), the main-class, and the manifest.
                    List<String> tokens = List.of(
                            "classes:" + dev.jkbuild.task.ClasspathFingerprint.entry(classes),
                            "main:" + (mainClass == null ? "" : mainClass),
                            "manifest:" + project.manifest());
                    String pkgTask = ActionKey.qualifiedTaskId("package-jar", jarPath);
                    String pkgKey = ActionKey.forArtifact(pkgTask, dev.jkbuild.util.JkVersion.VERSION, tokens);
                    if (restorePackaged(in.cache(), pkgKey, jarPath.getParent())) {
                        ctx.put(JAR_PATH, jarPath);
                        ctx.label(jarPath.getFileName() + " up-to-date");
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("package " + jarPath.getFileName());
                    JarPackager.JarRequest jarRequest = JarPackager.JarRequest.of(classes, jarPath);
                    if (mainClass != null && !mainClass.isBlank()) jarRequest = jarRequest.withMainClass(mainClass);
                    if (!project.manifest().isEmpty()) jarRequest = jarRequest.withAttributes(project.manifest());
                    new JarPackager().packageJar(jarRequest);
                    storePackaged(in.cache(), pkgTask, pkgKey, tokens, jarPath.getParent(), List.of(jarPath));
                    ctx.put(JAR_PATH, jarPath);
                    ctx.progress(1);
                })
                .build();
    }

    private static Phase writeStampPhase(StepContext cx) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Phase.builder("write-stamp")
                .requires("compile-java")
                .weight(() -> plan.get().fullyCached() ? 0 : W_STAMP)
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
                    Path javaOut = classes; // javac always writes to java/main/
                    @SuppressWarnings("unchecked")
                    List<Path> sources = (List<Path>) ctx.require(JAVA_SOURCES);
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    if (mixed) { // match compile-java's freshness inputs
                        classpath = new ArrayList<>(classpath);
                        classpath.add(ctx.require(LAYOUT).kotlinClassesDir());
                    }
                    String actionKey = ctx.get(ACTION_KEY).orElse("");
                    dev.jkbuild.task.FreshnessStamp.write(
                            javaOut,
                            dev.jkbuild.task.FreshnessStamp.JAVA_STAMP,
                            "compile-main",
                            actionKey,
                            sources,
                            classpath,
                            ctx.require(RELEASE));
                    ctx.progress(1);
                })
                .build();
    }

    private static Phase writeStampKotlinPhase(StepContext cx) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Phase.builder("write-stamp-kotlin")
                .requires("compile-kotlin")
                .weight(() -> plan.get().fullyCached() ? 0 : W_STAMP)
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
                            classes,
                            dev.jkbuild.task.FreshnessStamp.KOTLIN_STAMP,
                            "compile-kotlin",
                            "",
                            freshInputs,
                            classpath,
                            ctx.require(RELEASE));
                    ctx.progress(1);
                })
                .build();
    }

    private static Phase assembleClassesPhase(StepContext cx) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        ActionCache actionCache = cx.actionCache();
        java.util.function.Supplier<EffortWeights.Plan> plan = cx.plan();
        java.util.concurrent.atomic.AtomicReference<List<Path>> javaMainSrcRef = cx.javaMainSrcRef();
        java.util.concurrent.atomic.AtomicReference<List<Path>> kotlinMainSrcRef = cx.kotlinMainSrcRef();
        Path javaMainSrcDir = cx.javaMainSrcDir();
        boolean compact = cx.compact();
        boolean mixed = cx.mixed();
        boolean kotlinModule = cx.kotlinModule();
        boolean mixedWithJava = cx.mixedWithJava();
        String mainCompile = cx.mainCompile();
        return Phase.builder("assemble-classes")
                .label("Assembling")
                .kind(PhaseKind.CPU)
                .requires("compile-java", "compile-kotlin")
                .weight(() -> plan.get().fullyCached() ? 0 : W_ASSEMBLE)
                .scope(1)
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    String jOutcome = ctx.get(BUILD_OUTCOME).orElse("");
                    String kOutcome = ctx.get(KOTLIN_OUTCOME).orElse("");
                    boolean settled = (jOutcome.equals("up-to-date") || jOutcome.equals("no-sources"))
                            && (kOutcome.equals("up-to-date") || kOutcome.equals("no-sources"));
                    if (settled) { // both unchanged → classes already holds both
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
    }

    /**
     * Append the artifact tails the project's {@code jk.toml} declares: {@code shadow = true} →
     * fat-jar phase. {@code jk build} / {@code jk run} / {@code jk install} call this after {@link
     * #coreBuilder}.
     *
     * <p>Native images are <em>not</em> appended here: {@code native = true} makes a project
     * native-eligible, but a native artifact is only ever built by {@code jk native} (which composes
     * the native tail explicitly) or by {@code jk install} of a native application (which adds the
     * phase itself, resolving GraalVM up front). {@code jk build} stays JVM-only.
     */
    public static void appendDeclaredTails(Goal.Builder b, Inputs in) {
        try {
            JkBuild project = JkBuildParser.parse(in.buildFile());
            if (project.project().shadow()) {
                b.addPhase(shadowPhase(in.cache(), in.lockFile()));
            }
            if (project.project().sourcesMode() == JkBuild.SourcesMode.ALWAYS) {
                b.addPhase(sourcesPhase(in.cache()));
            }
        } catch (Exception ignored) {
        }
    }

    // ---- tail phases ----------------------------------------------------

    /** Fat-jar (shadow) packaging — requires package-jar. */
    public static Phase shadowPhase(Path cache, Path lockFile) {
        return Phase.builder("package-shadow")
                .label("Shadow")
                .kind(PhaseKind.CPU)
                .requires("package-jar")
                .weight(() -> EffortWeights.shadowWeight(lockFile.getParent()))
                .scope(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path classes = ctx.require(MAIN_CLASSES);
                    Path shadowJar = layout.shadowJar();
                    List<Path> depJars = new ArrayList<>();
                    if (Files.exists(lockFile)) {
                        ClasspathResolver resolver = new ClasspathResolver(new Cas(cache));
                        depJars.addAll(resolver.classpathFor(LockfileReader.read(lockFile), ClasspathResolver.RUNTIME));
                        // Workspace siblings are filtered out of the lockfile by
                        // WorkspaceMerge, but a fat jar must bundle them (and their
                        // own transitive external deps) or it can't run standalone —
                        // e.g. a worker jar would be missing PluginWorkerMain.
                        WorkspaceClasspath.Result siblings = WorkspaceClasspath.resolve(
                                layout.moduleRoot(), project, Set.of(Scope.EXPORT, Scope.MAIN));
                        for (Path j : siblings.jars()) {
                            if (!depJars.contains(j)) depJars.add(j);
                        }
                        for (Path sibLock : siblings.siblingLockfiles()) {
                            try {
                                for (Path p : resolver.classpathFor(
                                        LockfileReader.read(sibLock), ClasspathResolver.RUNTIME)) {
                                    if (!depJars.contains(p)) depJars.add(p);
                                }
                            } catch (Exception ignored) {
                                /* best-effort */
                            }
                        }
                    }
                    // Packaging cache: the fat jar is a pure function of the main
                    // classes, the bundled dependency jars' content, the main-class,
                    // and the manifest.
                    List<String> tokens = List.of(
                            "classes:" + dev.jkbuild.task.ClasspathFingerprint.entry(classes),
                            "deps:" + dev.jkbuild.task.ClasspathFingerprint.of(depJars),
                            "main:"
                                    + (project.project().main() == null
                                            ? ""
                                            : project.project().main()),
                            "manifest:" + project.manifest());
                    String shTask = ActionKey.qualifiedTaskId("package-shadow", shadowJar);
                    String shKey = ActionKey.forArtifact(shTask, dev.jkbuild.util.JkVersion.VERSION, tokens);
                    if (restorePackaged(cache, shKey, shadowJar.getParent())) {
                        ctx.label(shadowJar.getFileName() + " up-to-date");
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("package " + shadowJar.getFileName());
                    new ShadowPackager()
                            .packageShadow(new ShadowPackager.ShadowRequest(
                                    classes,
                                    depJars,
                                    shadowJar,
                                    project.project().main(),
                                    project.manifest(),
                                    0L));
                    storePackaged(cache, shTask, shKey, tokens, shadowJar.getParent(), List.of(shadowJar));
                    ctx.progress(1);
                })
                .build();
    }

    /** Sources-jar packaging — writes {@code <artifact>-<version>-sources.jar} to the artifact dir. */
    public static Phase sourcesPhase(Path cache) {
        return Phase.builder("package-sources")
                .label("Sources")
                .kind(PhaseKind.CPU)
                .requires("package-jar")
                .weight(W_SOURCES)
                .scope(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path moduleRoot = layout.moduleRoot();
                    Path sourcesJar = layout.sourcesJar();
                    // Source roots: simple layout uses src/, traditional uses src/main/java + src/main/kotlin.
                    boolean compact = CompileSupport.isSimpleLayout(project.project(), moduleRoot);
                    List<Path> sourceRoots = compact
                            ? List.of(moduleRoot.resolve("src"))
                            : List.of(
                                    moduleRoot.resolve("src/main/java"),
                                    moduleRoot.resolve("src/main/kotlin"));
                    // Cache key: hash of all source roots' content.
                    String srcHash = String.join(
                            ";",
                            sourceRoots.stream()
                                    .map(r -> {
                                        try {
                                            return dev.jkbuild.task.ClasspathFingerprint.entry(r);
                                        } catch (Exception e) {
                                            return "";
                                        }
                                    })
                                    .toList());
                    List<String> tokens = List.of("sources:" + srcHash);
                    String task = ActionKey.qualifiedTaskId("package-sources", sourcesJar);
                    String key = ActionKey.forArtifact(task, dev.jkbuild.util.JkVersion.VERSION, tokens);
                    if (restorePackaged(cache, key, sourcesJar.getParent())) {
                        ctx.label(sourcesJar.getFileName() + " up-to-date");
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("package " + sourcesJar.getFileName());
                    byte[] bytes = dev.jkbuild.cache.SourcesJar.build(sourceRoots);
                    Files.createDirectories(sourcesJar.getParent());
                    Files.write(sourcesJar, bytes);
                    storePackaged(cache, task, key, tokens, sourcesJar.getParent(), List.of(sourcesJar));
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * GraalVM native-image phase — what {@code jk native} composes onto the core build (and {@code jk
     * install} of a native application adds explicitly). Builds an <em>executable</em> when a main
     * class is resolvable ({@code mainOverride} > {@code [native].main-class} > {@code
     * [project].main}), otherwise a <em>shared library</em> ({@code native-image --shared}). Run
     * directly as a subprocess; requires package-jar.
     *
     * <p>{@code graalHome} is the GraalVM the CLI's {@code GraalResolver} selected (its {@code
     * bin/native-image} is used); when {@code null} the phase falls back to the project JDK / {@code
     * $GRAALVM_HOME} / {@code PATH} search.
     */
    public static Phase nativePhase(
            Path dir,
            Path cache,
            Path lockFile,
            Path jdksDir,
            Path graalHome,
            String mainOverride,
            List<String> extraArgs) {
        List<String> extra = extraArgs == null ? List.of() : extraArgs;
        return Phase.builder("native-image")
                .label("Native")
                .kind(PhaseKind.IO)
                .requires("package-jar")
                .weight(() -> EffortWeights.nativeWeight(dir))
                .scope(10) // preamble(1) + 8 native-image stages + done(1)
                .execute(ctx -> {
                    // Fail-fast: verify native-image is available before compilation
                    // has already run and the user has waited for potentially minutes.
                    Path javaHomeEarly = graalHome != null
                            ? graalHome
                            : dev.jkbuild.jdk.JdkResolver.forProject(dir, jdksDir)
                                    .map(dev.jkbuild.jdk.InstalledJdk::home)
                                    .orElseGet(JavaHomes::runningJavaHome);
                    if (dev.jkbuild.tool.NativeImageDriver.resolve(javaHomeEarly)
                            .isEmpty()) {
                        ctx.error(
                                "native",
                                dev.jkbuild.tool.NativeImageDriver.notFoundError(javaHomeEarly)
                                        .getMessage());
                        throw new RuntimeException("native-image not found");
                    }

                    JkBuild project = ctx.require(PROJECT);
                    JkBuild.NativeConfig nativeCfg = project.nativeConfig();
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path mainJar = layout.mainJar();
                    if (!Files.exists(mainJar)) {
                        ctx.error("native", "jar not found at " + mainJar);
                        throw new RuntimeException("missing main jar for native-image");
                    }
                    // Resolution order: --main CLI flag > [native].main-class > [project].main.
                    // A resolvable main → executable; none → shared library (--shared).
                    String mainClass = (mainOverride != null && !mainOverride.isBlank())
                            ? mainOverride
                            : (nativeCfg.mainClass() != null
                                    ? nativeCfg.mainClass()
                                    : project.project().main());
                    boolean shared = (mainClass == null || mainClass.isBlank());
                    if (shared) mainClass = null;
                    // Output path: [native].name overrides the artifact-derived name.
                    // Executable → target/<name>; library → target/lib<name> (native-image
                    // appends the platform extension .so/.dylib/.dll and emits C headers).
                    Path out;
                    if (nativeCfg.name() != null) {
                        String nm = nativeCfg.name();
                        out = layout.moduleTargetDir().resolve(shared && !nm.startsWith("lib") ? "lib" + nm : nm);
                    } else {
                        out = shared ? layout.nativeLibrary() : layout.nativeBinary();
                    }
                    Files.createDirectories(out.getParent());
                    // Args: [native].args (project-level) + extra (CLI --) in that order
                    List<String> allArgs = new ArrayList<>(nativeCfg.args());
                    allArgs.addAll(extra);

                    Path javaHome = javaHomeEarly; // resolved above in fail-fast check

                    List<Path> classpath = new ArrayList<>();
                    classpath.add(mainJar);
                    ClasspathResolver cpResolver = new ClasspathResolver(new Cas(cache));
                    if (Files.exists(lockFile)) {
                        Lockfile lock = LockfileReader.read(lockFile);
                        classpath.addAll(cpResolver.classpathFor(lock, ClasspathResolver.RUNTIME));
                    }
                    // Add workspace sibling JARs (and their external transitive deps)
                    // so native-image can resolve all referenced classes.
                    try {
                        dev.jkbuild.config.WorkspaceClasspath.Result siblings =
                                dev.jkbuild.config.WorkspaceClasspath.resolve(
                                        dir, project, Set.of(Scope.EXPORT, Scope.MAIN));
                        for (java.nio.file.Path sj : siblings.jars()) {
                            if (!classpath.contains(sj)) classpath.add(sj);
                        }
                        for (java.nio.file.Path sibLock : siblings.siblingLockfiles()) {
                            try {
                                Lockfile sibLockfile = LockfileReader.read(sibLock);
                                for (Path p : cpResolver.classpathFor(sibLockfile, ClasspathResolver.RUNTIME)) {
                                    if (!classpath.contains(p)) classpath.add(p);
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    } catch (Exception ignored) {
                    }

                    // Packaging cache (executable only): the binary is a pure function of
                    // the runtime classpath, the build args, the main class, and the GraalVM
                    // toolchain. Shared libraries (+ generated C headers) aren't cached yet.
                    Path releaseFile = javaHome.resolve("release");
                    String graalTok = Files.isRegularFile(releaseFile)
                            ? dev.jkbuild.util.Hashing.sha256Hex(releaseFile)
                            : javaHome.toString();
                    List<String> nativeTokens = List.of(
                            "cp:" + dev.jkbuild.task.ClasspathFingerprint.of(classpath),
                            "args:" + String.join(" ", allArgs),
                            "main:" + (mainClass == null ? "" : mainClass),
                            "shared:" + shared,
                            "out:" + out.getFileName(),
                            "graal:" + graalTok);
                    String nTask = ActionKey.qualifiedTaskId("native-image", out);
                    String nKey = ActionKey.forArtifact(nTask, dev.jkbuild.util.JkVersion.VERSION, nativeTokens);
                    if (!shared && restorePackaged(cache, nKey, out.getParent())) {
                        ctx.label(out.getFileName() + " up-to-date");
                        ctx.progress(1);
                        return;
                    }

                    ctx.label("native-image " + out.getFileName());

                    // Progress listener: parse [N/M] headers from native-image stdout.
                    //
                    // scope(10) is declared upfront (preamble + 8 GraalVM stages + done).
                    // Ticks: 1 preamble (when step 1 first appears) +
                    //        8 steps ([1/8]…[8/8]) +
                    //        1 final (ctx.progress after run() returns) = 10.
                    //
                    // Fallback: if no [N/M] headers appear (older GraalVM, --quiet),
                    // the listener never fires and the single ctx.progress(1) at the end
                    // is the only tick — the bar jumps to 1/10, which is acceptable.
                    java.util.concurrent.atomic.AtomicBoolean preambleDone =
                            new java.util.concurrent.atomic.AtomicBoolean(false);
                    dev.jkbuild.tool.NativeImageDriver.ProgressListener listener = (current, total, label) -> {
                        if (preambleDone.compareAndSet(false, true)) {
                            ctx.progress(1); // preamble done (output before [1/N])
                        }
                        ctx.label("[" + current + "/" + total + "] " + label);
                        ctx.progress(1); // stage N started = stage N-1 done
                    };

                    int exit = dev.jkbuild.tool.NativeImageDriver.run(
                            new dev.jkbuild.tool.NativeImageDriver.Request(
                                    javaHome, classpath, mainClass, out, allArgs, shared),
                            listener,
                            ctx::output);
                    if (exit != 0) {
                        ctx.error("native", "native-image exited " + exit);
                        throw new RuntimeException("native-image failed (exit " + exit + ")");
                    }
                    // Final tick: completes the last native-image step (or the only tick
                    // when no progress headers were emitted).
                    ctx.progress(1);
                    if (!shared) {
                        storePackaged(cache, nTask, nKey, nativeTokens, out.getParent(), List.of(out));
                    }
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
     * The main-compile classpath contributed by the lockfile and workspace siblings: {@code
     * COMPILE_MAIN} lockfile deps + each depended sibling's built jar + those siblings' own {@code
     * COMPILE_MAIN} lockfile deps (so e.g. tomlj declared in jk-core is visible when compiling
     * jk-io). Shared by the {@code compile-main} phase and {@code jk explain} so their javac action
     * keys agree.
     */
    public static List<Path> mainCompileClasspath(
            Lockfile lock, ClasspathResolver resolver, WorkspaceClasspath.Result siblings) throws IOException {
        List<Path> cp = new ArrayList<>(resolver.classpathFor(lock, ClasspathResolver.COMPILE_MAIN));
        // The declared closure (deterministic jar paths) — not just the built ones —
        // so the action key is stable whether or not target/ is currently populated.
        // In a valid build the siblings are all built (the missing-sibling check
        // upstream guarantees it), so these are the same paths javac compiles against;
        // after `jk clean` they still let `jk explain` reproduce the build's key.
        cp.addAll(siblings.siblingClosureJars());
        for (Path sibLock : siblings.siblingLockfiles()) {
            try {
                Lockfile sl = LockfileReader.read(sibLock);
                for (Path p : resolver.classpathFor(sl, ClasspathResolver.COMPILE_MAIN)) {
                    if (!cp.contains(p)) cp.add(p);
                }
            } catch (Exception ignored) {
                /* best-effort: a sibling's lock may be absent */
            }
        }
        return cp;
    }

    /**
     * Compile Kotlin {@code sources} into {@code outputDir} via the worker (action-cached: restores
     * from the CAS on an exact-input hit without launching the worker, else compiles incrementally).
     * Shared by the main {@code compile-kotlin} and {@code compile-test} phases. The caller owns
     * freshness stamps, output assembly, and outcome reporting.
     *
     * @param javaSourceRoot when non-null, passed as {@code -Xjava-source-roots} so a mixed module's
     *     Kotlin can read Java declarations from source
     */
    private static dev.jkbuild.task.KotlinCompile.Result compileKotlinSources(
            PhaseContext ctx,
            Inputs in,
            Cas cas,
            ActionCache actionCache,
            List<Path> sources,
            List<Path> classpath,
            Path outputDir,
            String taskId,
            Path workingDir,
            Path javaSourceRoot)
            throws IOException {
        String kotlinVersion = CompileToolchain.kotlinVersionFor(ctx.require(LOCKFILE), ctx.require(PROJECT));
        KotlinWorkerSetup.Prepared kt;
        try {
            dev.jkbuild.repo.RepoGroup repos = RepoGroupBuilder.buildFor(ctx.require(PROJECT), null, cas);
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
                .sources(sources)
                .classpath(compileCp)
                .outputDir(outputDir)
                .jvmTarget(CompileSupport.kotlinJvmTarget(ctx.require(RELEASE)))
                .workerClasspath(kt.workerClasspath())
                .javaHome(ctx.require(JAVA_HOME))
                .workingDir(workingDir)
                .snapshotDir(in.cache().resolve("kotlin-cp-snapshots"))
                .extraArgs(ktArgs)
                .build();
        boolean rerun = in.session().config().rerunOr(false);
        // Reweight from the real request: a CAS hit is a cheap restore (3), else a
        // full kotlinc. Same forKotlinc key KotlinCompile.run looks up.
        if (!rerun) {
            try {
                boolean restores = actionCache
                        .lookup(ActionKey.forKotlinc(taskId, req, dev.jkbuild.util.JkVersion.VERSION))
                        .isPresent();
                ctx.reweight(restores ? EffortWeights.RESTORE : EffortWeights.compileWeight(sources.size()));
            } catch (Exception ignored) {
                /* keep the up-front estimate */
            }
        }
        return dev.jkbuild.task.KotlinCompile.run(
                taskId, req, dev.jkbuild.util.JkVersion.VERSION, !rerun, cas, actionCache);
    }

    /**
     * The version-matched {@code kotlin-stdlib} path (already in the CAS from the worker closure).
     * Kotlin output needs it on the <em>runtime</em> classpath too — compilation pairs the stdlib
     * with {@code -no-stdlib}, but the JVM still needs {@code kotlin.jvm.internal.*} etc. when the
     * code runs.
     */
    private static Path kotlinStdlib(PhaseContext ctx, Cas cas) throws IOException {
        String kotlinVersion = CompileToolchain.kotlinVersionFor(ctx.require(LOCKFILE), ctx.require(PROJECT));
        try {
            dev.jkbuild.repo.RepoGroup repos = RepoGroupBuilder.buildFor(ctx.require(PROJECT), null, cas);
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
                Path target = classesDir.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Map each workspace sibling to its main output jar, keyed by both project name and {@code
     * group:artifact} coord. Used by {@link #workerJarProps} to locate {@code test-worker-jars}
     * entries. Empty when this module isn't in a workspace.
     */
    static Map<String, Path> siblingMainJars(Path moduleDir) throws IOException {
        Map<String, Path> out = new LinkedHashMap<>();
        var rootOpt = dev.jkbuild.config.WorkspaceLocator.findRoot(moduleDir);
        if (rootOpt.isEmpty()) return out;
        Path root = rootOpt.get();
        JkBuild rootManifest = JkBuildParser.parse(root.resolve("jk.toml"));
        if (!rootManifest.isWorkspaceRoot()) return out;
        for (String module : rootManifest.workspace().modules()) {
            Path dir = root.resolve(module);
            Path manifest = dir.resolve("jk.toml");
            if (!Files.exists(manifest)) continue;
            JkBuild sib;
            try {
                sib = JkBuildParser.parse(manifest);
            } catch (RuntimeException ignored) {
                continue;
            }
            BuildLayout layout = BuildLayout.of(dir, sib);
            // A shadow (fat) worker runs from its -all.jar — that's the artifact
            // that bundles plugin-api/PluginWorkerMain and the worker's deps; a
            // plain module ships only its main jar.
            Path jar = sib.project().shadow() ? layout.shadowJar() : layout.mainJar();
            out.put(sib.project().name(), jar);
            out.put(sib.project().group() + ":" + sib.project().name(), jar);
        }
        return out;
    }

    /**
     * Packaging cache (mirrors the compile {@link ActionCache} path, for artifacts). Returns {@code
     * true} when a cached artifact for {@code key} was hard-linked back into {@code baseDir} — the
     * caller then skips the (re)packaging work. Honors {@code --force}.
     */
    private static boolean restorePackaged(Path cacheRoot, String key, Path baseDir) throws IOException {
        if (dev.jkbuild.config.SessionContext.current().config().rerunOr(false)) return false;
        ActionCache ac = new ActionCache(new Cas(cacheRoot), cacheRoot.resolve("actions"));
        var hit = ac.lookup(key);
        return hit.isPresent() && ac.restoreArtifacts(hit.get(), baseDir);
    }

    /** Record a freshly-produced packaging artifact so a later build can skip it. */
    private static void storePackaged(
            Path cacheRoot, String taskId, String key, List<String> tokens, Path baseDir, List<Path> artifacts)
            throws IOException {
        if (dev.jkbuild.config.SessionContext.current().config().rerunOr(false)) return;
        new ActionCache(new Cas(cacheRoot), cacheRoot.resolve("actions"))
                .storeArtifacts(taskId, key, Map.of("inputs", String.join(";", tokens)), baseDir, artifacts);
    }

    private static Map<String, String> workerJarProps(Path moduleDir, List<String> modules) throws IOException {
        Map<String, String> props = new LinkedHashMap<>();
        if (modules.isEmpty()) return props;
        Map<String, Path> jarByModule = siblingMainJars(moduleDir);
        for (String module : modules) {
            var wj = dev.jkbuild.worker.WorkerJar.byArtifactId("jk-" + module);
            if (wj.isEmpty()) continue;
            Path jar = jarByModule.get(module);
            if (jar != null && Files.exists(jar)) {
                props.put(wj.get().jarProperty(), jar.toAbsolutePath().toString());
            } else {
                // Not a built sibling — self-host by reusing the running jk's worker
                // jar (located via its sha resource + CAS, or a -D override).
                Path located = wj.get().locateOrNull(new dev.jkbuild.cache.Cas(dev.jkbuild.util.JkDirs.cache()));
                if (located != null) props.put(wj.get().jarProperty(), located.toString());
            }
        }
        return props;
    }

    /**
     * The run-tests stamp's identity tokens for {@code project} at {@code dir} — the same set the
     * build folds into its {@code TestStamp} key, exposed so {@code jk explain}'s forecast predicts
     * test-skip without drifting.
     */
    public static List<String> testStampExtras(Path dir, JkBuild project) throws IOException {
        return testStampExtras(workerJarProps(dir, project.build().testWorkerJars()));
    }

    private static List<String> testStampExtras(Map<String, String> workerJars) {
        List<String> extras = new ArrayList<>();
        extras.add("jk:" + dev.jkbuild.util.JkVersion.VERSION);
        // Worker jars by content — a worker change retests the module that forks it.
        for (Map.Entry<String, String> e : workerJars.entrySet()) {
            String fp;
            try {
                fp = dev.jkbuild.task.ClasspathFingerprint.entry(Path.of(e.getValue()));
            } catch (IOException ex) {
                fp = "err";
            }
            extras.add("worker:" + e.getKey() + "=" + fp);
        }
        return extras;
    }
}
