// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import dev.jkbuild.http.Http;
import dev.jkbuild.util.JkDirs;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
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
            "https://download.jetbrains.com/jdk/feed/v1/jdks.json.xz";

    /** 24 h — the feed publishes new GA releases at most a few times a month. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'")
                    .withZone(ZoneId.of("GMT"));

    private final Http http;
    private final URI feedUri;
    private final Path cacheFile;
    private final Duration ttl;
    private final ObjectMapper json = JsonMapper.builder().build();

    public JdkCatalogClient() {
        this(new Http(), URI.create(DEFAULT_FEED_URL), defaultCachePath(), DEFAULT_TTL);
    }

    public JdkCatalogClient(Http http, URI feedUri, Path cacheFile, Duration ttl) {
        this.http = Objects.requireNonNull(http, "http");
        this.feedUri = Objects.requireNonNull(feedUri, "feedUri");
        this.cacheFile = Objects.requireNonNull(cacheFile, "cacheFile");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
    }

    /** Default cache location: {@code $JK_CACHE_DIR/jdks.json.xz} (XDG: {@code ~/.cache/jk/jdks.json.xz}). */
    public static Path defaultCachePath() {
        return JkDirs.cache().resolve("jdks.json.xz");
    }

    /**
     * Fetch the catalog, honouring the on-disk cache and TTL.
     * Network errors fall back to whatever the cache holds (with a stderr
     * warning) so {@code jk jdk list} stays useful offline.
     */
    public JdkCatalog fetch() throws IOException, InterruptedException {
        byte[] body = loadBody();
        return parse(body);
    }

    private byte[] loadBody() throws IOException, InterruptedException {
        boolean cacheFresh = Files.isRegularFile(cacheFile)
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
                System.err.println("jk: warning — JDK feed unreachable, using cached "
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

    private JdkCatalog parse(byte[] xz) throws IOException {
        byte[] inflated;
        try (XZCompressorInputStream in =
                     new XZCompressorInputStream(new ByteArrayInputStream(xz))) {
            inflated = in.readAllBytes();
        }
        JsonNode root = json.readTree(inflated);
        JsonNode jdks = root.path("jdks");
        if (!jdks.isArray()) {
            throw new IOException("JDK feed missing top-level `jdks` array");
        }
        List<JdkCatalog.Entry> entries = new ArrayList<>(jdks.size());
        for (JsonNode item : jdks) {
            String vendor = item.path("vendor").asString();
            String product = item.path("product").asString();
            String suggestedSdkName = item.path("suggested_sdk_name").asString();
            int majorVersion = item.path("jdk_version_major").asInt(0);
            String version = item.path("jdk_version").asString();
            boolean defaultForMajor = item.path("default").asBoolean(false);
            boolean preview = item.path("preview").asBoolean(false);
            List<String> aliases = readStrings(item.path("shared_index_aliases"));

            JsonNode packages = item.path("packages");
            if (!packages.isArray()) continue;
            for (JsonNode pkg : packages) {
                String os = pkg.path("os").asString();
                String arch = pkg.path("arch").asString();
                String packageType = pkg.path("package_type").asString();
                String url = pkg.path("url").asString();
                String sha256 = pkg.path("sha256").asString();
                long archiveSize = pkg.path("archive_size").asLong(0);
                String installFolderName = pkg.path("install_folder_name").asString();
                String javaHomeSubpath = pkg.path("package_to_java_home_prefix").asString();

                if (url == null || url.isEmpty()) continue;
                if (installFolderName == null || installFolderName.isEmpty()) continue;

                entries.add(new JdkCatalog.Entry(
                        vendor,
                        product,
                        suggestedSdkName,
                        majorVersion,
                        version,
                        defaultForMajor,
                        preview,
                        aliases,
                        os,
                        arch,
                        packageType,
                        URI.create(url),
                        sha256,
                        archiveSize,
                        installFolderName,
                        javaHomeSubpath));
            }
        }
        return new JdkCatalog(filterSupported(entries));
    }

    /**
     * Drop catalog rows for majors jk doesn't claim to support: anything
     * below {@link SupportedJdk#MIN_MAJOR}, and any non-LTS that isn't the
     * single most-recent major in the feed. The JetBrains feed publishes
     * 8 / 11 / 17 / 21 / 23 / 24 / 25 / 26 / …; after this pass we keep
     * {17, 21, 25, latestMajor}.
     */
    private static List<JdkCatalog.Entry> filterSupported(List<JdkCatalog.Entry> all) {
        if (all.isEmpty()) return all;
        int latest = all.stream().mapToInt(JdkCatalog.Entry::majorVersion).max().orElse(0);
        List<JdkCatalog.Entry> kept = new ArrayList<>(all.size());
        for (JdkCatalog.Entry e : all) {
            if (SupportedJdk.isFirstClass(e.majorVersion(), latest)) kept.add(e);
        }
        return kept;
    }

    private static List<String> readStrings(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> out = new ArrayList<>(node.size());
        for (JsonNode n : node) {
            String s = n.asString();
            if (s != null && !s.isEmpty()) out.add(s);
        }
        return out;
    }
}
