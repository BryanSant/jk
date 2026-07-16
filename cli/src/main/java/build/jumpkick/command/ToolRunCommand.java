// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.cli.CliOutput;
import build.jumpkick.cli.GlobalOptions;
import build.jumpkick.cli.run.PipelineConsole;
import build.jumpkick.jdk.JavaHomes;
import build.jumpkick.model.Coordinate;
import build.jumpkick.model.command.Arity;
import build.jumpkick.model.command.CliCommand;
import build.jumpkick.model.command.Exit;
import build.jumpkick.model.command.Invocation;
import build.jumpkick.model.command.Opt;
import build.jumpkick.model.command.Param;
import build.jumpkick.tool.ToolEnv;
import build.jumpkick.tool.ToolLauncher;
import build.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk run [<target>] [<args>…]} — the universal runner, mounted both as the top-level
 * {@code run} command and as {@code jk tool run} (one implementation, two mounts; {@code jkx} is the
 * argv[0] binary alias). No target runs the current jk.toml project; to pass the project args, use
 * {@code jk run . <args>} (the first positional is always the target — uv's model).
 *
 * <p>The target may be (docs/tool-targets-plan.md §2, step-1 kinds):
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
 * {@code jk mvn}/{@code jk run} reasoning. The test-only in-process path builds the identical pipeline
 * via {@link ToolPipelines}.
 *
 * <p>{@code jkx} — the uvx-style alias for this command — is a real binary: a hardlink to {@code jk}
 * dispatched on argv[0] in {@link build.jumpkick.cli.Jk#main} (created by {@code install.sh},
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
        return "Run the current project, a tool, script, directory, git repo, or URL";
    }

    @Override
    public List<Opt> options() {
        List<Opt> opts = List.of(
                Opt.value("<class>", "Override the Main-Class to exec (coordinate targets only).", "--main"),
                Opt.value("<coord>", "Add an extra dependency to the tool's classpath (repeatable).", "--with").repeat(),
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                        .hide(),
                Opt.value("<dir>", "Override the jk state directory.", "--state-dir")
                        .hide(),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir")
                        .hide(),
                Opt.value("<url>", "Override the Maven repository URL (for tests).", "--repo-url")
                        .hide());
        var all = new ArrayList<>(opts);
        all.addAll(VariantSelection.options()); // project targets only; tool/script targets ignore them
        return all;
    }

    @Override
    public List<Param> parameters() {
        // The tool args after the target are captured as trailing positionals via ZERO_OR_MORE
        return List.of(
                Param.of(
                        "target",
                        Arity.ZERO_OR_ONE,
                        "Catalog name, Maven coordinate (g:a[:version|@selector]),\n"
                                + ".java/.kt/.kts/.jar file, directory, git URL, web URL, or\n"
                                + "alias@catalog. Omit to run the current jk.toml project\n"
                                + "(pass its args via `jk run . <args>`)."),
                Param.of("args", Arity.ZERO_OR_MORE, "Arguments forwarded to the program."));
    }

    String target;
    String mainClass;
    Path cacheDirOverride;
    Path stateDirOverride;
    Path jdksDir;
    URI repoUrl;
    boolean forceRecompile;
    List<String> toolArgs = new ArrayList<>();
    GlobalOptions global;
    // Set by a JBang alias whose script-ref is a coordinate: the alias's dependencies and
    // java-options ride the normal coordinate flow (extra deps + exec JVM args).
    List<String> aliasDeps = List.of();
    List<String> aliasJavaOptions = List.of();

    /**
     * A directory target (docs/tool-targets-plan.md §4.4): a jk project builds (tests skipped) and
     * execs like {@code jk run} without the {@code cd}; a JBang-convention folder runs its {@code
     * main.java}; a folder holding exactly one script runs that (gist-checkout shape).
     */
    private int runDirectory(Path dir, List<String> args) throws IOException, InterruptedException {
        if (Files.isRegularFile(dir.resolve("jk.toml"))) {
            RunCommand delegate = new RunCommand();
            delegate.cacheDirOverride = cacheDirOverride;
            delegate.jdksDir = jdksDir;
            delegate.buildOpts = new build.jumpkick.cli.BuildOptions();
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
     * A git target (docs/tool-targets-plan.md §4.6): trust-gate the repo's https form, clone at
     * the requested ref (engine-hosted, no jk.toml requirement), then apply the §4.4 directory
     * rules to the checkout — a jk project builds and execs, a JBang-convention repo runs its
     * script.
     */
    private int runGit(String input, List<String> args) throws IOException, InterruptedException {
        String raw = input.startsWith("git+") ? input.substring("git+".length()) : input;
        // url[@ref|#rev][!subdir] — the same embedded-subdir grammar git deps use.
        String subdir = null;
        int bang = raw.indexOf('!');
        if (bang > 0) {
            subdir = raw.substring(bang + 1);
            raw = raw.substring(0, bang);
        }
        InstallCommand.UrlAndRef split = InstallCommand.splitUrlRef(raw);
        String expanded = build.jumpkick.util.GitUrl.expand(split.url());
        String canonical = build.jumpkick.util.GitUrl.canonicalize(split.url());
        Path stateDir = stateDirOverride != null ? stateDirOverride : JkDirs.state();
        Integer gated = UrlToolSource.gate(UrlToolSource.gitTrustUrl(canonical), stateDir, "jk tool run");
        if (gated != null) return gated;

        String refStr = split.ref() != null ? split.ref() : "main";
        Path cacheDir = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
        Files.createDirectories(cacheDir);
        boolean refresh = build.jumpkick.config.SessionContext.current().config().forceOr(false);
        PipelineConsole.Mode mode = PipelineConsole.modeFor(global);

        Path checkout;
        if (engineDisabledForTests()) {
            var o = build.jumpkick.cli.engine.InProcessEngine.require()
                    .gitFetchPipeline(expanded, canonical, refStr, cacheDir, refresh, /* requireJkToml */ false, mode);
            if (!o.result().success() || o.checkout() == null) return 1;
            checkout = o.checkout();
        } else {
            build.jumpkick.cli.engine.EngineClient.GitFetchOutcome outcome;
            try {
                outcome = build.jumpkick.cli.engine.EngineClient.runGitFetch(
                        build.jumpkick.engine.EnginePaths.current(),
                        new build.jumpkick.cli.engine.EngineClient.GitFetchRequest(
                                expanded, canonical, refStr, cacheDir, refresh, /* requireJkToml */ false),
                        steps -> PipelineConsole.chooseConsoleListener("tool-git-fetch", steps, mode));
            } catch (IOException e) {
                CliOutput.err("jk tool run: " + e.getMessage());
                return Exit.SOFTWARE;
            }
            if (!outcome.result().success() || outcome.checkout() == null) return 1;
            checkout = outcome.checkout();
        }
        if (subdir != null) {
            Path sub = checkout.resolve(subdir).normalize();
            if (!sub.startsWith(checkout) || !Files.isDirectory(sub)) {
                CliOutput.err("jk tool run: no directory `" + subdir + "` in " + input);
                return Exit.USAGE;
            }
            checkout = sub;
        }
        return runDirectory(checkout, args);
    }

    /**
     * A JBang {@code alias@catalog} target (docs/tool-targets-plan.md §6): locate the catalog,
     * trust-gate its page origin, then run the alias's {@code script-ref} — a relative ref fetches
     * from the catalog's raw base under that same gate; an absolute URL passes its own gate; a
     * coordinate falls through into the normal flow by rewriting {@code target}/{@code toolArgs}
     * (in that case this returns {@code null}).
     */
    private Integer resolveJBangAlias(String command) throws IOException, InterruptedException {
        JBangCatalog.Resolved r;
        try {
            r = JBangCatalog.resolve(target, new build.jumpkick.http.Http());
        } catch (IOException e) {
            CliOutput.err(command + ": " + e.getMessage());
            return Exit.SOFTWARE;
        }
        Path stateDir = stateDirOverride != null ? stateDirOverride : JkDirs.state();
        Integer gated = UrlToolSource.gate(r.pageOrigin(), stateDir, command);
        if (gated != null) return gated;
        List<String> merged = new ArrayList<>(r.arguments());
        merged.addAll(toolArgs);
        String ref = r.scriptRef();
        Path cacheDir = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
        if (ref.contains("://")) {
            Integer urlGate = UrlToolSource.gate(ref, stateDir, command);
            if (urlGate != null) return urlGate;
            Path fetched = UrlToolSource.fetch(ref, cacheDir, forceRecompile);
            return new ScriptRunner(global, cacheDirOverride, stateDirOverride, repoUrl, forceRecompile,
                            r.dependencies(), r.javaOptions())
                    .run(fetched, merged);
        }
        if (ref.contains(":")) {
            // Coordinate script-ref: rewrite the target and let the normal flow resolve it.
            target = ref;
            toolArgs = merged;
            aliasDeps = r.dependencies();
            aliasJavaOptions = r.javaOptions();
            return null;
        }
        Path fetched = UrlToolSource.fetch(r.rawBase().resolve(ref).toString(), cacheDir, forceRecompile);
        return new ScriptRunner(global, cacheDirOverride, stateDirOverride, repoUrl, forceRecompile,
                        r.dependencies(), r.javaOptions())
                .run(fetched, merged);
    }

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()} for the full rationale. A real {@code jk tool run} of
     * a coordinate hosts its resolve+fetch on the engine; the exec always runs here (it inherits
     * this terminal's stdio).
     */
    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "build.jumpkick.testrunner.TestRunner".equals(System.getProperty("jk.plugin.class"));
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        List<String> positionals = in.positionals();
        // No target = the current project (the `.` directory rules: jk.toml → build + exec).
        this.target = positionals.isEmpty() ? "." : positionals.get(0);
        this.toolArgs = positionals.size() > 1 ? positionals.subList(1, positionals.size()) : List.of();
        this.mainClass = in.value("main").orElse(null);
        this.cacheDirOverride = in.value("cache-dir").map(Path::of).orElse(null);
        this.stateDirOverride = in.value("state-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.forceRecompile = in.isSet("force");
        this.global = GlobalOptions.from(in);
        // --release / --variant parameterize project targets (current dir or a directory target):
        // the selection rides the ambient session into the delegate's build + deploy command.
        VariantSelection.install(in, global.workingDir());
        // A local file target (by extension) is compiled/run by ScriptRunner; the
        // extension is the signal even when the file is missing, so the user gets
        // a proper "not found" error from the matching mode handler. Routing goes
        // through the classifier so a remote `https://…/tool.jar` is NOT a file.
        build.jumpkick.tool.ToolTarget classified = build.jumpkick.tool.ToolTarget.classify(target);
        if (classified instanceof build.jumpkick.tool.ToolTarget.RunnableFile file) {
            List<String> fileWith;
            try {
                fileWith = ToolTargets.resolveWith(in.values("with"));
            } catch (ToolTargets.TargetException e) {
                CliOutput.err(e.getMessage());
                return Exit.USAGE;
            }
            return new ScriptRunner(
                            global, cacheDirOverride, stateDirOverride, repoUrl, forceRecompile, fileWith, List.of())
                    .run(file.path(), toolArgs);
        }
        if (classified instanceof build.jumpkick.tool.ToolTarget.Directory dir) {
            return runDirectory(global.workingDir().resolve(dir.path()).normalize(), toolArgs);
        }
        if (classified instanceof build.jumpkick.tool.ToolTarget.Git g) {
            return runGit(g.raw(), toolArgs);
        }
        if (classified instanceof build.jumpkick.tool.ToolTarget.Url u) {
            Path stateDir = stateDirOverride != null ? stateDirOverride : JkDirs.state();
            Integer gated = UrlToolSource.gate(u.raw(), stateDir, "jk tool run");
            if (gated != null) return gated;
            Path fetched;
            try {
                fetched = UrlToolSource.fetch(
                        u.raw(), cacheDirOverride != null ? cacheDirOverride : JkDirs.cache(), forceRecompile);
            } catch (IOException e) {
                CliOutput.err("jk tool run: " + e.getMessage());
                return Exit.SOFTWARE;
            }
            return new ScriptRunner(global, cacheDirOverride, stateDirOverride, repoUrl, forceRecompile)
                    .run(fetched, toolArgs);
        }

        if (classified instanceof build.jumpkick.tool.ToolTarget.JBangAlias) {
            Integer aliasExit = resolveJBangAlias("jk tool run");
            if (aliasExit != null) return aliasExit;
            // A GAV script-ref fell through: `target`/`toolArgs` were rewritten in place.
        }

        ToolTargets.Resolved resolved;
        List<String> with;
        try {
            resolved = ToolTargets.resolve(target);
            List<String> withInputs = new ArrayList<>(in.values("with"));
            withInputs.addAll(aliasDeps);
            with = ToolTargets.resolveWith(withInputs);
        } catch (ToolTargets.TargetException e) {
            CliOutput.err(e.getMessage());
            return Exit.USAGE;
        }
        String bin = resolved.defaultBin();
        Path cacheDir = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
        Files.createDirectories(cacheDir);

        ToolEnv env;
        if (engineDisabledForTests()) {
            var o = build.jumpkick.cli.engine.InProcessEngine.require()
                    .toolResolvePipeline(
                            build.jumpkick.model.ToolCoordSpec.parse(resolved.coordSpec()),
                            with.stream().map(build.jumpkick.model.ToolCoordSpec::parse).toList(),
                            bin, mainClass, repoUrl, cacheDir,
                            resolved.coordSpec(), PipelineConsole.modeFor(global));
            if (o.env() == null) return 1;
            env = o.env();
        } else {
            build.jumpkick.cli.engine.EngineClient.ToolResolveOutcome outcome;
            try {
                outcome = build.jumpkick.cli.engine.EngineClient.runToolResolve(
                        build.jumpkick.engine.EnginePaths.current(),
                        new build.jumpkick.cli.engine.EngineClient.ToolResolveRequest(
                                resolved.coordSpec(), with, bin, mainClass, repoUrl, cacheDir),
                        steps -> PipelineConsole.chooseConsoleListener(
                                "tool-run", steps, PipelineConsole.modeFor(global)));
            } catch (IOException e) {
                CliOutput.err("jk tool run: " + e.getMessage());
                return Exit.SOFTWARE;
            }
            if (!outcome.result().success() || outcome.mainClass() == null || outcome.coord() == null) return 1;
            env = new ToolEnv(bin, Coordinate.parse(outcome.coord()), outcome.mainClass(), outcome.classpath());
        }

        // The exec deliberately stays client-side: the tool inherits this terminal's stdio.
        Path javaHome = JavaHomes.runningJavaHome();
        return ToolLauncher.execEphemeral(javaHome, env, aliasJavaOptions, toolArgs);
    }
}
