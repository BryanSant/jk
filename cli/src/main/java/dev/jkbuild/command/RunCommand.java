// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.runtime.BuildPipeline;
import dev.jkbuild.runtime.CompileToolchain;
import dev.jkbuild.runtime.CompositeLocator;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.run.ConsoleSpec;
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
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.util.JkDirs;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * {@code jk run [-- <args>...]} — build the current project through the shared
 * {@link BuildPipeline} and run its best artifact, forwarding every argument to
 * the program.
 *
 * <p>The build runs in a {@link Goal} (so progress/warnings/run-log behave like
 * every other verb) and produces whatever {@code jk.toml} declares — a plain
 * jar, a shadow jar, and/or a native binary. We then exec the most
 * self-contained artifact available, in order of preference:
 * <strong>native binary &gt; shadow jar &gt; plain jar</strong>. The subprocess
 * starts <em>after</em> the goal returns (the progress widget has wiped itself,
 * so the inferior owns the TTY); a native binary is exec'd directly, a jar via
 * {@code java -cp … <main>}. We never hoist a jar into this JVM.
 *
 * <p>{@code jk run} does not interpret file arguments — a {@code .java} /
 * {@code .jar} argument is forwarded to the program, not executed. Use
 * {@code jk tool run} for a loose file or a published tool.
 */
public final class RunCommand implements CliCommand {

