// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Ansi;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Confirm;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.cli.tui.Spinner;
import dev.jkbuild.cli.tui.Wizard;
import dev.jkbuild.jdk.GlobalDefaultJdk;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.IntellijJdkDir;
import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.jdk.JdkInstaller;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkToolUninstaller;
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
 * <p>The Goal wraps the actual delete loop + default reconciliation so we get a run-log entry per
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
    private static final GoalKey<List> VICTIMS = GoalKey.of("victims", List.class);

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
            System.err.println("jk jdk uninstall: stdin is not a TTY — pass a spec "
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
            System.err.println("jk jdk uninstall: argument must be `<spec>` or `<source>/<spec>` "
                    + "(got `"
                    + argument
                    + "`). Examples: temurin-26.0.1, intellij/temurin-26.0.1.");
            return 64;
        }

        // Validate an explicitly-supplied source up front.
        if (source != null) {
            if (UNINSTALL_FORBIDDEN_SOURCES.contains(source)) {
                System.err.println(forbiddenSourceMessage(source));
                return 64;
            }
            if (!KNOWN_SOURCES.contains(source)) {
                System.err.println("jk jdk uninstall: unknown source `"
                        + source
                        + "` "
                        + "(supported: "
                        + String.join(", ", KNOWN_SOURCES.stream().sorted().toList())
                        + ")");
                return 64;
            }
        }
        // Keyword specs (lts, stable, latest) → resolve to best installed match.
        if (dev.jkbuild.jdk.JdkKeywords.isKeyword(spec)) {
            var hits = source != null
                    ? registry.listHits().stream()
                            .filter(h -> source.equals(h.source()))
                            .toList()
                    : registry.listHits();
            var kw = dev.jkbuild.jdk.JdkKeywords.bestInstalledMatch(spec, hits);
            if (kw.isEmpty()) {
                System.err.println("jk jdk uninstall: no installed JDK matches `" + spec + "` (try `jk jdk list`).");
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
            System.err.println("jk jdk uninstall: " + where + " (try `jk jdk list`).");
            return 1;
        }

        JdkHit hit = match.get();
        // A bare spec can resolve to a protected install (an OS `system` JDK, or
        // an IDE-registered `intellij` one). Refuse it the same way an explicit
        // `<source>/...` would be — naming the source so the reason is clear.
        if (UNINSTALL_FORBIDDEN_SOURCES.contains(hit.source())) {
            System.err.println(forbiddenSourceMessage(hit.source()));
            return 64;
        }

        // The confirmation prompt names the resolved <source>/<spec>, so a bare
        // spec that matched somewhere unexpected can still be cancelled here.
        if (!confirmDeletion(hit, null)) {
            System.out.println("Aborted.");
            return 0;
        }
        return runDeleteGoal(List.of(hit), registry, defaults);
    }

    // --- wizard path --------------------------------------------------------

    private Integer runWizard(JdkRegistry registry, GlobalDefaultJdk defaults) throws IOException {
        // Installs jk can't remove — OS-package-manager (`system`) and
        // IDE-registered (`intellij`) JDKs — don't belong in the checklist.
        List<JdkHit> installed = registry.listHits().stream()
                .filter(h -> !UNINSTALL_FORBIDDEN_SOURCES.contains(h.source()))
                .toList();
        if (installed.isEmpty()) {
            System.err.println("jk jdk uninstall: no removable JDKs installed "
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
                System.out.println("Aborted.");
                return 0;
            }
            return runDeleteGoal(List.of(victim), registry, defaults);
        }
    }

    // --- goal-wrapped delete + reconcile ------------------------------------

    /**
     * One goal per command invocation. The wizard or single-arg path has already settled which hits
     * are victims; the goal does the actual disk work + default-pointer reconciliation.
     * Interactive=true keeps the {@link Spinner} from competing with the framework's bar.
     */
    private Integer runDeleteGoal(List<JdkHit> victims, JdkRegistry registry, GlobalDefaultJdk defaults) {
        Path cache = JkDirs.cache();

        Phase deletePhase = Phase.builder("delete")
                .kind(PhaseKind.IO)
                .scope(victims.size())
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

        Phase reconcile = Phase.builder("reconcile-default")
                .requires("delete")
                .scope(1)
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

        Goal goal = Goal.builder("jdk-uninstall")
                .interactive(true)
                .addPhase(deletePhase)
                .addPhase(reconcile)
                .build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);
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
                System.out,
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
            // rethrow so the goal records the failure and the exit code is 1.
            System.out.println(Theme.colorize(Glyphs.CROSS, Theme.active().error())
                    + " Failed to remove "
                    + Theme.colorize(label, Theme.active().warning())
                    + "!");
            throw e;
        }
        System.out.println(JdkRender.removed(hit.source(), identifier, dev.jkbuild.config.GlobalConfig.nerdfont()));
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
            System.out.print(Ansi.cursorUp(1) + "\r" + Ansi.ERASE_LINE_TO_END);
            System.out.flush();
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
        boolean repointed = JdkDefaultCommand.applyLts(registry, defaults, System.out, swallow());
        if (!repointed) {
            // No LTS left — at minimum clear the dangling default pointer.
            List<InstalledJdk> survivors = registry.list();
            if (survivors.isEmpty()) {
                defaults.clear();
                System.out.println(Theme.colorize(
                        "(no remaining JDKs — global default cleared)",
                        Theme.active().normalGray()));
            } else {
                defaults.set(survivors.getFirst());
                System.out.println(Theme.colorize("➜", Theme.active().brightGreen())
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
        return System.console() != null && !"dumb".equals(System.getenv("TERM")) && System.getenv("CI") == null;
    }
}
