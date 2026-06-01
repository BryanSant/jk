// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo.s3;

import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.credential.RepoCredential;
import dev.jkbuild.http.Http;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

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
        return new S3Transport(new Http(), endpoint, "us-east-1",
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

        Optional<byte[]> body = signed().fetch(
                URI.create("s3://my-bucket/maven/g/a/1/a-1.jar"), RepoCredential.ANONYMOUS);

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
        int status = signed().put(URI.create("s3://my-bucket/up.jar"), payload,
                "application/java-archive", RepoCredential.ANONYMOUS);

        assertThat(status).isEqualTo(200);
        assertThat(seenBody.get()).isEqualTo(payload);
        assertThat(contentSha.get()).isEqualTo(SigV4Signer.sha256Hex(payload));
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

        S3Transport anon = new S3Transport(new Http(), endpoint, "us-east-1",
                Optional.empty(), () -> Instant.parse("2015-08-30T12:36:00Z"));
        anon.fetch(URI.create("s3://public/x.jar"), RepoCredential.ANONYMOUS);
        assertThat(auth.get()).isNull();   // no Authorization header on unsigned requests
    }
}
