// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Answers;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.cli.tui.Wizard;
import dev.jkbuild.cli.tui.WizardStep;
import dev.jkbuild.config.JkBuildEditor;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.util.JkDirs;
import dev.jkbuild.run.JkThreads;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedStringBuilder;

/**
 * {@code jk new} — create a new jk project (aliases: {@code init}, {@code create}).
 *
 * <p>If stdin/stdout is a TTY and no flags were supplied, drops into an interactive wizard (see
 * {@code dev.jkbuild.cli.tui}). Otherwise reads from flags with sane defaults. Both paths converge
 * on {@link NewInputs} + {@link NewScaffolder#write(NewInputs)}.
 *
 * <p><b>Project vs. module.</b> We walk up from where the project will live looking for an
 * enclosing {@code jk.toml}: the first one found is the parent and the new directory is registered
 * as its <em>module</em>. The search stops — meaning "standalone project" — when it exits a git
 * repo (a {@code .git} reached before any {@code jk.toml}) or reaches {@code $HOME}. {@code
 * --no-module} forces a standalone project.
 *
 * <p>For a module the wizard changes shape (titled "Create a New Module for &lt;project&gt;",
 * asking for a module name) and inherits the parent's group, JDK, and language as defaults; its
 * path is appended to the root {@code [workspace].modules} (promoting a plain project into a
 * workspace on its first module), and no per-module {@code jk.lock} is written (the root lock owns
 * resolution). Mirrors {@code cargo new} / {@code uv init}.
 */
public final class NewCommand implements CliCommand {

    @Override
    public String name() {
        return "new";
    }

    @Override
    public String description() {
        return "Create a new jk project (or workspace module)";
    }

