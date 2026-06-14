// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;

import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.tui.Confirm;
import dev.jkbuild.cli.tui.SpinnerProgressBar;
import dev.jkbuild.cli.tui.Spinner;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Wizard;
import dev.jkbuild.http.Http;
import dev.jkbuild.jdk.GlobalDefaultJdk;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.JdkCatalog;
import dev.jkbuild.jdk.JdkCatalogClient;
import dev.jkbuild.jdk.JdkInstaller;
import dev.jkbuild.jdk.JdkKeywords;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkSelector;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.run.PhaseStatus;
import dev.jkbuild.util.JkDirs;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedStyle;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

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
 * the way of the wizard, SpinnerProgressBar and Spinner — those keep ownership
 * of the rendered output.
 */
public final class JdkInstallCommand implements CliCommand {

    @Override public String name() { return "install"; }
    @Override public java.util.List<String> aliases() { return java.util.List.of("add"); }
    @Override public String description() { return "Install a Java Development Kit"; }
    @Override public java.util.List<Opt> options() {
        return java.util.List.of(
                Opt.flag("After install, mark this JDK as the system-wide default.", "-d", "--make-default"),
                Opt.flag("In the interactive wizard, list every vendor from the JetBrains feed.", "--show-all").hide(),
                Opt.value("<dir>", "Override the install root. Default: the IntelliJ JDK directory.", "--jdks-dir").hide(),
                Opt.value("<url>", "Override the JetBrains JDK feed URL (for tests).", "--feed-url").hide(),
                Opt.value("<file>", "Override the catalog cache path (for tests).", "--cache-file").hide());
    }
    @Override public java.util.List<Param> parameters() {
        return java.util.List.of(Param.of("spec", Arity.ZERO_OR_ONE,
                "Keyword (`lts` / `stable` / `latest` / `native`, where `native` is the latest Oracle GraalVM) "
                + "or a denormalized version. Omit to launch the interactive wizard."));
    }

    String spec;
    boolean makeDefault;
    GlobalOptions global;
    boolean showAll;
    Path jdksDir;
    URI feedUrl;
    Path cacheFile;

    private static final GoalKey<JdkCatalog> CATALOG = GoalKey.of("catalog", JdkCatalog.class);
    private static final GoalKey<JdkCatalog.Entry> ENTRY = GoalKey.of("entry", JdkCatalog.Entry.class);
    private static final GoalKey<JdkInstaller.DownloadedArchive> ARCHIVE =
            GoalKey.of("archive", JdkInstaller.DownloadedArchive.class);
    private static final GoalKey<InstalledJdk> INSTALLED = GoalKey.of("installed", InstalledJdk.class);
    private static final GoalKey<Boolean> WANT_DEFAULT = GoalKey.of("want-default", Boolean.class);

