// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.run.GoalConsole;
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
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.run.PhaseStatus;
import dev.jkbuild.util.JkDirs;
import org.jline.terminal.Terminal;
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
 * {@code jk jdk install [<spec>]} — pull a JDK from the JetBrains JDK feed
 * and unpack it into the IntelliJ JDK directory ({@code ~/.jdks/} or
 * {@code ~/Library/Java/JavaVirtualMachines/}).
 *
 * <p>Goal shape: {@code fetch-catalog} (IO) → {@code select} (SYNC; runs
 * the wizard when no spec was given) → {@code download} (IO; uses the
 * inline progress bar) → {@code extract} (IO; uses the inline spinner)
 * → {@code set-default} (SYNC; only when {@code --make-default}).
 *
 * <p>Marked interactive so the framework's progress widget stays out of
 * the way of the wizard, ProgressBar and Spinner — those keep ownership
 * of the rendered output.
 */
@Command(name = "install", aliases = {"add"},
        description = "Install a Java Development Kit")
public final class JdkInstallCommand implements Callable<Integer> {

    @Parameters(arity = "0..1", paramLabel = "<spec>",
            description = "Keyword (`lts` / `stable` / `latest`) or a denormalized version "
                    + "(`25`, `21.0.5`, `temurin-21`, `25-graal`, `java-17-openjdk`). "
                    + "Omit to launch the interactive wizard.")
    String spec;

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

    private static final GoalKey<JdkCatalog> CATALOG = GoalKey.of("catalog", JdkCatalog.class);
    private static final GoalKey<JdkCatalog.Entry> ENTRY = GoalKey.of("entry", JdkCatalog.Entry.class);
    private static final GoalKey<JdkInstaller.DownloadedArchive> ARCHIVE =
            GoalKey.of("archive", JdkInstaller.DownloadedArchive.class);
    private static final GoalKey<InstalledJdk> INSTALLED = GoalKey.of("installed", InstalledJdk.class);
    private static final GoalKey<Boolean> WANT_DEFAULT = GoalKey.of("want-default", Boolean.class);

