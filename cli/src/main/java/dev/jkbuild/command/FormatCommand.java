// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.PathDisplay;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.CommandManager;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.runtime.FormatGoals;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@code jk format} — format the project's Java/Kotlin sources via the {@code jk-formatter} worker
 * (Spotless + optional OpenRewrite import optimisation).
 *
 * <p><b>Engine-hosted</b> (Wave 2 of the slim-client migration): source collection, formatter-jar
 * resolution (through jk's own resolver — previously in this process), and the worker fork all run
 * inside the resident engine ({@link dev.jkbuild.cli.engine.EngineClient#runFormat}); per-file
 * results stream back as structured events and this command renders them. The goal machinery lives
 * in {@link FormatGoals} so the test-only in-process path (see {@link #engineDisabledForTests})
 * builds the identical goal.
 *
 * <p>On an interactive TTY (apply mode, non-quiet) the command renders a live {@link
 * CommandManager} progress view as files are processed. On a pipe / under {@code --quiet} / in
 * {@code --check} mode it falls back to plain println output.
 *
 * <p>Defaults to Palantir (Java) + ktfmt KOTLINLANG (Kotlin), both 4-space / 120-col. {@code
 * --java-style}/{@code --kotlin-style}/{@code --style} and the {@code [format]} block override (see
 * {@link FormatStyles}). {@code --check} verifies without writing and exits non-zero if anything is
 * unformatted.
 */
public final class FormatCommand implements CliCommand {

    @Override
    public String name() {
        return "format";
    }

    @Override
    public String description() {
        return "Format Java/Kotlin source code";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Check formatting; fail if unformatted.", "--check"),
                Opt.value("<style>", "Java style: palantir | google | aosp.", "--java-style"),
                Opt.value("<style>", "Kotlin style: kotlinlang | google | meta.", "--kotlin-style"),
                Opt.value("<preset>", "Cross-language preset for both: standard.", "--style"),
                Opt.flag("Shorten FQCNs and add imports (default on).", "--optimize-imports"),
                Opt.flag("Skip FQCN-to-import optimization.", "--no-optimize-imports"),
                Opt.value("<file>", "OpenRewrite YAML config; overrides/extends recipes.", "--rewrite-config"));
    }

    /** A format run's summary — the same fields whichever transport ran the goal. */
    private record Outcome(GoalResult result, int changed, int clean, int errors, int total, int workerExit) {}

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()}'s javadoc for the full rationale. Same system property,
     * same "never a user-facing flag" contract; a real {@code jk format} invocation always
     * engine-hosts.
     */
    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"));
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        long startMs = System.currentTimeMillis();
        GlobalOptions global = GlobalOptions.from(in);
        boolean check = in.isSet("check");
        Path projectDir = global.workingDir();
        Path buildFile = projectDir.resolve("jk.toml");
        if (!Files.exists(buildFile)) {
            CliOutput.err("jk format: no jk.toml in " + PathDisplay.styledRaw(projectDir));
            return Exit.CONFIG;
        }
        JkBuild build = JkBuildParser.parse(buildFile);

        // --optimize-imports / --no-optimize-imports / env var / jk.toml / default true
        Boolean cliOptimize = in.isSet("optimize-imports")
                ? Boolean.TRUE
                : in.isSet("no-optimize-imports") ? Boolean.FALSE : envBool("JK_FORMAT_OPTIMIZE_IMPORTS");
        // --rewrite-config / env var
        Path rewriteConfig = in.value("rewrite-config")
                .or(() -> java.util.Optional.ofNullable(System.getenv("JK_FORMAT_REWRITE_CONFIG")))
                .map(Path::of)
                .orElse(null);

        FormatStyles.Resolved styles;
        try {
            styles = FormatStyles.resolve(
                    in.value("java-style").orElse(null),
                    in.value("kotlin-style").orElse(null),
                    in.value("style").orElse(null),
                    cliOptimize,
                    build.format());
        } catch (IllegalArgumentException e) {
            CliOutput.err("jk format: " + e.getMessage());
            return Exit.USAGE;
        }
        // Supplying --rewrite-config implicitly enables optimize-imports when neither
        // flag nor env var said otherwise (so the OpenRewrite pipeline actually runs).
        boolean optimizeImports = styles.optimizeImports()
                || (rewriteConfig != null && cliOptimize == null && envBool("JK_FORMAT_OPTIMIZE_IMPORTS") == null);

        Path cache = JkDirs.cache();
        boolean animate = !check && !global.outputIsJson() && !global.noProgress && GoalConsole.isInteractiveTerminal();

        if (!animate) {
            // Plain path: --check, piped output, CI, --no-progress.
            int[] counts = {0, 0, 0}; // changed, clean, errors
            FormatGoals.FileObserver observer = (path, status, msg, index, total) -> {
                if ("changed".equals(status)) {
                    counts[0]++;
                    if (!global.outputIsJson()) {
                        String mark = Theme.colorize(Glyphs.CHECK, Theme.active().success());
                        String rel = Theme.colorize(
                                PathDisplay.of(Path.of(path), projectDir), Theme.active().path());
                        CliOutput.out(mark + " " + (check ? "Would format: " : "Formatted: ") + rel);
                    }
                } else if ("error".equals(status)) {
                    counts[2]++;
                    CliOutput.err("  error  " + path + ": " + msg);
                } else {
                    counts[1]++;
                }
            };
            Outcome o;
            try {
                o = runFormatGoal(
                        projectDir, cache, check, styles, optimizeImports, rewriteConfig, global, observer,
                        chatterListener(global, line -> CliOutput.err("  [formatter] " + line)));
            } catch (IOException e) {
                CliOutput.err("jk format: " + e.getMessage());
                return Exit.SOFTWARE;
            }
            if (!o.result().success()) {
                for (GoalResult.Diagnostic d : o.result().errors()) {
                    CliOutput.err("jk format: " + d.message());
                }
                return 1;
            }
            if (o.total() == 0) {
                if (!global.outputIsJson()) CliOutput.out("jk format: no Java or Kotlin sources found.");
                return 0;
            }
            if (!global.outputIsJson()) {
                String mark = Theme.colorize(Glyphs.CHECK, Theme.active().success());
                String verb = Theme.colorize(
                        check ? "Checked" : "Formatted", Theme.active().focused());
                String body = check
                        ? counts[0] + " to format, " + counts[1] + " already clean"
                        : counts[0] + " file" + (counts[0] == 1 ? "" : "s") + ", " + counts[1] + " already clean";
                if (counts[2] > 0) body += ", " + counts[2] + " error" + (counts[2] == 1 ? "" : "s");
                String inTime = ConsoleSpec.took(Duration.ofMillis(System.currentTimeMillis() - startMs));
                CliOutput.out(mark + " " + verb + " " + body + " " + inTime);
            }
            return o.workerExit();
        }

        // Animated path — start the TUI *first*, so the spinner is already visible while the goal's
        // collect/resolve phases (I/O) run behind it.
        String subtitle = optimizeImports ? "Examining source files & optimizing imports" : "Examining source files";
        try (CommandManager cm = CommandManager.goal(CliOutput.stdout(), "Format", true)) {
            cm.addPhaseLabeled("", "fmt", subtitle);
            cm.phaseRunning("", "fmt");

            int[] counts = {0, 0, 0}; // changed, clean, errors
            FormatGoals.FileObserver observer = (path, status, msg, index, total) -> {
                // Advance bar on every file so the scan is visually smooth.
                cm.progress(index, total);
                if ("changed".equals(status)) {
                    counts[0]++;
                    cm.addCompletion(completionLine(path, projectDir));
                } else if ("error".equals(status)) {
                    counts[2]++;
                    cm.writeAbove(Theme.colorize("  error", Theme.active().error()) + "  " + path + ": " + msg);
                } else {
                    counts[1]++;
                }
            };
            Outcome o;
            try {
                o = runFormatGoal(
                        projectDir, cache, false, styles, optimizeImports, rewriteConfig, global, observer,
                        chatterListener(global, line -> cm.writeAbove("  [formatter] " + line)));
            } catch (IOException e) {
                cm.finishGoalFailure(String.valueOf(e.getMessage()));
                return Exit.SOFTWARE;
            }
            if (!o.result().success()) {
                for (GoalResult.Diagnostic d : o.result().errors()) {
                    cm.writeAbove(Theme.colorize("  error", Theme.active().error()) + "  " + d.message());
                }
                cm.finishGoalFailure("format failed");
                return 1;
            }
            if (o.total() == 0) {
                cm.phaseDone("", "fmt", true);
                cm.finishGoalSuccess("no sources found");
                return 0;
            }

            cm.phaseDone("", "fmt", counts[2] == 0);
            String took = ConsoleSpec.took(Duration.ofMillis(System.currentTimeMillis() - startMs));
            if (counts[2] > 0) {
                String errTail = counts[2] + " error" + (counts[2] == 1 ? "" : "s");
                cm.finishGoalFailure(errTail);
            } else if (counts[0] == 0) {
                // Nothing needed formatting.
                cm.finishGoalSuccess(
                        Theme.colorize("Already formatted", Theme.active().success()) + " " + took);
            } else {
                // N formatted, M already clean.
                String formatted = Theme.colorize("Formatted", Theme.active().success())
                        + " "
                        + counts[0]
                        + " file"
                        + (counts[0] == 1 ? "" : "s");
                String clean = counts[1] > 0 ? ", " + counts[1] + " already clean" : "";
                cm.finishGoalSuccess(formatted + clean + "  " + took);
            }
            return o.workerExit();
        }
    }

    /**
     * Run the shared {@link FormatGoals} goal — engine-hosted normally, in-process under {@link
     * #engineDisabledForTests()} — driving the same {@code observer} either way. {@code listener}
     * receives the standard goal events (only worker passthrough chatter is rendered from it).
     */
    private static Outcome runFormatGoal(
            Path projectDir,
            Path cache,
            boolean check,
            FormatStyles.Resolved styles,
            boolean optimizeImports,
            Path rewriteConfig,
            GlobalOptions global,
            FormatGoals.FileObserver observer,
            GoalListener listener)
            throws IOException {
        if (engineDisabledForTests()) {
            Goal goal = FormatGoals.formatGoal(
                    projectDir, cache, check, styles.java(), styles.kotlin(), optimizeImports, rewriteConfig,
                    observer);
            goal.addListener(listener);
            GoalResult result = goal.run();
            return new Outcome(
                    result,
                    goal.get(FormatGoals.CHANGED).orElse(-1),
                    goal.get(FormatGoals.CLEAN).orElse(-1),
                    goal.get(FormatGoals.ERRORS).orElse(-1),
                    goal.get(FormatGoals.TOTAL).orElse(-1),
                    goal.get(FormatGoals.WORKER_EXIT).orElse(-1));
        }
        var session = dev.jkbuild.config.SessionContext.current();
        var outcome = dev.jkbuild.cli.engine.EngineClient.runFormat(
                dev.jkbuild.engine.EnginePaths.current(),
                new dev.jkbuild.cli.engine.EngineClient.FormatRequest(
                        projectDir,
                        cache,
                        check,
                        styles.java(),
                        styles.kotlin(),
                        optimizeImports,
                        rewriteConfig,
                        session.offline(),
                        global.verbose),
                phases -> listener,
                observer);
        return new Outcome(
                outcome.result(),
                outcome.changed(),
                outcome.clean(),
                outcome.errors(),
                outcome.total(),
                outcome.workerExit());
    }

    /** A goal listener that surfaces the worker's passthrough chatter under {@code --verbose}. */
    private static GoalListener chatterListener(GlobalOptions global, Consumer<String> sink) {
        return new GoalListener() {
            @Override
            public void output(String phase, String line) {
                if (global.verbose) sink.accept(line);
            }
        };
    }

    /** Format a single completion line: {@code ✓ path/to/File.java}. */
    private static String completionLine(String absPath, Path projectDir) {
        Theme t = Theme.active();
        return Theme.colorize(Glyphs.CHECK, t.success())
                + " "
                + Theme.colorize(PathDisplay.of(Path.of(absPath), projectDir), t.path());
    }

    /** Read an env var as a Boolean; returns null when absent or empty. */
    private static Boolean envBool(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return null;
        return Boolean.parseBoolean(v.trim());
    }
}
