// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.config.ImageConfigParser;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.JdkResolver;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.run.PhaseStatus;
import dev.jkbuild.tool.NativeImageDriver;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk native} — build a GraalVM-compiled native binary for the
 * project. Uses the project's pinned JDK (must be a GraalVM distribution);
 * classpath comes from {@code jk.lock} + the project's main jar.
 *
 * <p>Organised as a {@link Goal} with four phases:
 * <ol>
 *   <li>{@code parse-build} (SYNC) — validate inputs, parse jk.toml,
 *       resolve image config (main class, output path).</li>
 *   <li>{@code resolve-jdk} (IO, parallel with assemble-classpath) —
 *       find the GraalVM JDK to run native-image with.</li>
 *   <li>{@code assemble-classpath} (SYNC, parallel with resolve-jdk) —
 *       collect lockfile-referenced JARs from the CAS.</li>
 *   <li>{@code native-image} (CPU) — fork {@code native-image} subprocess
 *       and wait. This is the expensive step (30–180s typically);
 *       upstream's stdout/stderr stream through inherited IO.</li>
 * </ol>
 *
 * <p>native-image doesn't expose structured progress, so its phase
 * reports as one chunk of work that completes on subprocess exit.
 * The verbose listener still shows phase boundaries; the event log
 * records start/finish timestamps for "how long did native compile
 * take" diagnosis.
 */
@Command(name = "native",
        description = "Compile a native binary with GraalVM native-image")
public final class NativeCommand implements Callable<Integer> {

    @Option(names = "--main",
            description = "Main class to compile. Default: read from jk.toml's image.main-class.")
    String mainClass;

    @Option(names = "--output",
            description = "Output binary path. Default: target/<artifact>.")
    Path output;

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
    Path cacheDirOverride;

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @Parameters(arity = "0..*", paramLabel = "<native-image-args>",
            description = "Extra arguments forwarded to native-image (after --).")
    List<String> extra = new ArrayList<>();

    @picocli.CommandLine.Mixin GlobalOptions global;

