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
 * {@code jk tool install [<target>]} — the converged install verb (tool-targets-plan §9; {@code jk
 * install} is its hidden verb alias). Targets: a catalog name or Maven coordinate spec (launcher
 * under {@code $JK_BIN_DIR}, PRD §20.1); a script/jar file (snapshot env, §4.3 — or a local-cache
 * store when {@code --group/--name/--ver} carry m2 intent); a jk-project directory (defaulting to
 * {@code .}) or a git URL, both delegated to {@link InstallCommand}'s app pipeline.
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
        return "Install a tool, script, project, or git repo";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<name>", "Launcher name under $JK_BIN_DIR. Default: the artifact id.", "--bin"),
                Opt.value("<class>", "Override the Main-Class (default: read from the jar manifest).", "--main"),
                Opt.value("<coord>", "Add an extra dependency to the tool's classpath (repeatable).", "--with"),
                Opt.value("<group>", "Maven groupId — switches a file target to a local-cache install.", "--group"),
                Opt.value("<name>", "Maven artifactId for a local-cache file install.", "--name"),
                Opt.value("<ver>", "Version for a local-cache file install.", "--ver"),
                Opt.flag("Skip compiling and running tests (project targets).", "--skip-tests"),
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                        .hide(),
                Opt.value("<dir>", "Override the tool state directory.", "--state-dir")
                        .hide(),
                Opt.value("<dir>", "Override the bin directory.", "--bin-dir").hide(),
                Opt.value("<dir>", "Override the lib directory.", "--lib-dir").hide(),
                Opt.value("<dir>", "Override the local Maven repo root (~/.m2) for m2install.", "--m2-dir")
                        .hide(),
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
    String groupFlag;
    String nameFlag;
    String verFlag;
    boolean skipTests;
    Path cacheDirOverride;
    Path stateDirOverride;
    Path binDirOverride;
    Path libDirOverride;
    Path m2DirOverride;
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
        this.groupFlag = in.value("group").orElse(null);
        this.nameFlag = in.value("name").orElse(null);
        this.verFlag = in.value("ver").orElse(null);
        this.skipTests = in.isSet("skip-tests");
        this.libDirOverride = in.value("lib-dir").map(Path::of).orElse(null);
        this.m2DirOverride = in.value("m2-dir").map(Path::of).orElse(null);
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.global = GlobalOptions.from(in);

        // A local script/jar installs as a snapshot env (plan §4.3) — the launcher must not
        // depend on the source file continuing to exist. Project dirs and git URLs delegate to
        // the app-install pipeline (plan §9 convergence: one pipeline, two spellings).
        // Local paths (including the "." default) resolve against -C/--directory, not the
        // process cwd — same base InstallCommand's project mode always used.
        Path base = global.workingDir();
        dev.jkbuild.tool.ToolTarget classified = dev.jkbuild.tool.ToolTarget.classify(coord);
        boolean m2Intent = groupFlag != null || nameFlag != null || verFlag != null;
        if (m2Intent && classified instanceof dev.jkbuild.tool.ToolTarget.RunnableFile file) {
            // Coordinate flags = "store this artifact in the local cache" (the mvn install
            // equivalent), not "give me a launcher".
            return appInstallDelegate().installFromFile(base.resolve(file.path()).toAbsolutePath().normalize());
        }
        if (m2Intent && classified instanceof dev.jkbuild.tool.ToolTarget.UnsupportedFile file) {
            return appInstallDelegate().installFromFile(base.resolve(file.path()).toAbsolutePath().normalize());
        }
        if (classified instanceof dev.jkbuild.tool.ToolTarget.RunnableFile file) {
            return installFile(base.resolve(file.path()).normalize());
        }
        if (classified instanceof dev.jkbuild.tool.ToolTarget.Directory dir) {
            Path projectDir = base.resolve(dir.path()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(projectDir.resolve("jk.toml"))) {
                CliOutput.err("jk tool install: no jk.toml in " + projectDir
                        + " — a directory target must be a jk project.");
                return Exit.USAGE;
            }
            return appInstallDelegate().runProjectInstallGoal(projectDir, "install");
        }
        if (classified instanceof dev.jkbuild.tool.ToolTarget.Git git) {
            String raw = git.raw().startsWith("git+") ? git.raw().substring("git+".length()) : git.raw();
            String canonical =
                    dev.jkbuild.util.GitUrl.canonicalize(InstallCommand.splitUrlRef(raw).url());
            Path stateDirForGit = stateDirOverride != null ? stateDirOverride : JkDirs.state();
            Integer gitGate = UrlToolSource.gate(UrlToolSource.gitTrustUrl(canonical), stateDirForGit, "jk install");
            if (gitGate != null) return gitGate;
            return appInstallDelegate().installFromGit(raw);
        }
        if (classified instanceof dev.jkbuild.tool.ToolTarget.Url u) {
            Path stateDirForTrust = stateDirOverride != null ? stateDirOverride : JkDirs.state();
            Integer gated = UrlToolSource.gate(u.raw(), stateDirForTrust, "jk tool install");
            if (gated != null) return gated;
            Path fetched;
            try {
                fetched = UrlToolSource.fetch(
                        u.raw(), cacheDirOverride != null ? cacheDirOverride : JkDirs.cache(), false);
            } catch (IOException e) {
                CliOutput.err("jk tool install: " + e.getMessage());
                return Exit.SOFTWARE;
            }
            return installFile(fetched);
        }

        if (classified instanceof dev.jkbuild.tool.ToolTarget.JBangAlias) {
            Integer aliasExit = resolveJBangAliasForInstall();
            if (aliasExit != null) return aliasExit;
            // A GAV script-ref fell through: `coord` (and the default --bin) were rewritten.
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
     * A JBang {@code alias@catalog} install (plan §6): locate + trust-gate the catalog, then
     * install the alias's script-ref — fetched scripts snapshot an env named after the alias; a
     * coordinate ref rewrites {@code coord} and returns {@code null} to fall through. Alias
     * default {@code arguments} can't ride a launcher yet and warn when present.
     */
    private Integer resolveJBangAliasForInstall() throws IOException, InterruptedException {
        String aliasName = coord.substring(0, coord.indexOf('@'));
        JBangCatalog.Resolved r;
        try {
            r = JBangCatalog.resolve(coord, new dev.jkbuild.http.Http());
        } catch (IOException e) {
            CliOutput.err("jk tool install: " + e.getMessage());
            return Exit.SOFTWARE;
        }
        Path stateDirForTrust = stateDirOverride != null ? stateDirOverride : JkDirs.state();
        Integer gated = UrlToolSource.gate(r.pageOrigin(), stateDirForTrust, "jk tool install");
        if (gated != null) return gated;
        if (r.hasUnhonored() || !r.arguments().isEmpty()) {
            CliOutput.err("jk tool install: warning — this alias declares arguments/dependencies/java-options,"
                    + " which installed launchers do not honor yet.");
        }
        if (binName == null || binName.isBlank()) binName = aliasName;
        String ref = r.scriptRef();
        if (!ref.contains("://") && ref.contains(":")) {
            coord = ref; // coordinate script-ref — the normal flow takes it from here
            return null;
        }
        String url = ref.contains("://") ? ref : r.rawBase().resolve(ref).toString();
        if (ref.contains("://")) {
            Integer urlGate = UrlToolSource.gate(url, stateDirForTrust, "jk tool install");
            if (urlGate != null) return urlGate;
        }
        Path fetched;
        try {
            fetched = UrlToolSource.fetch(url, cacheDirOverride != null ? cacheDirOverride : JkDirs.cache(), false);
        } catch (IOException e) {
            CliOutput.err("jk tool install: " + e.getMessage());
            return Exit.SOFTWARE;
        }
        return installFile(fetched);
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
        if (!Files.isRegularFile(file)) {
            CliOutput.err("jk tool install: file not found: " + file);
            return Exit.NO_INPUT;
        }
        String mode = lower.endsWith(".jar")
                ? "jar"
                : lower.endsWith(".kts") ? "kts" : lower.endsWith(".kt") ? "kt" : "java";
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
        if (!prep.result().success() || (prep.mainClass() == null && !"kts".equals(mode))) return 1;

        // Snapshot into the env dir so the launcher survives the source moving/vanishing.
        Path envDir = envsRoot.resolve(bin);
        List<Path> classpath = new ArrayList<>();
        if ("kts".equals(mode)) {
            // Kotlin script: snapshot a neutralized copy (jk resolved its @file:DependsOn) and
            // write a kotlinc -script launcher over it + the resolved dep classpath.
            if (prep.kotlincBin() == null) return 1;
            Files.createDirectories(envDir);
            String source = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
            String neutralized = dev.jkbuild.script.ScriptHeaderParser.neutralizeKotlinAnnotations(source);
            Path scriptCopy = envDir.resolve(name);
            Files.writeString(scriptCopy, neutralized != null ? neutralized : source);
            ToolEnv ktsEnv =
                    new ToolEnv(bin, Coordinate.of("script", bin, "local"), "kotlin-script", prep.classpath());
            Path ktsLauncher = ToolLauncher.installKotlinScript(
                    envsRoot, binDir, JavaHomes.runningJavaHome(), prep.kotlincBin(), scriptCopy, ktsEnv);
            if (!global.outputIsJson()) {
                CliOutput.out("Installed " + file.getFileName() + " → " + ktsLauncher);
                CliOutput.out("Add to PATH if needed:");
                CliOutput.out("  export PATH=\"" + binDir + ":$PATH\"");
            }
            return 0;
        }
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
        delegate.groupFlag = groupFlag;
        delegate.nameFlag = nameFlag;
        delegate.verFlag = verFlag;
        delegate.cacheDirOverride = cacheDirOverride;
        delegate.stateDirOverride = stateDirOverride;
        delegate.binDirOverride = binDirOverride;
        delegate.libDirOverride = libDirOverride;
        delegate.m2DirOverride = m2DirOverride;
        delegate.repoUrl = repoUrl;
        delegate.buildOpts = new dev.jkbuild.cli.BuildOptions();
        delegate.buildOpts.skipTests = skipTests;
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
