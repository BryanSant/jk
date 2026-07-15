// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.run.StepNames;
import build.jumpkick.plugin.build.Phase;

import build.jumpkick.cache.Cas;
import build.jumpkick.compile.CompileRequest;
import build.jumpkick.compile.CompileResult;
import build.jumpkick.compile.JavacDriver;
import build.jumpkick.compile.KotlincDriver;
import build.jumpkick.compile.KotlincRequest;
import build.jumpkick.compile.KotlincResult;
import build.jumpkick.http.Http;
import build.jumpkick.jdk.HostPlatform;
import build.jumpkick.jdk.JavaHomes;
import build.jumpkick.model.Coordinate;
import build.jumpkick.model.Dependency;
import build.jumpkick.model.RepositorySpec;
import build.jumpkick.model.Scope;
import build.jumpkick.mvn.PomImporter;
import build.jumpkick.repo.EffectivePomBuilder;
import build.jumpkick.repo.MavenRepo;
import build.jumpkick.repo.RepoGroup;
import build.jumpkick.resolver.NaiveResolver;
import build.jumpkick.resolver.Resolution;
import build.jumpkick.run.Pipeline;
import build.jumpkick.run.PipelineKey;
import build.jumpkick.run.Step;
import build.jumpkick.run.StepKind;
import build.jumpkick.script.ScriptHeader;
import build.jumpkick.script.ScriptHeaderParser;
import build.jumpkick.tool.JarManifest;
import build.jumpkick.util.Hashing;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The script/jar <em>preparation</em> pipelines behind {@code jk tool run <file>} — parse the
 * JBang-style header, resolve its declared deps, compile the script (or provision {@code kotlinc}
 * / inspect a jar's manifest + embedded POMs) — hoisted out of the CLI's {@code ScriptRunner} so
 * the resident engine hosts them (the Stage-5 close of the inventory's "script-header resolution
 * residue"): they were the last in-process {@code RepoGroup}/{@code NaiveResolver}/compile-driver
 * users on the client. The <em>exec</em> of the prepared program stays client-side — it owns the
 * terminal.
 *
 * <p>Each factory returns a single pipeline whose terminal state carries the exec ingredients through
 * the public {@link PipelineKey}s ({@link #MAIN_CLASS}, {@link #CLASSPATH}, {@link #CLASSES_DIR},
 * {@link #KOTLINC_BIN}, {@link #KT_STDLIB}); the engine relays them on the terminal pipeline-finish.
 */
public final class ScriptPipelines {

    private ScriptPipelines() {}

    // Cross-step keys (mode-specific, but all live in the same record).
    static final PipelineKey<ScriptHeader> HEADER = PipelineKey.of("script-header", ScriptHeader.class);
    public static final PipelineKey<Path> CLASSES_DIR = PipelineKey.of("classes-dir", Path.class);

    @SuppressWarnings("rawtypes")
    private static final PipelineKey<List> WORKER_CP = PipelineKey.of("kotlin-worker-cp", List.class);

    public static final PipelineKey<Path> KT_STDLIB = PipelineKey.of("kotlin-stdlib", Path.class);
    public static final PipelineKey<Path> KOTLINC_BIN = PipelineKey.of("kotlinc-bin", Path.class);
    public static final PipelineKey<String> MAIN_CLASS = PipelineKey.of("main-class", String.class);

    @SuppressWarnings("rawtypes")
    public static final PipelineKey<List> CLASSPATH = PipelineKey.of("classpath", List.class);

    @SuppressWarnings("rawtypes")
    private static final PipelineKey<List> JAR_DECLARED_DEPS = PipelineKey.of("jar-declared-deps", List.class);

    /** The finished pipeline's classpath, as the typed list the raw {@link #CLASSPATH} key stores. */
    @SuppressWarnings("unchecked")
    public static List<Path> classpathOf(Pipeline pipeline) {
        return (List<Path>) pipeline.get(CLASSPATH).orElse(List.of());
    }

    /** {@code classes/} for compiled output, under the state dir, keyed by the source bytes' hash. */
    public static Path classesDirFor(Path stateDir, byte[] sourceBytes) {
        return stateDir.resolve("script-cache").resolve(Hashing.sha256Hex(sourceBytes)).resolve("classes");
    }

    // --- .java -----------------------------------------------------------

    /** {@code parse-script → resolve-deps → compile-java} for a {@code .java} script. */
    public static Pipeline javaScriptPipeline(Path script, Path cacheDir, Path stateDir, URI repoUrl, boolean forceRecompile)
            throws IOException {
        return javaScriptPipeline(script, cacheDir, stateDir, repoUrl, forceRecompile, List.of());
    }

    /** As above with {@code extraDeps} — alias/{@code --with} injections joining the header's deps. */
    public static Pipeline javaScriptPipeline(
            Path script, Path cacheDir, Path stateDir, URI repoUrl, boolean forceRecompile, List<Dependency> extraDeps)
            throws IOException {
        byte[] bytes = Files.readAllBytes(script);
        ScriptHeader header = withExtras(ScriptHeaderParser.parse(new String(bytes, StandardCharsets.UTF_8)), extraDeps);
        Path classesDir = classesDirFor(stateDir, bytes);
        String mainClass = header.main() != null ? header.main() : simpleMainClassName(script, ".java");

        Step parseHeader = Step.builder(StepNames.PARSE_SCRIPT)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("parse " + script.getFileName());
                    ctx.put(HEADER, header);
                    ctx.put(MAIN_CLASS, mainClass);
                    ctx.put(CLASSES_DIR, classesDir);
                    Files.createDirectories(cacheDir);
                    ctx.progress(1);
                })
                .build();

        Step resolveDeps = Step.builder(StepNames.RESOLVE_DEPS).phase(Phase.RESOLVE)
                .kind(StepKind.IO)
                .requires(StepNames.PARSE_SCRIPT)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("resolve script dependencies");
                    Cas cas = new Cas(cacheDir);
                    Http http = new Http();
                    RepoGroup repos = buildRepos(header, repoUrl, http, cas);
                    try {
                        List<Path> classpath = resolveClasspath(header.deps(), repos);
                        ctx.put(CLASSPATH, classpath);
                    } catch (RuntimeException e) {
                        ctx.error("resolve", e.getMessage());
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();

        Step compile = Step.builder(StepNames.COMPILE_JAVA).phase(Phase.COMPILE)
                .kind(StepKind.CPU)
                .requires(StepNames.RESOLVE_DEPS)
                .ticks(1)
                .execute(ctx -> {
                    boolean rerun = forceRecompile
                            || build.jumpkick.config.SessionContext.current().config().rebuildOr(false);
                    if (!rerun && Files.exists(classesDir.resolve(mainClass.replace('.', '/') + ".class"))) {
                        ctx.label("cache hit (" + mainClass + ".class)");
                        materializeFiles(script, header, classesDir);
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("javac " + script.getFileName());
                    Files.createDirectories(classesDir);
                    @SuppressWarnings("unchecked")
                    List<Path> cp = (List<Path>) ctx.require(CLASSPATH);
                    CompileResult result = compileJava(script, header, classesDir, cp);
                    if (!result.success()) {
                        for (var d : result.diagnostics()) {
                            ctx.error(
                                    "javac",
                                    d.severity()
                                            + " "
                                            + (d.source() != null ? d.source().getFileName() : "<unknown>")
                                            + ":"
                                            + d.line()
                                            + ": "
                                            + d.message());
                        }
                        throw new RuntimeException("javac failed");
                    }
                    materializeFiles(script, header, classesDir);
                    ctx.progress(1);
                })
                .build();

        return Pipeline.builder("run-java")
                .addStep(parseHeader)
                .addStep(resolveDeps)
                .addStep(compile)
                .build();
    }

    private static CompileResult compileJava(Path script, ScriptHeader header, Path classesDir, List<Path> classpath)
            throws IOException {
        int release =
                header.release() != null ? header.release() : Runtime.version().feature();
        CompileRequest request = CompileRequest.builder()
                .sources(withDeclaredSources(script, header))
                .classpath(classpath)
                .outputDir(classesDir)
                .release(release)
                .extraOptions(header.javacOptions())
                .javaHome(JavaHomes.resolveJavaHome(script.toAbsolutePath().getParent()))
                .build();
        return new JavacDriver().compile(request);
    }

    // --- .kt -------------------------------------------------------------

    /** {@code parse-script → (resolve-deps ∥ resolve-kotlinc) → compile-kt} for a {@code .kt} script. */
    public static Pipeline kotlinScriptPipeline(Path script, Path cacheDir, Path stateDir, URI repoUrl, boolean forceRecompile)
            throws IOException {
        return kotlinScriptPipeline(script, cacheDir, stateDir, repoUrl, forceRecompile, List.of());
    }

    /** As above with {@code extraDeps} — alias/{@code --with} injections joining the header's deps. */
    public static Pipeline kotlinScriptPipeline(
            Path script, Path cacheDir, Path stateDir, URI repoUrl, boolean forceRecompile, List<Dependency> extraDeps)
            throws IOException {
        byte[] bytes = Files.readAllBytes(script);
        // parseKotlin: // directives + @file:DependsOn/@file:Repository annotations.
        ScriptHeader header =
                withExtras(ScriptHeaderParser.parseKotlin(new String(bytes, StandardCharsets.UTF_8)), extraDeps);
        Path classesDir = classesDirFor(stateDir, bytes);
        String mainClass = header.main() != null ? header.main() : kotlinMainClassName(script);

        Step parseHeader = Step.builder(StepNames.PARSE_SCRIPT)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("parse " + script.getFileName());
                    ctx.put(HEADER, header);
                    ctx.put(MAIN_CLASS, mainClass);
                    ctx.put(CLASSES_DIR, classesDir);
                    Files.createDirectories(cacheDir);
                    ctx.progress(1);
                })
                .build();

        // resolve-deps and resolve-kotlinc are independent and slow; run
        // them in parallel.
        Step resolveDeps = Step.builder(StepNames.RESOLVE_DEPS).phase(Phase.RESOLVE)
                .kind(StepKind.IO)
                .requires(StepNames.PARSE_SCRIPT)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("resolve script dependencies");
                    Cas cas = new Cas(cacheDir);
                    Http http = new Http();
                    RepoGroup repos = buildRepos(header, repoUrl, http, cas);
                    try {
                        List<Path> classpath = resolveClasspath(header.deps(), repos);
                        ctx.put(CLASSPATH, classpath);
                    } catch (RuntimeException e) {
                        ctx.error("resolve", e.getMessage());
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();

        Step resolveKotlinc = Step.builder(StepNames.RESOLVE_KOTLINC).phase(Phase.RESOLVE)
                .kind(StepKind.IO)
                .requires(StepNames.PARSE_SCRIPT)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label(
                            header.kotlinVersion() != null
                                    ? "resolve kotlin compiler " + header.kotlinVersion()
                                    : "resolve kotlin compiler");
                    Cas cas = new Cas(cacheDir);
                    RepoGroup repos = buildRepos(header, repoUrl, new Http(), cas);
                    try {
                        KotlinWorkerSetup.Prepared prep = KotlinWorkerSetup.prepare(repos, cas, header.kotlinVersion());
                        ctx.put(WORKER_CP, prep.workerClasspath());
                        ctx.put(KT_STDLIB, prep.stdlib());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("interrupted resolving the Kotlin compiler", e);
                    } catch (RuntimeException e) {
                        ctx.error("kotlin", e.getMessage());
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();

        Step compile = Step.builder(StepNames.COMPILE_KOTLIN).phase(Phase.COMPILE)
                .kind(StepKind.CPU)
                .requires(StepNames.RESOLVE_DEPS, StepNames.RESOLVE_KOTLINC)
                .ticks(1)
                .execute(ctx -> {
                    boolean rerun = forceRecompile
                            || build.jumpkick.config.SessionContext.current().config().rebuildOr(false);
                    if (!rerun && Files.exists(classesDir.resolve(mainClass.replace('.', '/') + ".class"))) {
                        ctx.label("cache hit (" + mainClass + ".class)");
                        materializeFiles(script, header, classesDir);
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("kotlinc " + script.getFileName());
                    Files.createDirectories(classesDir);
                    @SuppressWarnings("unchecked")
                    List<Path> depsClasspath = (List<Path>) ctx.require(CLASSPATH);
                    int jvmTarget = header.release() != null
                            ? header.release()
                            : Runtime.version().feature();
                    // Compilation classpath: script deps + the version-matched
                    // stdlib (the in-process worker has no kotlin-home to auto-add
                    // it; paired with -no-stdlib).
                    List<Path> compileCp = new ArrayList<>(depsClasspath);
                    compileCp.add(ctx.require(KT_STDLIB));
                    Path workingDir = cacheDir
                            .resolve("actions")
                            .resolve("incremental-kotlin")
                            .resolve(build.jumpkick.task.ActionKey.qualifiedTaskId("script", classesDir));
                    @SuppressWarnings("unchecked")
                    List<Path> workerCp = (List<Path>) ctx.require(WORKER_CP);
                    // @file:DependsOn/@file:Repository were resolved by jk (parseKotlin);
                    // kotlinc can't compile them — feed it a line-preserving neutralized copy.
                    List<Path> ktSources = withDeclaredSources(script, header);
                    String neutralized = ScriptHeaderParser.neutralizeKotlinAnnotations(
                            new String(bytes, StandardCharsets.UTF_8));
                    if (neutralized != null) {
                        Path srcDir = classesDir.resolveSibling("src");
                        Files.createDirectories(srcDir);
                        Path copy = srcDir.resolve(script.getFileName().toString());
                        Files.writeString(copy, neutralized, StandardCharsets.UTF_8);
                        ktSources.set(0, copy);
                    }
                    KotlincRequest req = KotlincRequest.builder()
                            .sources(ktSources)
                            .classpath(compileCp)
                            .outputDir(classesDir)
                            .jvmTarget(CompileSupport.kotlinJvmTarget(jvmTarget))
                            .workerClasspath(workerCp)
                            .javaHome(JavaHomes.resolveJavaHome(
                                    script.toAbsolutePath().getParent()))
                            .workingDir(workingDir)
                            .extraArgs(List.of("-no-stdlib"))
                            .build();
                    KotlincResult result = new KotlincDriver().compile(req);
                    if (!result.success()) {
                        ctx.error("kotlinc", result.output());
                        throw new RuntimeException("kotlinc failed");
                    }
                    materializeFiles(script, header, classesDir);
                    ctx.progress(1);
                })
                .build();

        return Pipeline.builder("run-kt")
                .addStep(parseHeader)
                .addStep(resolveDeps)
                .addStep(resolveKotlinc)
                .addStep(compile)
                .build();
    }

    // --- .kts ------------------------------------------------------------

    /**
     * {@code parse-script → (resolve-deps ∥ resolve-kotlinc)} for a {@code .kts} script. The exec
     * is delegated to {@code kotlinc -script} client-side; the script's declared deps ({@code
     * @file:DependsOn} / {@code //DEPS} / {@code //jk dep}) resolve here and reach kotlinc via
     * {@code -classpath} — jk's own CAS-first resolution, not kotlin-main-kts's embedded Ivy.
     */
    public static Pipeline ktsScriptPipeline(Path script, Path cacheDir, URI repoUrl) throws IOException {
        return ktsScriptPipeline(script, cacheDir, repoUrl, List.of());
    }

    /** As above with {@code extraDeps} — alias/{@code --with} injections joining the header's deps. */
    public static Pipeline ktsScriptPipeline(Path script, Path cacheDir, URI repoUrl, List<Dependency> extraDeps)
            throws IOException {
        ScriptHeader header = withExtras(
                ScriptHeaderParser.parseKotlin(new String(Files.readAllBytes(script), StandardCharsets.UTF_8)),
                extraDeps);

        Step resolveDeps = Step.builder(StepNames.RESOLVE_DEPS).phase(Phase.RESOLVE)
                .kind(StepKind.IO)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("resolve script dependencies");
                    Cas cas = new Cas(cacheDir);
                    RepoGroup repos = buildRepos(header, repoUrl, new Http(), cas);
                    try {
                        ctx.put(CLASSPATH, resolveClasspath(header.deps(), repos));
                    } catch (RuntimeException e) {
                        ctx.error("resolve", e.getMessage());
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();

        Step resolveKotlinc = Step.builder(StepNames.RESOLVE_KOTLINC).phase(Phase.RESOLVE)
                .kind(StepKind.IO)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("provision kotlinc");
                    Path kotlinHome = CompileToolchain.resolveKotlinHome(cacheDir, null, ctx::output);
                    Path kotlinc =
                            kotlinHome.resolve("bin").resolve(HostPlatform.isWindows() ? "kotlinc.bat" : "kotlinc");
                    if (!Files.exists(kotlinc)) {
                        ctx.error("kotlinc-missing", "kotlinc not found at " + kotlinc);
                        throw new RuntimeException("kotlinc missing");
                    }
                    ctx.put(KOTLINC_BIN, kotlinc);
                    ctx.progress(1);
                })
                .build();
        return Pipeline.builder("run-kts")
                .addStep(resolveDeps)
                .addStep(resolveKotlinc)
                .build();
    }

    // --- .jar ------------------------------------------------------------

    /** {@code inspect-jar → resolve-jar-deps} for a prebuilt jar (manifest main + embedded-POM deps). */
    public static Pipeline jarPipeline(Path jar, Path cacheDir, URI repoUrl) {
        Step inspect = Step.builder(StepNames.INSPECT_JAR)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("read manifest + embedded poms");
                    Optional<String> mainClass = JarManifest.mainClass(jar);
                    if (mainClass.isEmpty()) {
                        ctx.error("no-main-class", jar + " has no Main-Class in its MANIFEST.MF");
                        throw new RuntimeException("missing Main-Class");
                    }
                    ctx.put(MAIN_CLASS, mainClass.get());

                    List<Dependency> declaredDeps = new ArrayList<>();
                    for (JarManifest.EmbeddedPom p : JarManifest.scanEmbeddedPoms(jar)) {
                        if (!p.hasPomXml()) continue;
                        try {
                            var imported = PomImporter.importFromBytes(p.pomXml());
                            var byScope = imported.jkBuild().dependencies().byScope();
                            for (Scope scope : EnumSet.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME)) {
                                List<Dependency> scoped = byScope.get(scope);
                                if (scoped != null) declaredDeps.addAll(scoped);
                            }
                        } catch (RuntimeException e) {
                            ctx.warn(
                                    "pom-parse",
                                    "failed to parse embedded pom for " + p.coord() + ": " + e.getMessage());
                        }
                    }
                    if (JarManifest.hasModuleInfo(jar)) {
                        ctx.warn(
                                "jpms",
                                jar.getFileName()
                                        + " ships a module-info.class (JPMS module); "
                                        + "running it from the classpath.");
                    }
                    ctx.put(JAR_DECLARED_DEPS, declaredDeps);
                    ctx.progress(1);
                })
                .build();

        Step resolveJarDeps = Step.builder(StepNames.RESOLVE_JAR_DEPS).phase(Phase.RESOLVE)
                .kind(StepKind.IO)
                .requires(StepNames.INSPECT_JAR)
                .ticks(1)
                .execute(ctx -> {
                    @SuppressWarnings("unchecked")
                    List<Dependency> declaredDeps = (List<Dependency>) ctx.require(JAR_DECLARED_DEPS);
                    List<Path> classpath = new ArrayList<>();
                    classpath.add(jar);
                    if (declaredDeps.isEmpty()) {
                        ctx.label("no embedded deps");
                        ctx.put(CLASSPATH, classpath);
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("fetch " + declaredDeps.size() + " embedded deps");
                    Files.createDirectories(cacheDir);
                    Cas cas = new Cas(cacheDir);
                    Http http = new Http();
                    RepoGroup repos = new RepoGroup(List.of(new MavenRepo(
                            "central", repoUrl != null ? repoUrl : RepositorySpec.MAVEN_CENTRAL.url(), http, cas)));
                    try {
                        classpath.addAll(resolveClasspath(declaredDeps, repos));
                    } catch (RuntimeException e) {
                        ctx.error("resolve", e.getMessage());
                        throw e;
                    }
                    ctx.put(CLASSPATH, classpath);
                    ctx.progress(1);
                })
                .build();

        return Pipeline.builder("run-jar")
                .addStep(inspect)
                .addStep(resolveJarDeps)
                .build();
    }

    // --- shared helpers --------------------------------------------------

    /** The header with {@code extraDeps} appended to its dependency list. */
    private static ScriptHeader withExtras(ScriptHeader header, List<Dependency> extraDeps) {
        if (extraDeps.isEmpty()) return header;
        List<Dependency> deps = new ArrayList<>(header.deps());
        deps.addAll(extraDeps);
        return new ScriptHeader(
                deps,
                header.release(),
                header.repos(),
                header.features(),
                header.javacOptions(),
                header.javaOptions(),
                header.sources(),
                header.files(),
                header.main(),
                header.gav(),
                header.description(),
                header.kotlinVersion());
    }

    /** The script plus its {@code //SOURCES} declarations, resolved against the script's dir. */
    private static List<Path> withDeclaredSources(Path script, ScriptHeader header) {
        List<Path> sources = new ArrayList<>();
        sources.add(script.toAbsolutePath());
        Path dir = script.toAbsolutePath().getParent();
        for (String s : header.sources()) {
            Path p = dir.resolve(s).normalize();
            if (!sources.contains(p)) sources.add(p);
        }
        return sources;
    }

    /**
     * Copy each {@code //FILES target=source} resource into {@code classesDir} so it rides the
     * runtime classpath (JBang semantics). Source is relative to the script's dir; a bare name
     * means target == source. Runs on cache hits too — the resources must exist even when the
     * compile was skipped.
     */
    private static void materializeFiles(Path script, ScriptHeader header, Path classesDir) throws IOException {
        if (header.files().isEmpty()) return;
        Path dir = script.toAbsolutePath().getParent();
        for (String spec : header.files()) {
            int eq = spec.indexOf('=');
            String target = eq >= 0 ? spec.substring(0, eq) : spec;
            String source = eq >= 0 ? spec.substring(eq + 1) : spec;
            Path from = dir.resolve(source).normalize();
            Path to = classesDir.resolve(target).normalize();
            if (!to.startsWith(classesDir)) {
                throw new IOException("//FILES target escapes the classpath dir: " + spec);
            }
            if (!Files.isRegularFile(from)) {
                throw new IOException("//FILES source not found: " + from + " (from `" + spec + "`)");
            }
            Files.createDirectories(to.getParent());
            Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Kotlin's convention for top-level {@code main()}: {@code Foo.kt} → {@code FooKt}. */
    private static String kotlinMainClassName(Path script) {
        String name = script.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".kt")) {
            name = name.substring(0, name.length() - 3);
        }
        if (name.isEmpty()) return "Kt";
        return Character.toUpperCase(name.charAt(0)) + name.substring(1) + "Kt";
    }

    /** {@code Foo.java} → {@code Foo}. (Matches the existing convention.) */
    private static String simpleMainClassName(Path script, String suffix) {
        String name = script.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(suffix)
                ? name.substring(0, name.length() - suffix.length())
                : name;
    }

    private static RepoGroup buildRepos(ScriptHeader header, URI repoUrl, Http http, Cas cas) {
        List<MavenRepo> list = new ArrayList<>();
        if (repoUrl != null) {
            list.add(new MavenRepo("central", repoUrl, http, cas));
        } else {
            for (URI uri : header.repos()) {
                list.add(new MavenRepo("script-repo-" + list.size(), uri, http, cas));
            }
            if (list.isEmpty()) {
                list.add(new MavenRepo(
                        RepositorySpec.MAVEN_CENTRAL.name(), RepositorySpec.MAVEN_CENTRAL.url(), http, cas));
            }
        }
        return new RepoGroup(list);
    }

    private static List<Path> resolveClasspath(List<Dependency> deps, RepoGroup repos)
            throws IOException, InterruptedException {
        if (deps.isEmpty()) return List.of();
        Resolution resolution = new NaiveResolver(new EffectivePomBuilder(repos)).resolve(deps);
        Set<String> ordered = new LinkedHashSet<>();
        for (Dependency d : deps) ordered.add(d.module());
        for (Resolution.ResolvedModule m : resolution.modules().values()) ordered.add(m.module());

        List<Path> jars = new ArrayList<>();
        for (String module : ordered) {
            Resolution.ResolvedModule m = resolution.modules().get(module);
            if (m == null) continue;
            Coordinate coord = m.coordinate();
            jars.add(repos.tryFetchArtifact(coord)
                    .orElseThrow(() ->
                            new MavenRepo.ArtifactNotFoundException("jar not found in any declared repo: " + coord))
                    .fetched()
                    .cachePath());
        }
        return jars;
    }
}
