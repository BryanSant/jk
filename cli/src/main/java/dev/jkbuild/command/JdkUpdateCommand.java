// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.http.Http;
import dev.jkbuild.jdk.GlobalDefaultJdk;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.IntellijJdkDir;
import dev.jkbuild.jdk.JdkCatalog;
import dev.jkbuild.jdk.JdkCatalogClient;
import dev.jkbuild.jdk.JdkGarbage;
import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.jdk.JdkInstaller;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkSelector;
import dev.jkbuild.jdk.StableJdkPointer;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code jk jdk update [spec]} (alias {@code upgrade}) — refresh jk-managed JDKs to the latest
 * point release of their own family and major.
 *
 * <p>Only installs jk owns (under {@code ~/.jk/jdks}, source {@code "jk"}) are touched; SDKMAN /
 * IntelliJ / system / {@code $JAVA_HOME} JDKs are left alone. With no spec every managed JDK is
 * considered; a spec narrows the set with the usual flexible matcher ({@code 25} = major 25 of any
 * vendor, {@code temurin} = all Temurin, {@code temurin-25} = Temurin 25).
 *
 * <p>Each match is updated <em>within its family+major</em>: {@code temurin-25.0.2 →
 * temurin-25.0.3}, never a major bump and never a vendor switch. The newer release is installed and
 * the superseded one removed; if a removed install was the global default, the default is
 * re-pointed at its replacement. The planned changes are shown and confirmed ({@code [Y/n]}) unless
 * {@code --yes} is passed.
 */
public final class JdkUpdateCommand implements CliCommand {

    @Override
    public String name() {
        return "update";
    }

    @Override
    public List<String> aliases() {
        return List.of("upgrade");
    }

