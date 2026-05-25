// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.tui.Spinner;
import dev.jkbuild.cli.tui.Theme;
import dev.jkbuild.jdk.GlobalDefaultJdk;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.jdk.JdkRegistry;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStyle;
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
 * wizard listing every install across every probe.
 *
 * <p>The single-target form requires <em>both</em> a source name (matching
 * one of the probe names — {@code intellij}, {@code sdkman}, {@code jbang},
 * {@code mise}, {@code asdf}, {@code jenv}, {@code homebrew}, {@code system},
 * {@code java-home}) and a full spec (not a bare major). Examples:
 * <pre>
 *   jk jdk uninstall intellij/temurin-26.0.1   # works
 *   jk jdk uninstall jbang/temurin-17.0.19     # works
 *   jk jdk uninstall sdkman/25                 # rejected — bare major
 *   jk jdk uninstall 25.0.3-tem                # rejected — no source
 * </pre>
 *
 * <p>Confirms before deleting unless {@code --yes} is passed. Confirmation
 * defaults to "yes" on Enter; any non-{@code y} response aborts.
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
        if (!confirmDeletion(source, spec)) {
            System.out.println("Aborted.");
            return 0;
        }

        Path installDir = jdkInstallDir(hit.home());
        InstalledJdk installed = new InstalledJdk(JdkRegistry.identifierFor(hit.home()), hit.home());
        try (var sp = Spinner.show(System.out,
                "Deleting " + Theme.colorize(JdkInstallCommand.tildeCollapse(installDir), Theme.warning())
                        + "...")) {
            registry.purge(installed);
        }
        System.out.println(
                Theme.colorize("✓", Theme.completedStep())
                        + " " + Theme.colorize(spec, Theme.focused())
                        + " from " + Theme.colorize(source, Theme.focused()));

        // Maintain the legacy reconcile flow for the global default symlink.
        reconcileDefaultAfterRemoval(registry, defaults, List.of(installed), Optional.empty());
        return 0;
    }

    /**
     * Prompts the user with a yellow warning glyph and returns {@code true}
     * on Enter or {@code y}/{@code Y}. {@code --yes} short-circuits the
     * prompt; on a non-TTY without {@code --yes} we already refuse earlier,
     * so this is only reached interactively.
     */
    private boolean confirmDeletion(String source, String spec) throws IOException {
        if (assumeYes) return true;
        System.out.print(
                Theme.colorize("⚠", Theme.warning().bold())
                        + " Are you sure you want to delete " + source + "/" + spec + "? [Y/n] ");
        System.out.flush();
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line = reader.readLine();
        if (line == null) return false;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return true; // Enter → default Y
        return trimmed.equalsIgnoreCase("y") || trimmed.equalsIgnoreCase("yes");
    }

    /** Install directory containing the home (handles macOS {@code Contents/Home} unwrap). */
    private static Path jdkInstallDir(Path home) {
        return dev.jkbuild.jdk.IntellijJdkDir.installDirOf(home);
    }

    private Integer runWizard(JdkRegistry registry, GlobalDefaultJdk defaults) throws IOException {
        List<InstalledJdk> installed = registry.list();
        if (installed.isEmpty()) {
            System.err.println("jk jdk uninstall: no JDKs installed under " + registry.jdksRoot());
            return 0;
        }
        Optional<String> currentDefault = defaults.currentIdentifier();

        Terminal terminal;
        try {
            terminal = TerminalBuilder.builder().system(true).build();
        } catch (IOException e) {
            throw new IOException("failed to open terminal: " + e.getMessage(), e);
        }
        try (terminal) {
            Optional<JdkUninstallWizard.Result> outcome =
                    JdkUninstallWizard.run(installed, currentDefault, terminal);
            if (outcome.isEmpty()) {
                return 130; // wizard cancelled
            }
            List<InstalledJdk> victims = outcome.get().victims();
            if (victims.isEmpty()) {
                System.out.println("Nothing selected — no JDKs removed.");
                return 0;
            }
            for (InstalledJdk v : victims) {
                executeWizardVictim(v, registry);
            }
            reconcileDefaultAfterRemoval(registry, defaults, victims, outcome.get().newDefault());
            return 0;
        }
    }

    /** Wizard-path uninstaller. Same shape as the legacy executeOne. */
    private static void executeWizardVictim(InstalledJdk jdk, JdkRegistry registry) throws IOException {
        String label = jdk.identifier();
        Path dir = jdk.home();
        try (var sp = Spinner.show(System.out,
                Theme.colorize("Uninstalling ", Theme.normalGray())
                        + Theme.colorize(label, Theme.focused()))) {
            registry.remove(label);
        }
        System.out.println(
                Theme.colorize("✓", Theme.completedStep())
                        + " " + Theme.colorize(label, Theme.focused())
                        + " " + Theme.colorize("has been uninstalled from", Theme.normalGray())
                        + " " + Theme.colorize(
                                JdkInstallCommand.tildeCollapse(dir), Theme.warning()));
    }

    /**
     * After uninstalls execute, fix up the global default symlink/config:
     * <ul>
     *   <li>Default not affected → nothing to do.</li>
     *   <li>All JDKs removed → clear the default (best-effort).</li>
     *   <li>One survivor → auto-promote it (no prompt was needed).</li>
     *   <li>Multiple survivors → use the user's wizard pick.</li>
     * </ul>
     */
    private static void reconcileDefaultAfterRemoval(
            JdkRegistry registry, GlobalDefaultJdk defaults,
            List<InstalledJdk> victims, Optional<InstalledJdk> userPick) throws IOException {
        Optional<String> currentDefault = defaults.currentIdentifier();
        if (currentDefault.isEmpty()) return;
        boolean defaultRemoved = victims.stream()
                .anyMatch(v -> v.identifier().equals(currentDefault.get()));
        if (!defaultRemoved) return;

        List<InstalledJdk> survivors = registry.list();
        if (survivors.isEmpty()) {
            defaults.clear();
            System.out.println(Theme.colorize(
                    "(no remaining JDKs — global default cleared)", Theme.normalGray()));
            return;
        }
        InstalledJdk promote = userPick.orElseGet(() -> survivors.size() == 1
                ? survivors.getFirst()
                : survivors.getFirst()); // safety net; wizard branch already prompted
        defaults.set(promote);
        System.out.println(
                Theme.colorize("➜ ", Theme.brightGreen())
                        + Theme.colorize(promote.identifier(), Theme.focused())
                        + " " + Theme.colorize("is now the ", Theme.normalGray())
                        + Theme.colorize("default", Theme.focused())
                        + Theme.colorize(" JDK", Theme.normalGray()));
    }

    private static boolean isInteractiveTerminal() {
        return System.console() != null
                && !"dumb".equals(System.getenv("TERM"))
                && System.getenv("CI") == null;
    }
}
