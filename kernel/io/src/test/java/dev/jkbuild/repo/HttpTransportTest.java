// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.credential.RepoCredential;
import dev.jkbuild.http.Http;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpTransportTest {

    private HttpServer server;
    private URI base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpTransport transport() {
        return new HttpTransport(new Http());
    }

    @Test
    void fetch_returns_body_and_sends_auth() throws Exception {
        AtomicReference<String> seenAuth = new AtomicReference<>();
        server.createContext("/a.jar", ex -> {
            seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] b = "bytes".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });

        Optional<byte[]> body = transport().fetch(base.resolve("/a.jar"), new RepoCredential.Bearer("tok"));
        assertThat(body).isPresent();
        assertThat(new String(body.get(), StandardCharsets.UTF_8)).isEqualTo("bytes");
        assertThat(seenAuth.get()).isEqualTo("Bearer tok");
    }

    @Test
    void fetch_404_is_empty_not_an_error() throws Exception {
        server.createContext("/missing.jar", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        assertThat(transport().fetch(base.resolve("/missing.jar"), RepoCredential.ANONYMOUS))
                .isEmpty();
    }

    @Test
    void fetch_other_4xx_throws() throws Exception {
        server.createContext("/denied.jar", ex -> {
            ex.sendResponseHeaders(403, -1);
            ex.close();
        });
        assertThatThrownBy(() -> transport().fetch(base.resolve("/denied.jar"), RepoCredential.ANONYMOUS))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("403");
    }

    @Test
    void fetchStream_returns_body_and_sends_auth() throws Exception {
        AtomicReference<String> seenAuth = new AtomicReference<>();
        server.createContext("/a.jar", ex -> {
            seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] b = "bytes".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });

        Optional<java.io.InputStream> body =
                transport().fetchStream(base.resolve("/a.jar"), new RepoCredential.Bearer("tok"));
        assertThat(body).isPresent();
        try (var in = body.get()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("bytes");
        }
        assertThat(seenAuth.get()).isEqualTo("Bearer tok");
    }

    @Test
    void fetchStream_404_is_empty_not_an_error() throws Exception {
        server.createContext("/missing.jar", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        assertThat(transport().fetchStream(base.resolve("/missing.jar"), RepoCredential.ANONYMOUS))
                .isEmpty();
    }

    @Test
    void fetchStream_other_4xx_throws() throws Exception {
        server.createContext("/denied.jar", ex -> {
            ex.sendResponseHeaders(403, -1);
            ex.close();
        });
        assertThatThrownBy(() -> transport().fetchStream(base.resolve("/denied.jar"), RepoCredential.ANONYMOUS))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("403");
    }

    @Test
    void put_sends_body_content_type_and_returns_status() throws Exception {
        AtomicReference<String> seenType = new AtomicReference<>();
        AtomicReference<byte[]> seenBody = new AtomicReference<>();
        server.createContext("/up.jar", ex -> {
            seenType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            seenBody.set(ex.getRequestBody().readAllBytes());
            ex.sendResponseHeaders(201, -1);
            ex.close();
        });

        byte[] payload = {1, 2, 3};
        int status = transport()
                .put(base.resolve("/up.jar"), payload, "application/java-archive", new RepoCredential.Basic("u", "p"));
        assertThat(status).isEqualTo(201);
        assertThat(seenType.get()).isEqualTo("application/java-archive");
        assertThat(seenBody.get()).isEqualTo(payload);
    }

    @Test
    void unsupported_scheme_is_rejected() {
        assertThatThrownBy(() -> RepoTransports.forUrl(URI.create("azblob://acct/container"), new Http()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("azblob");
    }

    @Test
    void s3_and_gs_schemes_resolve_to_an_s3_transport() {
        // Both resolve credentials from the environment lazily at use time.
        assertThat(RepoTransports.forUrl(URI.create("s3://my-bucket/maven"), new Http()))
                .isInstanceOf(dev.jkbuild.repo.s3.S3Transport.class);
        assertThat(RepoTransports.forUrl(URI.create("gs://my-bucket/maven"), new Http()))
                .isInstanceOf(dev.jkbuild.repo.s3.S3Transport.class);
    }
}
