// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.run.StepNames;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.Ansi;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.run.PipelineConsole;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Confirm;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.Spinner;
import cc.jumpkick.cli.tui.Wizard;
import cc.jumpkick.jdk.GlobalDefaultJdk;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.IntellijJdkDir;
import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkInstaller;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.JdkToolUninstaller;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.PipelineKey;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.Step;
import cc.jumpkick.run.StepKind;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jline.terminal.Terminal;

/**
 * {@code jk jdk uninstall <source>/<spec>} — source-qualified single-target removal. Without
 * arguments (and on a TTY) opens an interactive radio wizard listing every install across every
 * probe with the same {@code <source>/<spec>} contract applied per-row.
 *
 * <p>Both paths funnel through the same per-victim logic: {@link #uninstallOne}. The interactive
 * path asks for confirmation <em>once</em> with a summary of all victims rather than per row.
 *
 * <p>After all deletions, if the global default JDK was among the victims, delegate to {@link
 * JdkDefaultCommand#applyLts} so the next-best LTS on disk becomes the new default automatically.
 *
 * <p>The Pipeline wraps the actual delete loop + default reconciliation so we get a run-log entry per
 * uninstall. Marked interactive so the progress widget stays out of the way of the spinner + wizard
 * UI.
 */
// Take our CliCommand interface + main's expanded aliases and logic
public final class JdkUninstallCommand implements CliCommand {

    @Override
    public String name() {
        return "uninstall";
    }

    @Override
    public java.util.List<String> aliases() {
        return java.util.List.of("remove", "rm", "del");
    }

    @Override
    public String description() {
        return "Uninstall a Java Development Kit";
    }

    @Override
    public java.util.List<Opt> options() {
        return java.util.List.of(
                Opt.flag("Skip the confirmation prompt.", "-y", "--yes"),
                Opt.value("<dir>", "Override the JDK install root. Default: the IntelliJ JDK directory.", "--jdks-dir")
                        .hide());
    }

    @Override
    public java.util.List<Param> parameters() {
        return java.util.List.of(Param.of(
                "spec",
                Arity.ZERO_OR_ONE,
                "The vendor/version of JDK you'd like to uninstall\n"
                        + "  (ex: 25, lts, latest, temurin-25, openjdk-26)"));
    }

    private static final Set<String> KNOWN_SOURCES =
            Set.of("jk", "intellij", "jdks", "sdkman", "jbang", "mise", "asdf", "jenv", "homebrew", "path");

    /**
     * Sources jk refuses to uninstall from — the install's lifecycle belongs to another owner. {@code
     * system} is the OS package manager; {@code intellij} is a JDK an IDE has registered in its
     * {@code jdk.table.xml} (an unmanaged JDK merely sitting in {@code ~/.jdks} is labelled {@code
     * jdks} and stays removable). See {@link #forbiddenSourceMessage}.
     */
    private static final Set<String> UNINSTALL_FORBIDDEN_SOURCES = Set.of("system", "intellij");

    /** Explain why a forbidden {@code source} can't be removed by jk. */
    private static String forbiddenSourceMessage(String source) {
        return switch (source) {
            case "intellij" ->
                "jk jdk uninstall: `"
                        + source
                        + "` JDKs are managed by your IDE — "
                        + "remove this one through IntelliJ (Project Structure ▸ SDKs, or the "
                        + "Download JDK list), not jk.";
            default ->
                "jk jdk uninstall: refusing to remove `"
                        + source
                        + "` installs — they're managed by the OS package manager "
                        + "(use your distro's tooling, e.g. apt/dnf/brew, to remove them).";
        };
    }

    String argument;
    boolean assumeYes;
    Path jdksDir;
    GlobalOptions global;

    @SuppressWarnings("rawtypes")
    private static final PipelineKey<List> VICTIMS = PipelineKey.of("victims", List.class);

    @Override
    public int run(Invocation in) throws Exception {
        this.argument = in.positionals().isEmpty() ? null : in.positionals().get(0);
        this.assumeYes = in.isSet("yes");
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.global = GlobalOptions.from(in);

        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        // Reclaim any partial archive left by a previously canceled download.
        JdkInstaller.sweepStaleDownloads(registry.jdksRoot());
        GlobalDefaultJdk defaults = GlobalDefaultJdk.current();

        if (argument != null && !argument.isBlank()) {
            return runSingle(registry, defaults);
        }
        if (!isInteractiveTerminal()) {
            CliOutput.err("jk jdk uninstall: stdin is not a TTY — pass a spec "
                    + "(e.g. `jk jdk uninstall temurin-21.0.5`) or run interactively.");
            return Exit.USAGE;
        }
        return runWizard(registry, defaults);
    }

