// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.runtime.CompileToolchain;

import dev.jkbuild.cli.GlobalOptions;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.compile.CompileRequest;
import dev.jkbuild.compile.CompileResult;
import dev.jkbuild.compile.JavacDriver;
import dev.jkbuild.compile.KotlincDriver;
import dev.jkbuild.compile.KotlincRequest;
import dev.jkbuild.compile.KotlincResult;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Profile;
import dev.jkbuild.model.Profiles;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.run.PhaseStatus;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * {@code jk compile} — type-check sources without producing artifacts.
 * Was {@code jk check} pre-v1.0; {@code check} remains a hidden alias
 * (see {@code docs/aliases.md}).
 *
 * <p>Goal shape: {@code parse-build} → {@code resolve-classpath} →
 * {@code compile-java} (if Java sources) → {@code compile-kotlin}
 * (if Kotlin sources, after Java). The scratch output directory
 * lives under {@code $TMPDIR} and is deleted on goal completion.
 */
@Command(name = "compile", description = "Compile this project's source code")
public final class CompileCommand implements Callable<Integer> {

    @Option(names = "--profile", paramLabel = "<name>",
            description = "Build profile to apply. Default: auto (ci if CI=true, else none).")
    String profileName;

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
    Path cacheDir;

    @picocli.CommandLine.Mixin GlobalOptions global;

