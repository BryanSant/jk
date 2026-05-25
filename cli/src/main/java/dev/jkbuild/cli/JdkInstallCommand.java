// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.tui.ProgressBar;
import dev.jkbuild.cli.tui.Spinner;
import dev.jkbuild.cli.tui.Theme;
import dev.jkbuild.cli.tui.Wizard;
import dev.jkbuild.http.Http;
import dev.jkbuild.jdk.GlobalDefaultJdk;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.JdkCatalog;
import dev.jkbuild.jdk.JdkCatalogClient;
import dev.jkbuild.jdk.JdkInstaller;
import dev.jkbuild.jdk.JdkLts;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkSelector;
import dev.jkbuild.jdk.JdkSpec;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStyle;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk jdk install <spec>} — pull a JDK from the JetBrains JDK feed
 * and unpack it into the IntelliJ JDK directory ({@code ~/.jdks/} or
 * {@code ~/Library/Java/JavaVirtualMachines/}).
 *
 * <p>The {@code <spec>} matches the feed's vocabulary:
 * <ul>
 *   <li>{@code 21}, {@code 21.0.5} — bare version; picks whichever vendor
 *       the feed marks {@code default: true} for that major (OpenJDK,
 *       today).</li>
 *   <li>{@code temurin-21}, {@code openjdk-26}, {@code corretto-21.0.5} —
 *       a {@code suggested_sdk_name} from the feed.</li>
 * </ul>
 */
@Command(name = "install", aliases = {"add"},
        description = "Install a Java Development Kit")
public final class JdkInstallCommand implements Callable<Integer> {

    @Parameters(arity = "0..1", paramLabel = "<version>",
            description = "Denormalized version spec — `25`, `21.0.5`, `temurin-21`, "
                    + "`openjdk-26`, etc. Resolved to the best-matching catalog entry. "
                    + "Omit to launch the interactive wizard.")
    String spec;

    @Option(names = {"-l", "--lts"},
            description = "Install the most recent LTS major (e.g. 25 today, 29 after Sep 2027).")
    boolean lts;

    @Option(names = {"-s", "--stable"},
            description = "Alias for --lts.")
    boolean stable;

    @Option(names = {"-L", "--latest"},
            description = "Install the most recent JDK major (LTS or interim release).")
    boolean latest;

    @Option(names = {"-d", "--make-default"},
            description = "After install, mark this JDK as the system-wide default.")
    boolean makeDefault;

    /** Wired from the {@link GlobalOptions} mixin so we can gate the "resolved spec" diagnostic on -v. */
    @picocli.CommandLine.Mixin
    GlobalOptions global;

    @Option(names = "--show-all", hidden = true,
            description = "In the interactive wizard, list every vendor from the JetBrains feed "
                    + "instead of the curated default set.")
    boolean showAll;

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @Option(names = "--feed-url", hidden = true,
            description = "Override the JetBrains JDK feed URL (for tests).")
    URI feedUrl;

    @Option(names = "--cache-file", hidden = true,
            description = "Override the catalog cache path (for tests).")
    Path cacheFile;