    @Override
    public List<String> aliases() {
        return List.of("create");
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<name>", "Project name and target directory leaf.", "--name"),
                Opt.value("<group>", "Maven groupId (default: from git config).", "--group"),
                // --jdk rides the GLOBAL option (same canonical key "jdk"); a local
                // re-declaration would collide with it in the dispatcher.
                Opt.value("<lang>", "Language: java | kotlin. Default: java.", "--lang"),
                Opt.flag("Executable project (default is a library).", "--executable")
                        .negate(),
                Opt.flag("Shadow (fat) jar. Implies --executable.", "--shadow"),
                Opt.flag("Wire a GraalVM native-image build.", "--native"),
                Opt.flag("Spring Boot application (implies --executable).", "--spring"),
                Opt.value("<deps>", "Curated deps, comma-separated.", "--deps"),
                Opt.value("<layout>", "Layout: simple | traditional.", "--layout"),
                Opt.value("<module>", "Kotlin module name (-> project.module).", "--kotlin-module"),
                Opt.flag("Force a standalone project (not a module).", "--no-module"),
                Opt.flag("", "--no-member").hide()); // undocumented synonym for --no-module
    }

    @Override
    public List<Param> parameters() {
        return List.of(
                Param.of("directory", Arity.ZERO_OR_ONE, "Target directory. Default: cwd or a ./<name> subdir."));
    }

    String name;
    String group;
    String jdk;
    String lang;
    Boolean executable;
    boolean shadow;
    boolean nativeImage;
    boolean spring;
    String depsCsv;
    String layoutFlag;
    String kotlinModule;
    boolean noModule;
    Path directory;
    GlobalOptions global;

    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> CANDIDATES = GoalKey.of("candidates", List.class);

    private static final GoalKey<Terminal> TERMINAL = GoalKey.of("terminal", Terminal.class);
    private static final GoalKey<dev.jkbuild.jdk.JdkCatalog> CATALOG =
            GoalKey.of("catalog", dev.jkbuild.jdk.JdkCatalog.class);
    private static final GoalKey<Answers> ANSWERS = GoalKey.of("answers", Answers.class);
    private static final GoalKey<NewJdkCandidate> PICKED = GoalKey.of("picked", NewJdkCandidate.class);
    private static final GoalKey<NewInputs> INPUTS = GoalKey.of("inputs", NewInputs.class);

    /** Set during scaffold when the new project was registered as a workspace module. */
    private record Module(Path root, String rel, String projectName) {}

    private volatile Module registered;

    /**
     * The enclosing project/workspace this invocation will add a module to, or {@code null} when
     * we're creating a standalone project. Resolved once in {@link #call()} and consumed by the
     * wizard (UX + inherited defaults), the flag path, and scaffolding.
     */
    private ParentInfo parent;

    /**
     * The user's global default JDK identifier ({@code default-jdk} in {@code ~/.jk/config/jk.toml},
     * e.g. {@code temurin-25.0.1}), or empty when none is set. Resolved once in {@link #callBody()}
     * and used to skip the "Select a JDK" prompt: when a default exists we adopt its major and write
     * a bare-major pin (the vendor stays out of {@code jk.toml} per policy).
     */
    private Optional<String> defaultJdk = Optional.empty();

    /** Inherited context from the parent project's {@code [project]} block. */
    private record ParentInfo(Path root, dev.jkbuild.engine.protocol.ProjectInfo info) {
        String displayName() {
            return info.name();
        }

        String group() {
            return info.group();
        }

        boolean kotlin() {
            return info.kotlin();
        }

        /** The JDK toolchain version (which JDK runs the build). */
        int jdkMajor() {
            int major = dev.jkbuild.model.JkBuild.Project.majorOf(info.jdk());
            return major > 0 ? major : info.javaRelease();
        }

        /**
         * The {@code java = N} compile target, which flows through even when it diverges from {@link
         * #jdkMajor()}.
         */
        int javaRelease() {
            return info.javaRelease();
        }
    }

    /**
     * Decide whether we're adding a module to an existing project/workspace. Walk up from {@code
     * startDir}: the first directory with a {@code jk.toml} is the parent. Stop (— standalone project
     * —) on exiting a git repo (a {@code .git} dir reached before any jk.toml) or hitting {@code
     * $HOME}. {@code --no-module} short-circuits to standalone.
     */
    static Optional<Path> detectParentDir(Path startDir, Path home, boolean noModule) {
        if (noModule) return Optional.empty();
        Path normHome = home == null ? null : home.toAbsolutePath().normalize();
        for (Path dir = startDir.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve("jk.toml"))) return Optional.of(dir);
            if (Files.isDirectory(dir.resolve(".git"))) return Optional.empty(); // exited the repo
            if (dir.equals(normHome)) return Optional.empty(); // hit $HOME
        }
        return Optional.empty();
    }

    /**
     * Where to begin the parent search — the directory the project will live <em>in</em> (its
     * target's parent). For {@code jk new foo} that's the cwd; for {@code jk new /abs/foo} it's
     * {@code /abs}; for {@code .} / no arg it's the cwd (the module is the cwd itself, or
     * cwd/&lt;name&gt;).
     */
    private Path detectionStartDir(Path cwd) {
        if (directory == null || isCurrentDirArg(directory)) return cwd;
        Path parentDir = cwd.resolve(directory).normalize().getParent();
        return parentDir != null ? parentDir : cwd;
    }

    /**
     * Resolve {@link #parent} by parsing the detected parent's manifest (null if none / unparseable).
     */
    private ParentInfo resolveParent(Path startDir) {
        Path home = Optional.ofNullable(System.getProperty("user.home"))
                .map(Path::of)
                .orElse(null);
        Optional<Path> root = detectParentDir(startDir, home, noModule);
        if (root.isEmpty()) return null;
        var info = BuildCommand.projectInfoOrNull(root.get());
        if (info == null) return null; // unreadable/unparseable parent — treat as standalone
        return new ParentInfo(root.get(), info);
    }

    @Override
    public int run(Invocation in) throws IOException {
        this.name = in.value("name").orElse(null);
        this.group = in.value("group").orElse(null);
        this.jdk = in.value("jdk").orElse(null);
        this.lang = in.value("lang").orElse(null);
        this.executable = in.flag("executable").orElse(null);
        this.shadow = in.isSet("shadow");
        this.nativeImage = in.isSet("native");
        this.spring = in.isSet("spring");
        this.depsCsv = in.value("deps").orElse(null);
        this.layoutFlag = in.value("layout").orElse(null);
        this.kotlinModule = in.value("kotlin-module").orElse(null);
        this.noModule = in.isSet("no-module") || in.isSet("no-member");
        this.directory =
                in.positionals().isEmpty() ? null : Path.of(in.positionals().get(0));
        this.global = GlobalOptions.from(in);
        return callBody();
    }

    /** The body of run(), callable after fields are populated (used by InitCommand delegation). */
    int callBody() throws IOException {
        Path cwd = Path.of(".").toAbsolutePath().normalize();

        // Fail-fast for `jk new .` when the cwd already has a project.
        // For any other invocation we defer the existing-manifest check to
        // after the target is fully resolved (the project name may come from
        // the wizard or from `--name`).
        if (directory != null && isCurrentDirArg(directory) && Files.exists(cwd.resolve("jk.toml"))) {
            String existing = wizardPresetName(directory, cwd)
                    .orElseGet(
                            () -> cwd.getFileName() != null ? cwd.getFileName().toString() : "this directory");
            emitProjectExistsError(existing, parent != null, true, null);
            return Exit.CONFIG;
        }

        // Are we adding a module to an existing project/workspace, or creating
        // a standalone project? Search up from where the project will live (the
        // target's parent). Drives the wizard UX and inherited defaults.
        this.parent = resolveParent(detectionStartDir(cwd));
        this.defaultJdk = readDefaultJdk();

        if (shouldRunWizard()) {
            return runWizardGoal(cwd);
        }
        return runFlagGoal(cwd);
    }

    /**
     * Wizard mode: four phases under a Goal marked interactive.
     *
     * <ol>
     *   <li>{@code prewarm} (IO) — opens the terminal + discovers JDKs + fetches the catalog in
     *       parallel via {@link java.util.concurrent.CompletableFuture}; saves the perceived 50–200ms
     *       by overlapping three IO-bound calls.
     *   <li>{@code wizard} (SYNC) — runs the wizard UI on the open terminal. Ctrl-C halts the process
     *       hard (Runtime.halt) so JLine's cleanup hook can't deadlock on the NonBlockingReader.
     *   <li>{@code install-jdk} (IO) — only when the user picked an installable (not-yet-on-disk)
     *       candidate; runs the same download/extract dance as {@code jk jdk install}.
     *   <li>{@code scaffold} (SYNC) — writes jk.toml + jk.lock + sources, emits the styled "Done.
     *       Next:" line through the wizard terminal's writer.
     * </ol>
     *
     * The terminal lives across phases via a {@link GoalKey} and is closed in a finally so it
     * survives both success and failure.
     */
    private int runWizardGoal(Path cwd) throws IOException {
        Path cache = JkDirs.cache();

        Phase prewarm = Phase.builder("prewarm")
                .kind(PhaseKind.IO)
                .scope(1)
                .execute(ctx -> {
                    ctx.label("discover JDKs + fetch catalog + open terminal");
                    var jdkOptionsFuture =
                            java.util.concurrent.CompletableFuture.supplyAsync(NewJdkOptions::discover, JkThreads.io());
                    var catalogFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                            NewCommand::fetchCatalogQuiet, JkThreads.io());
                    Terminal terminal;
                    try {
                        terminal = Wizard.openTerminal();
                    } catch (IOException e) {
                        ctx.error("terminal", "failed to open terminal: " + e.getMessage());
                        throw new RuntimeException(e);
                    }
                    ctx.put(TERMINAL, terminal);
                    var jdkOptions = jdkOptionsFuture.join();
                    var catalog = catalogFuture.join();
                    var candidates = NewJdkCandidate.build(
                            jdkOptions,
                            catalog,
                            LATEST_LTS_MAJOR,
                            dev.jkbuild.jdk.HostPlatform.currentOs(),
                            dev.jkbuild.jdk.HostPlatform.currentArch());
                    if (candidates.isEmpty()) {
                        ctx.error("no-jdks", "no JDKs found on this system");
                        throw new RuntimeException("no jdks");
                    }
                    ctx.put(CANDIDATES, candidates);
                    catalog.ifPresent(c -> ctx.put(CATALOG, c)); // drives the two-track language list
                    ctx.progress(1);
                })
                .build();

        Phase wizardPhase = Phase.builder("wizard")
                .requires("prewarm")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("run wizard");
                    Terminal terminal = ctx.require(TERMINAL);
                    @SuppressWarnings("unchecked")
                    List<NewJdkCandidate> candidates = (List<NewJdkCandidate>) ctx.require(CANDIDATES);

                    Answers preset = wizardPresetName(directory, cwd)
                            .map(n -> Answers.of(Map.of("name", (Object) n)))
                            .orElseGet(() -> Answers.of(Map.of()));
                    var groupGuess = NewGroupGuess.guess(
                            cwd,
                            Optional.ofNullable(System.getProperty("user.home"))
                                    .map(Path::of)
                                    .orElse(null));
                    var wizard = buildWizard(candidates, ctx.get(CATALOG).orElse(null), groupGuess, parent,
                            defaultJdk.isPresent(), directory != null && isCurrentDirArg(directory));
                    var wizardResult = wizard.run(terminal, preset);
                    if (wizardResult.isEmpty()) {
                        // Cancelled via Ctrl-C. Wizard.printCancellation
                        // preserves the cyan active-rail closer and prints
                        // the red marker beside it. Runtime.halt() skips
                        // shutdown hooks — JLine's cleanup hook would block
                        // on the NonBlockingReader.
                        Wizard.printCancellation(
                                terminal,
                                parent != null ? "Module creation canceled" : "Project creation canceled");
                        Runtime.getRuntime().halt(130);
                    }
                    ctx.put(ANSWERS, wizardResult.get());
                    ctx.put(PICKED, pickCandidate(wizardResult.get(), candidates));
                    ctx.progress(1);
                })
                .build();

        Phase installJdk = Phase.builder("install-jdk")
                .kind(PhaseKind.IO)
                .requires("wizard")
                .scope(1)
                .execute(ctx -> {
                    NewJdkCandidate picked = ctx.require(PICKED);
                    if (picked.installed()) {
                        ctx.label("JDK already installed");
                        ctx.progress(1);
                        return;
                    }
                    ctx.label("install missing JDK");
                    var installed = installCandidate(picked);
                    if (installed.isEmpty()) {
                        ctx.error("jdk-install", "JDK install failed");
                        throw new RuntimeException("jdk install failed");
                    }
                    ctx.put(PICKED, installed.get());
                    ctx.progress(1);
                })
                .build();

        Phase scaffold = Phase.builder("scaffold")
                .requires("install-jdk")
                .scope(1)
                .execute(ctx -> {
                    NewJdkCandidate resolved = ctx.require(PICKED);
                    // After install-jdk, picked is always Installed; unwrap
                    // for fromAnswers.
                    var pickedOpt = ((NewJdkCandidate.Installed) resolved).option();
                    var inputs = fromAnswers(ctx.require(ANSWERS), cwd, pickedOpt);
                    ctx.put(INPUTS, inputs);
                    if (Files.exists(inputs.directory().resolve("jk.toml"))) {
                        ctx.error("exists", "project " + inputs.name() + " already exists at " + inputs.directory());
                        throw new RuntimeException("project exists");
                    }
                    ctx.label("scaffold " + inputs.name());
                    Files.createDirectories(inputs.directory());
                    scaffoldAndRegister(inputs);
                    ctx.put(INPUTS, inputs);
                    ctx.progress(1);
                })
                .build();

        Goal goal = Goal.builder("new")
                .interactive(true)
                .addPhase(prewarm)
                .addPhase(wizardPhase)
                .addPhase(installJdk)
                .addPhase(scaffold)
                .build();

        // Single try/finally wrapping the whole goal lifecycle plus the
        // success-emit path: emitSuccessOnTerminal writes through the
        // wizard's JLine terminal handle, so the terminal has to stay
        // open until after that call. The finally closes it on the way
        // out whether scaffold succeeded, failed, or threw.
        try {
            GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);

            if (!result.success()) {
                for (GoalResult.Diagnostic d : result.errors()) {
                    if ("no-jdks".equals(d.code())) {
                        emitNoJdksError();
                        return Exit.CONFIG;
                    }
                    if ("exists".equals(d.code())) {
                        NewInputs partial = goal.get(INPUTS).orElse(null);
                        String coord = partial != null
                                ? partial.group() + ":" + partial.name()
                                : "project";
                        boolean isInit = directory != null && isCurrentDirArg(directory);
                        Terminal term = goal.get(TERMINAL).orElse(null);
                        emitProjectExistsError(coord, parent != null, isInit, term);
                        return Exit.CONFIG;
                    }
                }
                return Exit.CONFIG;
            }

            NewInputs inputs = goal.get(INPUTS).orElseThrow();
            boolean isInit = directory != null && isCurrentDirArg(directory);
            goal.get(TERMINAL)
                    .ifPresentOrElse(
                            t -> emitSuccessOnTerminal(inputs, t, registered, isInit),
                            () -> emitSuccessPlain(inputs, registered, isInit));
            return 0;
        } finally {
            goal.get(TERMINAL).ifPresent(t -> {
                try {
                    t.close();
                } catch (IOException ignored) {
                }
            });
        }
    }

    /**
     * Flag mode: validate inputs, scaffold. Not interactive (no wizard, no progress widgets in the
     * command's own output). Wrapping it in a goal still gives us a run-log entry for `jk new
     * --name=X` etc.
     */
    private int runFlagGoal(Path cwd) {
        NewInputs inputs;
        try {
            inputs = fromFlags(cwd);
        } catch (IllegalArgumentException e) {
            CliOutput.err("jk new: " + e.getMessage());
            return Exit.USAGE;
        }
        if (shadow && inputs.main().isEmpty()) {
            CliOutput.err("jk new: --shadow requires --executable");
            return Exit.USAGE;
        }
        if (Files.exists(inputs.directory().resolve("jk.toml"))) {
            emitProjectExistsError(
                    inputs.group() + ":" + inputs.name(),
                    parent != null, directory != null && isCurrentDirArg(directory), null);
            return Exit.CONFIG;
        }
        Path cache = JkDirs.cache();

        Phase scaffold = Phase.builder("scaffold")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("scaffold " + inputs.name());
                    Files.createDirectories(inputs.directory());
                    scaffoldAndRegister(inputs);
                    ctx.progress(1);
                })
                .build();

        Goal goal = Goal.builder("new").addPhase(scaffold).build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);
        if (!result.success()) return 1;
        if (!global.outputIsJson())
            emitSuccessPlain(inputs, registered, directory != null && isCurrentDirArg(directory));
        return 0;
    }

    /**
     * TTY + no real flags. A bare positional ({@code jk new my-project} or {@code jk new .}) still
     * runs the wizard — the positional only pre-seeds the name.
     */
    private boolean shouldRunWizard() {
        if (!isInteractiveTerminal()) {
            return false;
        }
        return !anyFlagSupplied();
    }

    private static boolean isInteractiveTerminal() {
        // Gates the interactive `jk new` wizard — input axis, so key on the controlling terminal.
        return dev.jkbuild.cli.tui.Interactivity.canPrompt();
    }

    private boolean anyFlagSupplied() {
        return name != null
                || group != null
                || jdk != null
                || lang != null
                || executable != null
                || shadow
                || nativeImage
                || depsCsv != null
                || layoutFlag != null
                || kotlinModule != null;
    }

    /**
     * Scaffold the project, then — if it lands inside an existing workspace — skip the per-module
     * {@code jk.lock} and register the new module in the root {@code [workspace].modules} (Cargo/uv:
     * {@code cargo new} / {@code uv init} edit the workspace manifest). Records the registration for
     * the success message.
     */
    private void scaffoldAndRegister(NewInputs inputs) throws IOException {
        NewScaffolder.write(inputs, parent == null); // modules skip the gitignore (root owns it)
        if (parent != null) {
            Path root = parent.root();
            String rel = root.relativize(inputs.directory()).toString().replace('\\', '/');
            Path rootToml = root.resolve("jk.toml");
            // Registers the module, promoting a plain project into a workspace
            // root (creating the [workspace] table) when this is its first module.
            EngineEdits.apply(rootToml, "register-workspace-module", java.util.List.of(rel));
            registered = new Module(root, rel, parent.displayName());
        }
    }

    private NewInputs fromFlags(Path cwd) {
        var presetName = wizardPresetName(directory, cwd);
        var resolvedName = (name != null && !name.isBlank()) ? name : presetName.orElse("untitled");
        Path target = resolveTarget(directory, cwd, resolvedName);
        var resolvedGroup = (group != null && !group.isBlank())
                ? group
                : parent != null
                        ? parent.group()
                        : NewGroupGuess.guess(
                                cwd,
                                Optional.ofNullable(System.getProperty("user.home"))
                                        .map(Path::of)
                                        .orElse(null));
        // Resolve the JDK pin written to jk.toml: an explicit --jdk wins (keeping
        // a vendor only when the user typed one); else inherit the parent's major
        // (module); else adopt the global default JDK's major; else the latest
        // LTS. Every non-explicit path writes a bare major — the vendor stays out
        // of jk.toml unless the user asked for it.
        NewJdkPlan.Spec jdkSpec;
        if (jdk != null && !jdk.isBlank()) {
            jdkSpec = resolveJdkArg(jdk);
        } else if (parent != null && parent.jdkMajor() > 0) {
            int m = parent.jdkMajor();
            jdkSpec = new NewJdkPlan.Spec(m, Integer.toString(m));
        } else if (defaultJdk.map(dev.jkbuild.model.JkBuild.Project::majorOf).orElse(0) > 0) {
            int m = dev.jkbuild.model.JkBuild.Project.majorOf(defaultJdk.get());
            jdkSpec = new NewJdkPlan.Spec(m, Integer.toString(m));
        } else {
            jdkSpec = new NewJdkPlan.Spec(LATEST_LTS_MAJOR, Integer.toString(LATEST_LTS_MAJOR));
        }
        var resolvedJdk = jdkSpec.pin();
        int resolvedJdkMajor = jdkSpec.major();
        // The compile target flows through from the parent (workspace-wide
        // release) even when it diverges from the JDK toolchain; standalone
        // projects target their JDK.
        int resolvedJavaRelease = parent != null ? parent.javaRelease() : resolvedJdkMajor;
        // A native binary needs a GraalVM (native-image) JDK; reject a Java release no GraalVM
        // ships yet (e.g. 26 today). Same two-track rule the wizard enforces, checked here for the
        // non-interactive flag path. Consult the catalog only when --native is set.
        if (nativeImage && resolvedJavaRelease > 0) {
            int maxNative = maxNativeMajor(fetchCatalogQuiet().orElse(null));
            if (resolvedJavaRelease > maxNative) {
                throw new IllegalArgumentException("--native requires a GraalVM (native-image) JDK; the newest "
                        + "native-image-capable Java is " + maxNative + ", but Java " + resolvedJavaRelease
                        + " was requested");
            }
        }
        var resolvedLang = (lang != null && !lang.isBlank())
                ? parseLanguage(lang)
                : (parent != null && parent.kotlin()) ? NewInputs.Language.KOTLIN : NewInputs.Language.JAVA;
        var isExecutable = Boolean.TRUE.equals(executable) || shadow || nativeImage || spring;
        // Boot users expect the Maven layout (resources under src/main/resources); an
        // explicit --layout still wins.
        var resolvedLayout = (layoutFlag != null && !layoutFlag.isBlank())
                ? layoutFlag.toLowerCase()
                : spring ? "traditional" : "simple";
        var resolvedMain = spring
                // Kotlin's top-level main lives on the ApplicationKt facade class.
                ? Optional.of(resolvedGroup + (resolvedLang == NewInputs.Language.KOTLIN ? ".ApplicationKt" : ".Application"))
                : isExecutable
                        ? Optional.of(
                                deriveMainFqcn(resolvedGroup, resolvedLang, "simple".equalsIgnoreCase(resolvedLayout)))
                        : Optional.<String>empty();
        var resolvedDeps = parseDeps(depsCsv);
        var resolvedKotlinModule = (kotlinModule != null && !kotlinModule.isBlank())
                ? Optional.of(kotlinModule)
                : Optional.<String>empty();
        return new NewInputs(
                resolvedGroup,
                resolvedName,
                resolvedJdk,
                resolvedJdkMajor,
                resolvedJavaRelease,
                Optional.<String>empty(), // flag path doesn't resolve to a specific install
                resolvedMain,
                shadow,
                nativeImage,
                spring,
                resolvedLang,
                resolvedLayout,
                resolvedKotlinModule,
                resolvedDeps,
                true,
                target);
    }

    /**
     * The wizard's pre-seed value for the "Project name" answer when the user already supplied the
     * answer via the positional arg.
     *
     * <ul>
     *   <li>{@code "."} → cwd's leaf name.
     *   <li>{@code "my-project"} → {@code "my-project"} (the file-name of the path, in case the user
     *       typed a relative/absolute path).
     *   <li>{@code null} (no positional) → empty — wizard asks the user, defaulting to {@code
     *       "untitled"}.
     * </ul>
     *
     * Package-private for unit testing.
     */
    static Optional<String> wizardPresetName(Path directoryArg, Path cwd) {
        if (directoryArg == null) return Optional.empty();
        if (isCurrentDirArg(directoryArg)) {
            var leaf = cwd.getFileName();
            if (leaf == null) return Optional.empty();
            var s = leaf.toString();
            return (s.isBlank() || s.equals(".")) ? Optional.empty() : Optional.of(s);
        }
        var leaf = directoryArg.getFileName();
        if (leaf == null) return Optional.empty();
        var s = leaf.toString();
        return (s.isBlank() || s.equals(".")) ? Optional.empty() : Optional.of(s);
    }

    /**
     * Final target directory, given the positional arg, the cwd, and the resolved project name.
     * Package-private for unit testing.
     */
    static Path resolveTarget(Path directoryArg, Path cwd, String projectName) {
        if (directoryArg != null) {
            if (isCurrentDirArg(directoryArg)) return cwd;
            return cwd.resolve(directoryArg).normalize();
        }
        // No positional → create a subdir under cwd named after the project.
        return cwd.resolve(projectName);
    }

    /** Match the literal {@code "."} forms the user might type. */
    private static boolean isCurrentDirArg(Path arg) {
        var raw = arg.toString();
        return raw.equals(".") || raw.equals("./") || raw.equals(".\\");
    }

    private static void emitProjectExistsError(String coord, boolean isModule, boolean isInit, Terminal terminal) {
        Theme t = Theme.active();
        boolean nerdfont = dev.jkbuild.config.GlobalConfig.nerdfont();
        String noun = isModule ? "module" : "project";

        // Style the coord: group:name in coordGroup/coordName; bare name in coordName.
        int colon = coord.indexOf(':');
        String coordStyled = colon > 0
                ? Theme.colorize(coord.substring(0, colon), t.coordGroup())
                        + ":"
                        + Theme.colorize(coord.substring(colon + 1), t.coordName())
                : Theme.colorize(coord, t.coordName());

        // Line 1: ‼ The group:name project already exists in this directory.
        String warnLine = Theme.colorize(Glyphs.BANG, t.warning())
                + " The " + coordStyled + " " + noun + " already exists in this directory.";

        // Line 2: ✘ New Project  Failed to create project large. Project already exists.
        // Use chipLine (not failureLine) — failureLine auto-prepends "Failed to {verb}"
        // which would double up if the tail also starts with "Failed to".
        String chipVerb = isModule ? "New Module" : "New Project";
        String bareName = colon > 0 ? coord.substring(colon + 1) : coord;
        String failTail = "Failed to " + (isInit ? "initialize" : "create") + " " + noun
                + " " + bareName + ". Project already exists.";
        String chipLine = dev.jkbuild.cli.tui.GoalWedge.chipLine(
                Glyphs.CROSS, chipVerb, nerdfont, failTail);

        if (terminal != null) {
            var writer = terminal.writer();
            writer.println(warnLine);
            writer.println(chipLine);
            writer.flush();
        } else {
            CliOutput.err(warnLine);
            CliOutput.err(chipLine);
        }
    }

    /** Same styling as {@link #emitProjectExistsError} for the no-JDK case. */
    private static void emitNoJdksError() {
        var warn = Theme.active().warning();
        var label = Theme.active().activeStep();
        var body = Theme.active().normalGray();
        CliOutput.err(Theme.colorize(Glyphs.BANG, warn)
                + " "
                + Theme.colorize("Jk", label)
                + Theme.colorize(": No JDKs found on this system. Run ", body)
                + Theme.colorize("jk jdk install", warn)
                + Theme.colorize(" first, then re-run ", body)
                + Theme.colorize("jk new", warn)
                + Theme.colorize(".", body));
    }

    private static NewInputs.Language parseLanguage(String value) {
        if (value == null || value.isBlank()) {
            return NewInputs.Language.JAVA;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "java" -> NewInputs.Language.JAVA;
            case "kotlin", "kt" -> NewInputs.Language.KOTLIN;
            default -> throw new IllegalArgumentException("jk new: --lang must be 'java' or 'kotlin', got: " + value);
        };
    }

    private static List<String> parseDeps(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        var out = new ArrayList<String>();
        for (var part : csv.split(",")) {
            var trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static int parseJdkMajorOrDefault(String jdk) {
        return NewJdkOptions.parseMajor(jdk).orElse(25);
    }

    /** Current Java LTS feature release. Bumped on each new LTS. */
    static final int LATEST_LTS_MAJOR = 25;

    /** The user's global default JDK identifier, or empty (best-effort — never throws). */
    private static Optional<String> readDefaultJdk() {
        try {
            return dev.jkbuild.jdk.GlobalDefaultJdk.current().currentIdentifier();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * Resolve an explicit {@code --jdk <spec>} into its major + {@code jk.toml} pin. Keywords ({@code
     * lts} / {@code stable} / {@code latest}) resolve to a major against the JetBrains feed (falling
     * back to the latest LTS offline) and always pin a bare major; a {@code <vendor>-<major>} spec
     * keeps its vendor; a bare major stays bare. Throws {@link IllegalArgumentException} (caught by
     * the flag path) on a point release or a spec with no major.
     */
    private NewJdkPlan.Spec resolveJdkArg(String arg) {
        String a = arg.trim();
        if (dev.jkbuild.jdk.JdkKeywords.isKeyword(a) && !"native".equalsIgnoreCase(a)) {
            String os = dev.jkbuild.jdk.HostPlatform.currentOs();
            String arch = dev.jkbuild.jdk.HostPlatform.currentArch();
            int major = fetchCatalogQuiet()
                    .flatMap(c -> dev.jkbuild.jdk.JdkKeywords.resolveToMajorSpec(c, a, os, arch))
                    .map(dev.jkbuild.model.JkBuild.Project::majorOf)
                    .filter(m -> m > 0)
                    .orElse(LATEST_LTS_MAJOR);
            return new NewJdkPlan.Spec(major, Integer.toString(major));
        }
        return NewJdkPlan.parseExplicit(a);
    }

    /**
     * Best-effort catalog fetch for the wizard's "Select a JDK" step. Network failures (offline, DNS,
     * 5xx) degrade to an empty optional rather than killing the wizard: the user still sees whatever
     * installs are on disk.
     */
    private static Optional<dev.jkbuild.jdk.JdkCatalog> fetchCatalogQuiet() {
        try {
            return Optional.of(new dev.jkbuild.jdk.JdkCatalogClient().fetch());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * Resolve the wizard's {@code jdk} answer back to a candidate. The candidate's {@code id()}
     * matches one of the entries we surfaced via {@link NewJdkCandidate#filter}, so this is just a
     * lookup.
     */
    private NewJdkCandidate pickCandidate(Answers answers, List<NewJdkCandidate> candidates) {
        if (answers.has("jdk")) { // the "Select a JDK" step ran
            var pickedId = answers.get("jdk");
            return candidates.stream()
                    .filter(c -> c.id().equals(pickedId))
                    .findFirst()
                    .orElseGet(() -> candidates.getFirst());
        }
        // Step was skipped — resolve silently: inherit the parent's major
        // (module), adopt the global default's major, else auto-pick by the
        // chosen Java level (sole eligible install, or lts to install).
        int preferred = parent != null
                ? (parent.jdkMajor() > 0 ? parent.jdkMajor() : parent.javaRelease())
                : defaultJdk.map(dev.jkbuild.model.JkBuild.Project::majorOf).orElse(0);
        int floor = jdkFloor(answers, parent);
        return NewJdkPlan.autoCandidate(candidates, floor, preferred, LATEST_LTS_MAJOR)
                .orElseGet(() -> candidates.getFirst());
    }

    /**
     * Download + extract an installable candidate. Reuses the same progress UI as {@code jk jdk
     * install}. On success, returns the freshly-resolved installed candidate (so its {@code home()}
     * points at the new JDK). On failure, prints the error and returns empty so the caller exits.
     */
    private Optional<NewJdkCandidate> installCandidate(NewJdkCandidate candidate) {
        if (!(candidate instanceof NewJdkCandidate.Installable installable)) {
            return Optional.of(candidate);
        }
        try {
            var entry = installable.entry();
            var installer =
                    new dev.jkbuild.jdk.JdkInstaller(new dev.jkbuild.http.Http(), new dev.jkbuild.jdk.JdkRegistry());
            // Download (progress bar) then extract (spinner).
            var label = entry.vendor() + " " + entry.product() + " " + entry.majorVersion();
            long total = entry.archiveSize();
            try (var pb = dev.jkbuild.cli.tui.JdkDownloadBar.show(CliOutput.stdout(), label)) {
                var dl = installer.download(entry, bytes -> pb.update(bytes, total));
                pb.finish();
                try (var sp = dev.jkbuild.cli.tui.Spinner.show(CliOutput.stdout(), "Installing " + label + "...")) {
                    var installed = installer.extractInstalled(entry, dl);
                    CliOutput.out("✓ Installed " + label + " → " + installed.home());
                    var opt = new NewJdkOptions.Option(
                            installed.identifier(),
                            installed.identifier() + "  (JDK " + entry.majorVersion() + ")",
                            installed.home(),
                            entry.majorVersion(),
                            "jk");
                    return Optional.of(new NewJdkCandidate.Installed(opt, installable.vendor()));
                }
            }
        } catch (Exception e) {
            CliOutput.err("jk new: failed to install JDK: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Fully-qualified main class name for an executable project — must match where {@link
     * NewScaffolder} actually writes the file.
     *
     * <ul>
     *   <li>Java (both layouts) → {@code <group>.Main}; the scaffolder always packages {@code Main}
     *       under {@code <group>}, even in the simple layout (it just lives in {@code src/<group>/}
     *       rather than {@code src/main/java/<group>/}).
     *   <li>Kotlin compact → {@code MainKt} (no package; Kotlin emits a {@code FilenameKt} synthetic
     *       class for top-level {@code fun main}).
     *   <li>Kotlin standard → {@code <group>.MainKt}.
     * </ul>
     */
    private static String deriveMainFqcn(String group, NewInputs.Language lang, boolean compact) {
        return switch (lang) {
            case JAVA -> group + ".Main";
            case KOTLIN -> compact ? "MainKt" : group + ".MainKt";
        };
    }

    private static Wizard buildWizard(
            List<NewJdkCandidate> candidates,
            dev.jkbuild.jdk.JdkCatalog catalog,
            String groupGuess,
            ParentInfo parent,
            boolean hasDefaultJdk,
            boolean isInit) {
        boolean module = parent != null;
        // Modules inherit the parent's group, JDK, and language as defaults; a
        // standalone project guesses the group and defaults to the latest LTS.
        String effectiveGroup = module ? parent.group() : groupGuess;
        String langDefault = (module && parent.kotlin()) ? "kotlin" : "java";

        // The wizard opens with the "native" toggle off, so the initial radio
        // list is whatever filter() produces for the non-native case — which
        // promotes Temurin LTS to the top. Take the default selection from
        // there so the preselected row matches what the user sees. For a
        // module, prefer the candidate matching the parent's JDK major.
        var initial = NewJdkCandidate.filter(candidates, false, LATEST_LTS_MAJOR);
        if (initial.isEmpty()) initial = candidates;
        var defaultJdkId = initial.getFirst().id();
        if (module) {
            defaultJdkId = candidates.stream()
                    .filter(c -> c.major() == parent.jdkMajor())
                    .map(NewJdkCandidate::id)
                    .findFirst()
                    .orElse(defaultJdkId);
        }

        var javaLayoutStep = WizardStep.RadioStep.vertical("layout", "Project layout:")
                .choice("simple", "Simple (sources in ./src, tests in ./test)")
                .choice("traditional", "Traditional (sources in ./src/main/java, tests in ./src/test/java)")
                .defaultChoice("simple")
                .when(a -> "java".equals(a.get("lang")))
                .build();

        var kotlinLayoutStep = WizardStep.RadioStep.vertical("layout", "Project layout:")
                .choice("simple", "Simple (sources in ./src, tests in ./test)")
                .choice("traditional", "Traditional (sources in ./src/main/kotlin, tests in ./src/test/kotlin)")
                .defaultChoice("simple")
                .when(a -> "kotlin".equals(a.get("lang")))
                .build();

        var javaOptions = WizardStep.MultiSelectStep.vertical("javaOptions", "Include common libraries:")
                .choice("lombok", "Lombok (boilerplate reduction)")
                .choice("jspecify", "JSpecify (null-safety)")
                .defaults(java.util.Set.of("lombok", "jspecify"))
                .when(a -> "java".equals(a.get("lang")))
                .build();

        var kotlinOptions = WizardStep.MultiSelectStep.vertical("kotlinOptions", "Include common libraries:")
                .choice("module", "Set module name")
                .choice("kotest", "Kotest (unit testing)")
                .defaults(java.util.Set.of("module", "kotest"))
                .when(a -> "kotlin".equals(a.get("lang")))
                .build();

        var buildTargets = WizardStep.MultiSelectStep.vertical("targets", "Build output:")
                .choice("jar", "Regular jar")
                .choice("shadow", "Shadow (fat) jar")
                .choice("native", "Native binary")
                .defaults(java.util.Set.of("jar"))
                .when(a -> "executable".equals(a.get("kind")))
                .build();

        // Java projects pick their language version (the `java = N` target)
        // before choosing a JDK — it shapes the JDK list (you can't target a
        // release newer than the toolchain). Modules inherit the parent's
        // release, and when a global default JDK is set we adopt its major, so
        // both skip this question. Kotlin projects skip it too.
        //
        // Two tracks, both derived from the live jdks.json catalog (so a newly
        // published major appears automatically): a standard track over all
        // distributions, and a native track restricted to native-image-capable
        // (GraalVM) JDKs — you can't build a native binary against a Java release
        // no GraalVM ships yet (e.g. 26 today). `targets` (the native choice) is
        // asked before this step, so the choicesFn can read it per render. When
        // the catalog is unavailable (offline) we fall back to constants.
        String os = dev.jkbuild.jdk.HostPlatform.currentOs();
        String arch = dev.jkbuild.jdk.HostPlatform.currentArch();
        List<Integer> standardMajors = orElseList(
                dev.jkbuild.jdk.SupportedJdk.offerableMajors(catalog, false, os, arch), offlineMajors(false));
        List<Integer> nativeMajors = orElseList(
                dev.jkbuild.jdk.SupportedJdk.offerableMajors(catalog, true, os, arch), offlineMajors(true));
        var javaVersion = WizardStep.RadioStep.horizontal("javaVersion", "Java Language Version:")
                .choicesFn(a -> (a.getList("targets").contains("native") ? nativeMajors : standardMajors).stream()
                        .map(m -> new dev.jkbuild.cli.tui.Choice(String.valueOf(m), String.valueOf(m)))
                        .toList())
                .defaultChoice(String.valueOf(LATEST_LTS_MAJOR))
                .when(a -> "java".equals(a.get("lang")) && !module && !hasDefaultJdk)
                .build();

        // Dynamic choices: the JDKs that can compile the chosen Java release
        // (major >= the target), in the full preference order (installed plus
        // auto-installable latest-LTS rows). This is the *build* JDK only —
        // native projects do NOT pick a GraalVM here. The native-image GraalVM
        // is resolved automatically (latest Oracle GraalVM) into jk.lock when
        // project.native is set, so the toolchain choice stays decoupled from
        // the Java language version. Rebuilt per render so changing the language
        // version refreshes the list; empty results fall back so the user can
        // still progress.
        //
        // Only shown when there's a real choice to make: a standalone project,
        // no global default JDK, and more than one eligible installed JDK for
        // the chosen Java level. Modules inherit the parent; a default JDK is
        // adopted silently; 0/1 eligible resolves without asking (see
        // pickCandidate / NewJdkPlan).
        var jdkStep = WizardStep.RadioStep.vertical("jdk", "Select a JDK:")
                .choicesFn(answers -> {
                    int floor = jdkFloor(answers, parent);
                    var filtered = NewJdkCandidate.filter(candidates, false, LATEST_LTS_MAJOR).stream()
                            .filter(c -> c.major() >= floor)
                            .toList();
                    if (filtered.isEmpty()) {
                        filtered = candidates.stream()
                                .filter(c -> c.major() >= floor)
                                .toList();
                    }
                    if (filtered.isEmpty()) filtered = candidates;
                    return filtered.stream()
                            .map(c -> new dev.jkbuild.cli.tui.Choice(c.id(), c.label(), c.hint()))
                            .toList();
                })
                .when(a -> NewJdkPlan.shouldPrompt(module, hasDefaultJdk, candidates, jdkFloor(a, parent)))
                .defaultChoice(defaultJdkId);

        String wizardSubtitle = module
                ? "Create a new module for " + parent.displayName()
                : isInit ? "Initialize this project" : "Create a new project";
        return Wizard.builder()
                .verb(module ? "New Module" : isInit ? "Init" : "New Project")
                .subtitle(wizardSubtitle)
                .step(WizardStep.InputStep.of("name", module ? "Module name:" : "Project name:")
                        .placeholder("untitled")
                        .defaultValue("untitled")
                        .build())
                .step(WizardStep.InputStep.of("group", module ? "Module group:" : "Project group:")
                        .placeholder(effectiveGroup)
                        .defaultValue(effectiveGroup)
                        .build())
                .step(WizardStep.RadioStep.horizontal("kind", "Project type:")
                        .choice("executable", "Executable")
                        .choice("library", "Library")
                        .defaultChoice("executable")
                        .build())
                .step(buildTargets)
                // Language first, then (for Java) the language version, then the
                // JDK shaped by that version.
                .step(WizardStep.RadioStep.horizontal("lang", "Project language:")
                        .choice("java", "Java")
                        .choice("kotlin", "Kotlin")
                        .defaultChoice(langDefault)
                        .build())
                .step(javaVersion)
                .step(jdkStep.build())
                .step(javaLayoutStep)
                .step(kotlinLayoutStep)
                .step(javaOptions)
                .step(kotlinOptions)
                .build();
    }

    /**
     * Lowest JDK feature-release the "Select a JDK" step may offer: a JDK can't compile a release
     * newer than itself. A module inherits the parent's {@code java} target; a standalone Java
     * project uses the chosen Java Language Version; Kotlin (no Java target) imposes no floor.
     */
    /** {@code primary} unless it's empty, in which case {@code fallback} (the offline default). */
    private static List<Integer> orElseList(List<Integer> primary, List<Integer> fallback) {
        return primary.isEmpty() ? fallback : primary;
    }

    /**
     * Offline language-major list for one wizard track, from the compiled-in constants — used only
     * when the catalog can't be fetched. LTS majors down to {@link dev.jkbuild.jdk.SupportedJdk#MIN_MAJOR},
     * then (standard track only) the latest non-LTS stable. The native track caps at the latest LTS,
     * since GraalVM tracks LTS releases and we can't know a newer native-capable major offline.
     */
    private static List<Integer> offlineMajors(boolean nativeTrack) {
        List<Integer> out = new java.util.ArrayList<>();
        for (int v = dev.jkbuild.jdk.JdkLts.OFFLINE_LATEST_LTS; v >= dev.jkbuild.jdk.SupportedJdk.MIN_MAJOR; v--) {
            if (dev.jkbuild.jdk.JdkLts.isLtsMajor(v)) out.add(v);
        }
        int latestStable = dev.jkbuild.jdk.JdkLts.OFFLINE_LATEST_STABLE;
        if (!nativeTrack && latestStable > dev.jkbuild.jdk.JdkLts.OFFLINE_LATEST_LTS) out.add(latestStable);
        return out;
    }

    /** The newest native-image-capable (GraalVM) Java major, from the catalog or the offline cap. */
    private static int maxNativeMajor(dev.jkbuild.jdk.JdkCatalog catalog) {
        List<Integer> majors = orElseList(
                dev.jkbuild.jdk.SupportedJdk.offerableMajors(
                        catalog,
                        true,
                        dev.jkbuild.jdk.HostPlatform.currentOs(),
                        dev.jkbuild.jdk.HostPlatform.currentArch()),
                offlineMajors(true));
        return majors.stream().mapToInt(Integer::intValue).max().orElse(dev.jkbuild.jdk.JdkLts.OFFLINE_LATEST_LTS);
    }

    static int jdkFloor(Answers answers, ParentInfo parent) {
        if (parent != null) return parent.javaRelease();
        if ("kotlin".equalsIgnoreCase(answers.get("lang"))) return 0;
        String v = answers.get("javaVersion");
        if (v != null && !v.isBlank()) {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return LATEST_LTS_MAJOR;
    }

    private NewInputs fromAnswers(Answers answers, Path cwd, NewJdkOptions.Option pickedOpt) {
        var resolvedName = answers.has("name") && !answers.get("name").isBlank()
                ? answers.get("name")
                : wizardPresetName(directory, cwd).orElse("untitled");
        Path target = resolveTarget(directory, cwd, resolvedName);
        var resolvedGroup = answers.has("group") && !answers.get("group").isBlank()
                ? answers.get("group")
                : parent != null ? parent.group() : "com.example";

        // Resolve the JDK: a module inherits the parent's major; a global
        // default JDK is adopted; otherwise it's the candidate the user picked
        // (or the one auto-resolved when the "Select a JDK" step was skipped).
        // The pin written to jk.toml is always the bare major — the wizard never
        // emits a vendor (that's reserved for an explicit `--jdk <vendor>-<major>`).
        int resolvedJdkMajor;
        Optional<String> resolvedJdkIdentifier;
        if (parent != null) {
            resolvedJdkMajor = parent.jdkMajor() > 0 ? parent.jdkMajor() : parent.javaRelease();
            resolvedJdkIdentifier = Optional.empty(); // modules write no lock
        } else if (defaultJdk.isPresent()) {
            resolvedJdkMajor = dev.jkbuild.model.JkBuild.Project.majorOf(defaultJdk.get());
            resolvedJdkIdentifier = defaultJdk;
        } else {
            resolvedJdkMajor = pickedOpt.major();
            resolvedJdkIdentifier = Optional.of(pickedOpt.id());
        }
        var resolvedJdk = Integer.toString(resolvedJdkMajor);
        // Compile target: a module inherits the parent's; a standalone Java
        // project uses the Java Language Version it was asked for; otherwise
        // (Kotlin, or a default-JDK project that skipped the version step) it
        // falls back to the chosen JDK's major.
        int resolvedJavaRelease;
        if (parent != null) {
            resolvedJavaRelease = parent.javaRelease();
        } else if ("java".equalsIgnoreCase(answers.get("lang"))
                && answers.has("javaVersion")
                && !answers.get("javaVersion").isBlank()) {
            resolvedJavaRelease = parseJdkMajorOrDefault(answers.get("javaVersion"));
        } else {
            resolvedJavaRelease = resolvedJdkMajor;
        }

        var resolvedLang =
                "kotlin".equalsIgnoreCase(answers.get("lang")) ? NewInputs.Language.KOTLIN : NewInputs.Language.JAVA;
        var isExecutable = "executable".equals(answers.get("kind"));

        var targets = answers.getList("targets");
        boolean resolvedShadow = isExecutable && targets.contains("shadow");
        boolean resolvedNative = isExecutable && targets.contains("native");

        // Layout comes from its own dedicated step; default to "simple" if not answered.
        String resolvedLayout =
                answers.has("layout") && !answers.get("layout").isBlank() ? answers.get("layout") : "simple";
        Optional<String> resolvedKotlinModule = Optional.empty();
        var deps = new ArrayList<String>();
        if (resolvedLang == NewInputs.Language.JAVA) {
            var javaOpts = answers.getList("javaOptions");
            if (javaOpts.contains("lombok")) deps.add("lombok");
            if (javaOpts.contains("jspecify")) deps.add("jspecify");
        } else {
            var kotlinOpts = answers.getList("kotlinOptions");
            if (kotlinOpts.contains("module")) {
                resolvedKotlinModule = Optional.of(resolvedName);
            }
            if (kotlinOpts.contains("kotest")) deps.add("kotest");
        }

        var resolvedMain = isExecutable
                ? Optional.of(deriveMainFqcn(resolvedGroup, resolvedLang, "simple".equals(resolvedLayout)))
                : Optional.<String>empty();

        return new NewInputs(
                resolvedGroup,
                resolvedName,
                resolvedJdk,
                resolvedJdkMajor,
                resolvedJavaRelease,
                resolvedJdkIdentifier,
                resolvedMain,
                resolvedShadow,
                resolvedNative,
                resolvedLang,
                resolvedLayout,
                resolvedKotlinModule,
                deps,
                true,
                target);
    }

    private static void emitSuccessOnTerminal(NewInputs inputs, Terminal terminal, Module module, boolean isInit) {
        var writer = terminal.writer();
        writer.println(successLine(inputs, module, isInit));
        writer.flush();
    }

    private static void emitSuccessPlain(NewInputs inputs, Module module, boolean isInit) {
        CliOutput.out(successLine(inputs, module, isInit));
    }

    private static String successLine(NewInputs inputs, Module module, boolean isInit) {
        boolean nerdfont = dev.jkbuild.config.GlobalConfig.nerdfont();
        org.jline.utils.AttributedStyle accent = Theme.active().brightCyan().bold();
        if (module != null) {
            String message = "New module "
                    + Theme.colorize(inputs.name(), accent)
                    + Theme.colorize(" added to project ", Theme.active().normalGray())
                    + Theme.colorize(module.projectName(), accent);
            return dev.jkbuild.cli.tui.GoalWedge.chipLine(
                    dev.jkbuild.cli.tui.Glyphs.CHECK, "New Module", nerdfont, message);
        }
        String chipVerb = isInit ? "Init" : "New Project";
        String action = isInit ? "Initialized" : "Created new";
        String message = action + " project " + Theme.colorize(inputs.name(), accent);
        return dev.jkbuild.cli.tui.GoalWedge.chipLine(
                dev.jkbuild.cli.tui.Glyphs.CHECK, chipVerb, nerdfont, message);
    }

    // Suppress unused warning for the import we keep for clarity.
    @SuppressWarnings("unused")
    private static final List<String> CURATED_IDS =
            Arrays.asList("lombok", "jspecify", "kotest", "commons-lang", "commons-io", "guava");
}