    private static final GoalKey<JkBuild> PROJECT = GoalKey.of("project", JkBuild.class);
    private static final GoalKey<Lockfile> LOCK = GoalKey.of("lock", Lockfile.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> JAVA_SOURCES = GoalKey.of("java-sources", List.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> KT_SOURCES = GoalKey.of("kt-sources", List.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> CLASSPATH = GoalKey.of("classpath", List.class);
    private static final GoalKey<Path> SCRATCH = GoalKey.of("scratch", Path.class);

    @Override
    public Integer call() throws IOException {
        Path dir = global.workingDir();
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(buildFile)) {
            System.err.println("jk compile: no jk.toml in " + dir);
            return 2;
        }
        if (!Files.exists(lockFile)) {
            System.err.println("jk compile: no jk.lock in " + dir + " (run `jk lock` first)");
            return 2;
        }
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();

        Phase parseBuild = Phase.builder("parse-build")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml + jk.lock");
                    JkBuild project;
                    try {
                        project = JkBuildParser.parse(buildFile);
                    } catch (RuntimeException e) {
                        ctx.error("toml", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    ctx.put(PROJECT, project);
                    ctx.put(LOCK, LockfileReader.read(lockFile));
                    List<Path> javaSources = collectJavaSources(dir.resolve("src/main/java"));
                    List<Path> ktSources = collectKotlinSources(dir);
                    ctx.put(JAVA_SOURCES, javaSources);
                    ctx.put(KT_SOURCES, ktSources);
                    if (javaSources.isEmpty() && ktSources.isEmpty()) {
                        ctx.label("no sources");
                    }
                    ctx.progress(1);
                })
                .build();

        Phase resolveClasspath = Phase.builder("resolve-classpath")
                .requires("parse-build")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("collect compile classpath");
                    Cas cas = new Cas(cache);
                    List<Path> classpath = new ClasspathResolver(cas)
                            .classpathFor(ctx.require(LOCK), ClasspathResolver.COMPILE_MAIN);
                    ctx.put(CLASSPATH, classpath);
                    Path scratch = Files.createTempDirectory("jk-check-");
                    ctx.put(SCRATCH, scratch);
                    ctx.progress(1);
                })
                .build();

        Phase compileJava = Phase.builder("compile-java")
                .kind(PhaseKind.CPU)
                .requires("resolve-classpath")
                .scope(1)
                .execute(ctx -> {
                    @SuppressWarnings("unchecked")
                    List<Path> javaSources = (List<Path>) ctx.require(JAVA_SOURCES);
                    if (javaSources.isEmpty()) {
                        ctx.label("no java sources");
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("javac (" + javaSources.size() + " source"
                            + (javaSources.size() == 1 ? ")" : "s)"));
                    JkBuild project = ctx.require(PROJECT);
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    Path scratch = ctx.require(SCRATCH);
                    Profile profile = resolveProfile(project.profiles(), profileName);
                    int release = project.project().javaRelease();
                    Path javaHome = CompileToolchain.resolveJavaHome(dir);
                    CompileRequest request = CompileRequest.builder()
                            .sources(javaSources)
                            .classpath(classpath)
                            .outputDir(scratch)
                            .release(release)
                            .extraOptions(profile == null ? List.of() : profile.javacArgs())
                            .javaHome(javaHome)
                            .build();
                    CompileResult result = new JavacDriver().compile(request);
                    for (CompileResult.Diagnostic d : result.diagnostics()) {
                        System.err.println(d.render());
                    }
                    if (!result.success() || result.hasErrors()) {
                        ctx.error("javac", "javac reported errors");
                        throw new RuntimeException("javac failed");
                    }
                    ctx.progress(1);
                })
                .build();

        Phase compileKotlin = Phase.builder("compile-kotlin")
                .kind(PhaseKind.CPU)
                .requires("compile-java")
                .scope(1)
                .execute(ctx -> {
                    @SuppressWarnings("unchecked")
                    List<Path> ktSources = (List<Path>) ctx.require(KT_SOURCES);
                    if (ktSources.isEmpty()) {
                        ctx.label("no kotlin sources");
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("kotlinc (" + ktSources.size() + " source"
                            + (ktSources.size() == 1 ? ")" : "s)"));
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    Path scratch = ctx.require(SCRATCH);
                    JkBuild project = ctx.require(PROJECT);
                    int release = project.project().javaRelease();
                    List<Path> kotlincCp = new ArrayList<>(classpath);
                    kotlincCp.add(scratch);
                    Path kotlinHome = CompileToolchain.resolveKotlinHome(cache,
                            CompileToolchain.kotlinVersionFor(ctx.require(LOCK), project));
                    KotlincResult result = new KotlincDriver().compile(
                            KotlincRequest.builder()
                                    .sources(ktSources)
                                    .classpath(kotlincCp)
                                    .outputDir(scratch)
                                    .jvmTarget(kotlinJvmTarget(release))
                                    .kotlinHome(kotlinHome)
                                    .build());
                    if (!result.success()) {
                        System.err.print(result.output());
                        ctx.error("kotlinc", "kotlinc reported errors");
                        throw new RuntimeException("kotlinc failed");
                    }
                    ctx.progress(1);
                })
                .build();

        Goal goal = Goal.builder("compile")
                .addPhase(parseBuild)
                .addPhase(resolveClasspath)
                .addPhase(compileJava)
                .addPhase(compileKotlin)
                .build();

        ConsoleSpec spec = new ConsoleSpec("Compile",
                r -> {
                    int total = sourceCount(goal, JAVA_SOURCES) + sourceCount(goal, KT_SOURCES);
                    if (total == 0) return "No sources found";
                    String compiled = Theme.colorize("Compiled", Theme.active().focused());
                    return compiled + " " + total + " source file" + (total == 1 ? "" : "s")
                            + " " + BuildCommand.inTime(r);
                },
                r -> "Compilation failed " + BuildCommand.inTime(r));
        GoalResult result = GoalConsole.runGoal(goal, GoalConsole.modeFor(global), cache, spec,
                BuildCommand.buildTarget(buildFile, dir));

        // Clean up scratch regardless of outcome.
        goal.get(SCRATCH).ifPresent(CompileCommand::deleteRecursively);

        return result.success() ? 0 : 1;
    }

    @SuppressWarnings("unchecked")
    private static int sourceCount(dev.jkbuild.run.Goal goal, dev.jkbuild.run.GoalKey<?> key) {
        return goal.get(key).map(v -> ((List<Path>) v).size()).orElse(0);
    }

    static int kotlinJvmTarget(int release) {
        return dev.jkbuild.runtime.CompileSupport.kotlinJvmTarget(release);
    }

    static List<Path> collectKotlinSources(Path projectDir) throws IOException {
        return dev.jkbuild.runtime.CompileSupport.collectKotlinSources(projectDir);
    }

    private static void deleteRecursively(Path target) {
        if (target == null || !Files.exists(target)) return;
        try (Stream<Path> stream = Files.walk(target)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    static List<Path> collectJavaSources(Path root) throws IOException {
        return dev.jkbuild.runtime.CompileSupport.collectJavaSources(root);
    }

    /**
     * Pick the active profile. Explicit {@code --profile} wins. Otherwise
     * the {@code ci} profile is auto-selected when running on CI.
     */
    static Profile resolveProfile(Profiles profiles, String explicitName) {
        if (explicitName != null && !explicitName.isBlank()) {
            return profiles.resolve(explicitName);
        }
        String auto = Profiles.autoSelect(System.getenv());
        if (auto != null && profiles.contains(auto)) {
            return profiles.resolve(auto);
        }
        return null;
    }
}
