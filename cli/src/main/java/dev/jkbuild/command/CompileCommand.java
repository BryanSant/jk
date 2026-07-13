// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.ProjectContext;
import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk compile} — lock, sync, then compile this project's sources to {@code target/classes}
 * (no resources, tests, or packaging). It runs the shared engine pipeline in compile-only
 * mode, so it auto-locks and syncs on first run, re-locks when {@code jk.toml} changed, and reuses
 * the same incremental compile cache as {@code jk build}/{@code jk test}.
 *
 * <p>Was {@code jk check} pre-v1.0; {@code check} remains a hidden alias (see {@code
 * docs/aliases.md}).
 */
public final class CompileCommand implements CliCommand {

    @Override
    public String name() {
        return "compile";
    }

    @Override
    public String description() {
        return "Compile this project's source code";
    }

    @Override
    public List<Opt> options() {
        var opts = new java.util.ArrayList<Opt>(List.of(
                Opt.value("<name>", "Apply a build profile. Default: auto (ci on CI).", "--profile"),
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                        .hide()));
        opts.addAll(VariantSelection.options());
        return opts;
    }

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()} for the full rationale; every real {@code jk compile}
     * invocation goes through the engine (slim-client Wave 3).
     */
    private static boolean engineDisabledForTests() {
        // Also bypass inside a jk-forked test worker (jk.plugin.class=JkRunner): under the
        // self-hosted build, in-process dispatches would otherwise recurse into the very
        // engine hosting the test run and deadlock — see BuildCommand's javadoc.
        return Boolean.getBoolean("jk.test.noEngine")
                || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"));
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        String profileName = in.value("profile").orElse(null);
        Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        GlobalOptions global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        VariantSelection.install(in, dir);
        var proj = ProjectContext.require(dir, "compile").orElse(null);
        if (proj == null) return Exit.CONFIG;
        Path buildFile = proj.buildFile();
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();

        ConsoleSpec spec = new ConsoleSpec(
                "Compile", r -> Theme.colorize("Compiled", Theme.active().focused()), r -> "Compilation failed");
        String target = BuildCommand.buildTarget(buildFile, dir);
        GoalConsole.Mode mode = GoalConsole.modeFor(global);
        GoalResult result;
        if (engineDisabledForTests()) {
            result = dev.jkbuild.cli.engine.InProcessEngine.require()
                    .compileGoal(dir, cache, profileName, global.verbose, mode, spec, target);
        } else {
            // Engine-hosted (slim-client Wave 3): the engine assembles the exact same goal
            // (CompileGoals) and streams the single-goal event vocabulary back; the listener is
            // chosen once the phase list arrives over the socket, as with jk test.
            var session = dev.jkbuild.config.SessionContext.current();
            try {
                result = dev.jkbuild.cli.engine.EngineClient.runCompile(
                        dev.jkbuild.engine.EnginePaths.current(),
                        new dev.jkbuild.cli.engine.EngineClient.CompileRequest(
                                dir, cache, profileName, session.offline(), session.force(), global.verbose),
                        phases -> GoalConsole.chooseConsoleListener(phases, mode, spec, target));
            } catch (IOException e) {
                CliOutput.err("jk compile: " + e.getMessage());
                return Exit.SOFTWARE;
            }
        }
        return result.success() ? 0 : 1;
    }
}
