// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.PathDisplay;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.runtime.CompatGoals;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * {@code jk import <file>} — convert a Maven or Gradle build to {@code jk.toml} via the {@code
 * jk-compat-runner} worker subprocess (PRD §24.2 / §24.3).
 *
 * <p><b>Engine-hosted</b> (Wave 2 of the slim-client migration): the worker forks inside the
 * resident engine ({@link dev.jkbuild.cli.engine.EngineClient#runImport}); this command pre-flights
 * source detection and overwrite checks, then renders the streamed progress notes. The goal lives
 * in {@link CompatGoals} so the test-only in-process path (see {@link #engineDisabledForTests})
 * runs the identical code.
 */
public final class ImportCommand implements CliCommand {

    private static final List<String> AUTO_DETECT_ORDER = List.of("build.gradle.kts", "build.gradle", "pom.xml");

    @Override
    public String name() {
        return "import";
    }

    @Override
    public String description() {
        return "Convert a Maven or Gradle build to jk.toml";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<file>", "Path to write jk.toml.", "--out"),
                Opt.value("<file>", "Path to write the import report.", "--report"),
                Opt.flag("Overwrite existing jk.toml.", "--force"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("file", Arity.ZERO_OR_ONE, "The build file to import (auto-detected if omitted)."));
    }

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()}'s javadoc for the full rationale. Same system property,
     * same "never a user-facing flag" contract; a real {@code jk import} invocation always engine-hosts.
     */
    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"));
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        Path source =
                in.positionals().isEmpty() ? null : Path.of(in.positionals().get(0));
        Path out = in.value("out").map(Path::of).orElse(null);
        Path reportPath = in.value("report").map(Path::of).orElse(null);
        boolean force = in.isSet("force");
        GlobalOptions global = GlobalOptions.from(in);
        Path baseDir = global.workingDir();

        if (source == null) {
            source = autoDetectSource(baseDir);
            if (source == null) {
                CliOutput.err("jk import: no build file found in "
                        + baseDir
                        + " (looked for build.gradle.kts, build.gradle, pom.xml).");
                return Exit.NO_INPUT;
            }
            CliOutput.out("Importing " + PathDisplay.styled(source, baseDir));
        } else {
            source = source.isAbsolute() ? source : baseDir.resolve(source);
            if (!Files.exists(source)) {
                CliOutput.err("jk import: source not found: " + PathDisplay.styled(source, baseDir));
                return Exit.NO_INPUT;
            }
        }

        String filename = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!filename.endsWith("pom.xml") && !filename.equals("build.gradle") && !filename.equals("build.gradle.kts")) {
            CliOutput.err("jk import: expected pom.xml, build.gradle, or build.gradle.kts");
            return Exit.USAGE;
        }

        Path projectDir = source.toAbsolutePath().getParent();
        Path target = out != null ? out : projectDir.resolve("jk.toml");
        if (Files.exists(target) && !force) {
            CliOutput.err(
                    "jk import: refusing to overwrite " + PathDisplay.styled(target, baseDir) + " (use --force).");
            return Exit.CANT_CREATE;
        }

        Path cache = JkDirs.cache();

        // Renders the worker's progress notes as they stream — identical for both transports.
        CompatGoals.NoteObserver observer = (kind, text) -> {
            if ("wrote".equals(kind)) {
                CliOutput.out("Wrote " + text);
            } else {
                CliOutput.out(text);
            }
        };

        int exit;
        int warnings;
        String error;
        String diag;
        if (engineDisabledForTests()) {
            // CompatGoals.importGoal locates the worker jar eagerly; a missing worker throws
            // WorkerJarNotFoundException here, which CommandDispatch renders with side-load hints.
            Goal goal = CompatGoals.importGoal(
                    source.toAbsolutePath(), target.toAbsolutePath(), projectDir, JkDirs.tmp(), force, reportPath,
                    cache, observer);
            GoalResult result = goal.run();
            if (!result.success()) {
                for (GoalResult.Diagnostic d : result.errors()) {
                    CliOutput.err("jk import: " + d.message());
                }
                return 1;
            }
            exit = goal.get(CompatGoals.EXIT).orElse(1);
            warnings = goal.get(CompatGoals.WARNINGS).orElse(0);
            error = goal.get(CompatGoals.ERROR).orElse(null);
            diag = goal.get(CompatGoals.DIAG).orElse(null);
        } else {
            dev.jkbuild.cli.engine.EngineClient.ImportOutcome outcome;
            try {
                outcome = dev.jkbuild.cli.engine.EngineClient.runImport(
                        dev.jkbuild.engine.EnginePaths.current(),
                        new dev.jkbuild.cli.engine.EngineClient.ImportRequest(
                                source.toAbsolutePath(),
                                target.toAbsolutePath(),
                                projectDir,
                                JkDirs.tmp(),
                                force,
                                reportPath,
                                cache),
                        phases -> new dev.jkbuild.run.GoalListener() {},
                        observer);
            } catch (IOException e) {
                CliOutput.err("jk import: " + e.getMessage());
                return Exit.SOFTWARE;
            }
            if (!outcome.result().success()) {
                for (GoalResult.Diagnostic d : outcome.result().errors()) {
                    CliOutput.err("jk import: " + d.message());
                }
                return 1;
            }
            exit = outcome.exitCode();
            warnings = outcome.warnings();
            error = outcome.error();
            diag = outcome.diag();
        }

        if (error != null) CliOutput.err("jk import: " + error);
        if (warnings != 0) CliOutput.out("Import notes: " + warnings + " issue(s)");
        if (exit != 0 && diag != null && !diag.isBlank()) {
            CliOutput.err("jk import: " + diag);
        }
        return exit;
    }

    /**
     * Pick {@code <tmpDir>/<coord>-<n>-<sourceFile>-import.md}, incrementing {@code n} past any
     * existing file.
     */
    static Path defaultReportPath(Path tmpDir, String coord, String sourceFileName) {
        for (int n = 1; ; n++) {
            Path candidate = tmpDir.resolve(coord + "-" + n + "-" + sourceFileName + "-import.md");
            if (!Files.exists(candidate)) return candidate;
        }
    }

    private static Path autoDetectSource(Path dir) {
        for (String name : AUTO_DETECT_ORDER) {
            Path c = dir.resolve(name);
            if (Files.isRegularFile(c)) return c;
        }
        return null;
    }
}
