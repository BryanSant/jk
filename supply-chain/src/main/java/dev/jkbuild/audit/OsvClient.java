// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.audit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Tiny client over <a href="https://api.osv.dev/v1/querybatch">OSV v1 batch
 * query API</a> (PRD §23.5). One round trip per lockfile.
 *
 * <p>OSV is the federated source that aggregates GHSA, RustSec, PyPA, etc.
 * Each query asks "is this {ecosystem,name,version} affected?" and the
 * response lists vulnerability IDs. The client deliberately does not pull
 * the full vuln details — those are fetched only when the audit verb
 * needs them, keeping the default-path payload small.
 */
public final class OsvClient {

    public static final URI DEFAULT_BATCH = URI.create("https://api.osv.dev/v1/querybatch");
    public static final URI DEFAULT_VULNS = URI.create("https://api.osv.dev/v1/vulns/");

    private final HttpClient http;
    private final URI batchUrl;
    private final URI vulnsUrl;
    // Jackson 3 (tools.jackson.*) is the version we depend on; ObjectMapper from
    // tools.jackson.databind has the same API surface as Jackson 2 for our needs.
    private final JsonMapper json = JsonMapper.builder().build();

    public OsvClient() {
        this(DEFAULT_BATCH, DEFAULT_VULNS);
    }

    public OsvClient(URI batchUrl, URI vulnsUrl) {
        this.batchUrl = Objects.requireNonNull(batchUrl, "batchUrl");
        this.vulnsUrl = Objects.requireNonNull(vulnsUrl, "vulnsUrl");
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record Query(String ecosystem, String name, String version) {}

    public record Result(List<String> vulnIds) {
        public Result { vulnIds = List.copyOf(vulnIds); }
        public boolean clean() { return vulnIds.isEmpty(); }
    }

    public record Vulnerability(String id, String summary, String severity, String details) {}

    /** Batch query — one Result per input query, in the same order. */
    public List<Result> queryBatch(List<Query> queries) throws IOException, InterruptedException {
        if (queries.isEmpty()) return List.of();
        // Hand-roll the request body — Jackson's tree API is overkill here and we
        // avoid pulling another type onto the API surface.
        StringBuilder body = new StringBuilder();
        body.append("{\"queries\":[");
        for (int i = 0; i < queries.size(); i++) {
            if (i > 0) body.append(',');
            Query q = queries.get(i);
            body.append("{\"package\":{")
                    .append("\"ecosystem\":").append(quote(q.ecosystem())).append(',')
                    .append("\"name\":").append(quote(q.name()))
                    .append("},\"version\":").append(quote(q.version()))
                    .append('}');
        }
        body.append("]}");

        if (dev.jkbuild.config.ActiveConfig.get().offlineOr(false)) {
            throw new dev.jkbuild.http.OfflineException(batchUrl);
        }
        HttpRequest request = HttpRequest.newBuilder(batchUrl)
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OSV batch query failed: HTTP " + response.statusCode()
                    + " — " + response.body());
        }
        return parseBatchResponse(response.body(), queries.size());
    }

    /** Fetch full vulnerability metadata. */
    public Vulnerability fetchVuln(String vulnId) throws IOException, InterruptedException {
        var url = vulnsUrl.resolve(vulnId);
        if (dev.jkbuild.config.ActiveConfig.get().offlineOr(false)) {
            throw new dev.jkbuild.http.OfflineException(url);
        }
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OSV vuln fetch failed for " + vulnId
                    + ": HTTP " + response.statusCode());
        }
        try {
            var node = json.readTree(response.body());
            String summary = textOrEmpty(node, "summary");
            String details = textOrEmpty(node, "details");
            String severity = extractSeverity(node);
            return new Vulnerability(vulnId, summary, severity, details);
        } catch (Exception e) {
            throw new IOException("failed to parse OSV vuln body for " + vulnId, e);
        }
    }

    // --- helpers ---------------------------------------------------------

    private List<Result> parseBatchResponse(String body, int expectedSize) throws IOException {
        try {
            var root = json.readTree(body);
            var resultsNode = root.get("results");
            if (resultsNode == null || !resultsNode.isArray()) {
                throw new IOException("OSV batch response missing `results` array");
            }
            List<Result> out = new ArrayList<>(resultsNode.size());
            for (var entry : resultsNode) {
                var vulnsNode = entry.get("vulns");
                List<String> ids = new ArrayList<>();
                if (vulnsNode != null && vulnsNode.isArray()) {
                    for (var v : vulnsNode) {
                        var id = v.get("id");
                        if (id != null && id.isString()) ids.add(id.stringValue());
                    }
                }
                out.add(new Result(ids));
            }
            if (out.size() != expectedSize) {
                throw new IOException("OSV returned " + out.size()
                        + " result(s) for " + expectedSize + " quer" + (expectedSize == 1 ? "y" : "ies"));
            }
            return out;
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException("failed to parse OSV batch response", e);
        }
    }

    private static String extractSeverity(JsonNode node) {
        var sev = node.get("severity");
        if (sev != null && sev.isArray() && !sev.isEmpty()) {
            var first = sev.get(0);
            var score = first.get("score");
            if (score != null && score.isString()) return score.stringValue();
        }
        var dbSpecific = node.get("database_specific");
        if (dbSpecific != null) {
            var inner = dbSpecific.get("severity");
            if (inner != null && inner.isString()) return inner.stringValue();
        }
        return "UNKNOWN";
    }

    private static String textOrEmpty(JsonNode node, String field) {
        var v = node.get(field);
        return v == null || !v.isString() ? "" : v.stringValue();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
