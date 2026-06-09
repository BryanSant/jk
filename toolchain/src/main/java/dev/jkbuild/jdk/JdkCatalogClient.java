// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import dev.jkbuild.http.Http;
import dev.jkbuild.util.JkDirs;
import java.io.BufferedReader;
import java.io.StringReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Client for the JetBrains JDK feed
 * ({@value #DEFAULT_FEED_URL}). Fetches the xz-compressed JSON catalog,
 * caches it on disk with a 24 h TTL, revalidates with conditional GET,
 * and falls back to the cached copy when offline.
 *
 * <p>The feed is the same source IntelliJ uses, so any JDK jk downloads
 * lands in IntelliJ's expected directory and vice versa.
 */
public final class JdkCatalogClient {

    public static final String DEFAULT_FEED_URL =
            "https://download.jetbrains.com/jdk/feed/v1/jdks.json";

    /** 24 h — the feed publishes new GA releases at most a few times a month. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'")
                    .withZone(ZoneId.of("GMT"));

    private final Http http;
    private final URI feedUri;
    private final Path cacheFile;
    private final Duration ttl;

    /**
     * Where the "feed unreachable, using cached" degradation notice goes.
     * No-op by default — only the CLI view layer owns the streams, so callers
     * there install {@code System.err::println} via {@link #onWarning}, and
     * in-phase callers route it to {@code PhaseContext::warn}.
     */
    private java.util.function.Consumer<String> warn = s -> {};

    public JdkCatalogClient() {
        this(new Http(), URI.create(DEFAULT_FEED_URL), defaultCachePath(), DEFAULT_TTL);
    }

    public JdkCatalogClient(Http http, URI feedUri, Path cacheFile, Duration ttl) {
        this.http = Objects.requireNonNull(http, "http");
        this.feedUri = Objects.requireNonNull(feedUri, "feedUri");
        this.cacheFile = Objects.requireNonNull(cacheFile, "cacheFile");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
    }

    /** Install a sink for degradation warnings; returns {@code this} for chaining. */
    public JdkCatalogClient onWarning(java.util.function.Consumer<String> sink) {
        this.warn = Objects.requireNonNull(sink, "sink");
        return this;
    }

    /** Default cache location: {@code $JK_CACHE_DIR/jdks.json}. */
    public static Path defaultCachePath() {
        return JkDirs.cache().resolve("jdks.json");
    }

    /**
     * Fetch the catalog, honouring the on-disk cache and TTL.
     * Network errors fall back to whatever the cache holds (with a stderr
     * warning) so {@code jk jdk list} stays useful offline.
     */
    public JdkCatalog fetch() throws IOException, InterruptedException {
        return fetch(false);
    }

    /**
     * Fetch the catalog. When {@code noCache} is {@code true} the TTL
     * check is skipped and the feed is always re-fetched from the network.
     */
    public JdkCatalog fetch(boolean noCache) throws IOException, InterruptedException {
        return fetch(noCache, true);
    }

    /**
     * Fetch the catalog, choosing how aggressively to prune majors.
     *
     * <p>{@code firstClassOnly} = {@code true} (the default for every
     * resolution / auto-install path) keeps jk's curated set — LTS majors
     * at or above {@link SupportedJdk#MIN_MAJOR} plus the single most-recent
     * major. {@code false} keeps <em>every</em> major at or above the floor,
     * which is what {@code jk jdk list --all} wants: a complete catalogue of
     * installable vendors / products / majors, not just the recommended ones.
     */
    public JdkCatalog fetch(boolean noCache, boolean firstClassOnly)
            throws IOException, InterruptedException {
        byte[] body = loadBody(noCache);
        return parse(body, firstClassOnly);
    }

    private byte[] loadBody(boolean noCache) throws IOException, InterruptedException {
        boolean cacheFresh = !noCache
                && Files.isRegularFile(cacheFile)
                && Files.size(cacheFile) > 0
                && cacheAge().compareTo(ttl) < 0;
        if (cacheFresh) {
            return Files.readAllBytes(cacheFile);
        }
        try {
            Map<String, String> headers = Files.isRegularFile(cacheFile)
                    ? Map.of("If-Modified-Since",
                            HTTP_DATE.format(Files.getLastModifiedTime(cacheFile).toInstant()))
                    : Map.of();
            HttpResponse<byte[]> response = http.get(feedUri, headers);
            int status = response.statusCode();
            if (status == 304 && Files.isRegularFile(cacheFile)) {
                Files.setLastModifiedTime(cacheFile, FileTime.from(Instant.now()));
                return Files.readAllBytes(cacheFile);
            }
            if (status == 200) {
                byte[] payload = response.body();
                writeCache(payload);
                return payload;
            }
            throw new IOException("JDK feed " + feedUri + " returned HTTP " + status);
        } catch (IOException | InterruptedException e) {
            if (Files.isRegularFile(cacheFile)) {
                warn.accept("jk: warning — JDK feed unreachable, using cached "
                        + cacheFile + " (" + e.getMessage() + ")");
                return Files.readAllBytes(cacheFile);
            }
            throw e;
        }
    }

    private Duration cacheAge() throws IOException {
        Instant mtime = Files.getLastModifiedTime(cacheFile).toInstant();
        return Duration.between(mtime, Instant.now());
    }

    private void writeCache(byte[] payload) throws IOException {
        Files.createDirectories(cacheFile.getParent());
        Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
        Files.write(tmp, payload);
        Files.move(tmp, cacheFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Parse the JDK catalog from raw (uncompressed) JSON bytes using a
     * stateful line scanner — no JSON library required. The feed is always
     * pretty-printed with one key/value per line, so simple line-by-line
     * field extraction is reliable.
     */
    private JdkCatalog parse(byte[] json, boolean firstClassOnly) throws IOException {
        List<JdkCatalog.Entry> entries = new ArrayList<>(256);

        // Per-JDK fields (depth 2)
        String vendor = null, product = null, suggestedSdkName = null, version = null;
        int majorVersion = 0;
        boolean defaultForMajor = false, preview = false;
        List<String> aliases = new ArrayList<>();
        boolean inAliases = false;

        // Per-package fields (depth 3)
        String os = null, arch = null, packageType = null, url = null;
        String sha256 = null, installFolderName = null, javaHomeSubpath = null;
        long archiveSize = 0;

        int depth = 0;
        boolean inPackages = false;

        try (BufferedReader br = new BufferedReader(
                new StringReader(new String(json, StandardCharsets.UTF_8)))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.strip();
                // Depth tracking
                for (int i = 0; i < t.length(); i++) {
                    char c = t.charAt(i);
                    if (c == '"') { // skip string contents
                        i++;
                        while (i < t.length() && t.charAt(i) != '"') {
                            if (t.charAt(i) == '\\') i++;
                            i++;
                        }
                    } else if (c == '{') depth++;
                    else if (c == '}') {
                        if (depth == 3 && inPackages) {
                            // End of a package entry — emit if complete
                            if (url != null && !url.isEmpty()
                                    && installFolderName != null && !installFolderName.isEmpty()) {
                                try {
                                    entries.add(new JdkCatalog.Entry(
                                            vendor, product, suggestedSdkName, majorVersion, version,
                                            defaultForMajor, preview, List.copyOf(aliases),
                                            os, arch, packageType, URI.create(url), sha256,
                                            archiveSize, installFolderName, javaHomeSubpath));
                                } catch (IllegalArgumentException ignored) { /* bad URL */ }
                            }
                            os = arch = packageType = url = sha256 = installFolderName = javaHomeSubpath = null;
                            archiveSize = 0;
                        } else if (depth == 2) {
                            // End of JDK entry
                            vendor = product = suggestedSdkName = version = null;
                            majorVersion = 0; defaultForMajor = false; preview = false;
                            aliases = new ArrayList<>();
                            inPackages = false;
                        }
                        depth--;
                    }
                }

                // Field extraction
                int colon = t.indexOf(':');
                if (colon <= 0) {
                    // Array element (alias string)
                    if (inAliases && t.startsWith("\"")) {
                        String v = unquote(t.replaceAll(",$", ""));
                        if (!v.isEmpty()) aliases.add(v);
                    }
                    if (t.equals("]")) inAliases = false;
                    continue;
                }
                String key = unquote(t.substring(0, colon).strip());
                String val = t.substring(colon + 1).strip().replaceAll(",$", "");
                boolean isArray = val.startsWith("[");
                boolean isObj   = val.startsWith("{");
                if (!isArray && !isObj) val = unquote(val);

                if (depth == 2) {
                    switch (key) {
                        case "vendor"              -> vendor            = val;
                        case "product"             -> product           = val;
                        case "suggested_sdk_name"  -> suggestedSdkName  = val;
                        case "jdk_version_major"   -> { try { majorVersion = Integer.parseInt(val); } catch (NumberFormatException e2) { majorVersion = 0; } }
                        case "jdk_version"         -> version           = val;
                        case "default"             -> defaultForMajor   = "true".equals(val);
                        case "preview"             -> preview           = "true".equals(val);
                        case "packages"            -> inPackages        = true;
                        case "shared_index_aliases"-> { if (isArray && !val.equals("[]")) inAliases = true; }
                    }
                } else if (depth == 3 && inPackages) {
                    switch (key) {
                        case "os"                          -> os                = val;
                        case "arch"                        -> arch               = val;
                        case "package_type"                -> packageType        = val;
                        case "url"                         -> url                = val;
                        case "sha256"                      -> sha256             = val;
                        case "archive_size"                -> { try { archiveSize = Long.parseLong(val); } catch (NumberFormatException e2) { archiveSize = 0; } }
                        case "install_folder_name"         -> installFolderName  = val;
                        case "package_to_java_home_prefix" -> javaHomeSubpath    = val;
                    }
                }
            }
        }
        return new JdkCatalog(filterSupported(entries, firstClassOnly));
    }

    /** Strip surrounding double-quotes and unescape basic sequences. */
    private static String unquote(String s) {
        s = s.strip();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/");
    }

    /**
     * Drop catalog rows for majors jk doesn't claim to support. Always drops
     * anything below {@link SupportedJdk#MIN_MAJOR}. When {@code firstClassOnly}
     * is set, also drops any non-LTS that isn't the single most-recent major in
     * the feed — the JetBrains feed publishes 8 / 11 / 17 / 21 / 23 / 24 / 25 /
     * 26 / …, so that pass keeps {17, 21, 25, latestMajor}. With it cleared,
     * every major at or above the floor is kept (for {@code jk jdk list --all}).
     */
    private static List<JdkCatalog.Entry> filterSupported(
            List<JdkCatalog.Entry> all, boolean firstClassOnly) {
        if (all.isEmpty()) return all;
        int latest = all.stream().mapToInt(JdkCatalog.Entry::majorVersion).max().orElse(0);
        List<JdkCatalog.Entry> kept = new ArrayList<>(all.size());
        for (JdkCatalog.Entry e : all) {
            boolean keep = firstClassOnly
                    ? SupportedJdk.isFirstClass(e.majorVersion(), latest)
                    : SupportedJdk.isSupported(e.majorVersion());
            if (keep) kept.add(e);
        }
        return kept;
    }

}
