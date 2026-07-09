// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.GoalConsole;
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
 * <p>The target may be (docs/tool-targets-plan.md §2, phase-1 kinds):
 *
 * <ul>
 *   <li>a library-catalog short name ({@code ktlint}, {@code ktlint@1.3.0}) — resolved via the
 *       layered catalog, default version {@code latest} (stable);
 *   <li>a Maven coordinate spec ({@code g:a:v} pinned, {@code g:a@selector} floating, {@code g:a}
 *       = latest) — resolved, cached under {@code $JK_CACHE_DIR}, and exec'd via its {@code
 *       Main-Class}; or
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
    public List<String> aliases() {
        // `jk tool exec` — dotnet-tool muscle memory. Hidden per the
        // hidden-surface policy; documented in docs/aliases.md.
        return List.of("exec");
    }

    @Override
    public String description() {
        return "Run a tool from a Maven coord or .java/.kt/.kts/.jar file";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<class>", "Override the Main-Class to exec (coordinate targets only).", "--main"),
                Opt.value("<coord>", "Add an extra dependency to the tool's classpath (repeatable).", "--with"),
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
                Param.of(
                        "target",
                        Arity.ONE,
                        "Catalog name, Maven coordinate (g:a[:version|@selector]), or .java/.kt/.kts/.jar file."),
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
     * A directory target (docs/tool-targets-plan.md §4.4): a jk project builds (tests skipped) and
     * execs like {@code jk run} without the {@code cd}; a JBang-convention folder runs its {@code
     * main.java}; a folder holding exactly one script runs that (gist-checkout shape).
     */
    private int runDirectory(Path dir, List<String> args) throws IOException, InterruptedException {
        if (Files.isRegularFile(dir.resolve("jk.toml"))) {
            RunCommand delegate = new RunCommand();
            delegate.cacheDirOverride = cacheDirOverride;
            delegate.buildOpts = new dev.jkbuild.cli.BuildOptions();
            delegate.buildOpts.skipTests = true;
            delegate.global = global;
            return delegate.runProject(dir, args);
        }
        if (Files.isRegularFile(dir.resolve("jbang-catalog.json"))) {
            CliOutput.err("jk tool run: " + dir + " is a JBang catalog — `alias@…` references aren't"
                    + " supported yet (docs/tool-targets-plan.md §6).");
            return Exit.USAGE;
        }
        ScriptRunner runner = new ScriptRunner(global, cacheDirOverride, stateDirOverride, repoUrl, forceRecompile);
        Path mainJava = dir.resolve("main.java");
        if (Files.isRegularFile(mainJava)) return runner.run(mainJava, args);
        List<Path> scripts;
        try (var listing = Files.list(dir)) {
            scripts = listing.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return n.endsWith(".java") || n.endsWith(".kt") || n.endsWith(".kts");
                    })
                    .sorted()
                    .toList();
        }
        if (scripts.size() == 1) return runner.run(scripts.get(0), args);
        CliOutput.err("jk tool run: nothing runnable in " + dir
                + " — looked for jk.toml, main.java, or exactly one .java/.kt/.kts (found "
                + scripts.size() + ").");
        return Exit.USAGE;
    }

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
        // A local file target (by extension) is compiled/run by ScriptRunner; the
        // extension is the signal even when the file is missing, so the user gets
        // a proper "not found" error from the matching mode handler. Routing goes
        // through the classifier so a remote `https://…/tool.jar` is NOT a file.
        dev.jkbuild.tool.ToolTarget classified = dev.jkbuild.tool.ToolTarget.classify(target);
        if (classified instanceof dev.jkbuild.tool.ToolTarget.RunnableFile file) {
            return new ScriptRunner(global, cacheDirOverride, stateDirOverride, repoUrl, forceRecompile)
                    .run(file.path(), toolArgs);
        }
        if (classified instanceof dev.jkbuild.tool.ToolTarget.Directory dir) {
            return runDirectory(global.workingDir().resolve(dir.path()).normalize(), toolArgs);
        }

        ToolTargets.Resolved resolved;
        List<String> with;
        try {
            resolved = ToolTargets.resolve(target);
            with = ToolTargets.resolveWith(in.values("with"));
        } catch (ToolTargets.TargetException e) {
            CliOutput.err(e.getMessage());
            return Exit.USAGE;
        }
        String bin = resolved.defaultBin();
        Path cacheDir = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
        Files.createDirectories(cacheDir);

        ToolEnv env;
        if (engineDisabledForTests()) {
            var o = dev.jkbuild.cli.engine.InProcessEngine.require()
                    .toolResolveGoal(
                            dev.jkbuild.model.ToolCoordSpec.parse(resolved.coordSpec()),
                            with.stream().map(dev.jkbuild.model.ToolCoordSpec::parse).toList(),
                            bin, mainClass, repoUrl, cacheDir,
                            resolved.coordSpec(), GoalConsole.modeFor(global));
            if (o.env() == null) return 1;
            env = o.env();
        } else {
            dev.jkbuild.cli.engine.EngineClient.ToolResolveOutcome outcome;
            try {
                outcome = dev.jkbuild.cli.engine.EngineClient.runToolResolve(
                        dev.jkbuild.engine.EnginePaths.current(),
                        new dev.jkbuild.cli.engine.EngineClient.ToolResolveRequest(
                                resolved.coordSpec(), with, bin, mainClass, repoUrl, cacheDir),
                        phases -> GoalConsole.chooseConsoleListener(
                                "tool-run", phases, GoalConsole.modeFor(global)));
            } catch (IOException e) {
                CliOutput.err("jk tool run: " + e.getMessage());
                return Exit.SOFTWARE;
            }
            if (!outcome.result().success() || outcome.mainClass() == null || outcome.coord() == null) return 1;
            env = new ToolEnv(bin, Coordinate.parse(outcome.coord()), outcome.mainClass(), outcome.classpath());
        }

        // The exec deliberately stays client-side: the tool inherits this terminal's stdio.
        Path javaHome = JavaHomes.runningJavaHome();
        return ToolLauncher.execEphemeral(javaHome, env, toolArgs);
    }
}