    @Override
    public Integer call() throws Exception {
        if (!HostPlatform.supported()) {
            System.err.println("jk jdk install: host "
                    + System.getProperty("os.name") + "/" + System.getProperty("os.arch")
                    + " is not covered by the JetBrains JDK feed. Set JAVA_HOME explicitly.");
            return 1;
        }

        JdkCatalogClient client = feedUrl != null
                ? new JdkCatalogClient(new Http(), feedUrl,
                        cacheFile != null ? cacheFile : ephemeralCachePath(),
                        java.time.Duration.ZERO)
                : new JdkCatalogClient();
        JdkCatalog catalog = client.fetch();

        String os = HostPlatform.currentOs();
        String arch = HostPlatform.currentArch();

        // --lts / --stable / --latest resolve to a major-version spec by
        // looking at what the catalog actually ships. Mutually exclusive
        // with each other and with the positional <version>.
        int flagsSet = (lts ? 1 : 0) + (stable ? 1 : 0) + (latest ? 1 : 0);
        if (flagsSet > 1) {
            System.err.println("jk jdk install: --lts, --stable, and --latest are mutually exclusive.");
            return 64;
        }
        boolean haveSpec = spec != null && !spec.isBlank();
        String resolvedSpec = resolveShortcutSpec(catalog, os, arch);
        if (resolvedSpec != null && haveSpec) {
            System.err.println("jk jdk install: --lts/--stable/--latest and <version> are mutually exclusive.");
            return 64; // EX_USAGE
        }
        String effectiveSpec = resolvedSpec != null ? resolvedSpec : spec;

        JdkCatalog.Entry entry;
        boolean wantDefault = makeDefault;
        if (effectiveSpec == null || effectiveSpec.isBlank()) {
            if (!isInteractiveTerminal()) {
                System.err.println("jk jdk install: stdin is not a TTY — pass --lts/--stable/--latest "
                        + "or a <spec> (e.g. `jk jdk install temurin-21`) or run interactively.");
                return 64; // EX_USAGE
            }
            JdkInstallWizard.Result chosen = runWizard(catalog, os, arch, showAll);
            entry = chosen.entry();
            wantDefault = wantDefault || chosen.makeDefault();
        } else {
            JdkSpec parsed = JdkSpec.parse(effectiveSpec);
            Optional<JdkCatalog.Entry> selected = JdkSelector.select(catalog, parsed, os, arch);
            if (selected.isEmpty()) {
                System.err.println("jk jdk install: no JDK matches " + effectiveSpec
                        + " on " + os + "/" + arch);
                return 1;
            }
            entry = selected.get();
        }

        if (global != null && global.verbose) {
            System.err.println("jk jdk install: resolved spec='" + effectiveSpec + "' to "
                    + entry.installFolderName() + " (" + entry.vendor() + " " + entry.product()
                    + ", " + os + "/" + arch + ")");
        }

        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        JdkInstaller installer = new JdkInstaller(new Http(), registry);
        InstalledJdk installed = downloadAndInstall(installer, entry);

        if (wantDefault) {
            GlobalDefaultJdk.current().set(installed);
            System.out.println();
            // Bright-green ➜ + bold id + emphasized "default".
            System.out.println(Theme.colorize("➜", Theme.brightGreen())
                    + " " + Theme.colorize(installed.identifier(), AttributedStyle.DEFAULT.bold())
                    + " is now the " + Theme.colorize("default", Theme.focused())
                    + " JDK");
            Optional<Shell> shell = Shell.detect();
            if (shell.isPresent()) {
                System.out.println("Add `" + shell.get().hookInstallCommand()
                        + "` to activate JAVA_HOME on new shells.");
            } else {
                System.out.println("Add `jk hook <bash|zsh|fish>` output to your shell rc "
                        + "to activate JAVA_HOME on new shells.");
            }
        }
        return 0;
    }

    /**
     * Translate the shortcut flags ({@code --lts}, {@code --stable},
     * {@code --latest}) into a major-version spec string, or {@code null}
     * when no shortcut is set. Mutual-exclusion check happens in
     * {@link #call} before this is called.
     */
    private String resolveShortcutSpec(JdkCatalog catalog, String os, String arch) {
        if (!lts && !stable && !latest) return null;
        var majors = new java.util.TreeSet<Integer>();
        for (JdkCatalog.Entry e : catalog.entries()) {
            if (e.preview()) continue;
            if (!e.os().equals(os) || !e.arch().equals(arch)) continue;
            majors.add(e.majorVersion());
        }
        if (majors.isEmpty()) return null; // selector will then complain
        if (latest) {
            return String.valueOf(majors.last());
        }
        // --lts / --stable
        var picked = JdkLts.latestLtsIn(majors);
        if (picked.isEmpty()) {
            System.err.println("jk jdk install: no LTS major present in the feed for "
                    + os + "/" + arch + ".");
            return null;
        }
        return String.valueOf(picked.getAsInt());
    }