    // --- single-target path -------------------------------------------------

    private Integer runSingle(JdkRegistry registry, GlobalDefaultJdk defaults) throws IOException {
        // `<source>/<spec>` is optional: a slash qualifies which probe's copy to
        // remove, but a bare `<spec>` matches across every source. Specs never
        // contain a slash, so its presence unambiguously marks a source prefix.
        int slash = argument.indexOf('/');
        String source = slash < 0 ? null : argument.substring(0, slash);
        String spec = slash < 0 ? argument : argument.substring(slash + 1);
        if (slash == 0 || slash == argument.length() - 1) {
            CliOutput.err("jk jdk uninstall: argument must be `<spec>` or `<source>/<spec>` "
                    + "(got `"
                    + argument
                    + "`). Examples: temurin-26.0.1, intellij/temurin-26.0.1.");
            return Exit.USAGE;
        }

        // Validate an explicitly-supplied source up front.
        if (source != null) {
            if (UNINSTALL_FORBIDDEN_SOURCES.contains(source)) {
                CliOutput.err(forbiddenSourceMessage(source));
                return Exit.USAGE;
            }
            if (!KNOWN_SOURCES.contains(source)) {
                CliOutput.err("jk jdk uninstall: unknown source `"
                        + source
                        + "` "
                        + "(supported: "
                        + String.join(", ", KNOWN_SOURCES.stream().sorted().toList())
                        + ")");
                return Exit.USAGE;
            }
        }
        // Keyword specs (lts, stable, latest) → resolve to best installed match.
        if (cc.jumpkick.jdk.JdkKeywords.isKeyword(spec)) {
            var hits = source != null
                    ? registry.listHits().stream()
                            .filter(h -> source.equals(h.source()))
                            .toList()
                    : registry.listHits();
            var kw = cc.jumpkick.jdk.JdkKeywords.bestInstalledMatch(spec, hits);
            if (kw.isEmpty()) {
                CliOutput.err("jk jdk uninstall: no installed JDK matches `" + spec + "` (try `jk jdk list`).");
                return 1;
            }
            spec = JdkRegistry.identifierFor(kw.get().home());
        }

        // Source-qualified → that probe only; bare spec → first match in
        // probe-chain order across every source.
        Optional<JdkHit> match = source != null ? registry.findHitBySpec(spec, source) : registry.findHitBySpec(spec);
        if (match.isEmpty()) {
            String where = source != null
                    ? "no `" + source + "` install matches `" + spec + "`"
                    : "no install matches `" + spec + "`";
            CliOutput.err("jk jdk uninstall: " + where + " (try `jk jdk list`).");
            return 1;
        }

        JdkHit hit = match.get();
        // A bare spec can resolve to a protected install (an OS `system` JDK, or
        // an IDE-registered `intellij` one). Refuse it the same way an explicit
        // `<source>/...` would be — naming the source so the reason is clear.
        if (UNINSTALL_FORBIDDEN_SOURCES.contains(hit.source())) {
            CliOutput.err(forbiddenSourceMessage(hit.source()));
            return Exit.USAGE;
        }

        // The confirmation prompt names the resolved <source>/<spec>, so a bare
        // spec that matched somewhere unexpected can still be cancelled here.
        if (!confirmDeletion(hit, null)) {
            CliOutput.out("Aborted.");
            return 0;
        }
        return runDeletePipeline(List.of(hit), registry, defaults);
    }

    // --- wizard path --------------------------------------------------------

