// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.jdk;

import dev.buildjk.http.Http;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Client for foojay's <a href="https://api.foojay.io/swagger-ui">Disco API</a>.
 * Used by jk's JDK manager to discover downloadable distributions.
 *
 * <p>v0.4 first iteration: just the {@code /packages} endpoint with
 * filters for distribution / version / architecture / operating_system /
 * archive_type. Listing distributions, single-package lookup by id, and
 * EOL info join later as the JDK manager surfaces evolve.
 */
public final class DiscoClient {

    /** Default Disco API base URI. */
    public static final URI DEFAULT_BASE_URI = URI.create("https://api.foojay.io/disco/v3.0/");

    private final Http http;
    private final URI baseUri;
    private final ObjectMapper json = JsonMapper.builder().build();

    public DiscoClient() {
        this(new Http(), DEFAULT_BASE_URI);
    }

    public DiscoClient(Http http, URI baseUri) {
        this.http = Objects.requireNonNull(http, "http");
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
    }

    public List<JdkPackage> search(SearchQuery query) throws IOException, InterruptedException {
        URI uri = baseUri.resolve("packages?" + encode(query.parameters()));
        HttpResponse<byte[]> response = http.get(uri);
        if (response.statusCode() != 200) {
            throw new IOException("foojay Disco returned " + response.statusCode() + " for " + uri);
        }
        JsonNode root = json.readTree(response.body());
        JsonNode resultArray = root.path("result");
        if (!resultArray.isArray()) {
            throw new IOException("foojay Disco response missing `result` array");
        }
        List<JdkPackage> result = new ArrayList<>(resultArray.size());
        for (JsonNode item : resultArray) {
            result.add(fromJson(item));
        }
        return result;
    }

    private static JdkPackage fromJson(JsonNode item) {
        String distribution = item.path("distribution").asString();
        String version = item.path("java_version").asString();
        if (version == null || version.isEmpty()) {
            version = item.path("version").asString();
        }
        String architecture = item.path("architecture").asString();
        String operatingSystem = item.path("operating_system").asString();
        String archiveType = item.path("archive_type").asString();
        String filename = item.path("filename").asString();
        long size = item.path("size").asLong(0);
        String downloadUrl = null;
        JsonNode links = item.get("links");
        if (links != null && links.has("pkg_download_redirect")) {
            downloadUrl = links.get("pkg_download_redirect").asString();
        }
        if (downloadUrl == null && links != null && links.has("pkg_info_uri")) {
            downloadUrl = links.get("pkg_info_uri").asString();
        }
        if (downloadUrl == null) {
            downloadUrl = item.path("direct_download_uri").asString();
        }
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            throw new IllegalArgumentException("Disco package missing download URI: " + item);
        }
        String sha256 = item.path("sha256").asString();
        return new JdkPackage(
                distribution,
                version,
                architecture,
                operatingSystem,
                archiveType,
                filename,
                URI.create(downloadUrl),
                sha256,
                size);
    }

    private static String encode(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * Filters for {@link DiscoClient#search}. All fields are optional.
     * Common defaults (release type=ga, package_type=jdk, bundle_type=jdk)
     * are applied so the caller can ask for "Temurin 21 on linux x64" with
     * minimal ceremony.
     */
    public record SearchQuery(
            String distribution,
            String version,
            String architecture,
            String operatingSystem,
            String archiveType) {

        public Map<String, String> parameters() {
            Map<String, String> p = new LinkedHashMap<>();
            if (distribution != null) p.put("distribution", distribution);
            if (version != null) p.put("version", version);
            if (architecture != null) p.put("architecture", architecture);
            if (operatingSystem != null) p.put("operating_system", operatingSystem);
            if (archiveType != null) p.put("archive_type", archiveType);
            p.put("release_status", "ga");
            p.put("package_type", "jdk");
            p.put("bundle_type", "jdk");
            p.put("javafx_bundled", "false");
            p.put("latest", "available");
            return p;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private String distribution;
            private String version;
            private String architecture;
            private String operatingSystem;
            private String archiveType;

            public Builder distribution(String v) { this.distribution = v; return this; }
            public Builder version(String v) { this.version = v; return this; }
            public Builder architecture(String v) { this.architecture = v; return this; }
            public Builder operatingSystem(String v) { this.operatingSystem = v; return this; }
            public Builder archiveType(String v) { this.archiveType = v; return this; }

            public SearchQuery build() {
                return new SearchQuery(distribution, version, architecture, operatingSystem, archiveType);
            }
        }
    }
}
