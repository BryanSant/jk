// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.tui.Spinner;
import dev.jkbuild.cli.tui.Theme;
import dev.jkbuild.jdk.GlobalDefaultJdk;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.IntellijJdkDir;
import dev.jkbuild.jdk.JdkRegistry;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk jdk uninstall [<identifier>]} — remove one or more JDKs from
 * the IntelliJ JDK directory. With an explicit identifier this is the
 * single-target non-interactive removal. Without one (and on a TTY) we
 * open a checkbox wizard, optionally followed by a "pick a new default"
 * step when the global default is among the victims and survivors remain.
 */
@Command(name = "uninstall", aliases = {"remove"},
        description = "Uninstall JDK versions")
public final class JdkUninstallCommand implements Callable<Integer> {

    @Parameters(arity = "0..1", paramLabel = "<identifier>",
            description = "Installed JDK identifier (e.g. temurin-21.0.5). "
                    + "Omit to launch the interactive wizard.")
    String identifier;

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @Override
    public Integer call() throws Exception {
        Path jdksRoot = jdksDir != null ? jdksDir : IntellijJdkDir.root();
        JdkRegistry registry = new JdkRegistry(jdksRoot);
        GlobalDefaultJdk defaults = GlobalDefaultJdk.current();

        if (identifier != null && !identifier.isBlank()) {
            return runSingle(registry, defaults);
        }
        if (!isInteractiveTerminal()) {
            System.err.println("jk jdk uninstall: stdin is not a TTY — pass <identifier> "
                    + "(e.g. `jk jdk uninstall temurin-21.0.5`) or run interactively.");
            return 64; // EX_USAGE
        }
        return runWizard(registry, defaults);
    }

    private Integer runSingle(JdkRegistry registry, GlobalDefaultJdk defaults) throws IOException {
        Optional<InstalledJdk> match = registry.find(identifier);
        if (match.isEmpty()) {
            System.err.println("jk jdk uninstall: no installed JDK matches `" + identifier + "`");
            return 1;
        }
        executeOne(match.get(), registry);
        reconcileDefaultAfterRemoval(registry, defaults, List.of(match.get()), Optional.empty());
        return 0;
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
                executeOne(v, registry);
            }
            reconcileDefaultAfterRemoval(registry, defaults, victims, outcome.get().newDefault());
            return 0;
        }
    }

    /** Spinner → registry.remove → "✓ <label> has been uninstalled from <dir>". */
    private static void executeOne(InstalledJdk jdk, JdkRegistry registry) throws IOException {
        String label = jdk.identifier();
        Path dir = jdk.home();
        try (var sp = Spinner.show(System.out,
                Theme.colorize("Uninstalling ", Theme.normalGray())
                        + Theme.colorize(label, Theme.focused()))) {
            registry.remove(label);
        }
        // Spinner.close() wiped its line; replace it with the done line.
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
                Theme.colorize("➜ ", Theme.blue())
                        + Theme.colorize(promote.identifier(), Theme.focused())
                        + " " + Theme.colorize("is now the default JDK", Theme.normalGray()));
    }

    private static boolean isInteractiveTerminal() {
        return System.console() != null
                && !"dumb".equals(System.getenv("TERM"))
                && System.getenv("CI") == null;
    }
}
