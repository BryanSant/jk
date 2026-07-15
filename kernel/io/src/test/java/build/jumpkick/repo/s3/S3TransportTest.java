// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.repo.s3;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import build.jumpkick.credential.RepoCredential;
import build.jumpkick.http.Http;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class S3TransportTest {

    private HttpServer server;
    private URI endpoint;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private S3Transport signed() {
        return new S3Transport(
                new Http(),
                endpoint,
                "us-east-1",
                Optional.of(new AwsCredentials("AKIDEXAMPLE", "secret", null, "us-east-1")),
                () -> Instant.parse("2015-08-30T12:36:00Z"));
    }

    @Test
    void fetch_uses_path_style_url_and_signs() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> amzDate = new AtomicReference<>();
        // Path-style: /<bucket>/<key>
        server.createContext("/my-bucket/maven/g/a/1/a-1.jar", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            amzDate.set(ex.getRequestHeaders().getFirst("x-amz-date"));
            byte[] b = "jar".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });

        Optional<byte[]> body =
                signed().fetch(URI.create("s3://my-bucket/maven/g/a/1/a-1.jar"), RepoCredential.ANONYMOUS);

        assertThat(body).isPresent();
        assertThat(new String(body.get(), StandardCharsets.UTF_8)).isEqualTo("jar");
        assertThat(amzDate.get()).isEqualTo("20150830T123600Z");
        assertThat(auth.get())
                .startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/s3/aws4_request")
                .contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date")
                .contains("Signature=");
    }

    @Test
    void missing_object_is_empty() throws Exception {
        server.createContext("/my-bucket/missing.jar", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        assertThat(signed().fetch(URI.create("s3://my-bucket/missing.jar"), RepoCredential.ANONYMOUS))
                .isEmpty();
    }

    @Test
    void put_signs_with_body_hash_and_returns_status() throws Exception {
        AtomicReference<String> contentSha = new AtomicReference<>();
        AtomicReference<byte[]> seenBody = new AtomicReference<>();
        server.createContext("/my-bucket/up.jar", ex -> {
            contentSha.set(ex.getRequestHeaders().getFirst("x-amz-content-sha256"));
            seenBody.set(ex.getRequestBody().readAllBytes());
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });

        byte[] payload = {1, 2, 3};
        int status = signed().put(
                        URI.create("s3://my-bucket/up.jar"),
                        payload,
                        "application/java-archive",
                        RepoCredential.ANONYMOUS);

        assertThat(status).isEqualTo(200);
        assertThat(seenBody.get()).isEqualTo(payload);
        assertThat(contentSha.get()).isEqualTo(SigV4Signer.sha256Hex(payload));
    }

    @Test
    void gcs_via_endpoint_override_signs_with_auto_region() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        server.createContext("/gcs-bucket/maven/x.jar", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] b = "obj".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        // HMAC keys via the AWS env chain; AWS_ENDPOINT_URL points at our server.
        var chain = new AwsCredentialChain(
                k -> switch (k) {
                    case "AWS_ACCESS_KEY_ID" -> "GOOGAK";
                    case "AWS_SECRET_ACCESS_KEY" -> "googsk";
                    default -> null;
                },
                Path.of("/nonexistent"));
        var gcs = S3Transport.forGcs(
                new Http(),
                URI.create("gs://gcs-bucket/maven/x.jar"),
                chain,
                k -> "AWS_ENDPOINT_URL".equals(k) ? endpoint.toString() : null);

        var body = gcs.fetch(URI.create("gs://gcs-bucket/maven/x.jar"), RepoCredential.ANONYMOUS);
        assertThat(body).isPresent();
        // GCS V4 interop scope: region "auto", service "s3".
        assertThat(auth.get()).contains("/auto/s3/aws4_request");
    }

    @Test
    void forS3_applies_explicit_config_over_env() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        server.createContext("/cfg-bucket/x.jar", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] b = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });

        var cfg = new build.jumpkick.model.ObjectStoreConfig("eu-central-1", endpoint.toString(), "CFGAK", "cfgsk", null);
        // Empty chain + empty env: config must supply region/endpoint/creds.
        var chain = new AwsCredentialChain(k -> null, Path.of("/nonexistent"));
        S3Transport t = S3Transport.forS3(new Http(), URI.create("s3://cfg-bucket/x.jar"), cfg, chain, k -> null);

        assertThat(t.fetch(URI.create("s3://cfg-bucket/x.jar"), RepoCredential.ANONYMOUS))
                .isPresent();
        assertThat(auth.get()).contains("Credential=CFGAK/").contains("/eu-central-1/s3/aws4_request");
    }

    @Test
    void anonymous_when_no_credentials_sends_no_authorization() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        auth.set("unset");
        server.createContext("/public/x.jar", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            ex.sendResponseHeaders(200, 0);
            ex.close();
        });

        S3Transport anon = new S3Transport(
                new Http(), endpoint, "us-east-1", Optional.empty(), () -> Instant.parse("2015-08-30T12:36:00Z"));
        anon.fetch(URI.create("s3://public/x.jar"), RepoCredential.ANONYMOUS);
        assertThat(auth.get()).isNull(); // no Authorization header on unsigned requests
    }
}