    // Cross-phase keys.
    private static final GoalKey<JkBuild> PROJECT = GoalKey.of("project", JkBuild.class);
    private static final GoalKey<Path> MAIN_JAR = GoalKey.of("main-jar", Path.class);
    private static final GoalKey<String> MAIN_CLASS = GoalKey.of("main-class", String.class);
    private static final GoalKey<Path> OUTPUT_PATH = GoalKey.of("output-path", Path.class);
    private static final GoalKey<Path> JAVA_HOME = GoalKey.of("java-home", Path.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> CLASSPATH = GoalKey.of("classpath", List.class);

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path projectDir = global.workingDir();
        Path jkBuildPath = projectDir.resolve("jk.toml");
        Path cache = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();

        // Bail with the existing error shapes for misuse before the goal
        // even starts — these don't deserve a run-log entry.
        if (!Files.exists(jkBuildPath)) {
            System.err.println("jk native: " + jkBuildPath + " not found.");
            return 66;
        }

        Phase parseBuild = Phase.builder("parse-build")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml");
                    JkBuild project;
                    try {
                        project = JkBuildParser.parse(jkBuildPath);
                    } catch (RuntimeException e) {
                        ctx.error("toml", e.getMessage());
                        throw e;
                    }
                    ctx.put(PROJECT, project);

                    Path mainJar = projectDir.resolve("target").resolve(
                            project.project().artifact() + "-"
                                    + project.project().version() + ".jar");
                    if (!Files.exists(mainJar)) {
                        ctx.error("missing-jar", "main jar not found at " + mainJar
                                + " — run `jk build` first.");
                        throw new RuntimeException("missing main jar");
                    }
                    ctx.put(MAIN_JAR, mainJar);

                    String chosenMain = mainClass != null
                            ? mainClass
                            : ImageConfigParser.parse(jkBuildPath).mainClass();
                    if (chosenMain == null || chosenMain.isBlank()) {
                        ctx.error("no-main",
                                "no main class — pass --main or set image.main-class.");
                        throw new RuntimeException("missing main class");
                    }
                    ctx.put(MAIN_CLASS, chosenMain);

                    Path out = output != null
                            ? output
                            : projectDir.resolve("target").resolve(project.project().artifact());
                    ctx.put(OUTPUT_PATH, out);
                    ctx.progress(1);
                })
                .build();

        Phase resolveJdk = Phase.builder("resolve-jdk")
                .kind(PhaseKind.IO)
                .requires("parse-build")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("locate GraalVM");
                    Optional<InstalledJdk> jdk = JdkResolver.forProject(projectDir, jdksDir);
                    Path javaHome = jdk.map(InstalledJdk::home)
                            .orElseGet(CompileToolchain::runningJavaHome);
                    if (jdk.isEmpty()) {
                        ctx.warn("jdk-fallback",
                                "no pinned JDK; falling back to the running JVM's java home — "
                                        + "native-image requires GraalVM, this may fail.");
                    }
                    ctx.put(JAVA_HOME, javaHome);
                    ctx.progress(1);
                })
                .build();

        Phase assembleClasspath = Phase.builder("assemble-classpath")
                .requires("parse-build")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("resolve classpath");
                    List<Path> classpath = new ArrayList<>();
                    classpath.add(ctx.require(MAIN_JAR));
                    classpath.addAll(loadLockedJars(projectDir, cache));
                    ctx.put(CLASSPATH, classpath);
                    ctx.progress(1);
                })
                .build();

        Phase nativeImage = Phase.builder("native-image")
                .kind(PhaseKind.CPU)
                .requires("resolve-jdk", "assemble-classpath")
                .scope(1)
                .execute(ctx -> {
                    @SuppressWarnings("unchecked")
                    List<Path> classpath = (List<Path>) ctx.require(CLASSPATH);
                    String chosenMain = ctx.require(MAIN_CLASS);
                    Path out = ctx.require(OUTPUT_PATH);
                    ctx.label("compile " + out.getFileName() + " (native-image, may take minutes)");
                    NativeImageDriver.Request request = new NativeImageDriver.Request(
                            ctx.require(JAVA_HOME), classpath, chosenMain, out, extra);
                    int exit = NativeImageDriver.run(request);
                    if (exit != 0) {
                        ctx.error("native-image", "native-image exited " + exit);
                        throw new RuntimeException("native-image failed (exit " + exit + ")");
                    }
                    ctx.progress(1);
                })
                .build();

        Goal goal = Goal.builder("native")
                .addPhase(parseBuild)
                .addPhase(resolveJdk)
                .addPhase(assembleClasspath)
                .addPhase(nativeImage)
                .build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);

        if (result.success()) {
            if (!global.outputIsJson()) {
                goal.get(OUTPUT_PATH).ifPresent(out ->
                        System.out.println("Built native binary " + out));
            }
            return 0;
        }
        printFailureSummary(result, cache);
        // Preserve the previous error codes:
        //   66 (EX_NOINPUT) for missing inputs (jar / main class)
        //   64 (EX_USAGE)   for "no main class — pass --main"
        //   1              for native-image subprocess failure
        for (GoalResult.Diagnostic d : result.errors()) {
            if ("missing-jar".equals(d.code())) return 66;
            if ("no-main".equals(d.code())) return 64;
        }
        return 1;
    }

    private void printFailureSummary(GoalResult result, Path cache) {
        String failedPhase = result.phases().stream()
                .filter(p -> p.status() == PhaseStatus.FAIL)
                .map(GoalResult.PhaseReport::name)
                .findFirst().orElse("?");
        System.err.println("jk native failed: " + failedPhase);
        for (GoalResult.Diagnostic d : result.errors()) {
            System.err.println("  " + d.code() + ": " + d.message());
        }
        System.err.println("Run log: " + cache.resolve("runs"));
    }

    private static List<Path> loadLockedJars(Path projectDir, Path cache) throws IOException {
        Path lockPath = projectDir.resolve("jk.lock");
        if (!Files.exists(lockPath)) return List.of();
        Path casRoot = cache.resolve("sha256");
        Lockfile lock = LockfileReader.read(lockPath);
        List<Path> result = new ArrayList<>();
        for (Lockfile.Package pkg : lock.packages()) {
            if (pkg.checksum() == null) continue;
            String hex = pkg.checksum().startsWith("sha256:")
                    ? pkg.checksum().substring("sha256:".length())
                    : pkg.checksum();
            Path candidate = casRoot.resolve(hex.substring(0, 2)).resolve(hex);
            if (Files.exists(candidate)) result.add(candidate);
        }
        return result;
    }
}