    /**
     * Drive the download (progress bar) → extract (spinner) sequence and
     * print the final ✓ "Installing ... Done." line. Falls through to a
     * one-line "Already installed" message when the target is already on
     * disk so we don't show a 0%→0% bar for an instant.
     */
    private static InstalledJdk downloadAndInstall(JdkInstaller installer, JdkCatalog.Entry entry)
            throws IOException, InterruptedException {
        InstalledJdk already = installer.alreadyInstalled(entry);
        String label = entry.vendor() + " " + entry.product() + " " + entry.majorVersion();
        if (already != null) {
            System.out.println(doneLine(label, already.home(), "is already installed at"));
            return already;
        }

        String hostLabel = entry.os() + "/" + entry.arch();
        String downloading = "Downloading " + label + " (" + hostLabel + ")";
        // Spinner prints its message raw (unlike the bar, whose status is
        // wrapped in Theme.dim()), so we can splice bold-white ANSI around
        // the JDK label to make it pop on the active install line.
        String installing = "Installing "
                + Theme.colorize(label, Theme.focused())
                + "...";
        long total = entry.archiveSize();

        JdkInstaller.DownloadedArchive dl;
        try (ProgressBar pb = ProgressBar.show(System.out)) {
            pb.update(0, downloading);
            dl = installer.download(entry, bytes -> {
                int pct = total > 0
                        ? (int) Math.min(100, bytes * 100L / total)
                        : 0;
                pb.update(pct, downloading);
            });
            pb.finish(Theme.colorize("✓", Theme.completedStep())
                    + " " + Theme.colorize("Download finished for ", Theme.normalGray())
                    + Theme.colorize(label, Theme.focused()));
        }

        InstalledJdk installed;
        try (Spinner sp = Spinner.show(System.out, installing)) {
            installed = installer.extractInstalled(entry, dl);
        }
        // Spinner.close() cleared its line; replace it with the done line.
        // Bold-white label sits between normal-gray connective tissue, with
        // a yellow tilde-collapsed install path as the closing anchor.
        System.out.println(doneLine(label, installed.home(), "has been installed to"));
        return installed;
    }

    /**
     * Render the post-install summary line:
     * <pre>
     *   ✓ &lt;label&gt; &lt;verb&gt; &lt;~/path&gt;
     * </pre>
     * with the {@code ✓} in green, {@code label} bold-white, {@code verb}
     * normal-gray (with the word "installed" lifted to bold-white for
     * emphasis), and the install path yellow with {@code ~} collapsed
     * for the user's home dir.
     */
    private static String doneLine(String label, Path home, String verb) {
        return Theme.colorize("✓", Theme.completedStep())
                + " " + Theme.colorize(label, Theme.focused())
                + " " + emphasizeInstalled(verb)
                + " " + Theme.colorize(tildeCollapse(home), Theme.warning());
    }

    /** Render {@code verb} in normal-gray with the word "installed" promoted to bold-white. */
    private static String emphasizeInstalled(String verb) {
        String key = "installed";
        int idx = verb.indexOf(key);
        if (idx < 0) {
            return Theme.colorize(verb, Theme.normalGray());
        }
        var sb = new StringBuilder();
        if (idx > 0) {
            sb.append(Theme.colorize(verb.substring(0, idx), Theme.normalGray()));
        }
        sb.append(Theme.colorize(key, Theme.focused()));
        int end = idx + key.length();
        if (end < verb.length()) {
            sb.append(Theme.colorize(verb.substring(end), Theme.normalGray()));
        }
        return sb.toString();
    }

    /** Render an absolute path with {@code $HOME} collapsed to {@code ~}. */
    static String tildeCollapse(Path path) {
        String home = System.getProperty("user.home");
        String abs = path.toAbsolutePath().toString();
        if (home != null && !home.isBlank() && abs.startsWith(home)) {
            return "~" + abs.substring(home.length());
        }
        return abs;
    }

    private static JdkInstallWizard.Result runWizard(
            JdkCatalog catalog, String os, String arch, boolean showAll) throws IOException {
        Terminal terminal;
        try {
            terminal = TerminalBuilder.builder().system(true).build();
        } catch (IOException e) {
            throw new IOException("failed to open terminal: " + e.getMessage(), e);
        }
        Optional<JdkInstallWizard.Result> result =
                JdkInstallWizard.run(catalog, os, arch, showAll, terminal);
        if (result.isEmpty()) {
            // Ctrl-C cancellation. Wizard.printCancellation preserves the cyan
            // active-rail closer and prints the red marker beside it. See
            // NewCommand for the Runtime.halt() rationale.
            Wizard.printCancellation(terminal, "𝘅 JDK installation canceled");
            Runtime.getRuntime().halt(130); // 128 + SIGINT
            throw new AssertionError("unreachable");
        }
        terminal.close();
        return result.get();
    }

    private static boolean isInteractiveTerminal() {
        return System.console() != null
                && !"dumb".equals(System.getenv("TERM"))
                && System.getenv("CI") == null;
    }

    private static Path ephemeralCachePath() throws java.io.IOException {
        Path tmp = java.nio.file.Files.createTempFile("jk-feed-", ".json.xz");
        tmp.toFile().deleteOnExit();
        java.nio.file.Files.delete(tmp);  // force a fresh fetch (file present but empty would parse-fail)
        return tmp;
    }
}