    @Override public String name() { return "run"; }
    @Override public String description() { return "Run the current project's designated target"; }
    @Override public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir").hide(),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir").hide(),
                Opt.flag("Skip compiling and running tests.", "--skip-tests"));
    }
    @Override public List<Param> parameters() {
        return List.of(Param.of("args", Arity.ZERO_OR_MORE, "Arguments forwarded to the project's main method."));
    }

    List<String> positional = new ArrayList<>();
    Path cacheDirOverride;
    Path jdksDir;
    dev.jkbuild.cli.BuildOptions buildOpts;
    GlobalOptions global;

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.positional = in.positionals();
        this.cacheDirOverride = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.buildOpts = new dev.jkbuild.cli.BuildOptions();
        this.buildOpts.skipTests = in.isSet("skip-tests");
        this.global = GlobalOptions.from(in);

        Path projectDir = global.workingDir();
        Path manifest = projectDir.resolve("jk.toml");
        if (!Files.exists(manifest)) {
            System.err.println("jk run: no jk.toml in " + projectDir
                    + " — run from a project directory, or use `jk tool run` to run a file or tool.");
            return 64; // EX_USAGE
        }
        return runProject(projectDir, positional);
    }

    private int runProject(Path projectDir, List<String> appArgs)
            throws IOException, InterruptedException {
        JkBuild project = JkBuildParser.parse(projectDir.resolve("jk.toml"));
        if (project.project().main() == null) {
            System.err.println("jk run: no `main` class set in [project] for "
                    + projectDir + " — set `main = \"<fqcn>\"`.");
            return 64;
        }
        BuildLayout layout = BuildLayout.of(projectDir, project);
        Path cache = cacheDir();

        // Build composite (path / branch-git) dependency units first, so the
        // project's own build can locate their jars on its classpath.
        int dep = CompositeBuild.buildDependencies(projectDir, project, cache, jdksDir, null, global);
        if (dep != 0) return dep;

        // Build through the one pipeline, producing whatever jk.toml declares
        // (jar always; shadow/native when configured). Cache-aware, so a clean
        // tree is near-instant.
        Path lockFile = projectDir.resolve("jk.lock");
        int estimatedTestCount = TestCommand.estimateTestCount(projectDir.resolve("src/test/java"));
        BuildPipeline.Inputs inputs = new BuildPipeline.Inputs(
                projectDir, cache, projectDir.resolve("jk.toml"), lockFile, projectDir,
                1, estimatedTestCount, null, jdksDir, buildOpts.skipTests, global.verbose);
        Goal.Builder builder = BuildPipeline.coreBuilder(inputs);
        BuildPipeline.appendDeclaredTails(builder, inputs);
        Goal goal = builder.build();

        ConsoleSpec spec = new ConsoleSpec("Build",
                r -> "Built",
                r -> "Build failed");
        GoalResult result = GoalConsole.runGoal(goal, GoalConsole.modeFor(global), cache, spec,
                BuildCommand.buildTarget(projectDir.resolve("jk.toml"), projectDir));
        if (!result.success()) {
            var testResult = goal.get(BuildPipeline.TEST_RESULT).orElse(null);
            if (testResult != null && !testResult.allPassed()) return 4;
            return 1;
        }

        // Exec the most self-contained artifact: native > shadow > plain jar.
        List<String> command = execCommand(projectDir, project, layout);
        printExecBanner(projectDir, command);
        command.addAll(appArgs);
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    /** The command line for the best available artifact (native &gt; shadow &gt; jar). */
    private List<String> execCommand(Path projectDir, JkBuild project, BuildLayout layout)
            throws IOException {
        List<String> command = new ArrayList<>();

        Path nativeBin = layout.nativeBinary();
        if (Files.isRegularFile(nativeBin) && Files.isExecutable(nativeBin)) {
            command.add(nativeBin.toAbsolutePath().toString());
            return command;
        }

        String javaExe = CompileToolchain.runningJavaHome().resolve("bin")
                .resolve(isWindows() ? "java.exe" : "java").toString();
        command.add(javaExe);

        Path shadow = layout.shadowJar();
        if (Files.isRegularFile(shadow)) {
            // Shadow jar bundles deps + Main-Class manifest — runnable with -jar.
            command.add("-jar");
            command.add(shadow.toAbsolutePath().toString());
        } else {
            command.add("-cp");
            command.add(joinClasspath(assembleRuntimeClasspath(projectDir, project, layout.mainJar())));
            command.add(project.project().main());
        }
        return command;
    }

    /**
     * Prints the "→ Executing ..." line (to stderr, above the program's output)
     * followed by a blank line, so the program's stdout starts on a clean line.
     *
     * <p>Native:  {@code → Executing native binary: [yellow]target/myapp[/]}
     * <p>Shadow:  {@code → Executing [dim italic]{jdk}[/]:[yellow]java -jar target/app-all.jar[/]}
     * <p>Plain:   {@code → Executing [dim italic]{jdk}[/]:[yellow]java -cp … target/app.jar[/]}
     */
    private static void printExecBanner(Path projectDir, List<String> command) {
        Theme t = Theme.active();
        String exec;
        if (command.size() == 1) {
            // Native binary — "native binary: target/myapp"
            Path bin = Path.of(command.get(0));
            exec = "native binary: "
                    + Theme.colorize(relativeTo(projectDir, bin), t.warning());
        } else {
            // JVM — derive jdk leaf, resolving symlinks so "current" shows the real spec.
            Path javaExe = Path.of(command.get(0));
            try { javaExe = javaExe.toRealPath(); } catch (IOException ignored) {}
            Path jdkHome = javaExe.getParent() != null ? javaExe.getParent().getParent() : null;
            String jdkLeaf = jdkHome != null && jdkHome.getFileName() != null
                    ? jdkHome.getFileName().toString() : "java";

            String flag = command.get(1);   // "-jar" or "-cp"
            String javaCmd;
            if ("-jar".equals(flag)) {
                // Shadow jar — full relative path, no classpath noise.
                Path jar = Path.of(command.get(2));
                javaCmd = "java -jar " + relativeTo(projectDir, jar);
            } else {
                // Plain jar + classpath — elide the full cp, show project jar.
                String cpArg = command.size() >= 3 ? command.get(2) : "";
                String pathSep = System.getProperty("path.separator");
                String first = cpArg.contains(pathSep)
                        ? cpArg.substring(0, cpArg.indexOf(pathSep)) : cpArg;
                javaCmd = "java -cp … " + relativeTo(projectDir, Path.of(first));
            }
            exec = "(" + jdkLeaf + "): " + Theme.colorize(javaCmd, t.warning());
        }
        System.err.println("→ Executing " + exec);
        System.err.println();
    }

    private static String relativeTo(Path base, Path target) {
        try {
            return base.relativize(target.toAbsolutePath()).toString();
        } catch (IllegalArgumentException ignored) {
            return target.getFileName() != null ? target.getFileName().toString() : target.toString();
        }
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

        // Composite (path + branch-git) source deps: locate their jars + runtime
        // external deps (already built upfront by CompositeBuild) for the run classpath.
        try {
            CompositeLocator.Located composite = CompositeLocator.locate(
                    projectDir, project, EnumSet.of(Scope.MAIN, Scope.RUNTIME),
                    ClasspathResolver.RUNTIME, cas, cacheDir().resolve("git"));
            for (Path j : composite.jars())            if (!classpath.contains(j)) classpath.add(j);
            for (Path j : composite.externalDepJars()) if (!classpath.contains(j)) classpath.add(j);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
