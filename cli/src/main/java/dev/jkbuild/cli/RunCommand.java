// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.compile.CompileRequest;
import dev.jkbuild.compile.CompileResult;
import dev.jkbuild.compile.JavacDriver;
import dev.jkbuild.compile.KotlincDriver;
import dev.jkbuild.compile.KotlincRequest;
import dev.jkbuild.compile.KotlincResult;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceClasspath;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.http.Http;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
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
import dev.jkbuild.run.PhaseStatus;
import dev.jkbuild.script.ScriptHeader;
import dev.jkbuild.script.ScriptHeaderParser;
import dev.jkbuild.tool.JarManifest;
import dev.jkbuild.util.Hashing;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

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
import java.util.concurrent.Callable;

/**
 * {@code jk run [<file>] [-- <args>...]} — runs one of:
 * <ul>
 *   <li><b>{@code .java}</b> script (JBang-compatible, PRD §19).</li>
 *   <li><b>{@code .kt}</b> Kotlin script (JBang-compatible, with
 *       {@code //KOTLIN <version>} support).</li>
 *   <li><b>{@code .kts}</b> Kotlin Script — delegated to {@code kotlinc -script}.</li>
 *   <li><b>{@code .jar}</b> already-built artifact.</li>
 *   <li><b>(no arg)</b> the current project (from {@code jk.toml}).</li>
 * </ul>
 *
 * <p>Each mode runs its preparation (parse, resolve, compile) inside a
 * {@link Goal} so progress, warnings, and the run-log behave like every
 * other {@code jk} verb. The actual subprocess that exec's the user's
 * program runs <i>after</i> the goal returns — by then the progress
 * widget has wiped itself, so the inferior owns the TTY cleanly.
 *
 * <p>If the goal fails (compile error, resolution failure, missing
 * main class, etc.) we exit without forking.
 */
@Command(name = "run",
        description = "Run a script (.java/.kt/.kts), a jar, or the current project")
public final class RunCommand implements Callable<Integer> {

    @Parameters(arity = "0..*", paramLabel = "<args>",
            description = "Either a .java/.kt/.kts/.jar file (then args after it) "
                    + "or arguments forwarded to the project's main class.")
    List<String> positional = new ArrayList<>();

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
    Path cacheDirOverride;

    @Option(names = "--state-dir", hidden = true,
            description = "Override the jk state directory. Default: $JK_STATE_DIR.")
    Path stateDirOverride;

    @Option(names = "--repo-url", hidden = true,
            description = "Override the Maven repository URL (for tests).")
    URI repoUrl;

    @Option(names = "--force-recompile",
            description = "Ignore cached classes and recompile (script modes).")
    boolean forceRecompile;

