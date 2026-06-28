// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.config.GlobalConfig;
import dev.jkbuild.http.Http;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.JdkCatalog;
import dev.jkbuild.jdk.JdkCatalogClient;
import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.jdk.JdkInstaller;
import dev.jkbuild.jdk.JdkKeywords;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkSelector;
import dev.jkbuild.jdk.JdkSpec;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk jdk ensure <spec>} — guarantee that a JDK satisfying {@code <spec>} is installed,
 * downloading it from the JetBrains JDK feed when nothing on the host already qualifies, then print
 * where it lives.
 *
 * <p>Satisfaction is freshness-aware (unlike the project-scoped {@link
 * dev.jkbuild.runtime.JdkEnsure}, whose match is exact):
 *
 * <ul>
 *   <li>a bare major ({@code 25}) is satisfied by <em>any</em> installed point release of that
 *       major;
 *   <li>a full version ({@code 25.0.3}) is a <em>minimum</em> — an installed {@code 25.0.2} forces
 *       an install, {@code 25.0.4} satisfies it;
 *   <li>{@code lts} / {@code stable} requires the <em>latest</em> LTS point release in the feed;
 *       {@code latest} the latest GA point release — an older installed copy forces an upgrade.
 *   <li>{@code native} requires the latest <em>Oracle GraalVM</em> (the native-image build);
 *       satisfied by any installed GraalVM at that version.
 * </ul>
 *
 * <p>When the spec can't be satisfied from the feed at all (e.g. a major or point release that
 * doesn't exist), it warns and installs the latest LTS instead, still exiting {@code 0}.
 */
public final class JdkEnsureCommand implements CliCommand {

    @Override
    public String name() {
        return "ensure";
    }