    private Integer runWizard(JdkRegistry registry, GlobalDefaultJdk defaults) throws IOException {
        // Installs jk can't remove — OS-package-manager (`system`) and
        // IDE-registered (`intellij`) JDKs — don't belong in the checklist.
        List<JdkHit> installed = registry.listHits().stream()
                .filter(h -> !UNINSTALL_FORBIDDEN_SOURCES.contains(h.source()))
                .toList();
        if (installed.isEmpty()) {
            CliOutput.err("jk jdk uninstall: no removable JDKs installed "
                    + "(system- and IDE-managed installs aren't removable here).");
            return 0;
        }
        Optional<String> currentDefault = defaults.currentIdentifier();

        Terminal terminal;
        try {
            terminal = Wizard.openTerminal();
        } catch (IOException e) {
            throw new IOException("failed to open terminal: " + e.getMessage(), e);
        }
        // Keep terminal open through confirmation + deletion: JLine's
        // system(true) terminal owns the native FD 0, and `terminal.close()`
        // closes it. Reading System.in after that throws "Stream Closed".
        // Wizard.run's finally force-restores ECHO+ICANON on, so a plain
        // System.in readLine inside this block works as expected.
        try (terminal) {
            Optional<JdkHit> outcome = JdkUninstallWizard.run(installed, currentDefault, terminal);
            if (outcome.isEmpty()) {
                // Ctrl-C cancellation. Render the red closer on the active rail,
                // identical to `jk jdk install`. Runtime.halt() (not close())
                // skips shutdown hooks — JLine's cleanup hook blocks on its
                // stdin reader thread that macOS won't let us interrupt; the
                // wizard's finally already restored terminal attributes.
                Wizard.printCancellation(terminal, "JDK uninstall canceled");
                Runtime.getRuntime().halt(130); // 128 + SIGINT
                throw new AssertionError("unreachable");
            }
            JdkHit victim = outcome.get();
            if (!confirmDeletion(victim, terminal)) {
                CliOutput.out("Aborted.");
                return 0;
            }
            return runDeletePipeline(List.of(victim), registry, defaults);
        }
    }

    // --- pipeline-wrapped delete + reconcile ------------------------------------

    /**
     * One pipeline per command invocation. The wizard or single-arg path has already settled which hits
     * are victims; the pipeline does the actual disk work + default-pointer reconciliation.
     * Interactive=true keeps the {@link Spinner} from competing with the framework's bar.
     */
    private Integer runDeletePipeline(List<JdkHit> victims, JdkRegistry registry, GlobalDefaultJdk defaults) {
        Path cache = JkDirs.cache();

        Step deleteStep = Step.builder(StepNames.DELETE)
                .kind(StepKind.IO)
                .ticks(victims.size())
                .execute(ctx -> {
                    ctx.label("delete " + victims.size() + " install" + (victims.size() == 1 ? "" : "s"));
                    for (JdkHit v : victims) {
                        try {
                            uninstallOne(v, registry);
                            ctx.progress(1);
                        } catch (IOException e) {
                            ctx.error("delete", e.getMessage());
                            throw new RuntimeException(e);
                        }
                    }
                    ctx.put(VICTIMS, victims);
                })
                .build();

        Step reconcile = Step.builder(StepNames.RECONCILE_DEFAULT)
                .requires(StepNames.DELETE)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("reconcile default JDK pointer");
                    try {
                        reconcileDefaultAfterRemoval(registry, defaults, victims);
                    } catch (IOException e) {
                        ctx.error("reconcile", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    ctx.progress(1);
                })
                .build();

        Pipeline pipeline = Pipeline.builder("jdk-uninstall")
                .interactive(true)
                .addStep(deleteStep)
                .addStep(reconcile)
                .build();

        PipelineResult result = PipelineConsole.run(pipeline, PipelineConsole.modeFor(global), cache);
        if (!result.success()) return 1;
        return 0;
    }

    // --- shared mechanics ---------------------------------------------------

    /**
     * Spinner → owning-tool uninstall (best-effort) → {@link JdkRegistry#purge} fallback → {@code "✓
     * <spec> from <source>"}. The single-target and wizard paths funnel through here, so output shape
     * stays consistent.
     */
    private static void uninstallOne(JdkHit hit, JdkRegistry registry) throws IOException {
        String identifier = JdkRegistry.identifierFor(hit.home());
        Path installDir = IntellijJdkDir.installDirOf(hit.home());
        InstalledJdk installed = new InstalledJdk(identifier, hit.home());
        // Failure names the resolved `<source>/<identifier>` in yellow; the
        // success line (below) styles it as `[source]/identifier` instead.
        String label = hit.source() + "/" + identifier;
        try (var sp = Spinner.show(
                CliOutput.stdout(),
                "Deleting "
                        + Theme.colorize(
                                JdkInstallCommand.tildeCollapse(installDir),
                                Theme.active().path())
                        + "...")) {
            // Try the owning tool first so its manifest stays consistent
            // (sdkman, mise, jbang, jenv, asdf, brew). Anything left on disk
            // after — including the intellij / java-home sources, which
            // have no owning tool — gets the direct purge.
            var outcome = JdkToolUninstaller.tryUninstall(hit, identifier);
            if (outcome == JdkToolUninstaller.Outcome.FALL_THROUGH) {
                registry.purge(installed);
            }
        } catch (IOException e) {
            // The spinner has already cleared its line; print the failure where
            // the confirmation prompt was (confirmDeletion wiped it for us), then
            // rethrow so the pipeline records the failure and the exit code is 1.
            CliOutput.out(Theme.colorize(Glyphs.CROSS, Theme.active().error())
                    + " Failed to remove "
                    + Theme.colorize(label, Theme.active().warning())
                    + "!");
            throw e;
        }
        CliOutput.out(JdkRender.removed(hit.source(), identifier, cc.jumpkick.config.GlobalConfig.nerdfont()));
    }

