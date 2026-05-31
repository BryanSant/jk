// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.publish;

import dev.jkbuild.credential.RepoCredential;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.repo.AuthHeaders;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * <p>Authentication is supplied as a {@link RepoCredential} (Basic, Bearer, or
 * anonymous), resolved by the caller through the shared credential chain
 * (docs/artifact-repos.md). GPG signatures (.asc), Sigstore, SLSA provenance,
 * and SBOMs are layered on by the signing options.
 */
public final class MavenPublisher {

    private final HttpClient http;
    private final URI repoBase;
    private final Map<String, String> authHeaders;

    /** Authenticate with an explicit credential (anonymous → no auth header). */
    public MavenPublisher(URI repoBase, RepoCredential credential) {
        this.repoBase = normalize(Objects.requireNonNull(repoBase, "repoBase"));
        this.authHeaders = AuthHeaders.of(Objects.requireNonNull(credential, "credential"));
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /** Convenience for HTTP Basic auth; a blank username means anonymous. */
    public MavenPublisher(URI repoBase, String username, String password) {
        this(repoBase, (username == null || username.isEmpty())
                ? RepoCredential.ANONYMOUS
                : new RepoCredential.Basic(username, password == null ? "" : password));
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
     * Upload {@code artifacts} for {@code project} with no signing — every
     * artifact gets its four checksum files but no {@code .asc} or
     * {@code .sigstore} sidecar. See
     * {@link #publish(JkBuild.Project, Iterable, SigningOptions)} for the
     * signed variant.
     */
    public Result publish(JkBuild.Project project, Iterable<Artifact> artifacts)
            throws IOException, InterruptedException {
        return publish(project, artifacts, SigningOptions.none());
    }

    /**
     * Upload {@code artifacts} for {@code project}. Per artifact:
     * <ul>
     *   <li>the artifact itself + four checksum files;</li>
     *   <li>if {@link SigningOptions#gpg()} is set, a detached
     *       ASCII-armored {@code .asc} signature + its four checksum files
     *       (PRD §21.2);</li>
     *   <li>if {@link SigningOptions#sigstore()} is set, a Sigstore Bundle
     *       JSON {@code .sigstore} file + its four checksum files
     *       (PRD §23.3).</li>
     * </ul>
     */
    public Result publish(JkBuild.Project project, Iterable<Artifact> artifacts, SigningOptions signing)
            throws IOException, InterruptedException {
        if (signing == null) signing = SigningOptions.none();
        Map<String, Integer> results = new LinkedHashMap<>();
        String groupPath = project.group().replace('.', '/');
        String prefix = groupPath + "/" + project.artifact() + "/" + project.version() + "/";
        String stem = project.artifact() + "-" + project.version();

        for (Artifact a : artifacts) {
            String relPath = prefix + stem + a.filenameSuffix();
            putWithChecksums(relPath, a.body(), contentType(a.filenameSuffix()), results);

            if (signing.gpg() != null) {
                byte[] asc = signing.gpg().signArmored(a.body());
                putWithChecksums(relPath + ".asc", asc, contentType(".asc"), results);
            }
            if (signing.sigstore() != null) {
                byte[] bundle = signing.sigstore().signBundle(a.body());
                putWithChecksums(relPath + ".sigstore", bundle, contentType(".sigstore"), results);
            }
        }
        return new Result(results);
    }

    private void putWithChecksums(String relPath, byte[] body, String contentType,
                                  Map<String, Integer> results)
            throws IOException, InterruptedException {
        put(relPath, body, contentType, results);
        Checksums.Set sums = Checksums.of(body);
        put(relPath + ".md5",    sums.md5().getBytes(StandardCharsets.US_ASCII),    "text/plain", results);
        put(relPath + ".sha1",   sums.sha1().getBytes(StandardCharsets.US_ASCII),   "text/plain", results);
        put(relPath + ".sha256", sums.sha256().getBytes(StandardCharsets.US_ASCII), "text/plain", results);
        put(relPath + ".sha512", sums.sha512().getBytes(StandardCharsets.US_ASCII), "text/plain", results);
    }

    private void put(String relPath, byte[] body, String contentType, Map<String, Integer> out)
            throws IOException, InterruptedException {
        URI uri = repoBase.resolve(relPath);
        if (dev.jkbuild.config.ActiveConfig.get().offlineOr(false)) {
            throw new dev.jkbuild.http.OfflineException(uri);
        }
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        authHeaders.forEach(rb::header);
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
        if (suffix.endsWith(".sigstore") || suffix.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private static URI normalize(URI uri) {
        String s = uri.toString();
        return s.endsWith("/") ? uri : URI.create(s + "/");
    }
}