    @Override
    public String description() {
        return "Ensure a Java Development Kit is available";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Override the install root. Default: the IntelliJ JDK directory.", "--jdks-dir")
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
                Arity.ONE,
                "The vendor/version of JDK you'd like to ensure is installed\n"
                        + "  and available (ex: 25, lts, latest, temurin-25, openjdk-26)"));
    }

    private String spec;
    private Path jdksDir;
    private URI feedUrl;
    private Path cacheFile;

    @Override
    public int run(Invocation in) throws Exception {
        this.spec = in.positionals().isEmpty() ? null : in.positionals().get(0);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.feedUrl = in.value("feed-url").map(URI::create).orElse(null);
        this.cacheFile = in.value("cache-file").map(Path::of).orElse(null);

        if (spec == null || spec.isBlank()) {
            System.err.println("jk jdk ensure: a <spec> is required "
                    + "(e.g. `jk jdk ensure 25`, `jk jdk ensure 25.0.3`, `jk jdk ensure lts`).");
            return 64; // EX_USAGE
        }

        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        JdkInstaller installer = new JdkInstaller(new Http(), registry);

        return JdkKeywords.isKeyword(spec) ? ensureKeyword(registry, installer) : ensureVersion(registry, installer);
    }

    // --- version specs (bare major / full version / vendor-hinted) ----------

    private int ensureVersion(JdkRegistry registry, JdkInstaller installer) throws IOException, InterruptedException {
        JdkSelector.FlexibleQuery q = JdkSelector.parseFlexible(spec);
        String min = q.exactVersion().orElse(null);

        // Fast path: when the major is known we can answer from installed JDKs
        // without touching the network.
        if (q.major().isPresent()
                && reportIfPresent(registry.findHitAtLeast(q.major().get(), min, q.hints()))) {
            return 0;
        }

        // Need the catalog to install (or to learn the major for a hint-only spec).
        if (!hostSupported()) return 1;
        String os = HostPlatform.currentOs();
        String arch = HostPlatform.currentArch();
        JdkCatalog catalog = fetchCatalog();

        // Install candidate = latest point release of the requested major
        // (honoring vendor hints). The exact version is only a floor, never the
        // candidate — that's what lets a ≥ match install a newer point release
        // when the feed has moved past the requested one. selectPreferred adds
        // the Temurin bias when no vendor was named.
        String selectInput =
                q.major().isPresent() ? hintsAndMajor(q.hints(), q.major().get()) : spec;
        Optional<JdkCatalog.Entry> candidate = JdkSelector.selectPreferred(catalog, selectInput, os, arch);

        if (candidate.isPresent()) {
            JdkCatalog.Entry e = candidate.get();
            // Hint-only spec (no major typed): now that the feed told us the
            // major, re-check installed before downloading.
            if (q.major().isEmpty() && reportIfPresent(registry.findHitAtLeast(e.majorVersion(), min, q.hints()))) {
                return 0;
            }
            if (min == null || atLeast(e.version(), min)) {
                report(label(e), installEntry(installer, e, unableToLocatePreface(e)));
                return 0;
            }
        }
        return fallbackToLts(registry, installer, catalog, os, arch);
    }

    // --- keyword specs (lts / stable / latest) ------------------------------

    private int ensureKeyword(JdkRegistry registry, JdkInstaller installer) throws IOException, InterruptedException {
        if (!hostSupported()) return 1;
        String os = HostPlatform.currentOs();
        String arch = HostPlatform.currentArch();
        JdkCatalog catalog = fetchCatalog();

        Optional<JdkCatalog.Entry> entry = JdkKeywords.resolveToMajorSpec(catalog, spec, os, arch)
                .flatMap(majorSpec -> JdkSelector.select(catalog, JdkSpec.parse(majorSpec), os, arch));
        if (entry.isEmpty()) {
            // No LTS major in the feed (for lts/stable) or empty feed; the LTS
            // fallback resolves the same way, so let it surface the error.
            return fallbackToLts(registry, installer, catalog, os, arch);
        }
        JdkCatalog.Entry e = entry.get();
        // The latest point release is the floor — an older installed copy
        // doesn't satisfy a keyword spec. `native` additionally requires a
        // GraalVM (satisfactionHints); lts/latest are vendor-agnostic.
        if (reportIfPresent(
                registry.findHitAtLeast(e.majorVersion(), e.version(), JdkKeywords.satisfactionHints(spec)))) {
            return 0;
        }
        report(label(e), installEntry(installer, e, unableToLocatePreface(e)));
        return 0;
    }

    // --- fallback -----------------------------------------------------------

    private int fallbackToLts(JdkRegistry registry, JdkInstaller installer, JdkCatalog catalog, String os, String arch)
            throws IOException, InterruptedException {
        Optional<JdkCatalog.Entry> entry = JdkKeywords.resolveToMajorSpec(catalog, "lts", os, arch)
                .flatMap(majorSpec -> JdkSelector.select(catalog, JdkSpec.parse(majorSpec), os, arch));
        if (entry.isEmpty()) {
            System.err.println("jk jdk ensure: no JDK matches "
                    + spec
                    + " and no LTS JDK is available for "
                    + os
                    + "/"
                    + arch
                    + ".");
            return 1;
        }
        JdkCatalog.Entry e = entry.get();
        String head = Theme.colorize(Glyphs.BANG, Theme.active().warning())
                + " no JDK matches "
                + Theme.colorize(spec, Theme.active().cyan())
                + " in the JetBrains feed — ";
        Optional<JdkHit> hit = registry.findHitAtLeast(e.majorVersion(), e.version(), List.of());
        if (hit.isPresent()) {
            System.out.println(head
                    + "using the latest LTS "
                    + Theme.colorize(label(e), Theme.active().cyan())
                    + " instead");
            report(JdkRender.displayName(hit.get()), hit.get().home(), false);
            return 0;
        }
        String preface = head
                + "installing the latest LTS "
                + Theme.colorize(label(e), Theme.active().cyan())
                + " instead...";
        report(label(e), installEntry(installer, e, preface));
        return 0;
    }

    // --- shared mechanics ---------------------------------------------------

    /**
     * Install {@code entry} (skipping the download when it's already on disk), with a progress bar.
     * {@code preface} is printed immediately before the download starts — but only when a download
     * actually happens, so an already-present install stays silent.
     */
    private InstalledJdk installEntry(JdkInstaller installer, JdkCatalog.Entry entry, String preface)
            throws IOException, InterruptedException {
        InstalledJdk already = installer.alreadyInstalled(entry);
        if (already != null) return already;

        if (preface != null) System.out.println(preface);
        String label = label(entry);
        long total = entry.archiveSize();
        InstalledJdk installed;
        try (dev.jkbuild.cli.tui.JdkDownloadBar pb = dev.jkbuild.cli.tui.JdkDownloadBar.show(System.out, label)) {
            installed = installer.install(entry, bytes -> pb.update(bytes, total));
            pb.finish();
        }
        // Journal the install for the JDK-usage stats, same as `jk jdk install`.
        dev.jkbuild.jdk.JdkAccessLedger.atDefaultPath().touch(installed.identifier(), "install");
        return installed;
    }

    /** The human label for an entry, matching the download bar's "vendor product major". */
    private static String label(JdkCatalog.Entry e) {
        return e.vendor() + " " + e.product() + " " + e.majorVersion();
    }

    /** Pre-download notice shown when nothing installed satisfied the spec. */
    private static String unableToLocatePreface(JdkCatalog.Entry e) {
        return Theme.colorize(
                        "Unable to locate a suitable JDK. Installing ",
                        Theme.active().normalGray())
                + Theme.colorize(label(e), Theme.active().cyan())
                + Theme.colorize("...", Theme.active().normalGray());
    }

    /** Print the report line for an installed hit if present; return whether it was. */
    private static boolean reportIfPresent(Optional<JdkHit> hit) {
        if (hit.isEmpty()) return false;
        report(JdkRender.displayName(hit.get()), hit.get().home(), false);
        return true;
    }

    private static void report(String displayName, InstalledJdk jdk) {
        report(displayName, jdk.home(), true);
    }

    private static void report(String displayName, Path home, boolean downloaded) {
        System.out.println(JdkRender.available(displayName, home, GlobalConfig.nerdfont(), downloaded));
    }

    /** {@code true} when {@code version >= floor} per {@link JdkSelector#versionKey} ordering. */
    private static boolean atLeast(String version, String floor) {
        return JdkSelector.versionKey(version).compareTo(JdkSelector.versionKey(floor)) >= 0;
    }

    /**
     * Build a selector input that targets the latest point release of {@code major}, honoring hints.
     */
    private static String hintsAndMajor(List<String> hints, int major) {
        String h = String.join("-", hints);
        return h.isEmpty() ? String.valueOf(major) : h + "-" + major;
    }

    private boolean hostSupported() {
        if (HostPlatform.supported()) return true;
        System.err.println("jk jdk ensure: host "
                + System.getProperty("os.name")
                + "/"
                + System.getProperty("os.arch")
                + " is not covered by the JetBrains JDK feed. Set JAVA_HOME explicitly.");
        return false;
    }

    private JdkCatalog fetchCatalog() throws IOException, InterruptedException {
        boolean refresh = dev.jkbuild.config.ActiveConfig.get().refreshOr(false);
        JdkCatalogClient client = (feedUrl != null
                        ? new JdkCatalogClient(
                                new Http(),
                                feedUrl,
                                cacheFile != null ? cacheFile : ephemeralCachePath(),
                                Duration.ZERO)
                        : new JdkCatalogClient())
                .onWarning(System.err::println);
        return client.fetch(refresh);
    }

    private static Path ephemeralCachePath() throws IOException {
        Path tmp = java.nio.file.Files.createTempFile("jk-feed-", ".json.xz");
        tmp.toFile().deleteOnExit();
        java.nio.file.Files.delete(tmp); // force a fresh fetch
        return tmp;
    }
}