    /**
     * Confirmation prompt for a single-target deletion. {@code --yes} short-circuits. Returns {@code
     * true} on Enter / {@code y} / {@code yes}; anything else aborts. Caller is responsible for
     * keeping the JLine terminal open across this call (see {@link #runWizard}) — once the terminal
     * closes, the underlying {@code System.in} FD goes with it.
     */
    private boolean confirmDeletion(JdkHit victim, Terminal terminal) {
        if (assumeYes) return true;
        String warn = Theme.colorize(Glyphs.BANG, Theme.active().warning());
        String question = warn + " Are you sure you want to delete " + target(victim) + "?";
        // Reuse the wizard's already-open terminal when present; the single-spec
        // path has none, so the widget opens its own (and falls back to a cooked
        // read on a non-TTY).
        Confirm confirm = Confirm.of(question, true);
        boolean proceed = terminal != null ? confirm.ask(terminal) : confirm.ask();
        // Erase the question so the ✓/✗ result line (printed by uninstallOne)
        // lands in its place. Confirm left the cursor one row below the prompt,
        // so step back up, return to column 0, and clear to end of line.
        if (proceed && isInteractiveTerminal()) {
            CliOutput.outRaw(Ansi.cursorUp(1) + "\r" + Ansi.ERASE_LINE_TO_END);
            CliOutput.stdout().flush();
        }
        return proceed;
    }

    /** {@code {source}/identifier}: source in italic path color, identifier in plain path color. */
    private static String target(JdkHit h) {
        Theme t = Theme.active();
        String identifier = JdkRegistry.identifierFor(h.home());
        return Theme.colorize("{" + h.source() + "}", t.path().italic())
                + Theme.colorize("/" + identifier, t.path());
    }

    /**
     * If any of the victims was the system default, delegate to {@link JdkDefaultCommand#applyLts} so
     * the next-best LTS install on disk auto-promotes. When no LTS survives we clear the default;
     * applyLts handles the messaging in either case.
     */
    private static void reconcileDefaultAfterRemoval(
            JdkRegistry registry, GlobalDefaultJdk defaults, List<JdkHit> victims) throws IOException {
        Optional<String> currentDefault = defaults.currentIdentifier();
        if (currentDefault.isEmpty()) return;
        boolean defaultRemoved = victims.stream()
                .anyMatch(v -> JdkRegistry.identifierFor(v.home()).equals(currentDefault.get()));
        if (!defaultRemoved) return;

        // Try latest-LTS auto-pick first; fall back to "clear default" if no
        // LTS remains.
        boolean repointed = JdkDefaultCommand.applyLts(registry, defaults, CliOutput.stdout(), swallow());
        if (!repointed) {
            // No LTS left — at minimum clear the dangling default pointer.
            List<InstalledJdk> survivors = registry.list();
            if (survivors.isEmpty()) {
                defaults.clear();
                CliOutput.out(Theme.colorize(
                        "(no remaining JDKs — global default cleared)",
                        Theme.active().normalGray()));
            } else {
                defaults.set(survivors.getFirst());
                CliOutput.out(Theme.colorize("➜", Theme.active().brightGreen())
                        + " The "
                        + Theme.colorize("default", Theme.active().focused())
                        + " JDK is now set to "
                        + Theme.colorize(
                                survivors.getFirst().identifier(),
                                Theme.active().focused())
                        + " "
                        + Theme.colorize(Glyphs.BANG + " (non-LTS fallback)", Theme.active().warning()));
            }
        }
    }

    /** Swallow stderr from {@code applyLts} when we're going to fall back ourselves. */
    private static java.io.PrintStream swallow() {
        return new java.io.PrintStream(new java.io.OutputStream() {
            @Override
            public void write(int b) {}

            @Override
            public void write(byte[] b, int off, int len) {}
        });
    }

    private static boolean isInteractiveTerminal() {
        return cc.jumpkick.cli.tui.Interactivity.canPrompt();
    }
}