    @picocli.CommandLine.Mixin GlobalOptions global;

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

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path scriptPath = detectScript();
        if (scriptPath != null) {
            List<String> args = positional.subList(1, positional.size());
            String name = scriptPath.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".java")) return runJavaScript(scriptPath, args);
            if (name.endsWith(".kts"))  return runKtsScript(scriptPath, args);
            if (name.endsWith(".kt"))   return runKotlinScript(scriptPath, args);
            if (name.endsWith(".jar"))  return runJar(scriptPath, args);
        }
        Path projectDir = global.workingDir();
        Path manifest = projectDir.resolve("jk.toml");
        if (!Files.exists(manifest)) {
            System.err.println("jk run: no recognized file argument and no jk.toml in " + projectDir);
            return 64; // EX_USAGE
        }
        return runProject(projectDir, positional);
    }

    /**
     * If the first positional ends in a known file extension we treat it as
     * the run target — even if the file is missing, so the user gets a
     * proper "not found" error from the matching mode handler.
     */
    private Path detectScript() {
        if (positional.isEmpty()) return null;
        String first = positional.getFirst().toLowerCase(Locale.ROOT);
        if (first.endsWith(".java") || first.endsWith(".kt")
                || first.endsWith(".kts") || first.endsWith(".jar")) {
            return Path.of(positional.getFirst());
        }
        return null;
    }

    // --- .java -----------------------------------------------------------

    private int runJavaScript(Path script, List<String> args)
            throws IOException, InterruptedException {
        if (!Files.isRegularFile(script)) {
            System.err.println("jk run: script not found: " + script);
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
        if (!result.success()) return failureExitCode(result, "jk run-java");

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
            System.err.println("jk run: script not found: " + script);
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
        if (!result.success()) return failureExitCode(result, "jk run-kt");

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
            System.err.println("jk run: script not found: " + script);
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
            return failureExitCode(result, "jk run-kts");
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
            System.err.println("jk run: jar not found: " + jar);
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
            return failureExitCode(result, "jk run-jar");
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

    // --- project mode ----------------------------------------------------

    private int runProject(Path projectDir, List<String> appArgs)
            throws IOException, InterruptedException {
        JkBuild project = JkBuildParser.parse(projectDir.resolve("jk.toml"));
        if (project.project().main() == null) {
            System.err.println("jk run: no `main` class set in [project] for "
                    + projectDir + " — set `main = \"<fqcn>\"` or pass a script.");
            return 64;
        }
        BuildLayout layout = BuildLayout.of(projectDir, project);

        // If a native binary exists, it's the fast path — skip the goal
        // entirely and exec it directly. No prep needed.
        Path nativeBin = layout.nativeBinary();
        if (Files.isRegularFile(nativeBin) && Files.isExecutable(nativeBin)) {
            List<String> command = new ArrayList<>();
            command.add(nativeBin.toAbsolutePath().toString());
            command.addAll(appArgs);
            return new ProcessBuilder(command).inheritIO().start().waitFor();
        }

        Path jar = layout.mainJar();

        Phase ensureBuilt = Phase.builder("ensure-built")
                .kind(PhaseKind.CPU)
                .scope(1)
                .execute(ctx -> {
                    if (Files.exists(jar)) {
                        ctx.label("jar present");
                        ctx.progress(1);
                        return;
                    }
                    // The nested `jk build` invocation paints its own
                    // progress widget; this outer goal's widget yields
                    // for the duration. Acceptable for now — we could
                    // model build as a sub-goal in a future commit.
                    ctx.label("jar missing — running jk build");
                    int rc = Jk.execute("build", "-C", projectDir.toString());
                    if (rc != 0) {
                        ctx.error("nested-build", "jk build exited " + rc);
                        throw new RuntimeException("nested build failed");
                    }
                    if (!Files.exists(jar)) {
                        ctx.error("missing-jar", "expected jar at " + jar
                                + " but build did not produce it.");
                        throw new RuntimeException("jar not produced");
                    }
                    ctx.progress(1);
                })
                .build();

        Phase assembleClasspath = Phase.builder("assemble-classpath")
                .requires("ensure-built")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("collect runtime classpath");
                    List<Path> classpath = assembleRuntimeClasspath(projectDir, project, jar);
                    ctx.put(CLASSPATH, classpath);
                    ctx.progress(1);
                })
                .build();

        Goal goal = Goal.builder("run")
                .addPhase(ensureBuilt)
                .addPhase(assembleClasspath)
                .build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cacheDir());
        if (!result.success()) {
            for (GoalResult.Diagnostic d : result.errors()) {
                if ("missing-jar".equals(d.code())) return 70; // EX_SOFTWARE — build promised but didn't deliver
                if ("nested-build".equals(d.code())) return 1;
            }
            return failureExitCode(result, "jk run");
        }

        @SuppressWarnings("unchecked")
        List<Path> classpath = (List<Path>) goal.get(CLASSPATH).orElseThrow();
        Path java = CompileToolchain.runningJavaHome().resolve("bin")
                .resolve(isWindows() ? "java.exe" : "java");
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-cp");
        command.add(joinClasspath(classpath));
        command.add(project.project().main());
        command.addAll(appArgs);
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    private List<Path> assembleRuntimeClasspath(Path projectDir, JkBuild project, Path projectJar)
            throws IOException {
        List<Path> classpath = new ArrayList<>();
        classpath.add(projectJar);

        Path lockFile = projectDir.resolve("jk.lock");
        if (!Files.exists(lockFile)) {
            var rootOpt = WorkspaceLocator.findRoot(projectDir);
            if (rootOpt.isPresent()) {
                Path candidate = rootOpt.get().resolve("jk.lock");
                if (Files.exists(candidate)) lockFile = candidate;
            }
        }
        Cas cas = new Cas(cacheDir());
        if (Files.exists(lockFile)) {
            Lockfile lock = LockfileReader.read(lockFile);
            classpath.addAll(new ClasspathResolver(cas).classpathFor(lock,
                    EnumSet.of(Scope.MAIN, Scope.RUNTIME)));
        }
        WorkspaceClasspath.Result siblings = WorkspaceClasspath.resolve(projectDir, project,
                EnumSet.of(Scope.MAIN, Scope.RUNTIME));
        classpath.addAll(siblings.jars());
        return classpath;
    }

    // --- shared helpers --------------------------------------------------

    /**
     * Map a failed goal to exit code 1. The listener already painted
     * the diagnostic and the "Failed" bar; we don't repeat ourselves.
     */
    private int failureExitCode(GoalResult result, String commandLabel) {
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