    @Override
    public int run(Invocation in) throws Exception {
        this.spec = in.positionals().isEmpty() ? null : in.positionals().get(0);
        this.makeDefault = in.isSet("make-default");
        this.global = GlobalOptions.from(in);
        this.showAll = in.isSet("show-all");
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.feedUrl = in.value("feed-url").map(URI::create).orElse(null);
        this.cacheFile = in.value("cache-file").map(Path::of).orElse(null);

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
                    JdkCatalogClient client = (feedUrl != null
                            ? new JdkCatalogClient(new Http(), feedUrl,
                                    cacheFile != null ? cacheFile : ephemeralCachePath(),
                                    java.time.Duration.ZERO)
                            : new JdkCatalogClient())
                            .onWarning(ctx::output);
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
                        // selectPreferred applies the Temurin bias for
                        // vendor-unqualified specs (e.g. `26`, `25.0.3`); the
                        // keyword path already resolved `effective` to
                        // `temurin-<major>`, so it's a no-op bias there.
                        Optional<JdkCatalog.Entry> selected =
                                JdkSelector.selectPreferred(catalog, effective, os, arch);
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
                    try (SpinnerProgressBar pb = SpinnerProgressBar.show(System.out)) {
                        pb.update(0, downloading);
                        JdkInstaller.DownloadedArchive dl = installer.download(entry, bytes -> {
                            int pct = total > 0
                                    ? (int) Math.min(100, bytes * 100L / total)
                                    : 0;
                            pb.update(pct, downloading);
                        });
                        pb.finish(Theme.colorize("✓", Theme.active().completedStep())
                                + " " + Theme.colorize("Download finished for ", Theme.active().normalGray())
                                + Theme.colorize(label, Theme.active().focused()));
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
                            + Theme.colorize(label, Theme.active().focused()) + "...";
                    ctx.label("extract " + label);
                    InstalledJdk installed;
                    try (Spinner sp = Spinner.show(System.out, installing)) {
                        installed = installer.extractInstalled(
                                entry, ctx.require(ARCHIVE));
                        ctx.put(INSTALLED, installed);
                        // Journal the install event for the JDK-usage stats —
                        // feeds future wizards that surface a user's
                        // preferred vendors / versions.
                        dev.jkbuild.jdk.JdkAccessLedger.atDefaultPath()
                                .touch(installed.identifier(), "install");
                    } catch (Exception e) {
                        ctx.error("extract", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    // Print after close() so the done line overwrites the cleared spinner line.
                    System.out.println(doneLine(label, installed.home(), "has been installed to"));
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
                    System.out.println(Theme.colorize("➜", Theme.active().brightGreen())
                            + " " + Theme.colorize(installed.identifier(),
                                    AttributedStyle.DEFAULT.bold())
                            + " is now the " + Theme.colorize("default", Theme.active().focused())
                            + " JDK");
                    Optional<JdkShell> shell = JdkShell.detect();
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

        // Offer to adopt the new install as the default JDK / default GraalVM.
        // Runs AFTER the goal console closes, so the prompt never lands inside a
        // captured-output region. Skipped on a non-TTY (and when --make-default
        // already set the java default).
        if (Confirm.isInteractiveTerminal()) {
            boolean wantedDefault = Boolean.TRUE.equals(goal.get(WANT_DEFAULT).orElse(false));
            goal.get(INSTALLED).ifPresent(jdk -> offerDefaults(jdk, wantedDefault));
        }
        return 0;
    }

    /** Prompt to make {@code jdk} the default JDK and/or default GraalVM when it's ≥ the current ones. */
    private void offerDefaults(InstalledJdk jdk, boolean alreadyMadeDefault) {
        int newMajor = JdkListCommand.parseMajor(jdk.identifier());
        if (newMajor == 0) return;
        try {
            dev.jkbuild.jdk.GlobalDefaultJdk defaults = dev.jkbuild.jdk.GlobalDefaultJdk.current();
            if (!alreadyMadeDefault) {
                Integer cur = defaults.currentIdentifier().map(JdkListCommand::parseMajor).orElse(null);
                if (cur == null || newMajor >= cur) {
                    if (Confirm.of("Make " + jdk.identifier() + " the default JDK?", true).ask()) {
                        defaults.set(jdk);
                    }
                }
            }
            if (isGraalHome(jdk.home())) {
                Integer curGraal = defaults.graalIdentifier().map(JdkListCommand::parseMajor).orElse(null);
                if (curGraal == null || newMajor >= curGraal) {
                    if (Confirm.of("Make it the default GraalVM (jk native / GRAALVM_HOME)?", true).ask()) {
                        defaults.setGraal(jdk);
                    }
                }
            }
        } catch (IOException ignored) {
            // best-effort — a malformed config just means no prompt
        }
    }

    private static boolean isGraalHome(Path home) {
        try {
            dev.jkbuild.jdk.JdkVendor v = dev.jkbuild.jdk.JdkVendor.fromRelease(home);
            return v == dev.jkbuild.jdk.JdkVendor.ORACLE_GRAALVM || v == dev.jkbuild.jdk.JdkVendor.GRAALVM_CE;
        } catch (RuntimeException e) {
            return false;
        }
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
        if (!JdkKeywords.isKeyword(raw)) return null;
        String resolved = JdkKeywords.resolveToMajorSpec(catalog, raw, os, arch).orElse(null);
        if (resolved == null) {
            // A keyword resolved to nothing: lts/stable with no LTS major, or
            // `native` with no Oracle GraalVM, for this host.
            System.err.println("jk jdk install: could not resolve `" + raw.trim()
                    + "` against the JetBrains feed for " + os + "/" + arch + ".");
        }
        return resolved;
    }

    /**
     * Render the post-install summary line:
     * <pre>
     *   ✓ &lt;label&gt; &lt;verb&gt; &lt;~/path&gt;
     * </pre>
     */
    private static String doneLine(String label, Path home, String verb) {
        return Theme.colorize("✓", Theme.active().completedStep())
                + " " + Theme.colorize(label, Theme.active().focused())
                + " " + emphasizeInstalled(verb)
                + " " + Theme.colorize(tildeCollapse(home), Theme.active().warning());
    }

    /** Render {@code verb} in normal-gray with the word "installed" promoted to bold-white. */
    private static String emphasizeInstalled(String verb) {
        String key = "installed";
        int idx = verb.indexOf(key);
        if (idx < 0) {
            return Theme.colorize(verb, Theme.active().normalGray());
        }
        var sb = new StringBuilder();
        if (idx > 0) {
            sb.append(Theme.colorize(verb.substring(0, idx), Theme.active().normalGray()));
        }
        sb.append(Theme.colorize(key, Theme.active().focused()));
        int end = idx + key.length();
        if (end < verb.length()) {
            sb.append(Theme.colorize(verb.substring(end), Theme.active().normalGray()));
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