    @Override
    public String description() {
        return "Update a Java Development Kit";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Skip the confirmation prompt.", "-y", "--yes"),
                Opt.value("<dir>", "Override the install root. Default: the jk JDK directory.", "--jdks-dir")
                        .hide(),
                Opt.value("<url>", "Override the JetBrains JDK feed URL (for tests).", "--feed-url")
                        .hide(),
                Opt.value("<file>", "Override the catalog cache path (for tests).", "--cache-file")
                        .hide());
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "spec",
                Arity.ZERO_OR_ONE,
                "The vendor/version of JDK you'd like to update\n"
                        + "  (ex: 25, lts, latest, temurin-25, openjdk-26)"));
    }

    /** A planned update: the installed JDK being superseded and the feed entry replacing it. */
    private record Update(JdkHit old, JdkCatalog.Entry target) {}

    private String spec;
    private boolean assumeYes;
    private Path jdksDir;
    private URI feedUrl;
    private Path cacheFile;

    @Override
    public int run(Invocation in) throws Exception {
        this.spec = in.positionals().isEmpty() ? null : in.positionals().get(0);
        this.assumeYes = in.isSet("yes");
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.feedUrl = in.value("feed-url").map(URI::create).orElse(null);
        this.cacheFile = in.value("cache-file").map(Path::of).orElse(null);

        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        // Reclaim any partial archive left by a previously canceled download.
        JdkInstaller.sweepStaleDownloads(registry.jdksRoot());

        // Keyword specs → resolve to the major of the best installed match so
        // managedHits() can filter by that major (e.g. "lts" → "21").
        if (dev.jkbuild.jdk.JdkKeywords.isKeyword(spec)) {
            var kw = dev.jkbuild.jdk.JdkKeywords.bestInstalledMatch(spec, registry.managedHits(null));
            spec = kw.map(h -> {
                        Integer m = dev.jkbuild.jdk.JdkKeywords.leadingMajor(h.version());
                        return m != null ? String.valueOf(m) : JdkRegistry.identifierFor(h.home());
                    })
                    .orElse(spec);
        }
        List<JdkHit> managed = registry.managedHits(spec);
        if (managed.isEmpty()) {
            CliOutput.out(
                    spec == null || spec.isBlank()
                            ? "(no jk-managed JDKs installed)"
                            : "(no jk-managed JDK matches `" + spec + "`)");
            return 0;
        }

        if (!hostSupported()) return 1;
        String os = HostPlatform.currentOs();
        String arch = HostPlatform.currentArch();
        JdkCatalog catalog = fetchCatalog();

        // Build the plan.
        List<Update> updates = new ArrayList<>();
        int upToDate = 0;
        List<String> noTarget = new ArrayList<>();
        for (JdkHit hit : managed) {
            String id = JdkRegistry.identifierFor(hit.home());
            Optional<JdkCatalog.Entry> target = latestPointRelease(catalog, id, os, arch);
            if (target.isEmpty()) {
                noTarget.add(id);
                continue;
            }
            JdkCatalog.Entry e = target.get();
            if (newerThan(e.version(), hit.version())) {
                updates.add(new Update(hit, e));
            } else {
                upToDate++;
            }
        }

        for (String id : noTarget) {
            CliOutput.out(Theme.colorize("•", Theme.active().darkGray())
                    + " "
                    + Theme.colorize(id, Theme.active().cyan())
                    + Theme.colorize(
                            " — no update available in the feed", Theme.active().normalGray()));
        }

        if (updates.isEmpty()) {
            CliOutput.out(Theme.colorize(Glyphs.CHECK, Theme.active().completedStep())
                    + " All "
                    + managed.size()
                    + " jk-managed JDK"
                    + (managed.size() == 1 ? "" : "s")
                    + " are up to date.");
            return 0;
        }

        if (!assumeYes && !confirm(updates)) {
            CliOutput.out("Aborted.");
            return 0;
        }

        return apply(registry, updates) ? 0 : 1;
    }

    // --- apply --------------------------------------------------------------

    private boolean apply(JdkRegistry registry, List<Update> updates) {
        JdkInstaller installer = new JdkInstaller(new Http(), registry);
        GlobalDefaultJdk defaults = GlobalDefaultJdk.current();
        Optional<String> currentDefault;
        try {
            currentDefault = defaults.currentIdentifier();
        } catch (IOException e) {
            currentDefault = Optional.empty();
        }

        Map<String, InstalledJdk> built = new HashMap<>(); // dedupe installs by target folder
        int updated = 0;
        int failed = 0;
        for (Update u : updates) {
            String oldId = JdkRegistry.identifierFor(u.old.home());
            try {
                InstalledJdk newJdk = built.get(u.target.installFolderName());
                if (newJdk == null) {
                    newJdk = installEntry(installer, u.target);
                    built.put(u.target.installFolderName(), newJdk);
                }
                // Repoint the stable <vendor>-<major> handle at the new patch
                // BEFORE the old one is GC'd, so an IntelliJ SDK pinned to the
                // stable path never dangles. (install() refreshes it too, but
                // the alreadyInstalled fast path can skip that.)
                repointStablePointer(registry, newJdk);
                if (!oldId.equals(newJdk.identifier())) {
                    // Defer-delete the superseded patch: a running JVM may still
                    // hold it open (Windows can't unlink an in-use dir).
                    new JdkGarbage(registry.jdksRoot()).enqueue(IntellijJdkDir.installDirOf(u.old.home()));
                }
                if (currentDefault.isPresent() && currentDefault.get().equals(oldId)) {
                    defaults.set(newJdk);
                }
                CliOutput.out(Theme.colorize(Glyphs.CHECK, Theme.active().completedStep())
                        + " Updated "
                        + Theme.colorize(oldId, Theme.active().warning())
                        + " "
                        + Theme.colorize("→", Theme.active().darkGray())
                        + " "
                        + Theme.colorize(newJdk.identifier(), Theme.active().focused()));
                updated++;
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                CliOutput.out(Theme.colorize(Glyphs.CROSS, Theme.active().error())
                        + " Failed to update "
                        + Theme.colorize(oldId, Theme.active().warning())
                        + ": "
                        + e.getMessage());
                failed++;
            }
        }

        // Reap anything just enqueued (and any survivors from prior runs).
        new JdkGarbage(registry.jdksRoot()).drain();

        CliOutput.out(updated + " updated" + (failed > 0 ? ", " + failed + " failed" : ""));
        return failed == 0;
    }

    /**
     * Idempotently repoint {@code <vendor>-<major> → <install dir>} for a freshly-built JDK. Derives
     * the names from the install identifier ({@code temurin-25.0.4} → pointer {@code temurin-25}).
     */
    private static void repointStablePointer(JdkRegistry registry, InstalledJdk jdk) {
        JdkSelector.FlexibleQuery q = JdkSelector.parseFlexible(jdk.identifier());
        if (q.major().isEmpty() || q.hints().isEmpty()) return;
        String pointer = q.hints().get(0) + "-" + q.major().get();
        try {
            new StableJdkPointer(registry.jdksRoot()).ensure(pointer, IntellijJdkDir.installDirOf(jdk.home()));
        } catch (IOException ignored) {
            // Pointer is a convenience; the update itself already succeeded.
        }
    }

    /** Download + extract {@code entry} with a progress bar; journal the install. */
    private InstalledJdk installEntry(JdkInstaller installer, JdkCatalog.Entry entry)
            throws IOException, InterruptedException {
        InstalledJdk already = installer.alreadyInstalled(entry);
        if (already != null) return already;

        String label = entry.vendor() + " " + entry.product() + " " + entry.majorVersion();
        long total = entry.archiveSize();
        InstalledJdk installed;
        try (dev.jkbuild.cli.tui.JdkDownloadBar pb = dev.jkbuild.cli.tui.JdkDownloadBar.show(CliOutput.stdout(), label)) {
            installed = installer.install(entry, bytes -> pb.update(bytes, total));
            pb.finish();
        }
        dev.jkbuild.jdk.JdkAccessLedger.atDefaultPath().touch(installed.identifier(), "install");
        return installed;
    }

    // --- planning helpers ---------------------------------------------------

    /**
     * Highest-versioned non-preview catalog entry on this host that belongs to the same family as the
     * installed id (its {@code suggested_sdk_name} is a delimiter-bounded prefix of the id). The
     * suggested name encodes the major, so this never crosses majors.
     */
    private static Optional<JdkCatalog.Entry> latestPointRelease(
            JdkCatalog catalog, String installedId, String os, String arch) {
        JdkCatalog.Entry best = null;
        for (JdkCatalog.Entry e : catalog.entries()) {
            if (e.preview()) continue;
            if (!e.os().equals(os) || !e.arch().equals(arch)) continue;
            if (!belongsToFamily(installedId, e.suggestedSdkName())) continue;
            if (best == null || newerThan(e.version(), best.version())) best = e;
        }
        return Optional.ofNullable(best);
    }

    /** Does install id {@code id} belong to the family named by {@code suggested}? */
    private static boolean belongsToFamily(String id, String suggested) {
        if (suggested == null || suggested.isEmpty()) return false;
        return id.equals(suggested)
                || id.startsWith(suggested + ".")
                || id.startsWith(suggested + "-")
                || id.startsWith(suggested + "+");
    }

    /** {@code a > b} by {@link JdkSelector#versionKey} ordering ({@code 25.0.10 > 25.0.9}). */
    private static boolean newerThan(String a, String b) {
        if (a == null) return false;
        if (b == null) return true;
        return JdkSelector.versionKey(a).compareTo(JdkSelector.versionKey(b)) > 0;
    }

    // --- confirmation -------------------------------------------------------

    private boolean confirm(List<Update> updates) {
        CliOutput.out(
                "\nThe following " + updates.size() + " JDK" + (updates.size() == 1 ? "" : "s") + " will be updated:");
        for (Update u : updates) {
            CliOutput.out("   "
                    + Theme.colorize(
                            JdkRegistry.identifierFor(u.old.home()),
                            Theme.active().warning())
                    + " "
                    + Theme.colorize("→", Theme.active().darkGray())
                    + " "
                    + Theme.colorize(
                            u.target.installFolderName(), Theme.active().focused()));
        }
        return dev.jkbuild.cli.tui.Confirm.of(Theme.colorize(Glyphs.BANG, Theme.active().warning()) + " Proceed?", true)
                .ask();
    }

    // --- shared mechanics (mirrors JdkEnsureCommand) ------------------------

    private boolean hostSupported() {
        if (HostPlatform.supported()) return true;
        CliOutput.err("jk jdk update: host "
                + System.getProperty("os.name")
                + "/"
                + System.getProperty("os.arch")
                + " is not covered by the JetBrains JDK feed. Set JAVA_HOME explicitly.");
        return false;
    }

    private JdkCatalog fetchCatalog() throws IOException, InterruptedException {
        boolean refresh = dev.jkbuild.config.SessionContext.current().config().refreshOr(false);
        JdkCatalogClient client = (feedUrl != null
                        ? new JdkCatalogClient(
                                new Http(),
                                feedUrl,
                                cacheFile != null ? cacheFile : ephemeralCachePath(),
                                Duration.ZERO)
                        : new JdkCatalogClient())
                .onWarning(CliOutput.stderr()::println);
        return client.fetch(refresh);
    }

    private static Path ephemeralCachePath() throws IOException {
        Path tmp = java.nio.file.Files.createTempFile("jk-feed-", ".json.xz");
        tmp.toFile().deleteOnExit();
        java.nio.file.Files.delete(tmp); // force a fresh fetch
        return tmp;
    }
}
