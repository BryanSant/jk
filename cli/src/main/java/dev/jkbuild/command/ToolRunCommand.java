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
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk tool run <coord|file> -- <args>} — ephemerally run a tool or a standalone file (PRD
 * §20.3), forwarding {@code <args>} to the program.
 *
 * <p>The target may be either:
 *
 * <ul>
 *   <li>a Maven coordinate ({@code group:artifact:version}) — resolved, cached under {@code
 *       $JK_CACHE_DIR}, and exec'd via its {@code Main-Class}; or
 *   <li>a {@code .java}/{@code .kt}/{@code .kts}/{@code .jar} file — compiled (if needed) and run
 *       via {@link ScriptRunner}.
 * </ul>
 *
 * <p><b>Engine-hosted</b> (Wave 4 of the slim-client migration): a coordinate target's Maven
 * resolve + fetch runs inside the resident engine; the <em>exec</em> of the tool stays client-side
 * with inherited stdio — the tool's interactive run belongs to the user's terminal, exactly the
 * {@code jk mvn}/{@code jk run} reasoning. The test-only in-process path builds the identical goal
 * via {@link ToolGoals}.
 *
 * <p>{@code jkx} — the uvx-style alias for this verb — is a real binary: a hardlink to {@code jk}
 * dispatched on argv[0] in {@link dev.jkbuild.cli.Jk#main} (created by {@code install.sh},
 * self-healed by {@code jk activate}; see {@code JkxLink}).
 */
public final class ToolRunCommand implements CliCommand {

    @Override
    public String name() {
        return "run";
    }

    @Override
    public String description() {
        return "Run a tool from a Maven coord or .java/.kt/.kts/.jar file";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<class>", "Override the Main-Class to exec (coordinate targets only).", "--main"),
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                        .hide(),
                Opt.value("<dir>", "Override the jk state directory.", "--state-dir")
                        .hide(),
                Opt.value("<url>", "Override the Maven repository URL (for tests).", "--repo-url")
                        .hide(),
                Opt.flag("Ignore cached classes and recompile (file targets only).", "--force-recompile")
                        .hide()); // --force-recompile hidden; global --force covers this
    }

    @Override
    public List<Param> parameters() {
        // The tool args after the target are captured as trailing positionals via ZERO_OR_MORE
        return List.of(
                Param.of("coord|file", Arity.ONE, "Maven coordinate or .java/.kt/.kts/.jar file."),
                Param.of("args", Arity.ZERO_OR_MORE, "Arguments forwarded to the program."));
    }

    String target;
    String mainClass;
    Path cacheDirOverride;
    Path stateDirOverride;
    URI repoUrl;
    boolean forceRecompile;
    List<String> toolArgs = new ArrayList<>();
    GlobalOptions global;

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()} for the full rationale. A real {@code jk tool run} of
     * a coordinate hosts its resolve+fetch on the engine; the exec always runs here (it inherits
     * this terminal's stdio).
     */
    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"));
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        List<String> positionals = in.positionals();
        this.target = positionals.isEmpty() ? "" : positionals.get(0);
        this.toolArgs = positionals.size() > 1 ? positionals.subList(1, positionals.size()) : List.of();
        this.mainClass = in.value("main").orElse(null);
        this.cacheDirOverride = in.value("cache-dir").map(Path::of).orElse(null);
        this.stateDirOverride = in.value("state-dir").map(Path::of).orElse(null);
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        // --force (global) and legacy --force-recompile both force recompilation.
        this.forceRecompile = in.isSet("force") || in.isSet("force-recompile");
        this.global = GlobalOptions.from(in);
        // A file target (by extension) is compiled/run by ScriptRunner; the
        // extension is the signal even when the file is missing, so the user
        // gets a proper "not found" error from the matching mode handler.
        if (ScriptRunner.isRunnableFile(target)) {
            return new ScriptRunner(global, cacheDirOverride, stateDirOverride, repoUrl, forceRecompile)
                    .run(Path.of(target), toolArgs);
        }

        Coordinate primary = Coordinate.parse(target);
        Path cacheDir = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
        Files.createDirectories(cacheDir);

        ToolEnv env;
        if (engineDisabledForTests()) {
            var o = dev.jkbuild.cli.engine.InProcessEngine.require()
                    .toolResolveGoal(primary, primary.artifact(), mainClass, repoUrl, cacheDir,
                            Coords.gav(primary), GoalConsole.modeFor(global));
            if (o.env() == null) return 1;
            env = o.env();
        } else {
            dev.jkbuild.cli.engine.EngineClient.ToolResolveOutcome outcome;
            try {
                outcome = dev.jkbuild.cli.engine.EngineClient.runToolResolve(
                        dev.jkbuild.engine.EnginePaths.current(),
                        new dev.jkbuild.cli.engine.EngineClient.ToolResolveRequest(
                                target, primary.artifact(), mainClass, repoUrl, cacheDir),
                        phases -> GoalConsole.chooseConsoleListener(
                                "tool-run", phases, GoalConsole.modeFor(global)));
            } catch (IOException e) {
                CliOutput.err("jk tool run: " + e.getMessage());
                return Exit.SOFTWARE;
            }
            if (!outcome.result().success() || outcome.mainClass() == null) return 1;
            env = new ToolEnv(primary.artifact(), primary, outcome.mainClass(), outcome.classpath());
        }

        // The exec deliberately stays client-side: the tool inherits this terminal's stdio.
        Path javaHome = JavaHomes.runningJavaHome();
        return ToolLauncher.execEphemeral(javaHome, env, toolArgs);
    }
}
