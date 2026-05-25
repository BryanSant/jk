// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.tui.Spinner;
import dev.jkbuild.cli.tui.Theme;
import dev.jkbuild.jdk.GlobalDefaultJdk;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.IntellijJdkDir;
import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.jdk.JdkRegistry;
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
 */
@Command(name = "uninstall", aliases = {"remove"},
        description = "Uninstall JDK versions")
public final class JdkUninstallCommand implements Callable<Integer> {

    /** Probe names accepted on the left side of {@code <source>/<spec>}. */
    private static final Set<String> KNOWN_SOURCES = Set.of(
            "intellij", "sdkman", "jbang", "mise", "asdf", "jenv",
            "homebrew", "system", "java-home");

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

        uninstallOne(hit, registry);
        reconcileDefaultAfterRemoval(registry, defaults, List.of(hit));
        return 0;
    }

    // --- wizard path --------------------------------------------------------

    private Integer runWizard(JdkRegistry registry, GlobalDefaultJdk defaults) throws IOException {
        List<JdkHit> installed = registry.listHits();
        if (installed.isEmpty()) {
            System.err.println("jk jdk uninstall: no JDKs installed.");
            return 0;
        }
        Optional<String> currentDefault = defaults.currentIdentifier();

        Terminal terminal;
        try {
            terminal = TerminalBuilder.builder().system(true).build();
        } catch (IOException e) {
            throw new IOException("failed to open terminal: " + e.getMessage(), e);
        }
        List<JdkHit> victims;
        try (terminal) {
            Optional<List<JdkHit>> outcome =
                    JdkUninstallWizard.run(installed, currentDefault, terminal);
            if (outcome.isEmpty()) {
                return 130; // wizard cancelled
            }
            victims = outcome.get();
        }
        if (victims.isEmpty()) {
            System.out.println("Nothing selected — no JDKs removed.");
            return 0;
        }
        if (!confirmDeletion(victims)) {
            System.out.println("Aborted.");
            return 0;
        }
        for (JdkHit v : victims) {
            uninstallOne(v, registry);
        }
        reconcileDefaultAfterRemoval(registry, defaults, victims);
        return 0;
    }

    // --- shared mechanics ---------------------------------------------------

    /**
     * Spinner → {@link JdkRegistry#purge} → {@code "✓ <spec> from <source>"}.
     * The single-target and wizard paths funnel through here, so output
     * shape stays consistent.
     */
    private static void uninstallOne(JdkHit hit, JdkRegistry registry) throws IOException {
        String identifier = JdkRegistry.identifierFor(hit.home());
        Path installDir = IntellijJdkDir.installDirOf(hit.home());
        InstalledJdk installed = new InstalledJdk(identifier, hit.home());
        try (var sp = Spinner.show(System.out,
                "Deleting " + Theme.colorize(JdkInstallCommand.tildeCollapse(installDir), Theme.warning())
                        + "...")) {
            registry.purge(installed);
        }
        System.out.println(
                Theme.colorize("✓", Theme.completedStep())
                        + " " + Theme.colorize(identifier, Theme.focused())
                        + " from " + Theme.colorize(hit.source(), Theme.focused()));
    }

    /**
     * Single bulk confirmation. {@code --yes} short-circuits. Returns
     * {@code true} on Enter / {@code y} / {@code yes}; anything else aborts.
     *
     * <p>Reads via {@link System#console} when one is attached — important
     * for the wizard path, where JLine's {@code system(true)} terminal will
     * have closed the underlying {@code System.in} stream by the time we
     * get here. Falls back to {@code System.in} for piped / non-TTY runs
     * (tests, CI), where {@code System.console()} returns {@code null}.
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
        String line = readPromptLine();
        if (line == null) return false;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return true;
        return trimmed.equalsIgnoreCase("y") || trimmed.equalsIgnoreCase("yes");
    }

    private static String readPromptLine() throws IOException {
        var console = System.console();
        if (console != null) {
            return console.readLine();
        }
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        return reader.readLine();
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
