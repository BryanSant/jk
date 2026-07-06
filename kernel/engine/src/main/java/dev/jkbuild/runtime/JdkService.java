// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.http.Http;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.JdkAccessLedger;
import dev.jkbuild.jdk.JdkCatalog;
import dev.jkbuild.jdk.JdkCatalogClient;
import dev.jkbuild.jdk.JdkInstaller;
import dev.jkbuild.jdk.JdkKeywords;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkSelector;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Engine-side facade for the <em>non-interactive</em> {@code jk jdk install} pipeline: fetch the
 * JetBrains JDK feed, resolve a spec/keyword to a catalog entry, and download + extract it. All
 * progress is reported through a {@link JdkInstallListener}; nothing here writes to stdout/stderr,
 * opens a terminal, prompts, or references any CLI type. That interactivity — the install wizard,
 * the animated download bar, the "make this the default?" prompt — stays entirely in the CLI, which
 * drives this facade and renders the events.
 *
 * <p>This is deliberately separate from {@link BuildService}: installing a JDK is a toolchain
 * lifecycle operation, not a build. It is also distinct from {@link JdkEnsure} (the resolve-or-
 * install step {@code jk sync} runs): {@code JdkEnsure} walks the full resolution order and refuses
 * a {@code refresh}, whereas this facade backs the explicit {@code jk jdk install [<spec>]} command,
 * where {@code --refresh} forcing a reinstall is a first-class option.
 *
 * <p>A headless front-end (web / IDE / MCP) uses the one-call {@link #install(String, JdkRegistry,
 * boolean, URI, Path, String, String, JdkInstallListener)} aggregate; the CLI composes the finer
 * {@link #fetchCatalog}, {@link #resolveEntry} and {@link #install(JdkCatalog.Entry, JdkRegistry,
 * boolean, JdkInstallListener)} steps so it can slot its wizard in between resolution and install.
 */
public final class JdkService {

    /**
     * Fetch the JDK catalog, mirroring the CLI's {@code fetch-catalog} phase.
     *
     * @param feedUrl override feed URL, or {@code null} for the default JetBrains feed + default
     *     cache + 24h TTL
     * @param cacheFile override cache path; only consulted when {@code feedUrl} is set. When {@code
     *     null} (with a {@code feedUrl}), an ephemeral throwaway cache forces a fresh fetch
     * @param refresh skip the TTL check and always re-fetch from the network
     * @param warn sink for degradation notices (e.g. "feed unreachable, using cached"); {@code null}
     *     discards them
     */
    public JdkCatalog fetchCatalog(URI feedUrl, Path cacheFile, boolean refresh, Consumer<String> warn)
            throws IOException, InterruptedException {
        JdkCatalogClient client = feedUrl != null
                ? new JdkCatalogClient(
                        new Http(), feedUrl, cacheFile != null ? cacheFile : ephemeralCachePath(), Duration.ZERO)
                : new JdkCatalogClient();
        client.onWarning(warn != null ? warn : s -> {});
        return client.fetch(refresh);
    }

    /**
     * Resolve a spec or keyword to a catalog entry — the CLI's non-wizard {@code select} logic,
     * shared verbatim with headless callers. Keyword specs ({@code lts} / {@code stable} / {@code
     * latest} / {@code native}) are translated to a concrete {@code temurin-<major>} / GraalVM spec
     * via {@link #resolveKeyword}; everything else is passed straight through. Selection then applies
     * {@link JdkSelector#selectPreferred}'s vendor bias. Returns empty when nothing matches on the
     * given host.
     */
    public Optional<JdkCatalog.Entry> resolveEntry(JdkCatalog catalog, String spec, String os, String arch) {
        Objects.requireNonNull(catalog, "catalog");
        String effective = resolveKeyword(spec, catalog, os, arch).orElse(spec);
        if (effective == null || effective.isBlank()) return Optional.empty();
        return JdkSelector.selectPreferred(catalog, effective, os, arch);
    }

