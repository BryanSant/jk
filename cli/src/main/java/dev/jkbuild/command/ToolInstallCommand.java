// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.jdk.JavaHomes;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;
import dev.jkbuild.tool.ToolEnv;
import dev.jkbuild.tool.ToolLauncher;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk tool install <coord>} — install a Maven-published tool as a launcher under {@code
 * $JK_BIN_DIR} (PRD §20.1). Was {@code jk install} pre-v1.0; {@code install} remains a hidden
 * alias.
 *
 * <p><b>Engine-hosted</b> (Wave 4 of the slim-client migration): the Maven resolve + fetch runs
 * inside the resident engine ({@link dev.jkbuild.cli.engine.EngineClient#runToolResolve}); the
 * launcher write into {@code $JK_BIN_DIR} stays client-side, after the hosted goal succeeds. The
 * test-only in-process path builds the identical goal via the engine's {@code ToolGoals}.
 */
public final class ToolInstallCommand implements CliCommand {

    @Override
    public String name() {
        return "install";
    }

    @Override
    public String description() {
        return "Install a tool from a Maven coordinate";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<name>", "Launcher name under $JK_BIN_DIR. Default: the artifact id.", "--bin"),
                Opt.value("<class>", "Override the Main-Class (default: read from the jar manifest).", "--main"),
                Opt.value("<coord>", "Add an extra dependency to the tool's classpath (repeatable).", "--with"),
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                        .hide(),
                Opt.value("<dir>", "Override the tool state directory.", "--state-dir")
                        .hide(),
                Opt.value("<dir>", "Override the bin directory.", "--bin-dir").hide(),
                Opt.value("<url>", "Override the Maven repository URL (for tests).", "--repo-url")
                        .hide());
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "target", Arity.ONE, "Catalog name or Maven coordinate spec (g:a[:version|@selector])."));
    }

    String coord;
    String binName;
    String mainClass;
    Path cacheDirOverride;
    Path stateDirOverride;
    Path binDirOverride;
    URI repoUrl;
    GlobalOptions global;

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()} for the full rationale. A real {@code jk tool install}
     * hosts its resolve+fetch on the engine; the launcher write always runs here.
     */
    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"));
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.coord = in.positionals().get(0);
        this.binName = in.value("bin").orElse(null);
        this.mainClass = in.value("main").orElse(null);
        this.cacheDirOverride = in.value("cache-dir").map(Path::of).orElse(null);
        this.stateDirOverride = in.value("state-dir").map(Path::of).orElse(null);
        this.binDirOverride = in.value("bin-dir").map(Path::of).orElse(null);
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.global = GlobalOptions.from(in);

        ToolTargets.Resolved resolved;
        List<String> with;
        try {
            resolved = ToolTargets.resolve(coord);
            with = ToolTargets.resolveWith(in.values("with"));
        } catch (ToolTargets.TargetException e) {
            CliOutput.err(e.getMessage());
            return Exit.USAGE;
        }
        String bin = binName != null && !binName.isBlank() ? binName : resolved.defaultBin();

        Path cacheDir = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
        Path stateDir = stateDirOverride != null ? stateDirOverride : JkDirs.state();
        Path binDir = binDirOverride != null ? binDirOverride : JkDirs.binDir();
        Path envsRoot = stateDir.resolve("tools").resolve("envs");
        Files.createDirectories(cacheDir);
        GoalConsole.Mode mode = GoalConsole.modeFor(global);

        ToolEnv env;
        if (engineDisabledForTests()) {
            var o = dev.jkbuild.cli.engine.InProcessEngine.require()
                    .toolResolveGoal(
                            dev.jkbuild.model.ToolCoordSpec.parse(resolved.coordSpec()),
                            with.stream().map(dev.jkbuild.model.ToolCoordSpec::parse).toList(),
                            bin, mainClass, repoUrl, cacheDir, resolved.coordSpec(), mode);
            if (o.env() == null) return 1;
            env = o.env();
        } else {
            dev.jkbuild.cli.engine.EngineClient.ToolResolveOutcome outcome;
            try {
                outcome = dev.jkbuild.cli.engine.EngineClient.runToolResolve(
                        dev.jkbuild.engine.EnginePaths.current(),
                        new dev.jkbuild.cli.engine.EngineClient.ToolResolveRequest(
                                resolved.coordSpec(), with, bin, mainClass, repoUrl, cacheDir),
                        phases -> GoalConsole.chooseConsoleListener("tool-install", phases, mode));
            } catch (IOException e) {
                CliOutput.err("jk tool install: " + e.getMessage());
                return Exit.SOFTWARE;
            }
            if (!outcome.result().success() || outcome.mainClass() == null || outcome.coord() == null) return 1;
            env = new ToolEnv(bin, Coordinate.parse(outcome.coord()), outcome.mainClass(), outcome.classpath());
        }

        // The "make install" half stays client-side: the launcher into the user-owned bin dir.
        Path javaHome = JavaHomes.runningJavaHome();
        Path launcher = ToolLauncher.install(envsRoot, binDir, javaHome, env);

        if (!global.outputIsJson()) {
            CliOutput.out("Installed " + Coords.gav(env.primary()) + " → " + launcher);
            CliOutput.out("Add to PATH if needed:");
            CliOutput.out("  export PATH=\"" + binDir + ":$PATH\"");
        }
        return 0;
    }
}
