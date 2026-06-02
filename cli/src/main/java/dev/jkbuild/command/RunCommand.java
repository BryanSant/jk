// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;

import dev.jkbuild.cli.GlobalOptions;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceClasspath;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * {@code jk run [-- <args>...]} — build (if needed) and run the current
 * project's main artifact, forwarding every argument to the project's
 * {@code main} method.
 *
 * <p>{@code jk run} no longer interprets file arguments. A {@code .java},
 * {@code .kt}, {@code .kts}, or {@code .jar} argument is passed straight
 * through to the program like any other argument — it is not executed by
 * jk. To run a loose file or a published tool, use {@code jk tool run}.
 *
 * <p>The preparation (build, classpath assembly) runs inside a {@link Goal}
 * so progress, warnings, and the run-log behave like every other {@code jk}
 * verb. The subprocess that exec's the program runs <i>after</i> the goal
 * returns — by then the progress widget has wiped itself, so the inferior
 * owns the TTY cleanly. If the goal fails we exit without forking.
 */
@Command(name = "run",
        description = "Run the current project's designated target")
public final class RunCommand implements Callable<Integer> {

    @Parameters(arity = "0..*", paramLabel = "<args>",
            description = "Arguments forwarded to the project's main method.")
    List<String> positional = new ArrayList<>();

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
    Path cacheDirOverride;

    @picocli.CommandLine.Mixin GlobalOptions global;

    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> CLASSPATH = GoalKey.of("classpath", List.class);

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path projectDir = global.workingDir();
        Path manifest = projectDir.resolve("jk.toml");
        if (!Files.exists(manifest)) {
            System.err.println("jk run: no jk.toml in " + projectDir
                    + " — run from a project directory, or use `jk tool run` to run a file or tool.");
            return 64; // EX_USAGE
        }
        return runProject(projectDir, positional);
    }

    // --- project mode ----------------------------------------------------

    private int runProject(Path projectDir, List<String> appArgs)
            throws IOException, InterruptedException {
        JkBuild project = JkBuildParser.parse(projectDir.resolve("jk.toml"));
        if (project.project().main() == null) {
            System.err.println("jk run: no `main` class set in [project] for "
                    + projectDir + " — set `main = \"<fqcn>\"`.");
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
            return 1;
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

    private Path cacheDir() {
        return cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
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

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
