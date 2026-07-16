// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.run.PipelineConsole;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Confirm;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.Wizard;
import cc.jumpkick.jdk.GlobalDefaultJdk;
import cc.jumpkick.jdk.HostPlatform;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JdkCatalog;
import cc.jumpkick.jdk.JdkInstallListener;
import cc.jumpkick.jdk.JdkInstaller;
import cc.jumpkick.jdk.JdkKeywords;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.JdkService;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.PipelineKey;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.Step;
import cc.jumpkick.run.StepKind;
import cc.jumpkick.run.StepNames;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import org.jline.terminal.Terminal;

/**
 * {@code jk jdk install [<spec>]} — pull a JDK from the JetBrains JDK feed and unpack it into the
 * IntelliJ JDK directory ({@code ~/.jdks/} or {@code ~/Library/Java/JavaVirtualMachines/}).
 *
 * <p>Pipeline shape: {@code fetch-catalog} (IO) → {@code select} (SYNC; runs the wizard when no spec
 * was given) → {@code download} (IO; uses the inline progress bar) → {@code extract} (IO; uses the
 * inline spinner) → {@code set-default} (SYNC; only when {@code --make-default}).
 *
 * <p>Marked interactive so the framework's progress widget stays out of the way of the wizard,
 * SpinnerProgressBar and Spinner — those keep ownership of the rendered output.
 */
public final class JdkInstallCommand implements CliCommand {

    @Override
    public String name() {
        return "install";
    }

    @Override
    public java.util.List<String> aliases() {
        return java.util.List.of("add");
    }

    @Override
    public String description() {
        return "Install a Java Development Kit";
    }

    @Override
    public java.util.List<Opt> options() {
        return java.util.List.of(
                Opt.flag("After install, mark this JDK as the system-wide default.", "-d", "--make-default"),
                Opt.flag("In the interactive wizard, list every vendor from the JetBrains feed.", "--show-all")
                        .hide(),
                Opt.value("<dir>", "Override the install root. Default: the IntelliJ JDK directory.", "--jdks-dir")
                        .hide(),
                Opt.value("<url>", "Override the JetBrains JDK feed URL (for tests).", "--feed-url")
                        .hide(),
                Opt.value("<file>", "Override the catalog cache path (for tests).", "--cache-file")
                        .hide());
    }

    @Override
    public java.util.List<Param> parameters() {
        return java.util.List.of(Param.of(
                "spec",
                Arity.ZERO_OR_ONE,
                "The vendor/version of JDK you'd like to install\n"
                        + "  (ex: 25, lts, latest, native, temurin-25.0.3)"));
    }

    String spec;
    boolean makeDefault;
    GlobalOptions global;
    boolean showAll;
    Path jdksDir;
    URI feedUrl;
    Path cacheFile;

    private static final PipelineKey<JdkCatalog> CATALOG = PipelineKey.of("catalog", JdkCatalog.class);
    private static final PipelineKey<JdkCatalog.Entry> ENTRY = PipelineKey.of("entry", JdkCatalog.Entry.class);
    private static final PipelineKey<InstalledJdk> INSTALLED = PipelineKey.of("installed", InstalledJdk.class);
    private static final PipelineKey<Boolean> WANT_DEFAULT = PipelineKey.of("want-default", Boolean.class);

