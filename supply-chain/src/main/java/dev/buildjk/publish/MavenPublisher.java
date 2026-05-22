// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.publish;

import dev.buildjk.model.BuildJk;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Uploads an artifact bundle (jar, pom, optional sources jar, plus their
 * checksums) to a Maven-style HTTP repository via PUT (PRD §21.2).
 *
 * <p>Layout under the repo base URL:
 * <pre>
 *   &lt;group-path&gt;/&lt;artifact&gt;/&lt;version&gt;/&lt;artifact&gt;-&lt;version&gt;.{jar,pom,sources.jar}
 *                                       + .md5, .sha1, .sha256, .sha512 per file
 * </pre>
 *
 * <p>v0.6 first cut: HTTP Basic auth via optional {@code username}/{@code password}.
 * GPG signatures (.asc), Sigstore, SLSA provenance, and SBOMs arrive in
 * follow-up slices.
 */
public final class MavenPublisher {

    private final HttpClient http;
    private final URI repoBase;
    private final String authHeader;

    public MavenPublisher(URI repoBase, String username, String password) {
        this.repoBase = normalize(Objects.requireNonNull(repoBase, "repoBase"));
        this.authHeader = (username == null || username.isEmpty()) ? null
                : "Basic " + Base64.getEncoder().encodeToString(
                        (username + ":" + (password == null ? "" : password))
                                .getBytes(StandardCharsets.UTF_8));
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public record Artifact(String filenameSuffix, byte[] body) {
        public Artifact {
            Objects.requireNonNull(filenameSuffix, "filenameSuffix");
            Objects.requireNonNull(body, "body");
        }
    }

    public record Result(Map<String, Integer> statusByPath) {
        public Result { statusByPath = Map.copyOf(statusByPath); }
        public boolean allOk() {
            return statusByPath.values().stream().allMatch(s -> s >= 200 && s < 300);
        }
    }

    /**
     * Upload the artifact bundle for {@code project}. {@code artifacts}
     * carries the per-file payload — typically the main jar, the POM, and
     * a sources jar. Each is sent alongside its four checksum files.
     */
    public Result publish(BuildJk.Project project, Iterable<Artifact> artifacts)
            throws IOException, InterruptedException {
        Map<String, Integer> results = new LinkedHashMap<>();
        String groupPath = project.group().replace('.', '/');
        String prefix = groupPath + "/" + project.artifact() + "/" + project.version() + "/";
        String stem = project.artifact() + "-" + project.version();

        for (Artifact a : artifacts) {
            String relPath = prefix + stem + a.filenameSuffix();
            put(relPath, a.body(), contentType(a.filenameSuffix()), results);

            Checksums.Set sums = Checksums.of(a.body());
            put(relPath + ".md5",    sums.md5().getBytes(StandardCharsets.US_ASCII),
                    "text/plain", results);
            put(relPath + ".sha1",   sums.sha1().getBytes(StandardCharsets.US_ASCII),
                    "text/plain", results);
            put(relPath + ".sha256", sums.sha256().getBytes(StandardCharsets.US_ASCII),
                    "text/plain", results);
            put(relPath + ".sha512", sums.sha512().getBytes(StandardCharsets.US_ASCII),
                    "text/plain", results);
        }
        return new Result(results);
    }

    private void put(String relPath, byte[] body, String contentType, Map<String, Integer> out)
            throws IOException, InterruptedException {
        URI uri = repoBase.resolve(relPath);
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        if (authHeader != null) rb.header("Authorization", authHeader);
        HttpResponse<String> response = http.send(rb.build(),
                HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        out.put(relPath, status);
        if (status < 200 || status >= 300) {
            throw new IOException("PUT " + uri + " returned " + status
                    + (response.body().isEmpty() ? "" : ": " + response.body()));
        }
    }

    private static String contentType(String suffix) {
        if (suffix.endsWith(".jar")) return "application/java-archive";
        if (suffix.endsWith(".pom") || suffix.endsWith(".xml")) return "application/xml";
        if (suffix.endsWith(".asc")) return "application/pgp-signature";
        return "application/octet-stream";
    }

    private static URI normalize(URI uri) {
        String s = uri.toString();
        return s.endsWith("/") ? uri : URI.create(s + "/");
    }
}
