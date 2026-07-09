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
                "target",
                Arity.ZERO_OR_ONE,
                "Catalog name, Maven coordinate spec (g:a[:version|@selector]),\n"
                        + "script/jar file, project directory, or git URL. Omit to\n"
                        + "install the current jk.toml project."));
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
        this.coord = in.positionals().isEmpty() ? "." : in.positionals().get(0);
        this.binName = in.value("bin").orElse(null);
        this.mainClass = in.value("main").orElse(null);
        this.cacheDirOverride = in.value("cache-dir").map(Path::of).orElse(null);
        this.stateDirOverride = in.value("state-dir").map(Path::of).orElse(null);
        this.binDirOverride = in.value("bin-dir").map(Path::of).orElse(null);
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.global = GlobalOptions.from(in);

        // A local script/jar installs as a snapshot env (plan §4.3) — the launcher must not
        // depend on the source file continuing to exist. Project dirs and git URLs delegate to
        // the app-install pipeline (plan §9 convergence: one pipeline, two spellings).
        dev.jkbuild.tool.ToolTarget classified = dev.jkbuild.tool.ToolTarget.classify(coord);
        if (classified instanceof dev.jkbuild.tool.ToolTarget.RunnableFile file) {
            return installFile(file.path());
        }
        if (classified instanceof dev.jkbuild.tool.ToolTarget.Directory dir) {
            if (!Files.isRegularFile(dir.path().resolve("jk.toml"))) {
                CliOutput.err("jk tool install: no jk.toml in " + dir.path()
                        + " — a directory target must be a jk project.");
                return Exit.USAGE;
            }
            return appInstallDelegate().runProjectInstallGoal(dir.path().toAbsolutePath().normalize(), "install");
        }
        if (classified instanceof dev.jkbuild.tool.ToolTarget.Git git) {
            return appInstallDelegate().installFromGit(git.raw());
        }

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

    /**
     * Install a local {@code .java}/{@code .kt}/{@code .jar} as a tool (plan §4.3): the engine's
     * script-prepare goal compiles/inspects it, then the compiled classes (or the jar itself) are
     * snapshotted into the env dir — immutable, independent of the source file — and the standard
     * launcher is written over that snapshot + the resolved dep classpath.
     */
    private int installFile(Path file) throws IOException, InterruptedException {
        String name = file.getFileName().toString();
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".kts")) {
            CliOutput.err("jk tool install: .kts scripts can't be installed yet — run one with `jk tool run "
                    + file + "` (docs/tool-targets-plan.md §4.3).");
            return Exit.USAGE;
        }
        if (!Files.isRegularFile(file)) {
            CliOutput.err("jk tool install: file not found: " + file);
            return Exit.NO_INPUT;
        }
        String mode = lower.endsWith(".jar") ? "jar" : lower.endsWith(".kt") ? "kt" : "java";
        String bin = binName != null && !binName.isBlank()
                ? binName
                : name.substring(0, name.lastIndexOf('.')).toLowerCase(java.util.Locale.ROOT);

        Path cacheDir = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
        Path stateDir = stateDirOverride != null ? stateDirOverride : JkDirs.state();
        Path binDir = binDirOverride != null ? binDirOverride : JkDirs.binDir();
        Path envsRoot = stateDir.resolve("tools").resolve("envs");
        Files.createDirectories(cacheDir);
        GoalConsole.Mode consoleMode = GoalConsole.modeFor(global);

        dev.jkbuild.cli.engine.EngineClient.ScriptPrepareOutcome prep;
        if (engineDisabledForTests()) {
            prep = dev.jkbuild.cli.engine.InProcessEngine.require()
                    .scriptPrepare(mode, file.toAbsolutePath(), cacheDir, stateDir, repoUrl, false, consoleMode);
        } else {
            try {
                prep = dev.jkbuild.cli.engine.EngineClient.runScriptPrepare(
                        dev.jkbuild.engine.EnginePaths.current(),
                        new dev.jkbuild.cli.engine.EngineClient.ScriptPrepareRequest(
                                mode, file.toAbsolutePath(), cacheDir, stateDir, repoUrl, false),
                        phases -> GoalConsole.chooseConsoleListener("tool-install", phases, consoleMode));
            } catch (IOException e) {
                CliOutput.err("jk tool install: " + e.getMessage());
                return Exit.SOFTWARE;
            }
        }
        if (!prep.result().success() || prep.mainClass() == null) return 1;

        // Snapshot into the env dir so the launcher survives the source moving/vanishing.
        Path envDir = envsRoot.resolve(bin);
        List<Path> classpath = new ArrayList<>();
        if ("jar".equals(mode)) {
            Files.createDirectories(envDir);
            Path jarCopy = envDir.resolve(name);
            Files.copy(file, jarCopy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            classpath.add(jarCopy);
            // prep.classpath() leads with the source jar; keep only the resolved deps.
            prep.classpath().stream().skip(1).forEach(classpath::add);
        } else {
            Path classesCopy = envDir.resolve("classes");
            copyTree(prep.classesDir(), classesCopy);
            classpath.add(classesCopy);
            classpath.addAll(prep.classpath());
            if (prep.stdlib() != null) classpath.add(prep.stdlib());
        }

        ToolEnv env = new ToolEnv(bin, Coordinate.of("script", bin, "local"), prep.mainClass(), classpath);
        Path launcher = ToolLauncher.install(envsRoot, binDir, JavaHomes.runningJavaHome(), env);
        if (!global.outputIsJson()) {
            CliOutput.out("Installed " + file.getFileName() + " → " + launcher);
            CliOutput.out("Add to PATH if needed:");
            CliOutput.out("  export PATH=\"" + binDir + ":$PATH\"");
        }
        return 0;
    }

    /** The app-install pipeline, shared with `jk install` (plan §9: converged, two spellings). */
    private InstallCommand appInstallDelegate() {
        InstallCommand delegate = new InstallCommand();
        delegate.binName = binName;
        delegate.mainClass = mainClass;
        delegate.cacheDirOverride = cacheDirOverride;
        delegate.stateDirOverride = stateDirOverride;
        delegate.binDirOverride = binDirOverride;
        delegate.repoUrl = repoUrl;
        delegate.buildOpts = new dev.jkbuild.cli.BuildOptions();
        delegate.buildOpts.skipTests = false;
        delegate.global = global;
        return delegate;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path src : walk.toList()) {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
