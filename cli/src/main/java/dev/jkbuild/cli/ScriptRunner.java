// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.compile.CompileRequest;
import dev.jkbuild.compile.CompileResult;
import dev.jkbuild.compile.JavacDriver;
import dev.jkbuild.compile.KotlincDriver;
import dev.jkbuild.compile.KotlincRequest;
import dev.jkbuild.compile.KotlincResult;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.model.Scope;
import dev.jkbuild.mvn.PomImporter;
import dev.jkbuild.repo.EffectivePomBuilder;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.resolver.NaiveResolver;
import dev.jkbuild.resolver.Resolution;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.script.ScriptHeader;
import dev.jkbuild.script.ScriptHeaderParser;
import dev.jkbuild.tool.JarManifest;
import dev.jkbuild.util.Hashing;
import dev.jkbuild.util.JkDirs;

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
 * Executes a standalone file as a program — one of:
 * <ul>
 *   <li><b>{@code .java}</b> script (JBang-compatible, PRD §19).</li>
 *   <li><b>{@code .kt}</b> Kotlin script (JBang-compatible, with
 *       {@code //KOTLIN <version>} support).</li>
 *   <li><b>{@code .kts}</b> Kotlin Script — delegated to {@code kotlinc -script}.</li>
 *   <li><b>{@code .jar}</b> already-built artifact.</li>
 * </ul>
 *
 * <p>This is the engine behind {@code jk tool run <file>}. {@code jk run} no
 * longer interprets file arguments — it only runs the current project and
 * forwards every argument to the project's main method. To execute a loose
 * {@code .java}/{@code .kt}/{@code .kts}/{@code .jar} file, reach for
 * {@code jk tool run} instead.
 *
 * <p>Each mode runs its preparation (parse, resolve, compile) inside a
 * {@link Goal} so progress, warnings, and the run-log behave like every
 * other {@code jk} verb. The actual subprocess that exec's the user's
 * program runs <i>after</i> the goal returns — by then the progress widget
 * has wiped itself, so the inferior owns the TTY cleanly.
 */
final class ScriptRunner {

    private final GlobalOptions global;
    private final Path cacheDirOverride;
    private final Path stateDirOverride;
    private final URI repoUrl;
    private final boolean forceRecompile;

    ScriptRunner(GlobalOptions global, Path cacheDirOverride, Path stateDirOverride,
                 URI repoUrl, boolean forceRecompile) {
        this.global = global;
        this.cacheDirOverride = cacheDirOverride;
        this.stateDirOverride = stateDirOverride;
        this.repoUrl = repoUrl;
        this.forceRecompile = forceRecompile;
    }

    // Cross-phase keys (mode-specific, but all live in the same record).
    private static final GoalKey<ScriptHeader> HEADER = GoalKey.of("script-header", ScriptHeader.class);
    private static final GoalKey<Path> CLASSES_DIR = GoalKey.of("classes-dir", Path.class);
    private static final GoalKey<Path> KOTLIN_HOME = GoalKey.of("kotlin-home", Path.class);
    private static final GoalKey<Path> KOTLINC_BIN = GoalKey.of("kotlinc-bin", Path.class);
    private static final GoalKey<String> MAIN_CLASS = GoalKey.of("main-class", String.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> CLASSPATH = GoalKey.of("classpath", List.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> JAR_DECLARED_DEPS = GoalKey.of("jar-declared-deps", List.class);

    /** True when {@code arg} names a file type this runner can execute. */
    static boolean isRunnableFile(String arg) {
        String s = arg.toLowerCase(Locale.ROOT);
        return s.endsWith(".java") || s.endsWith(".kt")
                || s.endsWith(".kts") || s.endsWith(".jar");
    }

    /**
     * Run {@code file} (dispatched by extension) with {@code args} forwarded
     * to the program. The caller guarantees {@link #isRunnableFile} held for
     * the original argument.
     */
    int run(Path file, List<String> args) throws IOException, InterruptedException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".java")) return runJavaScript(file, args);
        if (name.endsWith(".kts"))  return runKtsScript(file, args);
        if (name.endsWith(".kt"))   return runKotlinScript(file, args);
        if (name.endsWith(".jar"))  return runJar(file, args);
        throw new IllegalArgumentException("unsupported file type: " + file);
    }

    // --- .java -----------------------------------------------------------

    private int runJavaScript(Path script, List<String> args)
            throws IOException, InterruptedException {
        if (!Files.isRegularFile(script)) {
            System.err.println("jk tool run: script not found: " + script);
            return 66;
        }
        byte[] bytes = Files.readAllBytes(script);
        String source = new String(bytes, StandardCharsets.UTF_8);
        ScriptHeader header = ScriptHeaderParser.parse(source);
        Paths paths = scriptPaths(bytes);
        String mainClass = simpleMainClassName(script, ".java");

        Phase parseHeader = Phase.builder("parse-script")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("parse " + script.getFileName());
                    ctx.put(HEADER, header);
                    ctx.put(MAIN_CLASS, mainClass);
                    ctx.put(CLASSES_DIR, paths.classesDir);
                    Files.createDirectories(paths.cacheDir);
                    ctx.progress(1);
                })
                .build();

        Phase resolveDeps = Phase.builder("resolve-deps")
                .kind(PhaseKind.IO)
                .requires("parse-script")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("resolve script dependencies");
                    Cas cas = new Cas(paths.cacheDir);
                    Http http = new Http();
                    RepoGroup repos = buildRepos(header, http, cas);
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

        Phase compile = Phase.builder("compile-java")
                .kind(PhaseKind.CPU)
                .requires("resolve-deps")
                .scope(1)
                .execute(ctx -> {
                    Path classesDir = ctx.require(CLASSES_DIR);
                    boolean noCache = forceRecompile
                            || dev.jkbuild.config.ActiveConfig.get().noCacheOr(false);
                    if (!noCache && Files.exists(classesDir.resolve(mainClass + ".class"))) {
                        ctx.label("cache hit (" + mainClass + ".class)");
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
                            ctx.error("javac",
                                    d.severity() + " "
                                            + (d.source() != null ? d.source().getFileName() : "<unknown>")
                                            + ":" + d.line() + ": " + d.message());
                        }
                        throw new RuntimeException("javac failed");
                    }
                    ctx.progress(1);
                })
                .build();

        Goal goal = Goal.builder("run-java")
                .addPhase(parseHeader)
                .addPhase(resolveDeps)
                .addPhase(compile)
                .build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cacheDir());
        if (!result.success()) return failureExitCode(result);

        @SuppressWarnings("unchecked")
        List<Path> cp = (List<Path>) goal.get(CLASSPATH).orElseThrow();
        return execJava(paths.classesDir, cp, header.javaOptions(), mainClass, args);
    }

    private CompileResult compileJava(Path script, ScriptHeader header,
                                      Path classesDir, List<Path> classpath) throws IOException {
        int release = header.release() != null
                ? header.release() : Runtime.version().feature();
        CompileRequest request = CompileRequest.builder()
                .sources(List.of(script.toAbsolutePath()))
                .classpath(classpath)
                .outputDir(classesDir)
                .release(release)
                .extraOptions(header.javacOptions())
                .javaHome(CompileToolchain.resolveJavaHome(script.toAbsolutePath().getParent()))
                .build();
        return new JavacDriver().compile(request);
    }

    // --- .kt -------------------------------------------------------------

    private int runKotlinScript(Path script, List<String> args)
            throws IOException, InterruptedException {
        if (!Files.isRegularFile(script)) {
            System.err.println("jk tool run: script not found: " + script);
            return 66;
        }
        byte[] bytes = Files.readAllBytes(script);
        String source = new String(bytes, StandardCharsets.UTF_8);
        ScriptHeader header = ScriptHeaderParser.parse(source);
        Paths paths = scriptPaths(bytes);
        String mainClass = kotlinMainClassName(script);

        Phase parseHeader = Phase.builder("parse-script")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("parse " + script.getFileName());
                    ctx.put(HEADER, header);
                    ctx.put(MAIN_CLASS, mainClass);
                    ctx.put(CLASSES_DIR, paths.classesDir);
                    Files.createDirectories(paths.cacheDir);
                    ctx.progress(1);
                })
                .build();

        // resolve-deps and resolve-kotlinc are independent and slow; run
        // them in parallel.
        Phase resolveDeps = Phase.builder("resolve-deps")
                .kind(PhaseKind.IO)
                .requires("parse-script")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("resolve script dependencies");
                    Cas cas = new Cas(paths.cacheDir);
                    Http http = new Http();
                    RepoGroup repos = buildRepos(header, http, cas);
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

        Phase resolveKotlinc = Phase.builder("resolve-kotlinc")
                .kind(PhaseKind.IO)
                .requires("parse-script")
                .scope(1)
                .execute(ctx -> {
                    ctx.label(header.kotlinVersion() != null
                            ? "provision kotlinc " + header.kotlinVersion()
                            : "provision kotlinc");
                    try {
                        Path kotlinHome = CompileToolchain.resolveKotlinHome(
                                paths.cacheDir, header.kotlinVersion());
                        ctx.put(KOTLIN_HOME, kotlinHome);
                    } catch (RuntimeException e) {
                        ctx.error("kotlinc", e.getMessage());
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();

        Phase compile = Phase.builder("compile-kt")
                .kind(PhaseKind.CPU)
                .requires("resolve-deps", "resolve-kotlinc")
                .scope(1)
                .execute(ctx -> {
                    Path classesDir = ctx.require(CLASSES_DIR);
                    boolean noCache = forceRecompile
                            || dev.jkbuild.config.ActiveConfig.get().noCacheOr(false);
                    if (!noCache && Files.exists(classesDir.resolve(mainClass + ".class"))) {
                        ctx.label("cache hit (" + mainClass + ".class)");
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("kotlinc " + script.getFileName());
                    Files.createDirectories(classesDir);
                    @SuppressWarnings("unchecked")
                    List<Path> depsClasspath = (List<Path>) ctx.require(CLASSPATH);
                    int jvmTarget = header.release() != null
                            ? header.release() : Runtime.version().feature();
                    KotlincRequest req = KotlincRequest.builder()
                            .sources(List.of(script.toAbsolutePath()))
                            .classpath(depsClasspath)
                            .outputDir(classesDir)
                            .jvmTarget(CompileCommand.kotlinJvmTarget(jvmTarget))
                            .kotlinHome(ctx.require(KOTLIN_HOME))
                            .build();
                    KotlincResult result = new KotlincDriver().compile(req);
                    if (!result.success()) {
                        ctx.error("kotlinc", result.output());
                        throw new RuntimeException("kotlinc failed");
                    }
                    ctx.progress(1);
                })
                .build();

        Goal goal = Goal.builder("run-kt")
                .addPhase(parseHeader)
                .addPhase(resolveDeps)
                .addPhase(resolveKotlinc)
                .addPhase(compile)
                .build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cacheDir());
        if (!result.success()) return failureExitCode(result);

        @SuppressWarnings("unchecked")
        List<Path> depsClasspath = (List<Path>) goal.get(CLASSPATH).orElseThrow();
        Path kotlinHome = goal.get(KOTLIN_HOME).orElseThrow();

        // At runtime, the Kotlin stdlib must be on the classpath.
        List<Path> runtime = new ArrayList<>(depsClasspath);
        Path stdlib = kotlinHome.resolve("lib").resolve("kotlin-stdlib.jar");
        if (Files.exists(stdlib)) runtime.add(stdlib);

        return execJava(paths.classesDir, runtime, header.javaOptions(), mainClass, args);
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

    // --- .kts ------------------------------------------------------------

    private int runKtsScript(Path script, List<String> args)
            throws IOException, InterruptedException {
        if (!Files.isRegularFile(script)) {
            System.err.println("jk tool run: script not found: " + script);
            return 66;
        }
        Path cacheDir = cacheDir();
        Files.createDirectories(cacheDir);

        Phase resolveKotlinc = Phase.builder("resolve-kotlinc")
                .kind(PhaseKind.IO)
                .scope(1)
                .execute(ctx -> {
                    ctx.label("provision kotlinc");
                    Path kotlinHome = CompileToolchain.resolveKotlinHome(cacheDir, null);
                    Path kotlinc = kotlinHome.resolve("bin").resolve(
                            isWindows() ? "kotlinc.bat" : "kotlinc");
                    if (!Files.exists(kotlinc)) {
                        ctx.error("kotlinc-missing", "kotlinc not found at " + kotlinc);
                        throw new RuntimeException("kotlinc missing");
                    }
                    ctx.put(KOTLINC_BIN, kotlinc);
                    ctx.progress(1);
                })
                .build();

        Goal goal = Goal.builder("run-kts")
                .addPhase(resolveKotlinc)
                .build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cacheDir);
        if (!result.success()) {
            // "kotlinc-missing" is an EX_SOFTWARE (70) shape; everything
            // else collapses to the generic resolver error code.
            for (GoalResult.Diagnostic d : result.errors()) {
                if ("kotlinc-missing".equals(d.code())) return 70;
            }
            return failureExitCode(result);
        }

        Path kotlinc = goal.get(KOTLINC_BIN).orElseThrow();
        List<String> command = new ArrayList<>();
        command.add(kotlinc.toString());
        command.add("-script");
        command.add(script.toAbsolutePath().toString());
        if (!args.isEmpty()) {
            command.add("--");
            command.addAll(args);
        }
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    // --- .jar ------------------------------------------------------------

    private int runJar(Path jar, List<String> args)
            throws IOException, InterruptedException {
        if (!Files.isRegularFile(jar)) {
            System.err.println("jk tool run: jar not found: " + jar);
            return 66;
        }

        Phase inspect = Phase.builder("inspect-jar")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("read manifest + embedded poms");
                    Optional<String> mainClass = JarManifest.mainClass(jar);
                    if (mainClass.isEmpty()) {
                        ctx.error("no-main-class",
                                jar + " has no Main-Class in its MANIFEST.MF");
                        throw new RuntimeException("missing Main-Class");
                    }
                    ctx.put(MAIN_CLASS, mainClass.get());

                    List<Dependency> declaredDeps = new ArrayList<>();
                    for (JarManifest.EmbeddedPom p : JarManifest.scanEmbeddedPoms(jar)) {
                        if (!p.hasPomXml()) continue;
                        try {
                            var imported = PomImporter.importFromBytes(p.pomXml());
                            var byScope = imported.jkBuild().dependencies().byScope();
                            for (Scope scope : EnumSet.of(Scope.MAIN, Scope.RUNTIME)) {
                                List<Dependency> scoped = byScope.get(scope);
                                if (scoped != null) declaredDeps.addAll(scoped);
                            }
                        } catch (RuntimeException e) {
                            ctx.warn("pom-parse",
                                    "failed to parse embedded pom for " + p.coord()
                                            + ": " + e.getMessage());
                        }
                    }
                    if (JarManifest.hasModuleInfo(jar)) {
                        ctx.warn("jpms", jar.getFileName()
                                + " ships a module-info.class (JPMS module); "
                                + "running it from the classpath.");
                    }
                    ctx.put(JAR_DECLARED_DEPS, declaredDeps);
                    ctx.progress(1);
                })
                .build();

        Phase resolveJarDeps = Phase.builder("resolve-jar-deps")
                .kind(PhaseKind.IO)
                .requires("inspect-jar")
                .scope(1)
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
                    Path cacheDir = cacheDir();
                    Files.createDirectories(cacheDir);
                    Cas cas = new Cas(cacheDir);
                    Http http = new Http();
                    RepoGroup repos = new RepoGroup(List.of(new MavenRepo(
                            "central",
                            repoUrl != null ? repoUrl : RepositorySpec.MAVEN_CENTRAL.url(),
                            http, cas)));
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

        Goal goal = Goal.builder("run-jar")
                .addPhase(inspect)
                .addPhase(resolveJarDeps)
                .build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cacheDir());
        if (!result.success()) {
            for (GoalResult.Diagnostic d : result.errors()) {
                if ("no-main-class".equals(d.code())) return 65; // EX_DATAERR
            }
            return failureExitCode(result);
        }

        String mainClass = goal.get(MAIN_CLASS).orElseThrow();
        @SuppressWarnings("unchecked")
        List<Path> classpath = (List<Path>) goal.get(CLASSPATH).orElseThrow();

        Path java = CompileToolchain.runningJavaHome().resolve("bin")
                .resolve(isWindows() ? "java.exe" : "java");
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        if (classpath.size() == 1) {
            // No extra deps — `java -jar` is cleaner and honors the jar's Class-Path attribute.
            command.add("-jar");
            command.add(jar.toAbsolutePath().toString());
        } else {
            command.add("-cp");
            command.add(joinClasspath(classpath));
            command.add(mainClass);
        }
        command.addAll(args);
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    // --- shared helpers --------------------------------------------------

    /**
     * Map a failed goal to exit code 1. The listener already painted
     * the diagnostic and the "Failed" bar; we don't repeat ourselves.
     */
    private int failureExitCode(GoalResult result) {
        return 1;
    }

    /** {@code classes/} for compiled output + shared CAS cache root. */
    private record Paths(Path cacheDir, Path classesDir) {}

    private Paths scriptPaths(byte[] sourceBytes) {
        String hash = Hashing.sha256Hex(sourceBytes);
        return new Paths(cacheDir(),
                stateDir().resolve("script-cache").resolve(hash).resolve("classes"));
    }

    private Path cacheDir() {
        return cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
    }

    private Path stateDir() {
        return stateDirOverride != null ? stateDirOverride : JkDirs.state();
    }

    private RepoGroup buildRepos(ScriptHeader header, Http http, Cas cas) {
        List<MavenRepo> list = new ArrayList<>();
        if (repoUrl != null) {
            list.add(new MavenRepo("central", repoUrl, http, cas));
        } else {
            for (URI uri : header.repos()) {
                list.add(new MavenRepo("script-repo-" + list.size(), uri, http, cas));
            }
            if (list.isEmpty()) {
                list.add(new MavenRepo(RepositorySpec.MAVEN_CENTRAL.name(),
                        RepositorySpec.MAVEN_CENTRAL.url(), http, cas));
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
            int colon = m.module().indexOf(':');
            Coordinate coord = Coordinate.of(
                    m.module().substring(0, colon),
                    m.module().substring(colon + 1),
                    m.version());
            jars.add(repos.tryFetchArtifact(coord)
                    .orElseThrow(() -> new MavenRepo.ArtifactNotFoundException(
                            "jar not found in any declared repo: " + coord))
                    .fetched().cachePath());
        }
        return jars;
    }

    private int execJava(Path classesDir, List<Path> classpath, List<String> jvmArgs,
                         String mainClass, List<String> args)
            throws IOException, InterruptedException {
        Path java = CompileToolchain.runningJavaHome().resolve("bin")
                .resolve(isWindows() ? "java.exe" : "java");
        List<Path> full = new ArrayList<>();
        full.add(classesDir);
        full.addAll(classpath);

        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(joinClasspath(full));
        command.add(mainClass);
        command.addAll(args);
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    private static String joinClasspath(List<Path> paths) {
        String sep = System.getProperty("path.separator");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(paths.get(i).toAbsolutePath());
        }
        return sb.toString();
    }

    /** {@code Foo.java} → {@code Foo}. (Matches the existing convention.) */
    private static String simpleMainClassName(Path script, String suffix) {
        String name = script.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(suffix)
                ? name.substring(0, name.length() - suffix.length())
                : name;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