    /**
     * Translate a keyword spec ({@code lts} / {@code stable} / {@code latest} / {@code native}) into
     * a concrete {@code <vendor>-<major>} spec for this host. Returns empty when {@code raw} is not a
     * keyword (the caller treats it as a normal spec) or when the keyword resolves to nothing for the
     * host (no LTS major, no Oracle GraalVM, …). Pure over the catalog — shared by the CLI (which
     * adds its own "could not resolve" message) and headless callers.
     */
    public static Optional<String> resolveKeyword(String raw, JdkCatalog catalog, String os, String arch) {
        if (!JdkKeywords.isKeyword(raw)) return Optional.empty();
        return JdkKeywords.resolveToMajorSpec(catalog, raw, os, arch);
    }

    /**
     * Install a resolved catalog entry, emitting progress to {@code listener}. Owns the {@code
     * download} + {@code extract} pipeline:
     *
     * <ol>
     *   <li>Unless {@code refresh}, short-circuit when the entry is already installed — emit {@link
     *       JdkInstallListener#onAlreadyInstalled} and return the existing install.
     *   <li>Otherwise download (streaming byte counts to {@link
     *       JdkInstallListener#onDownloadProgress}) and extract, then journal the install to the
     *       {@link JdkAccessLedger} and emit {@link JdkInstallListener#onInstalled}.
     * </ol>
     *
     * Writes nothing to stdout/stderr — all rendering is the listener's job.
     */
    public InstalledJdk install(
            JdkCatalog.Entry entry, JdkRegistry registry, boolean refresh, JdkInstallListener listener)
            throws IOException, InterruptedException {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(registry, "registry");
        JdkInstallListener l = listener != null ? listener : JdkInstallListener.NO_OP;

        JdkInstaller installer = new JdkInstaller(new Http(), registry);
        InstalledJdk already = refresh ? null : installer.alreadyInstalled(entry);
        if (already != null) {
            l.onAlreadyInstalled(already);
            return already;
        }

        String label = displayLabel(entry);
        long total = entry.archiveSize();
        l.onDownloadStart(label, total);
        JdkInstaller.DownloadedArchive archive = installer.download(entry, read -> l.onDownloadProgress(read, total));
        l.onExtractStart(label);
        InstalledJdk installed = installer.extractInstalled(entry, archive);
        // Journal the install for the JDK-usage stats that feed future wizards.
        JdkAccessLedger.atDefaultPath().touch(installed.identifier(), "install");
        l.onInstalled(installed);
        return installed;
    }

    /**
     * One-call install for a headless front-end: fetch the catalog, resolve {@code spec}, and install
     * the winning entry — equivalent to {@link #fetchCatalog} + {@link #resolveEntry} + {@link
     * #install(JdkCatalog.Entry, JdkRegistry, boolean, JdkInstallListener)}. Emits {@link
     * JdkInstallListener#onResolved} once the spec resolves, then the usual install events.
     *
     * @throws IllegalArgumentException when {@code spec} matches no JDK on {@code os}/{@code arch}
     */
    public InstalledJdk install(
            String spec,
            JdkRegistry registry,
            boolean refresh,
            URI feedUrl,
            Path cacheFile,
            String os,
            String arch,
            JdkInstallListener listener)
            throws IOException, InterruptedException {
        Objects.requireNonNull(registry, "registry");
        JdkInstallListener l = listener != null ? listener : JdkInstallListener.NO_OP;
        // Reclaim any partial archive orphaned by a previously canceled download.
        JdkInstaller.sweepStaleDownloads(registry.jdksRoot());

        JdkCatalog catalog = fetchCatalog(feedUrl, cacheFile, refresh, null);
        JdkCatalog.Entry entry = resolveEntry(catalog, spec, os, arch)
                .orElseThrow(() -> new IllegalArgumentException("no JDK matches " + spec + " on " + os + "/" + arch));
        l.onResolved(entry);
        return install(entry, registry, refresh, l);
    }

    /** The human-readable JDK label used in progress + result lines, e.g. {@code "Eclipse Temurin 26"}. */
    public static String displayLabel(JdkCatalog.Entry entry) {
        return entry.vendor() + " " + entry.product() + " " + entry.majorVersion();
    }

    /** A temp cache path that forces a fresh feed fetch (created, then deleted, and removed on exit). */
    private static Path ephemeralCachePath() throws IOException {
        Path tmp = Files.createTempFile("jk-feed-", ".json.xz");
        tmp.toFile().deleteOnExit();
        Files.delete(tmp); // force a fresh fetch
        return tmp;
    }
}