    @Override
    public Integer call() throws Exception {
        if (!HostPlatform.supported()) {
            System.err.println("jk jdk install: host "
                    + System.getProperty("os.name") + "/" + System.getProperty("os.arch")
                    + " is not covered by the JetBrains JDK feed. Set JAVA_HOME explicitly.");
            return 1;
        }
        String os = HostPlatform.currentOs();
        String arch = HostPlatform.currentArch();

        Path cache = JkDirs.cache();
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        JdkInstaller installer = new JdkInstaller(new Http(), registry);

        // Pre-goal sanity: when no spec and no TTY, we can't go further.
        boolean haveSpec = spec != null && !spec.isBlank();
        if (!haveSpec && !isInteractiveTerminal()) {
            System.err.println("jk jdk install: stdin is not a TTY — pass `lts` / `latest` "
                    + "or a <spec> (e.g. `jk jdk install temurin-21`) or run interactively.");
            return 64;
        }

        Phase fetchCatalog = Phase.builder("fetch-catalog")
                .kind(PhaseKind.IO)
                .scope(1)
                .execute(ctx -> {
                    ctx.label("fetch JetBrains JDK feed");
                    boolean noCache = dev.jkbuild.config.ActiveConfig.get().noCacheOr(false);
                    JdkCatalogClient client = feedUrl != null
                            ? new JdkCatalogClient(new Http(), feedUrl,
                                    cacheFile != null ? cacheFile : ephemeralCachePath(),
                                    java.time.Duration.ZERO)
                            : new JdkCatalogClient();
                    try {
                        ctx.put(CATALOG, client.fetch(noCache));
                    } catch (Exception e) {
                        ctx.error("catalog", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    ctx.progress(1);
                })
                .build();

        Phase select = Phase.builder("select")
                .requires("fetch-catalog")
                .scope(1)
                .execute(ctx -> {
                    JdkCatalog catalog = ctx.require(CATALOG);
                    String effective = haveSpec ? resolveKeyword(spec, catalog, os, arch) : null;
                    if (haveSpec && effective == null) effective = spec;

                    JdkCatalog.Entry entry;
                    boolean wantDefault = makeDefault;
                    if (effective == null || effective.isBlank()) {
                        ctx.label("wizard");
                        JdkInstallWizard.Result chosen;
                        try {
                            chosen = runWizard(catalog, os, arch, showAll);
                        } catch (RuntimeException e) {
                            ctx.error("wizard", e.getMessage());
                            throw e;
                        }
                        entry = chosen.entry();
                        wantDefault = wantDefault || chosen.makeDefault();
                    } else {
                        ctx.label("select " + effective);
                        JdkSpec parsed = JdkSpec.parse(effective);
                        Optional<JdkCatalog.Entry> selected =
                                JdkSelector.select(catalog, parsed, os, arch);
                        if (selected.isEmpty()) {
                            ctx.error("no-match", "no JDK matches " + effective
                                    + " on " + os + "/" + arch);
                            throw new RuntimeException("no match");
                        }
                        entry = selected.get();
                    }
                    ctx.put(ENTRY, entry);
                    ctx.put(WANT_DEFAULT, wantDefault);
                    if (global != null && global.verbose) {
                        System.err.println("jk jdk install: resolved spec='" + effective + "' to "
                                + entry.installFolderName() + " (" + entry.vendor() + " "
                                + entry.product() + ", " + os + "/" + arch + ")");
                    }
                    ctx.progress(1);
                })
                .build();

        Phase download = Phase.builder("download")
                .kind(PhaseKind.IO)
                .requires("select")
                .scope(1)
                .execute(ctx -> {
                    JdkCatalog.Entry entry = ctx.require(ENTRY);
                    boolean noCache = dev.jkbuild.config.ActiveConfig.get().noCacheOr(false);
                    InstalledJdk already = noCache ? null : installer.alreadyInstalled(entry);
                    if (already != null) {
                        String label = entry.vendor() + " " + entry.product()
                                + " " + entry.majorVersion();
                        System.out.println(doneLine(label, already.home(),
                                "is already installed at"));
                        ctx.put(INSTALLED, already);
                        ctx.label("already installed");
                        ctx.progress(1);
                        return;
                    }
                    String label = entry.vendor() + " " + entry.product()
                            + " " + entry.majorVersion();
                    String hostLabel = entry.os() + "/" + entry.arch();
                    ctx.label("download " + label + " (" + hostLabel + ")");
                    String downloading = "Downloading " + label + " (" + hostLabel + ")";
                    long total = entry.archiveSize();
                    try (ProgressBar pb = ProgressBar.show(System.out)) {
                        pb.update(0, downloading);
                        JdkInstaller.DownloadedArchive dl = installer.download(entry, bytes -> {
                            int pct = total > 0
                                    ? (int) Math.min(100, bytes * 100L / total)
                                    : 0;
                            pb.update(pct, downloading);
                        });
                        pb.finish(Theme.colorize("✓", Theme.completedStep())
                                + " " + Theme.colorize("Download finished for ", Theme.normalGray())
                                + Theme.colorize(label, Theme.focused()));
                        ctx.put(ARCHIVE, dl);
                    } catch (Exception e) {
                        ctx.error("download", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    ctx.progress(1);
                })
                .build();

        Phase extract = Phase.builder("extract")
                .kind(PhaseKind.IO)
                .requires("download")
                .scope(1)
                .execute(ctx -> {
                    if (ctx.get(INSTALLED).isPresent()) {
                        // Already-installed short-circuit from the download phase.
                        ctx.label("skip (already installed)");
                        ctx.progress(1);
                        return;
                    }
                    JdkCatalog.Entry entry = ctx.require(ENTRY);
                    String label = entry.vendor() + " " + entry.product()
                            + " " + entry.majorVersion();
                    String installing = "Installing "
                            + Theme.colorize(label, Theme.focused()) + "...";
                    ctx.label("extract " + label);
                    try (Spinner sp = Spinner.show(System.out, installing)) {
                        InstalledJdk installed = installer.extractInstalled(
                                entry, ctx.require(ARCHIVE));
                        ctx.put(INSTALLED, installed);
                        // Journal the install event for the JDK-usage stats —
                        // feeds future wizards that surface a user's
                        // preferred vendors / versions.
                        dev.jkbuild.jdk.JdkAccessLedger.atDefaultPath()
                                .touch(installed.identifier(), "install");
                        System.out.println(doneLine(label, installed.home(),
                                "has been installed to"));
                    } catch (Exception e) {
                        ctx.error("extract", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    ctx.progress(1);
                })
                .build();

        Phase setDefault = Phase.builder("set-default")
                .requires("extract")
                .scope(1)
                .execute(ctx -> {
                    if (!Boolean.TRUE.equals(ctx.get(WANT_DEFAULT).orElse(false))) {
                        ctx.label("skip (not requested)");
                        ctx.progress(1);
                        return;
                    }
                    InstalledJdk installed = ctx.require(INSTALLED);
                    ctx.label("set " + installed.identifier() + " as default");
                    GlobalDefaultJdk.current().set(installed);
                    dev.jkbuild.jdk.JdkAccessLedger.atDefaultPath()
                            .touch(installed.identifier(), "default-set");
                    System.out.println();
                    System.out.println(Theme.colorize("➜", Theme.brightGreen())
                            + " " + Theme.colorize(installed.identifier(),
                                    AttributedStyle.DEFAULT.bold())
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
                    ctx.progress(1);
                })
                .build();

        Goal goal = Goal.builder("jdk-install")
                .interactive(true)
                .addPhase(fetchCatalog)
                .addPhase(select)
                .addPhase(download)
                .addPhase(extract)
                .addPhase(setDefault)
                .build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);
        if (!result.success()) return 1;
        return 0;
    }

    /**
     * Translate the keyword specs ({@code lts}, {@code stable}, {@code latest})
     * into a concrete {@code temurin-<major>} spec string. Returns {@code null}
     * when {@code raw} is not a keyword — the caller treats it as a normal
     * denormalized spec in that case.
     *
     * <p>The Temurin bias matches the user's mental model of "the default LTS
     * JDK"; if Temurin isn't shipped at that major for this host, the flexible
     * selector falls back to the catalog's default-for-major.
     */
    private String resolveKeyword(String raw, JdkCatalog catalog, String os, String arch) {
        var norm = raw.trim().toLowerCase(java.util.Locale.ROOT);
        boolean wantLts = norm.equals("lts") || norm.equals("stable");
        boolean wantLatest = norm.equals("latest");
        if (!wantLts && !wantLatest) return null;

        var majors = new java.util.TreeSet<Integer>();
        for (JdkCatalog.Entry e : catalog.entries()) {
            if (e.preview()) continue;
            if (!e.os().equals(os) || !e.arch().equals(arch)) continue;
            majors.add(e.majorVersion());
        }
        if (majors.isEmpty()) return null;
        int picked;
        if (wantLatest) {
            picked = majors.last();
        } else {
            var lts = JdkLts.latestLtsIn(majors);
            if (lts.isEmpty()) {
                System.err.println("jk jdk install: no LTS major present in the feed for "
                        + os + "/" + arch + ".");
                return null;
            }
            picked = lts.getAsInt();
        }
        return "temurin-" + picked;
    }

    /**
     * Render the post-install summary line:
     * <pre>
     *   ✓ &lt;label&gt; &lt;verb&gt; &lt;~/path&gt;
     * </pre>
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
            terminal = Wizard.openTerminal();
        } catch (IOException e) {
            throw new IOException("failed to open terminal: " + e.getMessage(), e);
        }
        Optional<JdkInstallWizard.Result> result =
                JdkInstallWizard.run(catalog, os, arch, showAll, terminal);
        if (result.isEmpty()) {
            // Ctrl-C cancellation. Wizard.printCancellation preserves the cyan
            // active-rail closer and prints the red marker beside it.
            //
            // Runtime.halt() instead of System.exit(): halt skips shutdown
            // hooks, which is what we want here — JLine's cleanup hook blocks
            // on its stdin reader thread that macOS won't let us interrupt.
            // The wizard's finally already restored terminal attributes.
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
        java.nio.file.Files.delete(tmp);  // force a fresh fetch
        return tmp;
    }
}
