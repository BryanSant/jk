// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.publish.testkit.GpgTestFixture;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenPublisherTest {

    private HttpServer server;
    private URI base;
    private final Map<String, byte[]> received = new HashMap<>();
    private final Map<String, String> authHeaders = new HashMap<>();
    private volatile boolean failNext;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("PUT".equals(exchange.getRequestMethod())) {
                received.put(path, exchange.getRequestBody().readAllBytes());
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (auth != null) authHeaders.put(path, auth);
                if (failNext) {
                    failNext = false;
                    exchange.sendResponseHeaders(401, -1);
                } else {
                    exchange.sendResponseHeaders(201, -1);
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/repo/");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void publishes_jar_pom_and_four_checksums_each() throws Exception {
        MavenPublisher publisher = new MavenPublisher(base, null, null);
        JkBuild.Project project = new JkBuild.Project("com.example", "widget", "1.0.0", 21);
        byte[] jarBytes = "fake-jar".getBytes(StandardCharsets.UTF_8);
        byte[] pomBytes = "<project/>".getBytes(StandardCharsets.UTF_8);
        publisher.publish(
                project,
                List.of(new MavenPublisher.Artifact(".jar", jarBytes), new MavenPublisher.Artifact(".pom", pomBytes)));

        String prefix = "/repo/com/example/widget/1.0.0/widget-1.0.0";
        assertThat(received)
                .containsKeys(
                        prefix + ".jar",
                        prefix + ".jar.md5",
                        prefix + ".jar.sha1",
                        prefix + ".jar.sha256",
                        prefix + ".jar.sha512",
                        prefix + ".pom",
                        prefix + ".pom.md5",
                        prefix + ".pom.sha1",
                        prefix + ".pom.sha256",
                        prefix + ".pom.sha512");
        assertThat(received.get(prefix + ".jar")).isEqualTo(jarBytes);
        assertThat(received.get(prefix + ".pom")).isEqualTo(pomBytes);

        // Each checksum file is the hex digest of its sibling.
        assertThat(new String(received.get(prefix + ".jar.sha256"), StandardCharsets.US_ASCII))
                .isEqualTo(Checksums.sha256Hex(jarBytes));
    }

    @Test
    void basic_auth_header_is_attached_when_credentials_provided() throws Exception {
        MavenPublisher publisher = new MavenPublisher(base, "alice", "swordfish");
        publisher.publish(
                new JkBuild.Project("com.example", "widget", "1.0.0", 21),
                List.of(new MavenPublisher.Artifact(".jar", new byte[] {1, 2, 3})));

        String expected =
                "Basic " + Base64.getEncoder().encodeToString("alice:swordfish".getBytes(StandardCharsets.UTF_8));
        assertThat(authHeaders.values()).isNotEmpty().allMatch(h -> h.equals(expected));
    }

    @Test
    void bearer_credential_attaches_bearer_header() throws Exception {
        MavenPublisher publisher =
                new MavenPublisher(base, new dev.jkbuild.credential.RepoCredential.Bearer("tok-123"));
        publisher.publish(
                new JkBuild.Project("com.example", "widget", "1.0.0", 21),
                List.of(new MavenPublisher.Artifact(".jar", new byte[] {1, 2, 3})));

        assertThat(authHeaders.values()).isNotEmpty().allMatch(h -> h.equals("Bearer tok-123"));
    }

    @Test
    void signed_publish_emits_asc_files_with_checksums(@TempDir Path tempDir) throws Exception {
        var key = GpgTestFixture.generate(tempDir, "test-pass");
        GpgSigner signer = GpgSigner.fromKeyFile(key.secretKeyFile(), "test-pass".toCharArray());

        MavenPublisher publisher = new MavenPublisher(base, null, null);
        JkBuild.Project project = new JkBuild.Project("com.example", "widget", "1.0.0", 21);
        byte[] jarBytes = "fake-jar".getBytes(StandardCharsets.UTF_8);
        publisher.publish(project, List.of(new MavenPublisher.Artifact(".jar", jarBytes)), SigningOptions.of(signer));

        String stem = "/repo/com/example/widget/1.0.0/widget-1.0.0";
        assertThat(received)
                .containsKeys(
                        stem + ".jar", stem + ".jar.asc",
                        stem + ".jar.asc.md5", stem + ".jar.asc.sha1",
                        stem + ".jar.asc.sha256", stem + ".jar.asc.sha512");

        // The .asc file is a valid PGP signature over the jar bytes.
        byte[] sig = received.get(stem + ".jar.asc");
        GpgTestFixture.verifyDetached(jarBytes, sig, key.publicRing());
    }

    @Test
    void sigstore_signer_emits_sigstore_bundle_files() throws Exception {
        // Fake sigstore signer returns a deterministic bundle for unit testing.
        SigstoreSigner fake = bytes -> ("{\"fake-bundle-over-" + new String(bytes, StandardCharsets.UTF_8) + "\"}")
                .getBytes(StandardCharsets.UTF_8);

        MavenPublisher publisher = new MavenPublisher(base, null, null);
        JkBuild.Project project = new JkBuild.Project("com.example", "widget", "1.0.0", 21);
        byte[] jarBytes = "fake-jar".getBytes(StandardCharsets.UTF_8);
        publisher.publish(
                project, List.of(new MavenPublisher.Artifact(".jar", jarBytes)), new SigningOptions(null, fake));

        String stem = "/repo/com/example/widget/1.0.0/widget-1.0.0";
        assertThat(received)
                .containsKeys(
                        stem + ".jar.sigstore",
                        stem + ".jar.sigstore.md5",
                        stem + ".jar.sigstore.sha1",
                        stem + ".jar.sigstore.sha256",
                        stem + ".jar.sigstore.sha512");
        assertThat(new String(received.get(stem + ".jar.sigstore"), StandardCharsets.UTF_8))
                .isEqualTo("{\"fake-bundle-over-fake-jar\"}");
    }

    @Test
    void both_gpg_and_sigstore_emit_both_sidecars(@TempDir Path tempDir) throws Exception {
        var key = GpgTestFixture.generate(tempDir, "p");
        GpgSigner gpg = GpgSigner.fromKeyFile(key.secretKeyFile(), "p".toCharArray());
        SigstoreSigner fake = bytes -> "{}".getBytes(StandardCharsets.UTF_8);

        MavenPublisher publisher = new MavenPublisher(base, null, null);
        publisher.publish(
                new JkBuild.Project("com.example", "widget", "1.0.0", 21),
                List.of(new MavenPublisher.Artifact(".jar", new byte[] {1, 2, 3})),
                new SigningOptions(gpg, fake));

        String stem = "/repo/com/example/widget/1.0.0/widget-1.0.0";
        assertThat(received).containsKeys(stem + ".jar.asc", stem + ".jar.sigstore");
    }

    @Test
    void server_error_aborts_with_io_exception() {
        MavenPublisher publisher = new MavenPublisher(base, null, null);
        failNext = true;
        assertThatThrownBy(() -> publisher.publish(
                        new JkBuild.Project("com.example", "widget", "1.0.0", 21),
                        List.of(new MavenPublisher.Artifact(".jar", new byte[] {1, 2, 3}))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("401");
    }
}
