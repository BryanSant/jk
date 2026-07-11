// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.Ansi;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.PathDisplay;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.GoalWedge;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The current-project run pipeline — build through the shared engine pipeline, then exec the best
 * artifact. <b>Not a registered verb since the 2026-07-09 inversion</b>: {@code jk run} (and its
 * {@code jk tool run} mount) is {@link ToolRunCommand}, which delegates project and directory
 * targets here via {@link #runProject}; app args ride {@code jk run . <args>}.
 *
 * <p>The build runs in a {@link dev.jkbuild.run.Goal} (so progress/warnings/run-log behave like every other verb)
 * and produces whatever {@code jk.toml} declares — a plain jar, a shadow jar, and/or a native
 * binary. We then exec the most self-contained artifact available, in order of preference:
 * <strong>native binary &gt; shadow jar &gt; plain jar</strong>. The subprocess starts
 * <em>after</em> the goal returns (the progress widget has wiped itself, so the inferior owns the
 * TTY); a native binary is exec'd directly, a jar via {@code java -cp … <main>}. We never hoist a
 * jar into this JVM.
 *
 */
public final class RunCommand implements CliCommand {

    @Override
    public String name() {
        return "run";
    }

    @Override
    public String description() {
        return "Run the current project's designated target";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                        .hide(),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir")
                        .hide());
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("args", Arity.ZERO_OR_MORE, "Arguments forwarded to the project's main method."));
    }

    List<String> positional = new ArrayList<>();
    Path cacheDirOverride;
    Path jdksDir;
    dev.jkbuild.cli.BuildOptions buildOpts;
    GlobalOptions global;

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()} for the full rationale. A real {@code jk run} hosts
     * its build half on the engine (slim-client Wave 3); only the exec of the user's program stays
     * in this process (it owns the terminal).
     */
    private static boolean engineDisabledForTests() {
        // Also bypass inside a jk-forked test worker (jk.plugin.class=JkRunner) — see BuildCommand.
        return Boolean.getBoolean("jk.test.noEngine")
                || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"));
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.positional = in.positionals();
        this.cacheDirOverride = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.buildOpts = new dev.jkbuild.cli.BuildOptions();
        // `jk run` builds and runs the artifact; it never runs the test phase.
        this.buildOpts.skipTests = true;
        this.global = GlobalOptions.from(in);

        Path projectDir = global.workingDir();
        Path manifest = projectDir.resolve("jk.toml");
        if (!Files.exists(manifest)) {
            CliOutput.err("jk run: no jk.toml in "
                    + dev.jkbuild.cli.PathDisplay.styledRaw(projectDir)
                    + " — run from a project directory, or use `jk tool run` to run a file or tool.");
            return Exit.USAGE;
        }
        return runProject(projectDir, positional);
    }

    /** Package-private: {@code jk tool run <dir>} delegates a jk-project directory here. */
    int runProject(Path projectDir, List<String> appArgs) throws IOException, InterruptedException {
        // No client-side jk.toml parse: the engine computes the exec plan (artifact
        // preference, classpath, main-class scan) after the build — thin-client contract.
        Path cache = cacheDir();

        String coord = BuildCommand.buildTarget(projectDir.resolve("jk.toml"), projectDir);
        GoalConsole.Mode mode = GoalConsole.modeFor(global);
        // In chip modes (AUTO/QUIET) the goal settles with the ▶ Exec chip line showing
        // the exec command directly — no second banner line. In VERBOSE/JSON no chip is
        // printed, so printExecBanner runs after the goal as before.
        ConsoleSpec spec = new ConsoleSpec(
                "Exec",
                r -> {
                    try {
                        return execTail(projectDir, execPlan(projectDir));
                    } catch (IOException e) {
                        return "Executing";
                    }
                },
                r -> GoalWedge.coord(coord),
                true,
                true);

        GoalResult result;
        dev.jkbuild.run.TestSummary testResult;
        if (engineDisabledForTests()) {
            var o = dev.jkbuild.cli.engine.InProcessEngine.require()
                    .runBuildGoal(projectDir, cache, jdksDir, buildOpts.skipTests, global.verbose, mode, spec, coord);
            result = o.result();
            testResult = o.testResult();
        } else {
            // Engine-hosted build half (slim-client Wave 3): jk run's build is exactly the
            // single-project build goal the engine already hosts (SINGLE_BUILD_REQUEST, with
            // skipTests=true) — only the exec below stays in this process, which owns the TTY.
            var session = dev.jkbuild.config.SessionContext.current();
            dev.jkbuild.run.TestSummary[] testResultHolder = new dev.jkbuild.run.TestSummary[1];
            try {
                result = dev.jkbuild.cli.engine.EngineClient.runSingleBuild(
                        dev.jkbuild.engine.EnginePaths.current(),
                        new dev.jkbuild.cli.engine.EngineClient.SingleBuildRequest(
                                projectDir,
                                cache,
                                jdksDir,
                                1,
                                null,
                                buildOpts.skipTests,
                                global.verbose,
                                session.offline(),
                                session.force()),
                        phases -> GoalConsole.chooseConsoleListener(phases, mode, spec, coord),
                        testResultHolder,
                        new String[1]);
            } catch (IOException e) {
                CliOutput.err("jk run: " + e.getMessage());
                return Exit.SOFTWARE;
            }
            testResult = testResultHolder[0];
        }
        if (!result.success()) {
            if (testResult != null && !testResult.allPassed()) return 4;
            return 1;
        }

        // Exec the engine's plan: the most self-contained artifact (native > shadow > jar),
        // computed engine-side against the just-built outputs.
        List<String> command;
        try {
            command = new ArrayList<>(execPlan(projectDir).argv());
        } catch (IOException e) {
            // Typically the main-class scan: none/several found — message is ready to print.
            CliOutput.err("jk run: " + e.getMessage());
            return Exit.USAGE;
        }
        if (mode == GoalConsole.Mode.VERBOSE || mode == GoalConsole.Mode.JSON) {
            // No chip was printed in these modes — show the banner line as before.
            printExecBanner(projectDir, execPlan(projectDir));
        } else {
            // Chip already settled with exec info; emit the blank separator + color reset.
            CliOutput.err();
            if (Theme.colorEnabled()) {
                CliOutput.errRaw(Ansi.RESET);
                CliOutput.stderr().flush();
            }
        }
        command.addAll(appArgs);
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    /**
     * The engine-computed execution plan (docs/thin-client-plan.md): artifact preference, RUN
     * classpath, and main-class scan all happen engine-side — the client only forks the argv.
     * Memoized so the console's exec-tail closure and the real exec agree.
     */
    private dev.jkbuild.engine.protocol.ExecPlan execPlan(Path projectDir) throws IOException {
        if (cachedPlan == null) {
            cachedPlan = engineDisabledForTests()
                    ? dev.jkbuild.cli.engine.InProcessEngine.require()
                            .execPlan(projectDir, cacheDir(), "run", null, null)
                    : dev.jkbuild.cli.engine.EngineClient.execPlan(
                            dev.jkbuild.engine.EnginePaths.current(), projectDir, cacheDir(), "run", null, null);
        }
        // Checked on every access: the memoized plan may be an error plan (the console's
        // tail closure swallows the first throw; the exec path must still see it).
        if (cachedPlan.error() != null) {
            throw new IOException(cachedPlan.error());
        }
        return cachedPlan;
    }

    private dev.jkbuild.engine.protocol.ExecPlan cachedPlan;

    /**
     * The styled tail shown in the exec chip line or banner: {@code "[cyan]{jdk}[/]: [yellow]java
     * …[/]"} for JVM, {@code "native binary: [yellow]target/app[/]"} for native. The plan carries
     * the display command; the JDK leaf derives from the plan's javaHome.
     */
    private static String execTail(Path projectDir, dev.jkbuild.engine.protocol.ExecPlan plan) {
        Theme t = Theme.active();
        if (plan.argv().size() == 1) {
            // Native binary — exec'd directly, no JVM.
            Path bin = Path.of(plan.argv().get(0));
            return "native binary: " + Theme.colorize(PathDisplay.of(bin, projectDir), t.highlight());
        }
        Path jdkHome = Path.of(plan.javaHome());
        try {
            jdkHome = jdkHome.toRealPath();
        } catch (IOException ignored) {
        }
        String jdkLeaf = jdkHome.getFileName() != null ? jdkHome.getFileName().toString() : "java";
        return Theme.colorize(jdkLeaf, t.cyan()) + ": " + Theme.colorize(plan.display(), t.shell());
    }

    /**
     * Prints the {@code ▶ Executing …} line to stderr (verbose/JSON modes, where no chip is
     * rendered). Delegates styling to {@link #execTail}.
     */
    private static void printExecBanner(Path projectDir, dev.jkbuild.engine.protocol.ExecPlan plan) {
        Theme t = Theme.active();
        CliOutput.err(
                Theme.colorize(dev.jkbuild.cli.tui.Glyphs.PLAY, t.brightGreen()) + " " + execTail(projectDir, plan));
        CliOutput.err();
        // Reset any lingering SGR state so the program's own output starts from
        // the terminal's default colors (only when we're emitting color at all).
        if (Theme.colorEnabled()) {
            CliOutput.errRaw(Ansi.RESET);
            CliOutput.stderr().flush();
        }
    }

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
}