    /** True when the interactive wizard ran — it already settled the default decision. */
    private static final PipelineKey<Boolean> WIZARD_RAN = PipelineKey.of("wizard-ran", Boolean.class);

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
            CliOutput.err("jk jdk install: host "
                    + System.getProperty("os.name")
                    + "/"
                    + System.getProperty("os.arch")
                    + " is not covered by the JetBrains JDK feed. Set JAVA_HOME explicitly.");
            return 1;
        }
        String os = HostPlatform.currentOs();
        String arch = HostPlatform.currentArch();

        Path cache = JkDirs.cache();
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        // Reclaim any partial archive left by a previously canceled download
        // (Ctrl-C halts the JVM mid-download, skipping the inline cleanup).
        // Kept in the CLI so every path — including the wizard and non-TTY
        // early-outs below — sweeps once, before any pipeline runs.
        JdkInstaller.sweepStaleDownloads(registry.jdksRoot());
        JdkService service = new JdkService();

        // Pre-pipeline sanity: when no spec and no TTY, we can't go further.
        boolean haveSpec = spec != null && !spec.isBlank();
        if (!haveSpec && !isInteractiveTerminal()) {
            CliOutput.err("jk jdk install: stdin is not a TTY — pass `lts` / `latest` "
                    + "or a <spec> (e.g. `jk jdk install temurin-21`) or run interactively.");
            return Exit.USAGE;
        }

        Step fetchCatalog = Step.builder(StepNames.FETCH_CATALOG)
                .kind(StepKind.IO)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("fetch JetBrains JDK feed");
                    boolean refresh =
                            cc.jumpkick.config.SessionContext.current().config().forceOr(false);
                    try {
                        ctx.put(CATALOG, service.fetchCatalog(feedUrl, cacheFile, refresh, ctx::output));
                    } catch (Exception e) {
                        ctx.error("catalog", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    ctx.progress(1);
                })
                .build();

        Step select = Step.builder(StepNames.SELECT)
                .requires(StepNames.FETCH_CATALOG)
                .ticks(1)
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
                        ctx.put(WIZARD_RAN, true);
                    } else {
                        ctx.label("select " + effective);
                        // resolveEntry re-applies the keyword→major translation and
                        // selectPreferred's Temurin bias for vendor-unqualified specs
                        // (e.g. `26`, `25.0.3`); passing the original spec keeps the
                        // resolution identical to the headless path.
                        Optional<JdkCatalog.Entry> selected = service.resolveEntry(catalog, spec, os, arch);
                        if (selected.isEmpty()) {
                            ctx.error("no-match", "no JDK matches " + effective + " on " + os + "/" + arch);
                            throw new RuntimeException("no match");
                        }
                        entry = selected.get();
                    }
                    ctx.put(ENTRY, entry);
                    ctx.put(WANT_DEFAULT, wantDefault);
                    if (global != null && global.verbose) {
                        CliOutput.err("jk jdk install: resolved spec='"
                                + effective
                                + "' to "
                                + entry.installFolderName()
                                + " ("
                                + entry.vendor()
                                + " "
                                + entry.product()
                                + ", "
                                + os
                                + "/"
                                + arch
                                + ")");
                    }
                    ctx.progress(1);
                })
                .build();

        // download + extract are owned by JdkService.install; the CLI keeps only
        // the presentation — an InstallView that drives the download bar and the
        // installing spinner off the facade's listener events, plus the done
        // lines. Merged into one step because install() is a single atomic call;
        // interactive pipelines render via SilentListener, so step labels aren't
        // shown and the bars/done-lines are the only visible output.
        Step install = Step.builder(StepNames.INSTALL)
                .kind(StepKind.IO)
                .requires(StepNames.SELECT)
                .ticks(1)
                .execute(ctx -> {
                    JdkCatalog.Entry entry = ctx.require(ENTRY);
                    boolean refresh =
                            cc.jumpkick.config.SessionContext.current().config().forceOr(false);
                    ctx.label("install " + JdkService.displayLabel(entry));
                    // try-with-resources guarantees the download bar / spinner is
                    // wiped even when the install throws mid-download (matches the
                    // old per-step try-with-resources cleanup).
                    try (InstallView view = new InstallView(entry)) {
                        ctx.put(INSTALLED, service.install(entry, registry, refresh, view));
                    } catch (Exception e) {
                        ctx.error("install", e.getMessage());
                        throw new RuntimeException(e);
                    }
                    ctx.progress(1);
                })
                .build();

        Step setDefault = Step.builder(StepNames.SET_DEFAULT)
                .requires(StepNames.INSTALL)
                .ticks(1)
                .execute(ctx -> {
                    if (!Boolean.TRUE.equals(ctx.get(WANT_DEFAULT).orElse(false))) {
                        ctx.label("skip (not requested)");
                        ctx.progress(1);
                        return;
                    }
                    InstalledJdk installed = ctx.require(INSTALLED);
                    ctx.label("set " + installed.identifier() + " as default");
                    GlobalDefaultJdk.current().set(installed);
                    cc.jumpkick.jdk.JdkAccessLedger.atDefaultPath().touch(installed.identifier(), "default-set");
                    CliOutput.out();
                    CliOutput.out(Theme.colorize("➜", Theme.active().brightGreen())
                            + " "
                            + Theme.colorize(
                                    installed.identifier(), Theme.active().focused())
                            + Theme.colorize(" is now the ", Theme.active().normalGray())
                            + Theme.colorize("default", Theme.active().focused())
                            + Theme.colorize(" JDK", Theme.active().normalGray()));
                    Optional<JdkShell> shell = JdkShell.detect();
                    if (shell.isPresent()) {
                        CliOutput.out(
                                "Add `" + shell.get().hookInstallCommand() + "` to activate JAVA_HOME on new shells.");
                    } else {
                        CliOutput.out("Add `jk hook <bash|zsh|fish>` output to your shell rc "
                                + "to activate JAVA_HOME on new shells.");
                    }
                    ctx.progress(1);
                })
                .build();

        Pipeline pipeline = Pipeline.builder("jdk-install")
                .interactive(true)
                .addStep(fetchCatalog)
                .addStep(select)
                .addStep(install)
                .addStep(setDefault)
                .build();

        PipelineResult result = PipelineConsole.run(pipeline, PipelineConsole.modeFor(global), cache);
        if (!result.success()) return 1;

        // Offer to adopt the new install as the default JDK / default GraalVM.
        // Runs AFTER the pipeline console closes, so the prompt never lands inside a
        // captured-output region. Skipped on a non-TTY (and when --make-default
        // already set the java default).
        //
        // Skipped entirely when the wizard ran: it already asked "Make this the
        // default JDK?", so re-asking here would be a duplicate prompt — and the
        // wizard's own terminal has been closed (taking System.in with it), so a
        // fresh Confirm would fail with "Stream Closed". The post-pipeline offer is
        // for the non-interactive spec path (e.g. `jk jdk install 25`), which
        // never opened a terminal and never asked about the default.
        boolean wizardRan = Boolean.TRUE.equals(pipeline.get(WIZARD_RAN).orElse(false));
        if (!wizardRan && Confirm.isInteractiveTerminal()) {
            boolean wantedDefault =
                    Boolean.TRUE.equals(pipeline.get(WANT_DEFAULT).orElse(false));
            pipeline.get(INSTALLED).ifPresent(jdk -> offerDefaults(jdk, wantedDefault));
        }
        return 0;
    }

    /**
     * Prompt to make {@code jdk} the default JDK and/or default GraalVM when it's ≥ the current ones.
     */
    private void offerDefaults(InstalledJdk jdk, boolean alreadyMadeDefault) {
        int newMajor = JdkListCommand.parseMajor(jdk.identifier());
        if (newMajor == 0 || !Confirm.isInteractiveTerminal()) return;
        // Open one terminal for all prompts in this call — avoids repeated
        // TerminalBuilder probes (DA / DECRQM) that would accumulate in stdin
        // and cause JLine errors on the second Confirm.ask() invocation.
        try (org.jline.terminal.Terminal terminal = Wizard.openTerminal()) {
            Wizard.drainInput(terminal.reader(), 40L);
            cc.jumpkick.jdk.GlobalDefaultJdk defaults = cc.jumpkick.jdk.GlobalDefaultJdk.current();
            if (!alreadyMadeDefault) {
                Integer cur = defaults.currentIdentifier()
                        .map(JdkListCommand::parseMajor)
                        .orElse(null);
                if (cur == null || newMajor >= cur) {
                    if (Confirm.of("Make " + jdk.identifier() + " the default JDK?", true)
                            .ask(terminal)) {
                        defaults.set(jdk);
                    }
                }
            }
            if (isGraalHome(jdk.home())) {
                Integer curGraal = defaults.graalIdentifier()
                        .map(JdkListCommand::parseMajor)
                        .orElse(null);
                if (curGraal == null || newMajor >= curGraal) {
                    if (Confirm.of("Make it the default GraalVM (jk native / GRAALVM_HOME)?", true)
                            .ask(terminal)) {
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
            cc.jumpkick.jdk.JdkVendor v = cc.jumpkick.jdk.JdkVendor.fromRelease(home);
            return v == cc.jumpkick.jdk.JdkVendor.ORACLE_GRAALVM || v == cc.jumpkick.jdk.JdkVendor.GRAALVM_CE;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Translate the keyword specs ({@code lts}, {@code stable}, {@code latest}) into a concrete
     * {@code temurin-<major>} spec string. Returns {@code null} when {@code raw} is not a keyword —
     * the caller treats it as a normal denormalized spec in that case.
     *
     * <p>The Temurin bias matches the user's mental model of "the default LTS JDK"; if Temurin isn't
     * shipped at that major for this host, the flexible selector falls back to the catalog's
     * default-for-major.
     */
    private String resolveKeyword(String raw, JdkCatalog catalog, String os, String arch) {
        if (!JdkKeywords.isKeyword(raw)) return null;
        // Pure keyword→major resolution lives in the engine (shared with the
        // headless path); the CLI adds only the "could not resolve" message.
        String resolved = JdkService.resolveKeyword(raw, catalog, os, arch).orElse(null);
        if (resolved == null) {
            // A keyword resolved to nothing: lts/stable with no LTS major, or
            // `native` with no Oracle GraalVM, for this host.
            CliOutput.err("jk jdk install: could not resolve `"
                    + raw.trim()
                    + "` against the JetBrains feed for "
                    + os
                    + "/"
                    + arch
                    + ".");
        }
        return resolved;
    }

    /**
     * Render the post-install summary line:
     *
     * <pre>
     *   ✓ JDK ▶ {label} {command} {~/path}
     * </pre>
     */
    private static String doneLine(String label, Path home, String command) {
        Theme t = Theme.active();
        boolean nerdfont = cc.jumpkick.config.GlobalConfig.nerdfont();
        String msg = Theme.colorize(label, t.focused())
                + Theme.colorize(" " + command + " ", t.normalGray())
                + Theme.colorize(tildeCollapse(home), t.path());
        return cc.jumpkick.cli.tui.PipelineWedge.chipLine(Glyphs.CHECK, "JDK", nerdfont, msg);
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

    private static JdkInstallWizard.Result runWizard(JdkCatalog catalog, String os, String arch, boolean showAll)
            throws IOException {
        Terminal terminal;
        try {
            terminal = Wizard.openTerminal();
        } catch (IOException e) {
            throw new IOException("failed to open terminal: " + e.getMessage(), e);
        }
        Optional<JdkInstallWizard.Result> result = JdkInstallWizard.run(catalog, os, arch, showAll, terminal);
        if (result.isEmpty()) {
            // Ctrl-C cancellation. Wizard.printCancellation preserves the cyan
            // active-rail closer and prints the red marker beside it.
            //
            // Runtime.halt() instead of System.exit(): halt skips shutdown
            // hooks, which is what we want here — JLine's cleanup hook blocks
            // on its stdin reader thread that macOS won't let us interrupt.
            // The wizard's finally already restored terminal attributes.
            Wizard.printCancellation(terminal, "JDK installation canceled");
            Runtime.getRuntime().halt(130); // 128 + SIGINT
            throw new AssertionError("unreachable");
        }
        terminal.close();
        return result.get();
    }

    private static boolean isInteractiveTerminal() {
        return cc.jumpkick.cli.tui.Interactivity.canPrompt();
    }

    /**
     * CLI presentation for {@link JdkService}'s install pipeline: turns the facade's listener events
     * into the animated {@link cc.jumpkick.cli.tui.JdkDownloadBar} (download then installing spinner)
     * and the {@code doneLine} summaries. {@link AutoCloseable} so a mid-install failure still wipes
     * the active bar (via the step's try-with-resources), matching the old per-step cleanup.
     */
    private static final class InstallView implements JdkInstallListener, AutoCloseable {

        private final String label;
        private cc.jumpkick.cli.tui.JdkDownloadBar bar;

        InstallView(JdkCatalog.Entry entry) {
            this.label = JdkService.displayLabel(entry);
        }

        @Override
        public void onAlreadyInstalled(InstalledJdk jdk) {
            CliOutput.out(doneLine(label, jdk.home(), "is already installed at"));
        }

        @Override
        public void onDownloadStart(String displayName, long totalBytes) {
            bar = cc.jumpkick.cli.tui.JdkDownloadBar.show(CliOutput.stdout(), displayName);
        }

        @Override
        public void onDownloadProgress(long readBytes, long totalBytes) {
            if (bar != null) bar.update(readBytes, totalBytes);
        }

        @Override
        public void onExtractStart(String displayName) {
            if (bar != null) bar.finish();
            bar = cc.jumpkick.cli.tui.JdkDownloadBar.showInstalling(CliOutput.stdout(), displayName);
        }

        @Override
        public void onInstalled(InstalledJdk jdk) {
            if (bar != null) {
                bar.finish();
                bar = null;
            }
            // Print after the spinner is wiped so the done line takes its place.
            CliOutput.out(doneLine(label, jdk.home(), "has been installed to"));
        }

        @Override
        public void close() {
            if (bar != null) {
                bar.finish();
                bar = null;
            }
        }
    }
}
