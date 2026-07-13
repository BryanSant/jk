// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.compile.CycloneDxSbom;
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

    /** The javac half of the processor split — set by the ksp phase (KSP jars removed). */
    @SuppressWarnings("rawtypes")
    public static final GoalKey<List> JAVAC_PROCESSOR_CP = GoalKey.of("javac-processor-cp", List.class);

    /** The [[contribute.provided-classpath]] jars (platform), published for the test phase. */
    @SuppressWarnings("rawtypes")
    public static final GoalKey<List> PROVIDED_CP = GoalKey.of("provided-cp", List.class);

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
            dev.jkbuild.config.Session session,
            // The variant selection ("", "release", "release|tier=free") — folded into plugin
            // configs at parse time (VariantApply.apply), so goals are parameterized, never configured.
            String variant,
            // Client-resolved env values (env: indirection in plugin configs — signing secrets):
            // the user's shell env rides the request; the engine env is only the fallback.
            Map<String, String> clientEnv) {

        /**
         * Back-compat: the pre-variant canonical shape. The variant selection defaults from the
         * SESSION — a command that installs a selection there (jk run/test/image/native/publish)
         * parameterizes every goal factory without each one threading it explicitly.
         */
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
                boolean compileOnly,
                Set<Path> projectModules,
                dev.jkbuild.config.Session session) {
            this(
                    dir, cache, buildFile, lockFile, lockDir, workerCount, estimatedTestCount, profileName,
                    jdksDir, skipTests, verbose, testOnly, compileOnly, projectModules, session,
                    session.variant(), session.clientEnv());
        }

        /** This request with a variant selection + client-resolved env attached. */
        public Inputs withVariant(String variant, Map<String, String> clientEnv) {
            return new Inputs(
                    dir, cache, buildFile, lockFile, lockDir, workerCount, estimatedTestCount, profileName,
                    jdksDir, skipTests, verbose, testOnly, compileOnly, projectModules, session,
                    variant == null ? "" : variant, clientEnv == null ? Map.of() : clientEnv);
        }

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
        JkBuild parsedBuild = null;
        Map<String, String> variantSecrets = Map.of();
        try {
            var jkBuild = JkBuildParser.parse(in.buildFile());
            // Third-party plugin pre-flight: extract any locked-but-unmaterialized manifests
            // from the CAS and re-parse, so a declared plugin's table validates (and its
            // contributions apply) on the very first build after `jk sync`.
            if (!jkBuild.plugins().isEmpty()
                    && PluginManifestOps.ensureMaterialized(in.dir(), in.cache())) {
                jkBuild = JkBuildParser.reparse(in.buildFile());
            }
            // Variant overlays fold into plugin configs HERE, so describe keys, contribution
            // predicates, step/packager action keys, and worker specs all see one flat effective
            // config (build-plugins §3.1: parameterized goals, not configured objects).
            var applied = dev.jkbuild.plugin.manifest.VariantApply.apply(
                    jkBuild, in.dir(), dev.jkbuild.model.Variants.Selection.parse(in.variant()),
                    in.clientEnv());
            jkBuild = applied.build();
            variantSecrets = applied.secrets();
            parsedBuild = jkBuild;
            var project = jkBuild.project();
            CompileSupport.Languages langs = CompileSupport.resolveLanguages(project, in.dir());
            useJava = langs.java();
            useKotlin = langs.kotlin();
            // [processor-dependencies] on a Kotlin module can generate Java sources (Hilt's
            // components are Java) — route through the mixed pipeline so javac compiles them.
            if (useKotlin && !useJava && hasProcessorDeps(jkBuild)) {
                useJava = true;
            }
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

        // Build-plugin code layer (build-plugins plan §3.2): learn the registered steps/packager
        // over the file-cached describe protocol. A missing worker jar or a broken registration
        // must fail the build loudly here, not mid-pipeline.
        PluginBuild.Active pluginActive = null;
        PluginBuild.Declarations pluginDecls = null;
        if (parsedBuild != null) {
            var activeOpt = PluginBuild.activeCodePlugin(parsedBuild, in.dir());
            if (activeOpt.isPresent()) {
                try {
                    BuildLayout layout = BuildLayout.of(in.dir(), parsedBuild);
                    pluginDecls = PluginBuild.declarations(
                            activeOpt.get(), parsedBuild, in.dir(), in.cache(), layout.moduleTargetDir());
                    pluginActive = activeOpt.get();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "plugin " + activeOpt.get().manifest().id() + ": " + e.getMessage(), e);
                }
            }
        }
        final PluginBuild.Active pluginActiveF = pluginActive;
        final PluginBuild.Declarations pluginDeclsF = pluginDecls;
        final Map<String, String> variantSecretsF = variantSecrets;

        // Plugin-contributed generated sources can be Java even in a Kotlin-only module
        // (protoc's --kotlin_out DSL wraps its own --java_out classes) — same mixed-pipeline
        // routing the KSP/Hilt case above takes, decided here because the declarations only
        // exist after the describe round.
        if (useKotlin && !useJava && pluginDecls != null) {
            for (PluginBuild.StepDecl step : pluginDecls.steps()) {
                if (!step.contributesSources().isEmpty()) {
                    useJava = true;
                    break;
                }
            }
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
        final boolean kspEnabled = useKotlin && parsedBuild != null && hasProcessorDeps(parsedBuild);
        StepContext cx = new StepContext(
                in, cas, actionCache, plan, javaMainSrcRef, kotlinMainSrcRef,
                javaMainSrcDir, compact, mixed, kotlinModule, mixedWithJava, mainCompile, kspEnabled);

        Phase parseBuild = parseBuildPhase(cx);

        // ---- sync-deps --------------------------------------------------
        Phase syncDeps = syncDepsPhase(cx);

        // ---- ensure-jdk -------------------------------------------------
        Phase ensureJdk = ensureJdkPhase(cx);

        // ---- compile-java -----------------------------------------------
        Phase compileJava = compileJavaPhase(cx, pluginDeclsF);

        // ---- compile-kotlin ---------------------------------------------
        Phase compileKotlin = compileKotlinPhase(cx, pluginDeclsF);

        // ---- copy-resources ---------------------------------------------
        Phase copyResources = copyResourcesPhase(cx);

        // ---- compile-test -----------------------------------------------
        Phase compileTest = compileTestPhase(cx);

        // ---- run-tests --------------------------------------------------
        Phase runTests = runTestsPhase(cx, pluginDeclsF);

        // ---- plugin steps (build-plugins plan §3.2) ----------------------
        List<Phase> pluginSteps = new ArrayList<>();
        PluginBuild.StepDecl transform = transformStep(pluginDeclsF);
        if (pluginDeclsF != null) {
            for (PluginBuild.StepDecl step : pluginDeclsF.steps()) {
                pluginSteps.add(pluginStepPhase(cx, pluginActiveF, step, transform));
            }
        }

        // ---- package-jar ------------------------------------------------
        Phase packageJar = packageJarPhase(cx, pluginActiveF, pluginDeclsF, variantSecretsF);

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
        if (kspEnabled) {
            b.addPhase(kspPhase(cx, pluginDeclsF));
        }
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
        // `jk test` stops at run-tests — it never packages a jar. Plugin steps run only
        // when packaging does: they exist to feed the packaged/native artifact.
        if (!in.testOnly()) {
            for (Phase p : pluginSteps) b.addPhase(p);
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
            String mainCompile,
            boolean ksp) {}

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
                        project = dev.jkbuild.plugin.manifest.VariantApply.apply(
                                        JkBuildParser.parse(in.buildFile()),
                                        in.dir(),
                                        dev.jkbuild.model.Variants.Selection.parse(in.variant()),
                                        in.clientEnv())
                                .build();
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
                    // Plugin-contributed PROVIDED classpath (an Android platform jar): javac
                    // sees it, runtime/packaging never do. Resolved through the same engine
                    // fetch the steps use, so the compile action key fingerprints it.
                    List<Path> contributedProvided = contributedProvidedClasspath(project, in, cas);
                    mainCp.addAll(contributedProvided);

                    Profile profile = CompileSupport.resolveProfile(project.profiles(), in.profileName());
                    // Default lint (deprecation/unchecked) unless [build] lint = false;
                    // the profile's own javac args win (appended after). Shared by the
                    // main- and test-compile phases (both read JAVAC_ARGS).
                    ctx.put(
                            JAVAC_ARGS,
                            dev.jkbuild.compile.JavacLint.effectiveArgs(
                                    project.build().lint(),
                                    dev.jkbuild.plugin.manifest.PluginContributions.javacArgs(
                                            project, in.dir(), lockModules(lock)),
                                    profile == null ? List.of() : profile.javacArgs()));
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
                    compileTestCp.addAll(contributedProvided);
                    ctx.put(PROVIDED_CP, contributedProvided);
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
                    List<Path> kotlinMainSrcs = kotlinMainSrcRef.get();
                    if (kotlinMainSrcs == null) {
                        kotlinMainSrcs = CompileSupport.collectKotlinSources(in.dir(), compact);
                        kotlinMainSrcRef.compareAndSet(null, kotlinMainSrcs);
                        kotlinMainSrcs = kotlinMainSrcRef.get();
                    }
                    // [build] extra-src roots (variant overlays folded in by VariantApply) join
                    // the source set here — the scope suppliers' pre-walk never saw them.
                    List<Path> extraSrcDirs = CompileSupport.extraSrcDirs(project, in.dir());
                    if (!extraSrcDirs.isEmpty()) {
                        javaMainSrcs = CompileSupport.withExtraSources(javaMainSrcs, extraSrcDirs, ".java");
                        kotlinMainSrcs = CompileSupport.withExtraSources(kotlinMainSrcs, extraSrcDirs, ".kt");
                        javaMainSrcRef.set(javaMainSrcs);
                        kotlinMainSrcRef.set(kotlinMainSrcs);
                    }
                    ctx.put(JAVA_SOURCES, javaMainSrcs);
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
                    JkBuild project = ctx.require(PROJECT);
                    boolean mirrorToM2 = project.project().m2install();
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
                    var report = new CacheSync(cas, new Http(), mirrorToM2).sync(lock, observer, refresh);
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

    /** The phase names of every source-generating plugin step the compilers must wait for. */
    private static List<String> sourceGenStepPhases(PluginBuild.Declarations decls) {
        List<String> out = new ArrayList<>();
        if (decls != null) {
            for (PluginBuild.StepDecl step : decls.steps()) {
                if (beforeCompile(step)) out.add("plugin-" + step.name());
            }
        }
        return out;
    }

    /**
     * Plugin-contributed generated sources ({@code contributesSources} of before-compile steps):
     * files with {@code suffix} under each contributed scratch dir. They join the compiler's
     * source list, so the freshness stamp and the javac action key see them like any source.
     */
    private static List<Path> pluginContributedSources(
            BuildLayout layout, PluginBuild.Declarations decls, String suffix) throws IOException {
        List<Path> out = new ArrayList<>();
        if (decls == null) return out;
        for (PluginBuild.StepDecl step : decls.steps()) {
            for (String rel : step.contributesSources()) {
                Path dir = PluginBuild.stepScratch(layout, step.name()).resolve(rel);
                if (!Files.isDirectory(dir)) continue;
                try (var walk = Files.walk(dir)) {
                    walk.filter(f -> f.toString().endsWith(suffix) && Files.isRegularFile(f))
                            .sorted()
                            .forEach(out::add);
                }
            }
        }
        return out;
    }

    /** Plugin steps' declared source-contribution dirs (existing ones only). */
    private static List<Path> pluginContributedSourceDirs(BuildLayout layout, PluginBuild.Declarations decls) {
        List<Path> out = new ArrayList<>();
        if (decls == null) return out;
        for (PluginBuild.StepDecl step : decls.steps()) {
            for (String rel : step.contributesSources()) {
                Path dir = PluginBuild.stepScratch(layout, step.name()).resolve(rel);
                if (Files.isDirectory(dir)) out.add(dir);
            }
        }
        return out;
    }

    /** Plugin steps' declared test-classpath contribution dirs (existing ones only). */
    private static List<Path> pluginTestClasspath(BuildLayout layout, PluginBuild.Declarations decls) {
        List<Path> out = new ArrayList<>();
        if (decls == null) return out;
        for (PluginBuild.StepDecl step : decls.steps()) {
            for (String rel : step.contributesTestClasspath()) {
                Path dir = PluginBuild.stepScratch(layout, step.name()).resolve(rel);
                if (Files.isDirectory(dir)) out.add(dir);
            }
        }
        return out;
    }

    /** The provided-classpath contribution (platform jars), re-read for the test phase. */
    @SuppressWarnings("unchecked")
    private static List<Path> contributedProvidedFor(dev.jkbuild.run.PhaseContext ctx) {
        return (List<Path>) ctx.get(PROVIDED_CP).orElse(List.of());
    }

    /** Generated-source dirs the KSP round writes (checked by the compile-phase unions). */
    static Path kspOutBase(BuildLayout layout) {
        return layout.moduleTargetDir().resolve("ksp");
    }

    /** Files with {@code suffix} under the KSP output tree, sorted — empty when no round ran. */
    private static List<Path> kspGeneratedSources(BuildLayout layout, String suffix) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String lang : List.of("kotlin", "java")) {
            Path dir = kspOutBase(layout).resolve(lang);
            if (!Files.isDirectory(dir)) continue;
            try (var walk = Files.walk(dir)) {
                walk.filter(f -> f.toString().endsWith(suffix) && Files.isRegularFile(f))
                        .sorted()
                        .forEach(out::add);
            }
        }
        return out;
    }

    /**
     * The KSP2 round (android-plan §3.5): fork {@code KSPJvmMain} over the module's sources with
     * the KSP half of {@code [processor-dependencies]} (detected by the jar's registered
     * {@code SymbolProcessorProvider} — see {@link dev.jkbuild.compile.KspProcessors}); generated
     * Kotlin/Java land under {@code target/ksp/} and join the compilers' source lists. Freshness
     * mirrors compile-kotlin's stamp discipline (no action cache yet — the direct kotlinc path
     * has none either); the processors, classpath, and sources are all stamp inputs.
     */
    private static Phase kspPhase(StepContext cx, PluginBuild.Declarations pluginDecls) {
        Inputs in = cx.in();
        Cas cas = cx.cas();
        boolean compact = cx.compact();
        // Plugin-contributed sources (protoc output, variant extra-src) must exist before the
        // round and join its source roots — a contributed @Module/@Entity is processor input
        // like any hand-written one.
        List<String> requires = new ArrayList<>(List.of("parse-build", "sync-deps", "ensure-jdk"));
        requires.addAll(sourceGenStepPhases(pluginDecls));
        return Phase.builder("ksp")
                .label("KSP")
                .kind(PhaseKind.CPU)
                .requires(requires.toArray(new String[0]))
                .scope(1)
                .execute(ctx -> {
                    @SuppressWarnings("unchecked")
                    List<Path> processorCp = (List<Path>) ctx.require(PROCESSOR_CP);
                    var split = dev.jkbuild.compile.KspProcessors.split(processorCp);
                    ctx.put(JAVAC_PROCESSOR_CP, split.javac());
                    if (split.ksp().isEmpty()) {
                        ctx.label("no KSP processors");
                        ctx.progress(1);
                        return;
                    }
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path outBase = kspOutBase(layout);
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    List<Path> ktSources = kotlinSources(ctx);
                    List<Path> javaSources = javaSources(ctx);

                    List<Path> stampInputs = new ArrayList<>(ktSources);
                    stampInputs.addAll(javaSources);
                    // Contributed sources are round input too — an extra-src/protoc edit re-runs.
                    stampInputs.addAll(pluginContributedSources(ctx.require(LAYOUT), pluginDecls, ".kt"));
                    stampInputs.addAll(pluginContributedSources(ctx.require(LAYOUT), pluginDecls, ".java"));
                    List<Path> stampCp = new ArrayList<>(classpath);
                    stampCp.addAll(split.ksp());
                    boolean rerun = in.session().config().rerunOr(false);
                    if (!rerun
                            && dev.jkbuild.task.FreshnessStamp.isFresh(
                                    outBase, KSP_STAMP, stampInputs, stampCp, ctx.require(RELEASE))) {
                        ctx.reweight(EffortWeights.SKIP);
                        ctx.label("up to date");
                        ctx.progress(1);
                        return;
                    }

                    ctx.label("KSP: " + split.ksp().size() + " processor jar(s)");
                    JkBuild project = ctx.require(PROJECT);
                    String kotlinVersion = CompileToolchain.kotlinVersionFor(ctx.require(LOCKFILE), project);
                    if (kotlinVersion == null || kotlinVersion.isBlank()) {
                        kotlinVersion = dev.jkbuild.kotlin.KotlinResolver.DEFAULT_VERSION;
                    }
                    List<Path> kspClasspath;
                    Path stdlib;
                    try {
                        dev.jkbuild.repo.RepoGroup repos = RepoGroupBuilder.buildFor(project, null, cas);
                        String kspVersion = KspResolver.discoverVersion(repos);
                        kspClasspath = KspResolver.resolveClasspath(repos, cas, kspVersion);
                        stdlib = KotlinBtaResolver.resolveStdlib(repos, cas, kotlinVersion);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("interrupted resolving KSP", e);
                    }

                    // A stale round's outputs must not survive into the source union.
                    for (String sub : List.of("kotlin", "java", "classes", "resources")) {
                        dev.jkbuild.util.PathUtil.deleteRecursively(outBase.resolve(sub));
                    }
                    Files.createDirectories(outBase.resolve("caches"));

                    List<Path> srcRoots = new ArrayList<>(
                            compact
                                    ? List.of(in.dir().resolve("src"))
                                    : List.of(
                                            in.dir().resolve("src/main/kotlin"),
                                            in.dir().resolve("src/main/java")));
                    // Plugin-contributed source dirs (protoc output, generated code) and
                    // [build] extra-src roots (variant overlays) are processor input like any
                    // hand-written source.
                    srcRoots.addAll(pluginContributedSourceDirs(ctx.require(LAYOUT), pluginDecls));
                    srcRoots.addAll(CompileSupport.extraSrcDirs(project, in.dir()));
                    List<Path> ktRoots = new ArrayList<>();
                    for (Path root : srcRoots) {
                        if (Files.isDirectory(root)) ktRoots.add(root);
                    }
                    String sep = java.io.File.pathSeparator;
                    List<Path> libs = new ArrayList<>(classpath);
                    libs.add(stdlib);

                    String languageVersion = majorMinor(kotlinVersion);
                    // KSP is jk's tool: it runs on jk's own runtime, not the project's pinned
                    // JDK (same rule as every worker — requirements.md "worker host"). AGP runs
                    // KSP in the Gradle daemon's JVM the same way; the project JDK stays the
                    // -jdk-home cross-compile input below.
                    Path javaHome = ctx.require(JAVA_HOME);
                    List<String> cmd = new ArrayList<>();
                    cmd.add(dev.jkbuild.jdk.JavaHomes.runningJavaHome().resolve("bin/java").toString());
                    cmd.addAll(dev.jkbuild.worker.JvmOptions.workerFlags(1));
                    cmd.add("-cp");
                    cmd.add(joinPaths(kspClasspath, sep));
                    cmd.add(KspResolver.KSP_MAIN);
                    cmd.add("-module-name=" + project.project().name());
                    cmd.add("-source-roots=" + joinPaths(ktRoots, sep));
                    cmd.add("-java-source-roots=" + joinPaths(ktRoots, sep));
                    cmd.add("-project-base-dir=" + in.dir().toAbsolutePath());
                    cmd.add("-output-base-dir=" + outBase.toAbsolutePath());
                    cmd.add("-caches-dir=" + outBase.resolve("caches").toAbsolutePath());
                    cmd.add("-class-output-dir=" + outBase.resolve("classes").toAbsolutePath());
                    cmd.add("-kotlin-output-dir=" + outBase.resolve("kotlin").toAbsolutePath());
                    cmd.add("-java-output-dir=" + outBase.resolve("java").toAbsolutePath());
                    cmd.add("-resource-output-dir=" + outBase.resolve("resources").toAbsolutePath());
                    cmd.add("-language-version=" + languageVersion);
                    cmd.add("-api-version=" + languageVersion);
                    cmd.add("-jvm-target=" + CompileSupport.kotlinJvmTarget(ctx.require(RELEASE)));
                    cmd.add("-jdk-home=" + javaHome.toAbsolutePath());
                    cmd.add("-libraries=" + joinPaths(libs, sep));
                    // Processor options: plugin-contributed ([[contribute.compiler-args]] ksp —
                    // Hilt's superclass-validation toggle) plus project-declared ([build]
                    // ksp-options — Room's schemaLocation; last wins, so the project overrides).
                    // KSP's map syntax joins entries with the platform path separator, same as
                    // its list args; relative option paths resolve against the module dir (the
                    // KSP process CWD).
                    List<String> kspOptions = new ArrayList<>(
                            dev.jkbuild.plugin.manifest.PluginContributions.kspOptions(
                                    project, in.dir(), lockModules(ctx.require(LOCKFILE))));
                    kspOptions.addAll(project.build().kspOptions());
                    if (!kspOptions.isEmpty()) {
                        cmd.add("-processor-options=" + String.join(sep, kspOptions));
                    }
                    // The trailing processor classpath is the WHOLE [processor-dependencies]
                    // closure — a provider jar (room-compiler) loads its own deps from it.
                    cmd.add(joinPaths(processorCp, sep));

                    ProcessBuilder pb =
                            new ProcessBuilder(cmd).directory(in.dir().toFile()).redirectErrorStream(true);
                    Process proc = pb.start();
                    // Read on a drainer thread and bound the wait: on an internal error KSP's JVM
                    // can linger (non-daemon compiler pools survive the main thread's exception),
                    // which would hang a plain readAllBytes forever.
                    StringBuilder captured = new StringBuilder();
                    Thread drainer = new Thread(() -> {
                        try (var in2 = proc.getInputStream()) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in2.read(buf)) >= 0) {
                                captured.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                            }
                        } catch (IOException ignored) {
                            // stream closed with the process
                        }
                    });
                    drainer.setDaemon(true);
                    drainer.start();
                    int exit;
                    try {
                        if (!proc.waitFor(15, java.util.concurrent.TimeUnit.MINUTES)) {
                            proc.destroyForcibly();
                            ctx.error("ksp", "KSP timed out after 15 minutes\n" + captured);
                            throw new RuntimeException("KSP timed out");
                        }
                        exit = proc.exitValue();
                        drainer.join(5_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        proc.destroyForcibly();
                        throw new RuntimeException("interrupted waiting for KSP", e);
                    }
                    String output = captured.toString();
                    if (exit != 0) {
                        ctx.error("ksp", output.isBlank() ? ("KSP exited " + exit) : output);
                        throw new RuntimeException("KSP processing failed");
                    }
                    dev.jkbuild.task.FreshnessStamp.write(
                            outBase, KSP_STAMP, "ksp", "", stampInputs, stampCp, ctx.require(RELEASE));
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * The Java source roots kotlinc reads declarations from in a mixed module: the hand-written
     * root plus the KSP round's generated-Java dir (Hilt components are Java — a Kotlin class
     * extending a generated base must resolve it during Kotlin analysis).
     */
    private static List<Path> kotlinJavaSourceRoots(
            boolean mixedWithJava, boolean compact, Path dir, BuildLayout layout, PluginBuild.Declarations decls) {
        if (!mixedWithJava) return null;
        List<Path> roots = new ArrayList<>();
        roots.add(compact ? dir.resolve("src") : dir.resolve("src/main/java"));
        Path kspJava = kspOutBase(layout).resolve("java");
        if (Files.isDirectory(kspJava)) roots.add(kspJava);
        // Plugin-contributed generated dirs can carry Java that Kotlin sources reference
        // (protoc: the --kotlin_out DSL wraps its own --java_out message classes).
        if (decls != null) {
            for (PluginBuild.StepDecl step : decls.steps()) {
                for (String rel : step.contributesSources()) {
                    Path contributed = PluginBuild.stepScratch(layout, step.name()).resolve(rel);
                    if (Files.isDirectory(contributed)) roots.add(contributed);
                }
            }
        }
        return roots;
    }

    /** The {@code major.minor} language level of a full Kotlin version ({@code 2.4.0} → 2.4). */
    private static String majorMinor(String version) {
        int first = version.indexOf('.');
        int second = version.indexOf('.', first + 1);
        return second > 0 ? version.substring(0, second) : version;
    }

    private static String joinPaths(List<Path> paths, String sep) {
        StringBuilder b = new StringBuilder();
        for (Path pth : paths) {
            if (b.length() > 0) b.append(sep);
            b.append(pth.toAbsolutePath());
        }
        return b.toString();
    }

    /** KSP's freshness companion, mirroring compile-kotlin's stamp discipline. */
    private static final String KSP_STAMP = ".kspstamp";

    private static Phase compileJavaPhase(StepContext cx, PluginBuild.Declarations pluginDecls) {
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
                .requires(javaCompileRequires(mixed, pluginDecls, cx.ksp()))
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
                    List<Path> generated = pluginContributedSources(ctx.require(LAYOUT), pluginDecls, ".java");
                    List<Path> kspGenerated = kspGeneratedSources(ctx.require(LAYOUT), ".java");
                    if (!generated.isEmpty() || !kspGenerated.isEmpty()) {
                        sources = new ArrayList<>(sources);
                        sources.addAll(generated);
                        sources.addAll(kspGenerated);
                        // Re-publish the union so write-stamp records the same input set
                        // this compile checked (else the fast freshness path never holds).
                        ctx.put(JAVA_SOURCES, sources);
                    }
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
                    List<Path> processorCp =
                            (List<Path>) ctx.get(JAVAC_PROCESSOR_CP).orElseGet(() -> ctx.require(PROCESSOR_CP));
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

    private static String[] kotlinCompileRequires(PluginBuild.Declarations decls, boolean ksp) {
        List<String> requires = new ArrayList<>(List.of("parse-build", "sync-deps", "ensure-jdk"));
        if (ksp) requires.add("ksp");
        requires.addAll(sourceGenStepPhases(decls));
        return requires.toArray(new String[0]);
    }

    private static String[] javaCompileRequires(boolean mixed, PluginBuild.Declarations decls, boolean ksp) {
        List<String> requires = new ArrayList<>(List.of("parse-build", "sync-deps", "ensure-jdk"));
        if (mixed) requires.add("compile-kotlin");
        if (ksp) requires.add("ksp");
        requires.addAll(sourceGenStepPhases(decls));
        return requires.toArray(new String[0]);
    }

    /** True when the module declares {@code [processor-dependencies]} entries. */
    private static boolean hasProcessorDeps(JkBuild build) {
        List<dev.jkbuild.model.Dependency> procs =
                build.dependencies().byScope().get(dev.jkbuild.model.Scope.PROCESSOR);
        return procs != null && !procs.isEmpty();
    }

    private static Phase compileKotlinPhase(StepContext cx, PluginBuild.Declarations pluginDecls) {
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
                // only needs the base phases — javac runs after it in a mixed module —
                // plus any source-generating plugin steps.
                .requires(kotlinCompileRequires(pluginDecls, cx.ksp()))
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
                    // Plugin-contributed generated Kotlin (a KSP round, a codegen step) joins the
                    // source list exactly like the Java side — the freshness stamp and the worker
                    // see generated files as ordinary sources.
                    List<Path> generatedKt = pluginContributedSources(ctx.require(LAYOUT), pluginDecls, ".kt");
                    List<Path> kspKt = kspGeneratedSources(ctx.require(LAYOUT), ".kt");
                    if (!generatedKt.isEmpty() || !kspKt.isEmpty()) {
                        ktSources = new ArrayList<>(ktSources);
                        ktSources.addAll(generatedKt);
                        ktSources.addAll(kspKt);
                        // Re-publish so write-stamp-kotlin records what this compile checked.
                        ctx.put(KOTLIN_SOURCES, ktSources);
                    }
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
                    // A shrunken Kotlin source set (a variant switch dropping an extra-src root)
                    // must not leave the dropped classes in the merged output: kotlinc's IC
                    // prunes its own dir, but the assemble merge into classes/ is additive.
                    // Start the merged tree clean — both stamps die with it, so javac re-runs
                    // too (rare: only on source removals).
                    if (dev.jkbuild.task.FreshnessStamp.hasRemovedSources(
                            classes, dev.jkbuild.task.FreshnessStamp.KOTLIN_STAMP, freshInputs)) {
                        dev.jkbuild.util.PathUtil.deleteRecursively(classes);
                        Files.createDirectories(classes);
                    }
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
                            kotlinJavaSourceRoots(
                                    mixedWithJava, compact, in.dir(), ctx.require(LAYOUT), pluginDecls));
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
                                mixedTest ? List.of(javaTestSrc) : null);
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
                        List<Path> processorCp =
                            (List<Path>) ctx.get(JAVAC_PROCESSOR_CP).orElseGet(() -> ctx.require(PROCESSOR_CP));
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

    private static Phase runTestsPhase(StepContext cx, PluginBuild.Declarations pluginDecls) {
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
                    testRtCp = new ArrayList<>(testRtCp);
                    // Plugin test-classpath contributions (contributesTestClasspath — e.g. the
                    // android plugin's Robolectric test_config dir) join the test runtime cp.
                    testRtCp.addAll(pluginTestClasspath(ctx.require(LAYOUT), pluginDecls));
                    // The provided platform (android.jar) rides LAST: unit tests calling framework
                    // stubs get the platform's throw-on-call contract (AGP's default posture), and
                    // anything real on the classpath shadows it.
                    testRtCp.addAll(contributedProvidedFor(ctx));
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
                    if (!rerun && stampKey != null) {
                        var greenRecord = actionCache.lookup(stampKey);
                        if (greenRecord.isPresent()) {
                            ctx.reweight(EffortWeights.SKIP);
                            ctx.label("tests up-to-date");
                            // Replay the green run's counts (stored on the marker) so the summary
                            // line reads "Passed N tests", not "No tests" — without this a
                            // legitimate skip was indistinguishable from a module with no test
                            // sources. Markers written before counts were stored replay nothing;
                            // the next real run upgrades them.
                            dev.jkbuild.run.TestSummary previous = stampedSummary(greenRecord.get());
                            if (previous != null) ctx.put(TEST_RESULT, previous);
                            return; // skip — nothing changed since last green run
                        }
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
                    // same, and the marker is found. The green counts ride the record so the
                    // skip path can replay them in its summary. (Skip if the key failed open —
                    // nothing to key the marker on.)
                    if (stampKey != null) {
                        actionCache.storeWithOutputs(
                                testTaskId,
                                stampKey,
                                java.util.Map.of(),
                                java.util.Map.of(
                                        "tests.total", String.valueOf(result.total()),
                                        "tests.succeeded", String.valueOf(result.succeeded()),
                                        "tests.skipped", String.valueOf(result.skipped())));
                    }
                })
                .build();
    }

    /** The green run's counts replayed off a run-tests marker; {@code null} for markers written
     * before counts were stored (or with unparseable ones) — the caller then replays nothing. */
    private static dev.jkbuild.run.TestSummary stampedSummary(dev.jkbuild.task.ActionCache.ActionRecord record) {
        try {
            String total = record.outputs().get("tests.total");
            if (total == null) return null;
            long succeeded = Long.parseLong(record.outputs().getOrDefault("tests.succeeded", total));
            long skipped = Long.parseLong(record.outputs().getOrDefault("tests.skipped", "0"));
            return new dev.jkbuild.run.TestSummary(
                    Long.parseLong(total), succeeded, 0, skipped, java.util.List.of());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Phase packageJarPhase(
            StepContext cx,
            PluginBuild.Active pluginActive,
            PluginBuild.Declarations pluginDecls,
            Map<String, String> variantSecrets) {
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
                .requires(packageRequires(in, pluginDecls))
                .weight(() -> plan.get().pkg())
                .scope(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path classes = ctx.require(MAIN_CLASSES);
                    Path jarPath = layout.mainJar();
                    if (pluginDecls != null && pluginDecls.packager() != null) {
                        // The packager's declared artifact extension replaces .jar (an APK, …).
                        jarPath = PluginBuild.mainArtifactPath(layout, pluginActive);
                        Files.createDirectories(jarPath.getParent());
                        packagePlugin(
                                ctx, in, cas, project, classes, jarPath, pluginActive, pluginDecls, variantSecrets);
                        return;
                    }
                    Files.createDirectories(jarPath.getParent());
                    String mainClass = project.mainClass();
                    // Application jars embed the lockfile-derived SBOM (libraries don't:
                    // their consumers' lockfiles are the truth for the final classpath).
                    byte[] sbom = null;
                    if (project.isApplication()) {
                        Lockfile sbomLock = ctx.get(LOCKFILE).orElse(null);
                        if (sbomLock != null) sbom = applicationSbom(project, sbomLock, cas);
                    }
                    // Packaging cache: the jar is a pure function of the main classes
                    // (resources already copied in), the main-class, the manifest, and
                    // the SBOM content (a lock change re-embeds).
                    List<String> tokens = List.of(
                            "classes:" + dev.jkbuild.task.ClasspathFingerprint.entry(classes),
                            "main:" + (mainClass == null ? "" : mainClass),
                            "sbom:" + (sbom == null ? "" : dev.jkbuild.util.Hashing.sha256Hex(sbom)),
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
                    Map<String, String> jarAttrs = new LinkedHashMap<>(project.manifest());
                    if (sbom != null) {
                        jarAttrs.put("Sbom-Format", "CycloneDX");
                        jarAttrs.put("Sbom-Location", SBOM_JAR_ENTRY);
                        jarRequest = jarRequest.withExtraEntries(Map.of(SBOM_JAR_ENTRY, sbom));
                    }
                    if (!jarAttrs.isEmpty()) jarRequest = jarRequest.withAttributes(jarAttrs);
                    new JarPackager().packageJar(jarRequest);
                    storePackaged(in.cache(), pkgTask, pkgKey, tokens, jarPath.getParent(), List.of(jarPath));
                    ctx.put(JAR_PATH, jarPath);
                    ctx.progress(1);
                })
                .build();
    }

    /** package-jar's requires: resources/tests as always, plus every before-PACKAGE plugin step. */
    private static String[] packageRequires(Inputs in, PluginBuild.Declarations decls) {
        List<String> requires = new ArrayList<>();
        requires.add("copy-resources");
        if (!in.skipTests()) requires.add("run-tests");
        if (decls != null) {
            for (PluginBuild.StepDecl step : decls.steps()) {
                if ("package".equals(step.before())) requires.add("plugin-" + step.name());
            }
        }
        return requires.toArray(new String[0]);
    }

    /**
     * One declared build-plugin step as a pipeline phase (build-plugins plan §3.2). The engine
     * owns the whole caching contract: the declared inputs are fingerprinted into the action key,
     * a hit restores the step's scratch and skips the worker entirely, a miss forks the plugin's
     * worker with the resolved inputs. The step body never learns any of this.
     */
    /** True when a declared step must run before the compilers (source generation). */
    static boolean beforeCompile(PluginBuild.StepDecl step) {
        return "compile".equals(step.before()) || !step.contributesSources().isEmpty();
    }

    /**
     * The (at most one) declared step whose output <em>replaces</em> the module's classes dir
     * ({@code StepSpec.transformsClasses} — bytecode weaving). Validated here so a bad declaration
     * fails the build's construction, not mid-run: the transform runs in the COMPILE→PACKAGE
     * window, consumes {@code In.classes()}, replaces rather than merges (no {@code contributes*}),
     * and its dir is a declared output. Two transforms are an error, not a priority — the plugin
     * ground rules.
     */
    static PluginBuild.StepDecl transformStep(PluginBuild.Declarations decls) {
        if (decls == null) return null;
        PluginBuild.StepDecl transform = null;
        for (PluginBuild.StepDecl s : decls.steps()) {
            if (!s.transforms()) continue;
            if (transform != null) {
                throw new IllegalStateException("plugin steps " + transform.name() + " and " + s.name()
                        + " both declare transformsClasses — at most one step may replace the classes dir"
                        + " (conflicts are errors, not priorities)");
            }
            if (beforeCompile(s)) {
                throw new IllegalStateException("plugin step " + s.name()
                        + " declares transformsClasses but runs before compile — a transform rewrites"
                        + " compiled classes (after COMPILE, before PACKAGE)");
            }
            if (!"package".equals(s.before())) {
                throw new IllegalStateException("plugin step " + s.name()
                        + " declares transformsClasses but not before(PACKAGE) — the transform must"
                        + " finish before anything consumes the replaced classes");
            }
            if (!s.inputs().contains("classes")) {
                throw new IllegalStateException("plugin step " + s.name()
                        + " declares transformsClasses but not In.classes() — the classes dir is what"
                        + " it transforms");
            }
            if (!s.contributesClasses().isEmpty() || !s.contributesResources().isEmpty()
                    || !s.contributesSources().isEmpty()) {
                throw new IllegalStateException("plugin step " + s.name()
                        + " declares transformsClasses and contributes* — a transform REPLACES the"
                        + " classes dir; contributions merge, and the two don't compose");
            }
            if (!s.outputs().contains(s.transformsClasses())) {
                throw new IllegalStateException("plugin step " + s.name() + " transformsClasses(\""
                        + s.transformsClasses() + "\") must name a declared output dir");
            }
            transform = s;
        }
        return transform;
    }

    private static Phase pluginStepPhase(
            StepContext cx, PluginBuild.Active active, PluginBuild.StepDecl step, PluginBuild.StepDecl transform) {
        Inputs in = cx.in();
        boolean beforeCompile = beforeCompile(step);
        if (beforeCompile && step.inputs().contains("classes")) {
            // Declared-input validation: a source-generating step runs before any classes exist.
            throw new IllegalStateException("plugin step " + step.name()
                    + " runs before compile but declares In.classes() — generated-source steps"
                    + " consume project files (In.projectFiles), config, or other step outputs");
        }
        List<String> requires = new ArrayList<>();
        if (beforeCompile) {
            requires.add("parse-build");
            requires.add("sync-deps");
            requires.add("ensure-jdk");
        } else {
            requires.add("copy-resources");
            if ("test".equals(step.after()) && !in.skipTests()) requires.add("run-tests");
        }
        // A step consuming another step's output (In.stepOutput) runs after it — the edge
        // that lets a chain like merge-manifest → aapt2-link order itself inside one anchor
        // window (build-plugins SPI: declared inputs ARE the dependency graph).
        for (String input : step.inputs()) {
            if (input.startsWith("step:")) requires.add("plugin-" + input.substring("step:".length()));
        }
        // Any other classes-consuming step reads MAIN_CLASSES, which the transform re-points —
        // it must observe the transformed dir, never race the transform (dex after Hilt's rewrite).
        if (transform != null && !step.name().equals(transform.name()) && step.inputs().contains("classes")) {
            requires.add("plugin-" + transform.name());
        }
        return Phase.builder("plugin-" + step.name())
                .label(step.name())
                .kind(PhaseKind.CPU)
                .requires(requires.toArray(new String[0]))
                .scope(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path classes = ctx.require(MAIN_CLASSES);
                    Path javaHome = ctx.require(JAVA_HOME);
                    Path scratch = PluginBuild.stepScratch(layout, step.name());
                    // Before compile no classes exist to scan — the declared main (or null) rides.
                    String startClass = beforeCompile(step)
                            ? project.mainClass()
                            : resolvedMain(project, in.dir(), classes);

                    List<Path> classpath =
                            PluginBuild.productionClasspath(in.dir(), in.cache(), in.lockFile(), project);
                    List<PluginBuild.ProdEntry> prodEntries = step.inputs().contains("runtime-entries")
                            ? PluginBuild.productionEntries(in.dir(), in.cache(), in.lockFile(), project)
                            : List.of();

                    // Manifest-contributed tool artifacts (aapt2, r8, a platform jar) — fetched
                    // into the cache, handed to the body by artifact name, keyed like any input.
                    java.util.Map<String, Path> toolExtras =
                            PluginBuild.fetchStepDependencies(project, in.dir(), cx.cas(), PluginBuild.sdkPins(in.lockFile()));

                    // Action key: exactly the declared inputs, plus the facts the body sees.
                    List<String> tokens = new ArrayList<>();
                    for (String input : step.inputs()) {
                        switch (input) {
                            case "classes" ->
                                tokens.add("classes:" + dev.jkbuild.task.ClasspathFingerprint.entry(classes));
                            case "runtime-classpath" ->
                                tokens.add("cp:" + dev.jkbuild.task.ClasspathFingerprint.of(classpath));
                            case "runtime-entries" -> {
                                tokens.add("cp:" + dev.jkbuild.task.ClasspathFingerprint.of(classpath));
                                for (var pe : prodEntries) {
                                    if (pe.container() != null) {
                                        tokens.add("container:" + pe.fileName() + ":"
                                                + dev.jkbuild.task.ClasspathFingerprint.entry(pe.container()));
                                    }
                                }
                            }
                            case "config" -> tokens.add("config:" + PluginBuild.configToken(active.config()));
                            default -> {
                                if (input.startsWith("step:")) {
                                    Path other = PluginBuild.stepScratch(layout, input.substring("step:".length()));
                                    tokens.add(input + ":" + dev.jkbuild.task.ClasspathFingerprint.entry(other));
                                } else if (input.startsWith("project:")) {
                                    Path files = in.dir().resolve(input.substring("project:".length()));
                                    tokens.add(input + ":" + dev.jkbuild.task.ClasspathFingerprint.entry(files));
                                }
                            }
                        }
                    }
                    for (var tool : toolExtras.entrySet()) {
                        tokens.add("tool:" + tool.getKey() + ":"
                                + dev.jkbuild.task.ClasspathFingerprint.entry(tool.getValue()));
                    }
                    tokens.add("facts:" + project.project().group() + ":" + project.project().name() + ":"
                            + project.project().version() + ":" + project.project().javaRelease() + ":"
                            + startClass);
                    // The step's CODE is an input: a changed plugin worker jar must re-run the
                    // step, or a plugin upgrade (or first-party dev iteration) silently restores
                    // outputs produced by the old code.
                    tokens.add("worker:"
                            + dev.jkbuild.task.ClasspathFingerprint.entry(
                                    PluginBuild.workerJarFor(active, in.cache())));
                    String taskId = ActionKey.qualifiedTaskId("plugin-" + step.name(), scratch);
                    String actionKey = ActionKey.forArtifact(taskId, dev.jkbuild.util.JkVersion.VERSION, tokens);
                    dev.jkbuild.task.ActionCache actionCache = cx.actionCache();
                    var hit = actionCache.lookup(actionKey);
                    if (hit.isPresent()) {
                        try {
                            actionCache.restore(hit.get(), scratch);
                            if (step.transforms()) {
                                ctx.put(MAIN_CLASSES, scratch.resolve(step.transformsClasses()));
                            }
                            ctx.label(step.name() + " up-to-date");
                            ctx.progress(1);
                            return;
                        } catch (IOException e) {
                            // A missing CAS blob (pruned cache) falls through to a fresh run.
                        }
                    }

                    dev.jkbuild.util.PathUtil.deleteRecursively(scratch); // stale outputs never survive
                    Files.createDirectories(scratch);
                    ctx.label(step.name());
                    PluginBuild.SpecWriter specWriter = new PluginBuild.SpecWriter()
                            .op("run-step", step.name(), active.manifest().id())
                            .config(active.config())
                            .project(project, startClass)
                            .layout(classes, in.dir(), scratch)
                            .javaHome(javaHome)
                            .classpath(classpath);
                    for (var pe : prodEntries) {
                        specWriter.entry(pe.fileName(), pe.jar(), pe.snapshot(), pe.container());
                    }
                    for (var tool : toolExtras.entrySet()) {
                        specWriter.extra(tool.getKey(), tool.getValue());
                    }
                    for (String input : step.inputs()) {
                        if (input.startsWith("step:")) {
                            String other = input.substring("step:".length());
                            specWriter.stepOutput(other, PluginBuild.stepScratch(layout, other));
                        }
                    }
                    Path spec = specWriter.write();
                    try {
                        PluginBuild.runWorker(active, in.cache(), spec, ctx::label);
                    } catch (IOException e) {
                        ctx.error(step.name(), e.getMessage());
                        throw e;
                    } finally {
                        Files.deleteIfExists(spec);
                    }
                    actionCache.store(taskId, actionKey, java.util.Map.of(), scratch);
                    // A transform's output IS the classes dir from here on: re-point MAIN_CLASSES
                    // so packaging, later steps' In.classes(), and the native tail read it
                    // (ordering: consumers carry a requires edge on this phase).
                    if (step.transforms()) {
                        ctx.put(MAIN_CLASSES, scratch.resolve(step.transformsClasses()));
                    }
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * Run the plugin's packager in place of plain jar packaging (build-plugins plan §3.3). The
     * engine keys the artifact cache on the packager's declared inputs (same rebuild triggers the
     * hand-written boot-jar path had), fetches manifest-declared packager dependencies, prepares
     * the SBOM, and hands the worker coordinate-named runtime entries — the worker only assembles.
     */
    private static void packagePlugin(
            PhaseContext ctx,
            Inputs in,
            Cas cas,
            JkBuild project,
            Path classes,
            Path jarPath,
            PluginBuild.Active active,
            PluginBuild.Declarations decls,
            Map<String, String> secrets)
            throws Exception {
        Lockfile lock = ctx.require(LOCKFILE);
        BuildLayout layout = ctx.require(LAYOUT);
        ClasspathResolver resolver = new ClasspathResolver(cas);
        String startClass = resolvedMain(project, in.dir(), classes);

        // Coordinate-named runtime entries + the SBOM components they imply.
        record Entry(String fileName, Path jar, boolean snapshot, Path container) {}
        List<Entry> entries = new ArrayList<>();
        List<CycloneDxSbom.Component> sbomComponents = new ArrayList<>();
        for (ClasspathResolver.Entry entry : resolver.entriesFor(lock, ClasspathResolver.RUNTIME)) {
            Lockfile.Artifact a = entry.artifact();
            entries.add(new Entry(
                    a.moduleArtifact() + "-" + a.version() + (entry.container() != null ? ".aar" : ".jar"),
                    entry.jar(),
                    a.version().contains("SNAPSHOT"),
                    entry.container()));
            sbomComponents.add(new CycloneDxSbom.Component(
                    a.moduleGroup(), a.moduleArtifact(), a.version(), a.checksumHex()));
        }
        // Packagers get the packager-dependency artifacts AND the step-dependency tools (the
        // same artifacts verbs receive — an AAB packager forks bundletool exactly like a step
        // forks aapt2). A packager-dependency wins a name collision.
        java.util.Map<String, Path> extras = new LinkedHashMap<>(
                PluginBuild.fetchStepDependencies(project, in.dir(), cas, PluginBuild.sdkPins(in.lockFile())));
        extras.putAll(PluginBuild.fetchPackagerDependencies(project, in.dir(), cas));

        // Action key from the declared inputs + facts — any config, classes, dependency-set,
        // step-output, extra-artifact, or manifest change re-packages; nothing else does.
        List<Path> entryJars = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            if (e.jar() != null) entryJars.add(e.jar());
        }
        List<String> tokens = new ArrayList<>();
        for (String input : decls.packager().inputs()) {
            switch (input) {
                case "classes" -> tokens.add("classes:" + dev.jkbuild.task.ClasspathFingerprint.entry(classes));
                case "runtime-classpath", "runtime-entries" -> {
                    tokens.add("libs:" + dev.jkbuild.task.ClasspathFingerprint.of(entryJars));
                    // Container content (an AAR's res/assets/jni) is packaged input too — an
                    // assets-only AAR bump must re-package even though no classes jar changed.
                    for (Entry e : entries) {
                        if (e.container() != null) {
                            tokens.add("container:" + e.fileName() + ":"
                                    + dev.jkbuild.task.ClasspathFingerprint.entry(e.container()));
                        }
                    }
                }
                case "config" -> tokens.add("config:" + PluginBuild.configToken(active.config()));
                default -> {
                    if (input.startsWith("step:")) {
                        Path other = PluginBuild.stepScratch(layout, input.substring("step:".length()));
                        tokens.add(input + ":" + dev.jkbuild.task.ClasspathFingerprint.entry(other));
                    } else if (input.startsWith("project:")) {
                        Path files = in.dir().resolve(input.substring("project:".length()));
                        tokens.add(input + ":" + dev.jkbuild.task.ClasspathFingerprint.entry(files));
                    }
                }
            }
        }
        List<Path> extraJars = new ArrayList<>(extras.values());
        tokens.add("extras:" + dev.jkbuild.task.ClasspathFingerprint.of(extraJars));
        if (!secrets.isEmpty()) {
            // A changed signing credential re-signs (the signature is part of the artifact);
            // the key carries only a digest — a secret value never appears anywhere readable.
            StringBuilder sb = new StringBuilder();
            for (var e : new java.util.TreeMap<>(secrets).entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            tokens.add("secrets:" + dev.jkbuild.util.Hashing.sha256Hex(
                    sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        tokens.add("facts:" + project.project().group() + ":" + project.project().name() + ":"
                + project.project().version() + ":" + startClass);
        tokens.add("manifest:" + project.manifest());
        // The packager's CODE is an input, same as plugin steps (see pluginStepPhase).
        tokens.add("worker:"
                + dev.jkbuild.task.ClasspathFingerprint.entry(PluginBuild.workerJarFor(active, in.cache())));
        String pkgTask = ActionKey.qualifiedTaskId("package-jar", jarPath);
        String pkgKey = ActionKey.forArtifact(pkgTask, dev.jkbuild.util.JkVersion.VERSION, tokens);
        if (restorePackaged(in.cache(), pkgKey, jarPath.getParent())) {
            ctx.put(JAR_PATH, jarPath);
            ctx.label(jarPath.getFileName() + " up-to-date");
            ctx.progress(1);
            return;
        }

        // SBOM (always on): free and deterministic straight from the lockfile.
        byte[] sbom = CycloneDxSbom.write(
                project.project().group(), project.project().name(), project.project().version(), sbomComponents);
        Path sbomFile = Files.createTempFile("jk-plugin-sbom-", ".cdx.json");
        Files.write(sbomFile, sbom);

        PluginBuild.SpecWriter spec = new PluginBuild.SpecWriter()
                .op("package", null, active.manifest().id())
                .config(active.config())
                .project(project, startClass)
                .layout(classes, in.dir(), layout.moduleTargetDir().resolve("plugin"))
                .javaHome(ctx.require(JAVA_HOME))
                .artifact(jarPath);
        for (Entry e : entries) spec.entry(e.fileName(), e.jar(), e.snapshot(), e.container());
        for (var e : extras.entrySet()) spec.extra(e.getKey(), e.getValue());
        for (var e : secrets.entrySet()) spec.secret(e.getKey(), e.getValue());
        spec.extra("sbom", sbomFile);
        for (PluginBuild.StepDecl step : decls.steps()) {
            Path scratch = PluginBuild.stepScratch(layout, step.name());
            if (Files.isDirectory(scratch)) spec.stepOutput(step.name(), scratch);
        }
        Path specFile = spec.write();
        // A stale conventional sibling from an earlier run must never survive a re-package.
        String staleName = jarPath.getFileName().toString();
        int staleDot = staleName.lastIndexOf('.');
        if (staleDot > 0 && !staleName.endsWith(".jar")) {
            Files.deleteIfExists(jarPath.resolveSibling(staleName.substring(0, staleDot) + ".jar"));
        }
        try {
            PluginBuild.runWorker(active, in.cache(), specFile, ctx::label);
        } catch (IOException e) {
            ctx.error("package", e.getMessage());
            throw e;
        } finally {
            Files.deleteIfExists(specFile);
            Files.deleteIfExists(sbomFile);
        }
        if (!Files.isRegularFile(jarPath)) {
            throw new IOException(
                    "plugin packager " + decls.packager().name() + " reported success but produced no " + jarPath);
        }
        // A container packager (an AAR) may also emit the conventional classes jar next to the
        // main artifact — the host-classpath view workspace siblings compile against. Both cache
        // under the same key so a hit restores the pair.
        List<Path> produced = new ArrayList<>();
        produced.add(jarPath);
        String artifactName = jarPath.getFileName().toString();
        int dot = artifactName.lastIndexOf('.');
        if (dot > 0 && !artifactName.endsWith(".jar")) {
            Path conventional = jarPath.resolveSibling(artifactName.substring(0, dot) + ".jar");
            if (Files.isRegularFile(conventional)) produced.add(conventional);
        }
        storePackaged(in.cache(), pkgTask, pkgKey, tokens, jarPath.getParent(), produced);
        ctx.put(JAR_PATH, jarPath);
        ctx.progress(1);
    }

    /** The resolved application entry point: declared, else the unique compiled main (when scannable). */
    private static String resolvedMain(JkBuild project, Path moduleDir, Path classes) throws IOException {
        String main = project.mainClass();
        if ((main == null || main.isBlank())
                && PluginBuild.shape(project, moduleDir).map(sh -> sh.mainScan()).orElse(false)) {
            main = dev.jkbuild.layout.MainClassScanner.scanUnique(classes);
        }
        return main;
    }

    /**
     * The application SBOM entry + manifest headers shared by every packager: the lockfile's
     * production RUNTIME components as CycloneDX (see {@link CycloneDxSbom}). Returns null when
     * there is no lockfile to speak from.
     */
    static byte[] applicationSbom(JkBuild project, Lockfile lock, Cas cas) {
        List<CycloneDxSbom.Component> components = new ArrayList<>();
        for (ClasspathResolver.Entry entry : new ClasspathResolver(cas).entriesFor(lock, ClasspathResolver.RUNTIME)) {
            Lockfile.Artifact a = entry.artifact();
            components.add(new CycloneDxSbom.Component(
                    a.moduleGroup(), a.moduleArtifact(), a.version(), a.checksumHex()));
        }
        return CycloneDxSbom.write(
                project.project().group(), project.project().name(), project.project().version(), components);
    }

    /** SBOM path inside plain/shadow application jars (jar root = classpath root). */
    static final String SBOM_JAR_ENTRY = "META-INF/sbom/application.cdx.json";

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
            if (project.shadowJar()) {
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
                            "main:" + (project.mainClass() == null ? "" : project.mainClass()),
                            "manifest:" + project.manifest());
                    String shTask = ActionKey.qualifiedTaskId("package-shadow", shadowJar);
                    String shKey = ActionKey.forArtifact(shTask, dev.jkbuild.util.JkVersion.VERSION, tokens);
                    if (restorePackaged(cache, shKey, shadowJar.getParent())) {
                        ctx.label(shadowJar.getFileName() + " up-to-date");
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("package " + shadowJar.getFileName());
                    byte[] shadowSbom = null;
                    Map<String, String> shadowAttrs = new LinkedHashMap<>(project.manifest());
                    if (Files.exists(lockFile)) {
                        shadowSbom = applicationSbom(project, LockfileReader.read(lockFile), new Cas(cache));
                        shadowAttrs.put("Sbom-Format", "CycloneDX");
                        shadowAttrs.put("Sbom-Location", SBOM_JAR_ENTRY);
                    }
                    new ShadowPackager()
                            .packageShadow(new ShadowPackager.ShadowRequest(
                                    classes,
                                    depJars,
                                    shadowJar,
                                    project.mainClass(),
                                    shadowAttrs,
                                    shadowSbom == null ? Map.of() : Map.of(SBOM_JAR_ENTRY, shadowSbom),
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
                    JkBuild.NativeConfig nativeCfg = project.nativeConfig()
                            .orElseGet(() -> new JkBuild.NativeConfig(null, null, List.of(), null, false));
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path mainJar = layout.mainJar();
                    if (!Files.exists(mainJar)) {
                        ctx.error("native", "jar not found at " + mainJar);
                        throw new RuntimeException("missing main jar for native-image");
                    }
                    // Resolution order: --main CLI flag > [native].main-class > [application].main.
                    // A resolvable main → executable; none → shared library (--shared).
                    String mainClass = (mainOverride != null && !mainOverride.isBlank())
                            ? mainOverride
                            : (nativeCfg.mainClass() != null ? nativeCfg.mainClass() : project.mainClass());
                    if ((mainClass == null || mainClass.isBlank())
                            && PluginBuild.shape(project, dir).map(sh -> sh.mainScan()).orElse(false)) {
                        // main-scan packagers carry exactly one main — same scan packaging used.
                        mainClass = dev.jkbuild.layout.MainClassScanner.scanUnique(layout.classesDir());
                    }
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
                    if (PluginBuild.shape(project, dir).map(sh -> sh.classesRun()).orElse(false)) {
                        // A classes-run packager's jar is not classpath-able (e.g. Boot's
                        // BOOT-INF nesting) — native-image gets the exploded classes plus
                        // whatever the plugin's steps contributed (generated classes +
                        // META-INF/native-image hints), produced just before this phase.
                        classpath.add(layout.classesDir());
                        var activeOpt = PluginBuild.activeCodePlugin(project, dir);
                        if (activeOpt.isPresent()) {
                            var decls = PluginBuild.declarations(
                                    activeOpt.get(), project, dir, cache, layout.moduleTargetDir());
                            for (Path contributed : PluginBuild.contributedDirs(decls, layout)) {
                                if (Files.isDirectory(contributed)) classpath.add(contributed);
                            }
                        }
                    } else {
                        classpath.add(mainJar);
                    }
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

                    // Reachability metadata (general, not Boot-specific): third-party libs
                    // publish native-image config to the GraalVM metadata repository rather
                    // than their own jars. Matched dirs ride -H:ConfigurationFileDirectories;
                    // unavailable (offline) degrades to building without it.
                    List<Path> metadataDirs = List.of();
                    if (Files.exists(lockFile)) {
                        Lockfile metaLock = LockfileReader.read(lockFile);
                        List<Lockfile.Artifact> runtimeArtifacts = new ArrayList<>();
                        for (Lockfile.Artifact a : metaLock.artifacts()) {
                            if (a.inAnyScope(ClasspathResolver.RUNTIME) && a.checksum() != null) {
                                runtimeArtifacts.add(a);
                            }
                        }
                        dev.jkbuild.repo.RepoGroup metaRepos = RepoGroupBuilder.buildFor(project, null, new Cas(cache));
                        metadataDirs = ReachabilityMetadata.configDirs(
                                cache, metaRepos, runtimeArtifacts, msg -> ctx.label(msg));
                    }
                    if (!metadataDirs.isEmpty()) {
                        StringBuilder dirsArg = new StringBuilder();
                        for (Path d : metadataDirs) {
                            if (dirsArg.length() > 0) dirsArg.append(',');
                            dirsArg.append(d.toAbsolutePath());
                        }
                        // Prepended (before [native].args + CLI extras) so user flags win;
                        // the unlock pair scopes the experimental option to just this flag.
                        List<String> withMeta = new ArrayList<>();
                        withMeta.add("-H:+UnlockExperimentalVMOptions");
                        withMeta.add("-H:ConfigurationFileDirectories=" + dirsArg);
                        withMeta.add("-H:-UnlockExperimentalVMOptions");
                        withMeta.addAll(allArgs);
                        allArgs = withMeta;
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

    /**
     * The resolved paths of {@code [[contribute.provided-classpath]]} entries — declared
     * step-dependency artifacts (an SDK platform jar) that join the COMPILE classpaths only.
     */
    private static List<Path> contributedProvidedClasspath(
            dev.jkbuild.model.JkBuild project, Inputs in, dev.jkbuild.cache.Cas cas) {
        List<String> names = dev.jkbuild.plugin.manifest.PluginContributions.providedClasspath(project, in.dir());
        if (names.isEmpty()) return List.of();
        try {
            java.util.Map<String, Path> fetched = PluginBuild.fetchStepDependencies(
                    project, in.dir(), cas, PluginBuild.sdkPins(in.lockFile()));
            List<Path> out = new ArrayList<>();
            for (String name : names) {
                Path path = fetched.get(name);
                if (path == null) {
                    throw new RuntimeException("[[contribute.provided-classpath]] names `" + name
                            + "` but no step-dependency resolved under that artifact name");
                }
                out.add(path);
            }
            return out;
        } catch (java.io.IOException | InterruptedException e) {
            throw new RuntimeException("cannot resolve the plugin-contributed compile classpath: " + e.getMessage(),
                    e);
        }
    }

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
     * @param javaSourceRoots when non-empty, passed as {@code -Xjava-source-roots} so a mixed module's
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
            List<Path> javaSourceRoots)
            throws IOException {
        String kotlinVersion = CompileToolchain.kotlinVersionFor(ctx.require(LOCKFILE), ctx.require(PROJECT));
        KotlinWorkerSetup.Prepared kt;
        // The installed plugins' [[contribute.kotlin-plugin]] entries (e.g. spring-boot's
        // all-open, and no-arg gated on jakarta.persistence via classpath-has) — evaluated
        // from the manifest, fetched version-locked to the compiler actually used. The
        // embeddable variants match the BTA worker's embeddable compiler.
        java.util.Set<String> lockModules = lockModules(ctx.require(LOCKFILE));
        List<KotlincRequest.Plugin> ktPlugins = new ArrayList<>();
        try {
            dev.jkbuild.repo.RepoGroup repos = RepoGroupBuilder.buildFor(ctx.require(PROJECT), null, cas);
            kt = KotlinWorkerSetup.prepare(repos, cas, kotlinVersion);
            // Same null-defaulting as KotlinWorkerSetup.prepare — a contributed plugin must
            // match the compiler actually used.
            String pluginVersion = (kotlinVersion == null || kotlinVersion.isBlank())
                    ? dev.jkbuild.kotlin.KotlinResolver.DEFAULT_VERSION
                    : kotlinVersion;
            for (var use : dev.jkbuild.plugin.manifest.PluginContributions.kotlinPlugins(
                    ctx.require(PROJECT), workingDir, pluginVersion, lockModules)) {
                Path jar = repos.tryFetchArtifact(
                                dev.jkbuild.model.Coordinate.of(use.group(), use.artifact(), use.version()))
                        .map(hit -> hit.fetched().cachePath())
                        .orElseThrow(() -> new RuntimeException("cannot fetch the " + use.id()
                                + " Kotlin compiler plugin (" + use.group() + ":" + use.artifact() + ":"
                                + use.version() + ") — a plugin contribution requires it"));
                ktPlugins.add(new KotlincRequest.Plugin(use.id(), jar, use.options()));
            }
            // Project-declared [[kotlin-plugins]] (serialization et al.) ride the same lane;
            // an omitted coordinate version means "match the compiler" — the org.jetbrains.kotlin
            // plugin convention, and the only version that can load into this kotlinc anyway.
            for (var decl : ctx.require(PROJECT).build().kotlinPlugins()) {
                String[] parts = decl.coordinate().split(":");
                String version = parts.length == 3 ? parts[2] : pluginVersion;
                Path jar = repos.tryFetchArtifact(dev.jkbuild.model.Coordinate.of(parts[0], parts[1], version))
                        .map(hit -> hit.fetched().cachePath())
                        .orElseThrow(() -> new RuntimeException("cannot fetch the " + decl.id()
                                + " Kotlin compiler plugin (" + parts[0] + ":" + parts[1] + ":" + version
                                + ") — declared under [[kotlin-plugins]]"));
                ktPlugins.add(new KotlincRequest.Plugin(decl.id(), jar, decl.options()));
            }
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
        // Contributed kotlinc args (e.g. spring-boot's -java-parameters, mirroring its javac
        // -parameters — Boot reflects on parameter names). User-position args still win: these
        // sit before extraArgs additions exactly where the hard-coded flag used to.
        for (String arg : dev.jkbuild.plugin.manifest.PluginContributions.kotlinArgs(
                ctx.require(PROJECT), workingDir, lockModules)) {
            if (!ktArgs.contains(arg)) ktArgs.add(arg);
        }
        // Compiler plugins ride the typed BTA COMPILER_PLUGINS argument — raw -Xplugin/-P
        // strings in extraArgs are silently ignored by the BTA execution path.
        if (javaSourceRoots != null && !javaSourceRoots.isEmpty()) {
            StringBuilder roots = new StringBuilder();
            for (Path root : javaSourceRoots) {
                if (roots.length() > 0) roots.append(',');
                roots.append(root.toAbsolutePath());
            }
            ktArgs.add("-Xjava-source-roots=" + roots);
        }
        Files.createDirectories(outputDir);
        String moduleName = ctx.require(PROJECT).project().name();
        // The incremental state is only valid for the exact compile CONFIG that produced it:
        // BTA's IC sees "no source changes" after an args/plugins/module-name change and would
        // emit nothing into a clean output dir. Key the working dir by a config hash so any
        // config change starts fresh IC state (stale dirs age out with the cache).
        String configToken = dev.jkbuild.util.Hashing.sha256Hex((CompileSupport.kotlinJvmTarget(
                                        ctx.require(RELEASE))
                                + "|" + moduleName + "|" + String.join(",", ktArgs) + "|"
                                + ktPlugins.stream()
                                        .map(p -> p.id() + "=" + p.options())
                                        .collect(java.util.stream.Collectors.joining(",")))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .substring(0, 12);
        Path icWorkingDir = workingDir == null
                ? null
                : workingDir.resolveSibling(workingDir.getFileName() + "-" + configToken);
        KotlincRequest req = KotlincRequest.builder()
                .sources(sources)
                .classpath(compileCp)
                .outputDir(outputDir)
                .jvmTarget(CompileSupport.kotlinJvmTarget(ctx.require(RELEASE)))
                .workerClasspath(kt.workerClasspath())
                .javaHome(ctx.require(JAVA_HOME))
                .workingDir(icWorkingDir)
                .snapshotDir(in.cache().resolve("kotlin-cp-snapshots"))
                .extraArgs(ktArgs)
                .plugins(ktPlugins)
                // Lockstep with the KSP round's -module-name: internal-member mangling
                // (member$module_name) is baked into call sites KSP-generated Java emits
                // (Hilt factories calling internal providers).
                .moduleName(moduleName)
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

    /** The resolved lock's {@code group:artifact} names — the classpath-has condition's universe. */
    static java.util.Set<String> lockModules(Lockfile lock) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (var a : lock.artifacts()) out.add(a.name());
        return out;
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
            Path jar = sib.shadowJar() ? layout.shadowJar() : layout.mainJar();
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
