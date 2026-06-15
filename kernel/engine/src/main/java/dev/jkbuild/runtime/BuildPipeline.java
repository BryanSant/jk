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
import dev.jkbuild.resolver.pubgrub.UnsatisfiableException;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseContext;
import dev.jkbuild.run.PhaseKind;
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
    @SuppressWarnings("rawtypes")
    public static final GoalKey<List> TEST_SOURCES     = GoalKey.of("test-sources",   List.class);
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
    static final int W_PARSE        = 4;
    static final int W_SYNC         = 6;
    static final int W_JDK          = 3;
    static final int W_COMPILE      = 30;
    static final int W_COMPILE_KT   = 30;
    static final int W_ASSEMBLE     = 2;
    static final int W_RESOURCES    = 1;
    static final int W_COMPILE_TEST = 12;
    static final int W_RUN_TESTS    = 30;
    static final int W_PACKAGE      = 5;
    static final int W_STAMP        = 1;
    static final int W_SHADOW       = 8;
    static final int W_NATIVE       = 50;

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
            compactLayout = CompileSupport.isSimpleLayout(project, in.dir());
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
                .weight(W_PARSE)
                .scope(() -> {
                    if (Files.exists(in.lockFile())) {
                        try { return LockfileReader.read(in.lockFile()).artifacts().size() + 5; }
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
                        LockFlow.Result result;
                        try {
                            result = LockFlow.run(in.lockDir(), in.cache(), List.of(), true, null);
                        } catch (UnsatisfiableException e) {
                            ctx.error("verbatim", e.getMessage());
                            throw new RuntimeException("dependency resolution failed");
                        }
                        if (result.status() != 0) {
                            ctx.error("lock", result.error() != null
                                    ? result.error() : "dependency resolution failed");
                            throw new RuntimeException("lock failed");
                        }
                        ctx.put(LOCKFILE, result.lockfile());
                    } else if (AutoLock.isStale(in.dir(), in.lockFile())) {
                        ctx.label("jk.toml changed — updating lock");
                        Lockfile existing = LockfileReader.read(in.lockFile());
                        Lockfile updated = AutoLock.maybeReLock(
                                in.dir(), existing, in.lockFile(), in.cache(), null,
                                dev.jkbuild.util.JkVersion.VERSION,
                                List.of(), true, dev.jkbuild.resolver.ResolveObserver.NOOP);
                        ctx.put(LOCKFILE, updated != null ? updated : existing);
                    } else {
                        ctx.put(LOCKFILE, LockfileReader.read(in.lockFile()));
                    }

                    Lockfile lock = ctx.require(LOCKFILE);
                    // Reading the lock keeps its deps fresh against the 90-day cache GC.
                    dev.jkbuild.task.AccessLedger.atDefaultPath().touchLock(lock);
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
                    // Add external deps from transitive workspace siblings' lockfiles so
                    // that e.g. tomlj declared in jk-core is available when compiling jk-io.
                    for (java.nio.file.Path sibLock : mainSiblings.siblingLockfiles()) {
                        try {
                            dev.jkbuild.lock.Lockfile sibLockfile =
                                    dev.jkbuild.lock.LockfileReader.read(sibLock);
                            for (Path p : resolver.classpathFor(sibLockfile,
                                    ClasspathResolver.COMPILE_MAIN)) {
                                if (!mainCp.contains(p)) mainCp.add(p);
                            }
                        } catch (Exception ignored) { /* best-effort */ }
                    }

                    // Composite source deps (`path` + branch-git): build from source
                    // and inject onto the main compile classpath (jk's includeBuild).
                    List<String> mainComposite = addCompositeDeps(in.dir(), project, cas, in.cache(),
                            Set.of(Scope.MAIN), ClasspathResolver.COMPILE_MAIN, mainCp);
                    if (!mainComposite.isEmpty()) {
                        for (String e : mainComposite) ctx.error("composite", e);
                        throw new RuntimeException("composite dependency build failed");
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
                        } catch (Exception ignored) { /* best-effort */ }
                    }
                    // Composite deps on the test classpaths too (compile + runtime scopes).
                    List<String> testComposite = addCompositeDeps(in.dir(), project, cas, in.cache(),
                            Set.of(Scope.MAIN, Scope.TEST), ClasspathResolver.COMPILE_TEST, compileTestCp);
                    addCompositeDeps(in.dir(), project, cas, in.cache(),
                            Set.of(Scope.MAIN, Scope.TEST), ClasspathResolver.RUNTIME, testRuntimeCp);
                    if (!testComposite.isEmpty()) {
                        for (String e : testComposite) ctx.error("composite", e);
                        throw new RuntimeException("composite dependency build failed");
                    }
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
                .weight(W_SYNC)
                .scope(() -> {
                    try { return LockfileReader.read(in.lockFile()).artifacts().size(); }
                    catch (Exception ignored) { return 10; }
                })
                .execute(ctx -> {
                    Lockfile lock = ctx.require(LOCKFILE);
                    // Scope is already counted up front by estimateScope() (artifact
                    // count); progress(1)-per-artifact below fills it.
                    var observer = new CacheSync.ProgressObserver() {
                        @Override public void fetched(Lockfile.Artifact pkg) {
                            ctx.label("fetched " + pkg.name());
                            ctx.progress(1);
                        }
                        @Override public void upToDate(Lockfile.Artifact pkg) { ctx.progress(1); }
                        @Override public void skipped(Lockfile.Artifact pkg)  { ctx.progress(1); }
                        @Override public void failed(Lockfile.Artifact pkg, String err) {
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
                .weight(W_JDK)
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
                // Scope counts sources (granularity); weight is the bar share. javac
                // is opaque — one progress(sources.size()) on completion — so ease the
                // slice forward over time while it runs instead of sitting flat.
                .weight(W_COMPILE)
                .interpolated()
                .scope(() -> estimateJavaSources(in, compact))
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
                        ap = new dev.jkbuild.task.JavaIncrementalCompile.ApSetup(() -> {
                            try {
                                return WorkerJar.JAVA_COMPILER.locate(cas);
                            } catch (RuntimeException e) {
                                ctx.warn("javac", "java-compiler worker unavailable ("
                                        + e.getMessage() + "); compiling with plain javac"
                                        + " (no incremental annotation-processing provenance)");
                                return null;
                            }
                        }, genDir);
                    }
                    ctx.label("compiling " + sources.size() + " sources");
                    dev.jkbuild.task.JavaIncrementalCompile.Result r =
                            dev.jkbuild.task.JavaIncrementalCompile.run(
                                    taskId, request, dev.jkbuild.util.JkVersion.VERSION,
                                    !noCache, cas, actionCache, javaStateDir, ap);
                    ctx.put(ACTION_KEY, r.actionKey());
                    // Forward every javac diagnostic to the terminal, by severity:
                    // errors fail the build, warnings/notes (e.g. deprecation) are
                    // surfaced but don't. Strip the leading severity word — the
                    // console renderer adds its own ✗/⚠ marker.
                    for (CompileResult.Diagnostic d : r.diagnostics()) {
                        String msg = diagnosticMessage(d);
                        if (d.severity() == CompileResult.Severity.ERROR) ctx.error("javac", msg);
                        else ctx.warn("javac", msg);
                    }
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
                // Scope counts Kotlin sources (granularity); weight is the bar share
                // (see compile-java). kotlinc is opaque too, so ease it over time.
                .weight(W_COMPILE_KT)
                .interpolated()
                .scope(() -> estimateKotlinSources(in, compact))
                .execute(ctx -> {
                    Path classes = ctx.require(MAIN_CLASSES);
                    Files.createDirectories(classes);   // compile-java may be skipped
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
                .weight(W_RESOURCES)
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
                .weight(W_COMPILE_TEST)
                .interpolated()   // opaque javac/kotlinc call — ease it over time
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
                .requires("compile-test", "copy-resources", "embed-sha")
                .weight(W_RUN_TESTS)
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
                    List<Path> testSrcs = ctx.get(TEST_SOURCES)
                            .orElse(java.util.List.of());
                    // Worker jars handed to the test JVM ([build.test-worker-jars]) —
                    // worker-forking tests' behavior depends on their content, so resolve
                    // them up front so they also feed the freshness key below.
                    Map<String, String> workerJars = workerJarProps(
                            in.dir(), ctx.require(PROJECT).build().testWorkerJars());

                    // Incremental test skip: a content key over every input that affects
                    // the outcome — own main output, test sources, the *content* of the
                    // runtime classpath (sibling members included), the lock, and the
                    // toolchain/runner/worker identity. Unchanged → skip the runner.
                    String stampKey = dev.jkbuild.task.TestStamp.computeKey(
                            testSrcs, ctx.require(MAIN_CLASSES), in.lockFile(), testRtCp,
                            testStampExtras(workerJars));
                    if (dev.jkbuild.task.TestStamp.isFresh(testClassesForStamp, stampKey)) {
                        ctx.label("tests up-to-date");
                        return; // skip — nothing changed since last green run
                    }
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
                                runtimeCp, in.cache(), in.workerCount(), workerJars, listener);
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
                        // Wipe any stale stamp so the next run doesn't skip.
                        dev.jkbuild.task.TestStamp.write(testClassesForStamp, null);
                        // Surface each failure (name + stack trace) above the bar —
                        // not just the count — like Maven/Gradle.
                        for (String line : TestSupport.renderFailures(result)) ctx.output(line);
                        throw new RuntimeException(result.failed() + " test failure"
                                + (result.failed() == 1 ? "" : "s"));
                    }
                    // All tests passed — write stamp so subsequent builds can skip.
                    dev.jkbuild.task.TestStamp.write(testClassesForStamp, stampKey);
                })
                .build();

        // ---- package-jar ------------------------------------------------
        Phase packageJar = Phase.builder("package-jar")
                .label("Packaging")
                .kind(PhaseKind.CPU)
                .requires(in.skipTests()
                        ? new String[]{"copy-resources", "embed-sha"}
                        : new String[]{"copy-resources", "embed-sha", "run-tests"})
                .weight(W_PACKAGE)
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
                .weight(W_STAMP)
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
                .weight(W_STAMP)
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
                .weight(W_ASSEMBLE)
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

        // ---- embed-sha --------------------------------------------------
        // Pin sibling worker jars: hash each [build.embed-sha] member's output
        // jar and write META-INF/<basename>-sha256.txt into this module's classes,
        // so the resource ships in the jar and is on the test classpath. Sibling
        // build-order is guaranteed by the order-after edges those entries imply
        // (BuildCommand.topoSortMembers). No-op when the table is empty. Runs
        // IN-PROCESS — deliberately not a worker, which would need its own sha
        // resource and recurse.
        //
        // An unknown member (not in the workspace) is a config typo → fail. A
        // member that exists but isn't built yet is skipped, not fatal: a SCOPED
        // build (jk build -C this-module) doesn't build the order-after siblings,
        // so their jars are legitimately absent — only a full workspace build
        // (where order-after runs them first) embeds every sha.
        Phase embedSha = Phase.builder("embed-sha")
                .label("Embedding SHAs")
                .kind(PhaseKind.CPU)
                .requires("copy-resources")
                .weight(W_RESOURCES)
                .scope(1)
                .execute(ctx -> {
                    Map<String, String> embed = ctx.require(PROJECT).build().embedSha();
                    if (embed.isEmpty()) { ctx.label("none"); ctx.progress(1); return; }
                    Path classes = ctx.require(MAIN_CLASSES);
                    Map<String, Path> jarByMember = siblingMainJars(in.dir());
                    Path metaInf = classes.resolve("META-INF");
                    Files.createDirectories(metaInf);
                    int written = 0;
                    int skipped = 0;
                    for (Map.Entry<String, String> e : embed.entrySet()) {
                        Path jar = jarByMember.get(e.getValue());
                        if (jar == null) {
                            ctx.error("embed-sha", "'" + e.getValue()
                                    + "' is not a workspace member");
                            throw new RuntimeException("embed-sha: unknown member '" + e.getValue() + "'");
                        }
                        if (!Files.exists(jar)) { skipped++; continue; }  // not built (scoped build)
                        Files.writeString(metaInf.resolve(e.getKey() + "-sha256.txt"),
                                dev.jkbuild.util.Hashing.sha256Hex(jar));
                        written++;
                    }
                    ctx.label(skipped == 0
                            ? "embedded " + written + " SHA" + (written == 1 ? "" : "s")
                            : "embedded " + written + ", skipped " + skipped + " unbuilt");
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
        b.addPhase(embedSha);
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
     * {@code shadow = true} → fat-jar phase. {@code jk build} / {@code jk run} /
     * {@code jk install} call this after {@link #coreBuilder}.
     *
     * <p>Native images are <em>not</em> appended here: {@code native = true}
     * makes a project native-eligible, but a native artifact is only ever built
     * by {@code jk native} (which composes the native tail explicitly) or by
     * {@code jk install} of a native application (which adds the phase itself,
     * resolving GraalVM up front). {@code jk build} stays JVM-only.
     */
    public static void appendDeclaredTails(Goal.Builder b, Inputs in) {
        try {
            JkBuild project = JkBuildParser.parse(in.buildFile());
            if (project.project().shadow()) {
                b.addPhase(shadowPhase(in.cache(), in.lockFile()));
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
                .weight(W_SHADOW)
                .scope(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(PROJECT);
                    BuildLayout layout = ctx.require(LAYOUT);
                    Path classes = ctx.require(MAIN_CLASSES);
                    Path shadowJar = layout.shadowJar();
                    ctx.label("package " + shadowJar.getFileName());
                    List<Path> depJars = new ArrayList<>();
                    if (Files.exists(lockFile)) {
                        ClasspathResolver resolver = new ClasspathResolver(new Cas(cache));
                        depJars.addAll(resolver.classpathFor(
                                LockfileReader.read(lockFile), ClasspathResolver.RUNTIME));
                        // Workspace siblings are filtered out of the lockfile by
                        // WorkspaceMerge, but a fat jar must bundle them (and their
                        // own transitive external deps) or it can't run standalone —
                        // e.g. a worker jar would be missing PluginWorkerMain.
                        WorkspaceClasspath.Result siblings = WorkspaceClasspath.resolve(
                                layout.memberRoot(), project, Set.of(Scope.MAIN));
                        for (Path j : siblings.jars()) {
                            if (!depJars.contains(j)) depJars.add(j);
                        }
                        for (Path sibLock : siblings.siblingLockfiles()) {
                            try {
                                for (Path p : resolver.classpathFor(
                                        LockfileReader.read(sibLock), ClasspathResolver.RUNTIME)) {
                                    if (!depJars.contains(p)) depJars.add(p);
                                }
                            } catch (Exception ignored) { /* best-effort */ }
                        }
                        // Composite (path + branch-git) deps must be bundled into the fat jar too.
                        addCompositeDeps(layout.memberRoot(), project, new Cas(cache), cache,
                                Set.of(Scope.MAIN), ClasspathResolver.RUNTIME, depJars);
                    }
                    new ShadowPackager().packageShadow(new ShadowPackager.ShadowRequest(
                            classes, depJars, shadowJar,
                            project.project().main(), project.manifest(), 0L));
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * GraalVM native-image phase — what {@code jk native} composes onto the core
     * build (and {@code jk install} of a native application adds explicitly).
     * Builds an <em>executable</em> when a main class is resolvable
     * ({@code mainOverride} > {@code [native].main-class} > {@code [project].main}),
     * otherwise a <em>shared library</em> ({@code native-image --shared}). Run
     * directly as a subprocess; requires package-jar.
     *
     * <p>{@code graalHome} is the GraalVM the CLI's {@code GraalResolver} selected
     * (its {@code bin/native-image} is used); when {@code null} the phase falls
     * back to the project JDK / {@code $GRAALVM_HOME} / {@code PATH} search.
     */
    public static Phase nativePhase(Path dir, Path cache, Path lockFile, Path jdksDir,
                                    Path graalHome, String mainOverride, List<String> extraArgs) {
        List<String> extra = extraArgs == null ? List.of() : extraArgs;
        return Phase.builder("native-image")
                .label("Native")
                .kind(PhaseKind.IO)
                .requires("package-jar")
                .weight(W_NATIVE)
                .scope(10)  // preamble(1) + 8 native-image stages + done(1)
                .execute(ctx -> {
                    // Fail-fast: verify native-image is available before compilation
                    // has already run and the user has waited for potentially minutes.
                    Path javaHomeEarly = graalHome != null ? graalHome
                            : dev.jkbuild.jdk.JdkResolver.forProject(dir, jdksDir)
                                    .map(dev.jkbuild.jdk.InstalledJdk::home)
                                    .orElseGet(CompileToolchain::runningJavaHome);
                    if (dev.jkbuild.tool.NativeImageDriver.resolve(javaHomeEarly).isEmpty()) {
                        ctx.error("native", dev.jkbuild.tool.NativeImageDriver
                                .notFoundError(javaHomeEarly).getMessage());
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
                            : (nativeCfg.mainClass() != null ? nativeCfg.mainClass()
                            : project.project().main());
                    boolean shared = (mainClass == null || mainClass.isBlank());
                    if (shared) mainClass = null;
                    // Output path: [native].name overrides the artifact-derived name.
                    // Executable → target/<name>; library → target/lib<name> (native-image
                    // appends the platform extension .so/.dylib/.dll and emits C headers).
                    Path out;
                    if (nativeCfg.name() != null) {
                        String nm = nativeCfg.name();
                        out = layout.memberTargetDir().resolve(
                                shared && !nm.startsWith("lib") ? "lib" + nm : nm);
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
                                        dir, project, Set.of(Scope.MAIN));
                        for (java.nio.file.Path sj : siblings.jars()) {
                            if (!classpath.contains(sj)) classpath.add(sj);
                        }
                        for (java.nio.file.Path sibLock : siblings.siblingLockfiles()) {
                            try {
                                Lockfile sibLockfile = LockfileReader.read(sibLock);
                                for (Path p : cpResolver.classpathFor(sibLockfile,
                                        ClasspathResolver.RUNTIME)) {
                                    if (!classpath.contains(p)) classpath.add(p);
                                }
                            } catch (Exception ignored) {}
                        }
                        // Composite (path + branch-git) deps: native-image must see their classes.
                        addCompositeDeps(dir, project, new Cas(cache), cache,
                                Set.of(Scope.MAIN), ClasspathResolver.RUNTIME, classpath);
                    } catch (Exception ignored) {}

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
                    dev.jkbuild.tool.NativeImageDriver.ProgressListener listener =
                            (current, total, label) -> {
                        if (preambleDone.compareAndSet(false, true)) {
                            ctx.progress(1); // preamble done (output before [1/N])
                        }
                        ctx.label("[" + current + "/" + total + "] " + label);
                        ctx.progress(1); // stage N started = stage N-1 done
                    };

                    int exit = dev.jkbuild.tool.NativeImageDriver.run(
                            new dev.jkbuild.tool.NativeImageDriver.Request(
                                    javaHome, classpath, mainClass, out, allArgs, shared),
                            listener);
                    if (exit != 0) {
                        ctx.error("native", "native-image exited " + exit);
                        throw new RuntimeException("native-image failed (exit " + exit + ")");
                    }
                    // Final tick: completes the last native-image step (or the only tick
                    // when no progress headers were emitted).
                    ctx.progress(1);
                })
                .build();
    }

    // ---- helpers --------------------------------------------------------

    /** {@code <file>:<line>: <message>} — a diagnostic without its severity word
     *  (the console renderer supplies the ✗/⚠ marker). */
    private static String diagnosticMessage(CompileResult.Diagnostic d) {
        StringBuilder sb = new StringBuilder();
        if (d.source() != null) {
            sb.append(d.source());
            if (d.line() > 0) sb.append(':').append(d.line());
            sb.append(": ");
        }
        return sb.append(d.message()).toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Path> javaSources(PhaseContext ctx) {
        return (List<Path>) ctx.get(JAVA_SOURCES).orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Path> kotlinSources(PhaseContext ctx) {
        return (List<Path>) ctx.get(KOTLIN_SOURCES).orElse(List.of());
    }

    /**
     * Predicted Java-source count for {@code compile-java}'s up-front scope,
     * collected the same way {@code parse-build} populates {@link #JAVA_SOURCES}.
     * Best-effort — a walk failure yields 0 (a flat segment), never an error.
     */
    private static int estimateJavaSources(Inputs in, boolean compact) {
        Path javaMainSrc = compact ? in.dir().resolve("src") : in.dir().resolve("src/main/java");
        try {
            return CompileSupport.collectJavaSources(javaMainSrc).size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** Predicted Kotlin-source count for {@code compile-kotlin}'s up-front scope. */
    private static int estimateKotlinSources(Inputs in, boolean compact) {
        try {
            return CompileSupport.collectKotlinSources(in.dir(), compact).size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    /**
     * Build the consumer's composite ({@code path} + branch-git) deps from source
     * and add their main jars + external transitive deps to {@code cp} — jk's
     * {@code includeBuild} analog ({@link CompositeDepResolver}). No-op (and no JDK
     * resolution) when none are declared. {@code depScopes} selects which consumer
     * scopes contribute composite deps; {@code externalCpScopes} is the scope set
     * for each target's external deps. Returns failures (empty on success).
     */
    /**
     * Add the consumer's composite ({@code path} / branch-git) dependency jars +
     * their external transitive deps to {@code cp} — <em>locate-only</em>: the
     * build driver ({@code BuildGraph} in the CLI) has already built every
     * composite unit via the real pipeline, so this just finds the jars (jk's
     * includeBuild analog). Returns any not-yet-built coords as "missing", handled
     * like {@code WorkspaceClasspath.missingSiblingJars}.
     */
    private static List<String> addCompositeDeps(Path consumerDir, JkBuild project, Cas cas, Path cache,
            Set<Scope> depScopes, Set<Scope> externalCpScopes, List<Path> cp) {
        try {
            CompositeLocator.Located r = CompositeLocator.locate(
                    consumerDir, project, depScopes, externalCpScopes, cas, cache.resolve("git"));
            for (Path j : r.jars())            if (!cp.contains(j)) cp.add(j);
            for (Path j : r.externalDepJars()) if (!cp.contains(j)) cp.add(j);
            return r.missing();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of("interrupted locating composite dependencies");
        } catch (IOException | RuntimeException e) {
            return List.of("composite dependency lookup failed: " + e.getMessage());
        }
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

    /**
     * Map each workspace sibling to its main output jar, keyed by both project
     * name and {@code group:artifact} coord. Used by the {@code embed-sha} phase
     * to find the jar a {@code [build.embed-sha]} entry names. Empty when this
     * module isn't in a workspace.
     */
    static Map<String, Path> siblingMainJars(Path moduleDir) throws IOException {
        Map<String, Path> out = new LinkedHashMap<>();
        var rootOpt = dev.jkbuild.config.WorkspaceLocator.findRoot(moduleDir);
        if (rootOpt.isEmpty()) return out;
        Path root = rootOpt.get();
        JkBuild rootManifest = JkBuildParser.parse(root.resolve("jk.toml"));
        if (!rootManifest.isWorkspaceRoot()) return out;
        for (String member : rootManifest.workspace().members()) {
            Path dir = root.resolve(member);
            Path manifest = dir.resolve("jk.toml");
            if (!Files.exists(manifest)) continue;
            JkBuild sib;
            try { sib = JkBuildParser.parse(manifest); }
            catch (RuntimeException ignored) { continue; }
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
     * {@code jk.<worker>.worker.jar} → built jar path for each member named in this
     * module's {@code [build.test-worker-jars]}, handed to the test JVM so a test that
     * forks that worker locates it by path instead of CAS-by-sha. Per-module by design:
     * {@code engine} declares only {@code git-client} (its {@code KotlinWorkerSetupTest}
     * deliberately exercises the CAS path, so {@code kotlin} must stay unset), while
     * {@code cli} declares the workers its publish/import/compile tests fork. The built
     * sibling jars exist because the declaration also implies an order-after edge
     * ({@link JkBuild.Build#allOrderAfter()}); a member with no built (or no shadow,
     * for fat workers) jar is silently skipped. Member name → property comes from the
     * {@link dev.jkbuild.worker.WorkerJar} registry (artifactId is {@code jk-<member>}).
     */
    private static Map<String, String> workerJarProps(Path moduleDir, List<String> members)
            throws IOException {
        Map<String, String> props = new LinkedHashMap<>();
        if (members.isEmpty()) return props;
        Map<String, Path> jarByMember = siblingMainJars(moduleDir);
        Map<String, String> propByMember = new LinkedHashMap<>();
        for (dev.jkbuild.worker.WorkerJar w : dev.jkbuild.worker.WorkerJar.values()) {
            String a = w.artifactId();
            propByMember.put(a.startsWith("jk-") ? a.substring("jk-".length()) : a, w.jarProperty());
        }
        for (String member : members) {
            String prop = propByMember.get(member);
            Path jar = jarByMember.get(member);
            if (prop != null && jar != null && Files.exists(jar)) {
                props.put(prop, jar.toAbsolutePath().toString());
            }
        }
        return props;
    }

    /**
     * Toolchain / runner / forked-worker identity tokens for the test freshness
     * key — so a jk-version, test-runner, or worker-jar change retests. The
     * resolved JDK and dependency set are already covered by jk.lock's content,
     * and a {@code --release} change recompiles main (caught via its output).
     */
    private static List<String> testStampExtras(Map<String, String> workerJars) {
        List<String> extras = new ArrayList<>();
        extras.add("jk:" + dev.jkbuild.util.JkVersion.VERSION);
        String runnerSha = dev.jkbuild.worker.WorkerJar.TEST_RUNNER.expectedShaOrNull();
        if (runnerSha != null) extras.add("runner:" + runnerSha);
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
