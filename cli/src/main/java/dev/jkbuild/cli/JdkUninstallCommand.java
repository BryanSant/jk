// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.tui.Spinner;
import dev.jkbuild.cli.tui.Theme;
import dev.jkbuild.jdk.GlobalDefaultJdk;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.IntellijJdkDir;
import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.run.PhaseStatus;
import dev.jkbuild.util.JkDirs;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code jk jdk uninstall <source>/<spec>} — source-qualified single-target
 * removal. Without arguments (and on a TTY) opens an interactive checkbox
 * wizard listing every install across every probe with the same
 * {@code <source>/<spec>} contract applied per-row.
 *
 * <p>Both paths funnel through the same per-victim logic:
 * {@link #uninstallOne}. The interactive path asks for confirmation
 * <em>once</em> with a summary of all victims rather than per row.
 *
 * <p>After all deletions, if the global default JDK was among the victims,
 * delegate to {@link JdkDefaultCommand#applyLts} so the next-best LTS on
 * disk becomes the new default automatically.
 *
 * <p>The Goal wraps the actual delete loop + default reconciliation so
 * we get a run-log entry per uninstall. Marked interactive so the
 * progress widget stays out of the way of the spinner + wizard UI.
 */
@Command(name = "uninstall", aliases = {"remove"},
        description = "Uninstall JDK versions")
public final class JdkUninstallCommand implements Callable<Integer> {

    /**
     * Probe names accepted on the left side of {@code <source>/<spec>}.
     * {@code system} is intentionally excluded — those installs are owned
     * by the OS package manager and jk can't safely remove them.
     */
    private static final Set<String> KNOWN_SOURCES = Set.of(
            "intellij", "sdkman", "jbang", "mise", "asdf", "jenv",
            "homebrew", "java-home");

    /** Probe names jk refuses to uninstall from. Listed for a friendlier error. */
    private static final Set<String> UNINSTALL_FORBIDDEN_SOURCES = Set.of("system");

    @Parameters(arity = "0..1", paramLabel = "<source>/<spec>",
            description = "Source-qualified install (e.g. intellij/temurin-26.0.1). "
                    + "Omit to launch the interactive wizard.")
    String argument;

    @Option(names = {"-y", "--yes"},
            description = "Skip the confirmation prompt.")
    boolean assumeYes;

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> VICTIMS = GoalKey.of("victims", List.class);

    @Override
    public Integer call() throws Exception {
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        GlobalDefaultJdk defaults = GlobalDefaultJdk.current();

        if (argument != null && !argument.isBlank()) {
            return runSingle(registry, defaults);
        }
        if (!isInteractiveTerminal()) {
            System.err.println("jk jdk uninstall: stdin is not a TTY — pass <source>/<spec> "
                    + "(e.g. `jk jdk uninstall intellij/temurin-21.0.5`) or run interactively.");
            return 64; // EX_USAGE
        }
        return runWizard(registry, defaults);
    }

    // --- single-target path -------------------------------------------------

    private Integer runSingle(JdkRegistry registry, GlobalDefaultJdk defaults) throws IOException {
        int slash = argument.indexOf('/');
        if (slash <= 0 || slash == argument.length() - 1) {
            System.err.println("jk jdk uninstall: argument must be `<source>/<spec>` "
                    + "(got `" + argument + "`). Examples: intellij/temurin-26.0.1, "
                    + "jbang/temurin-17.0.19.");
            return 64;
        }
        String source = argument.substring(0, slash);
        String spec = argument.substring(slash + 1);

        if (UNINSTALL_FORBIDDEN_SOURCES.contains(source)) {
            System.err.println("jk jdk uninstall: refusing to remove `" + source
                    + "` installs — they're managed by the OS package manager "
                    + "(use your distro's tooling, e.g. apt/dnf/brew, to remove them).");
            return 64;
        }
        if (!KNOWN_SOURCES.contains(source)) {
            System.err.println("jk jdk uninstall: unknown source `" + source + "` "
                    + "(supported: " + String.join(", ", KNOWN_SOURCES.stream().sorted().toList()) + ")");
            return 64;
        }
        if (spec.matches("\\d+")) {
            System.err.println("jk jdk uninstall: `" + source + "/" + spec
                    + "` — full spec required (e.g. `" + source + "/temurin-" + spec
                    + ".0.1`), not a bare major.");
            return 64;
        }

        Optional<JdkHit> match = registry.findHitBySpec(spec, source);
        if (match.isEmpty()) {
            System.err.println("jk jdk uninstall: no `" + source + "` install matches `"
                    + spec + "` (try `jk jdk list`).");
            return 1;
        }

        JdkHit hit = match.get();
        if (!confirmDeletion(List.of(hit))) {
            System.out.println("Aborted.");
            return 0;
        }
        return runDeleteGoal(List.of(hit), registry, defaults);
    }

    // --- wizard path --------------------------------------------------------

    private Integer runWizard(JdkRegistry registry, GlobalDefaultJdk defaults) throws IOException {
        // OS-package-manager-managed installs (probe source = "system") can't
        // be removed by jk, so they don't belong in the checklist at all.
        List<JdkHit> installed = registry.listHits().stream()
                .filter(h -> !UNINSTALL_FORBIDDEN_SOURCES.contains(h.source()))
                .toList();
        if (installed.isEmpty()) {
            System.err.println("jk jdk uninstall: no removable JDKs installed "
                    + "(system-managed installs aren't supported here).");
            return 0;
        }
        Optional<String> currentDefault = defaults.currentIdentifier();

        Terminal terminal;
        try {
            terminal = TerminalBuilder.builder().system(true).build();
        } catch (IOException e) {
            throw new IOException("failed to open terminal: " + e.getMessage(), e);
        }
        // Keep terminal open through confirmation + deletion: JLine's
        // system(true) terminal owns the native FD 0, and `terminal.close()`
        // closes it. Reading System.in after that throws "Stream Closed".
        // The wizard restores cooked mode before returning, so a plain
        // System.in readLine inside this block works as expected.
        try (terminal) {
            Optional<List<JdkHit>> outcome =
                    JdkUninstallWizard.run(installed, currentDefault, terminal);
            if (outcome.isEmpty()) {
                return 130; // wizard cancelled
            }
            List<JdkHit> victims = outcome.get();
            if (victims.isEmpty()) {
                System.out.println("Nothing selected — no JDKs removed.");
                return 0;
            }
            if (!confirmDeletion(victims)) {
                System.out.println("Aborted.");
                return 0;
            }
            return runDeleteGoal(victims, registry, defaults);
        }
    }

    // --- goal-wrapped delete + reconcile ------------------------------------

    /**
     * One goal per command invocation. The wizard or single-arg path has
     * already settled which hits are victims; the goal does the actual
     * disk work + default-pointer reconciliation. Interactive=true keeps
     * the {@link Spinner} from competing with the framework's bar.
     */
    private Integer runDeleteGoal(List<JdkHit> victims, JdkRegistry registry,
                                  GlobalDefaultJdk defaults) {
        Path cache = JkDirs.cache();

        Phase deletePhase = Phase.builder("delete")
                .kind(PhaseKind.IO)
                .scope(victims.size())
                .execute(ctx -> {
                    ctx.label("delete " + victims.size() + " install"
                            + (victims.size() == 1 ? "" : "s"));
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

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(null), cache);
        if (!result.success()) {
            String failed = result.phases().stream()
                    .filter(p -> p.status() == PhaseStatus.FAIL)
                    .map(GoalResult.PhaseReport::name).findFirst().orElse("?");
            System.err.println("jk jdk uninstall failed: " + failed);
            for (GoalResult.Diagnostic d : result.errors()) {
                System.err.println("  " + d.code() + ": " + d.message());
            }
            System.err.println("Run log: " + cache.resolve("runs"));
            return 1;
        }
        return 0;
    }

    // --- shared mechanics ---------------------------------------------------

    /**
     * Spinner → owning-tool uninstall (best-effort) → {@link JdkRegistry#purge}
     * fallback → {@code "✓ <spec> from <source>"}. The single-target and
     * wizard paths funnel through here, so output shape stays consistent.
     */
    private static void uninstallOne(JdkHit hit, JdkRegistry registry) throws IOException {
        String identifier = JdkRegistry.identifierFor(hit.home());
        Path installDir = IntellijJdkDir.installDirOf(hit.home());
        InstalledJdk installed = new InstalledJdk(identifier, hit.home());
        try (var sp = Spinner.show(System.out,
                "Deleting " + Theme.colorize(JdkInstallCommand.tildeCollapse(installDir), Theme.warning())
                        + "...")) {
            // Try the owning tool first so its manifest stays consistent
            // (sdkman, mise, jbang, jenv, asdf, brew). Anything left on disk
            // after — including the intellij / java-home sources, which
            // have no owning tool — gets the direct purge.
            var outcome = JdkToolUninstaller.tryUninstall(hit, identifier);
            if (outcome == JdkToolUninstaller.Outcome.FALL_THROUGH) {
                registry.purge(installed);
            }
        }
        System.out.println(
                Theme.colorize("✓", Theme.completedStep())
                        + " " + Theme.colorize(identifier, Theme.focused())
                        + " from " + Theme.colorize(hit.source(), Theme.focused()));
    }

    /**
     * Single bulk confirmation. {@code --yes} short-circuits. Returns
     * {@code true} on Enter / {@code y} / {@code yes}; anything else aborts.
     * Caller is responsible for keeping the JLine terminal open across
     * this call (see {@link #runWizard}) — once the terminal closes, the
     * underlying {@code System.in} FD goes with it.
     */
    private boolean confirmDeletion(List<JdkHit> victims) throws IOException {
        if (assumeYes) return true;
        String warn = Theme.colorize("⚠", Theme.warning().bold());
        if (victims.size() == 1) {
            JdkHit h = victims.getFirst();
            System.out.print(warn + " Are you sure you want to delete "
                    + h.source() + "/" + JdkRegistry.identifierFor(h.home()) + "? [Y/n] ");
        } else {
            System.out.println(warn + " Are you sure you want to delete the following "
                    + victims.size() + " JDKs?");
            for (JdkHit h : victims) {
                System.out.println("   " + h.source() + "/" + JdkRegistry.identifierFor(h.home()));
            }
            System.out.print("[Y/n] ");
        }
        System.out.flush();
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line = reader.readLine();
        if (line == null) return false;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return true;
        return trimmed.equalsIgnoreCase("y") || trimmed.equalsIgnoreCase("yes");
    }

    /**
     * If any of the victims was the system default, delegate to
     * {@link JdkDefaultCommand#applyLts} so the next-best LTS install on
     * disk auto-promotes. When no LTS survives we clear the default;
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
                        "(no remaining JDKs — global default cleared)", Theme.normalGray()));
            } else {
                defaults.set(survivors.getFirst());
                System.out.println(Theme.colorize("➜", Theme.brightGreen())
                        + " The " + Theme.colorize("default", Theme.focused())
                        + " JDK is now set to " + Theme.colorize(survivors.getFirst().identifier(), Theme.focused())
                        + " " + Theme.colorize("(non-LTS fallback)", Theme.darkGray()));
            }
        }
    }

    /** Swallow stderr from {@code applyLts} when we're going to fall back ourselves. */
    private static java.io.PrintStream swallow() {
        return new java.io.PrintStream(new java.io.OutputStream() {
            @Override public void write(int b) {}
            @Override public void write(byte[] b, int off, int len) {}
        });
    }

    private static boolean isInteractiveTerminal() {
        return System.console() != null
                && !"dumb".equals(System.getenv("TERM"))
                && System.getenv("CI") == null;
    }
}
