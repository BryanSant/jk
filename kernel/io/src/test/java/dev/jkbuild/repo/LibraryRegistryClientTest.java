// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.http.Http;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Conditional-GET behaviour of the library registry client. */
class LibraryRegistryClientTest {

    private static final String ETAG = "\"v1\"";
    private static final byte[] BODY = "[libraries]\nfoo = \"com.acme:foo\"\n".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private URI uri;
    private AtomicInteger hits;
    private volatile int forceStatus;
    private volatile String lastIfNoneMatch;

    @BeforeEach
    void start() throws IOException {
        hits = new AtomicInteger();
        forceStatus = 0;
        lastIfNoneMatch = null;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/libraries.toml", exchange -> {
            hits.incrementAndGet();
            lastIfNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (forceStatus != 0) {
                exchange.sendResponseHeaders(forceStatus, -1);
                exchange.close();
                return;
            }
            if (ETAG.equals(lastIfNoneMatch)) {
                exchange.sendResponseHeaders(304, -1);
            } else {
                exchange.getResponseHeaders().set("ETag", ETAG);
                exchange.sendResponseHeaders(200, BODY.length);
                exchange.getResponseBody().write(BODY);
            }
            exchange.close();
        });
        server.start();
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/libraries.toml");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private LibraryRegistryClient client() {
        return new LibraryRegistryClient(new Http());
    }

    @Test
    void first_fetch_with_no_etag_sidecar_returns_updated_with_body_and_etag(@TempDir Path dir) throws Exception {
        Path etagFile = dir.resolve(".libraries.global.toml.etag");

        var result = client().fetch(uri, etagFile);

        assertThat(result).isInstanceOf(LibraryRegistryClient.Result.Updated.class);
        var updated = (LibraryRegistryClient.Result.Updated) result;
        assertThat(updated.body()).isEqualTo(BODY);
        assertThat(updated.etag()).isEqualTo(ETAG);
        assertThat(lastIfNoneMatch).isNull(); // no sidecar yet → unconditional GET
    }

    @Test
    void fetch_sends_stored_etag_and_treats_304_as_unchanged(@TempDir Path dir) throws Exception {
        Path etagFile = dir.resolve(".libraries.global.toml.etag");
        Files.writeString(etagFile, ETAG, StandardCharsets.UTF_8);

        var result = client().fetch(uri, etagFile);

        assertThat(result).isInstanceOf(LibraryRegistryClient.Result.Unchanged.class);
        assertThat(lastIfNoneMatch).isEqualTo(ETAG);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void stale_etag_still_yields_a_fresh_updated_body(@TempDir Path dir) throws Exception {
        Path etagFile = dir.resolve(".libraries.global.toml.etag");
        Files.writeString(etagFile, "\"stale\"", StandardCharsets.UTF_8);

        var result = client().fetch(uri, etagFile);

        assertThat(result).isInstanceOf(LibraryRegistryClient.Result.Updated.class);
    }

    @Test
    void non_200_non_304_status_throws(@TempDir Path dir) {
        forceStatus = 503;
        Path etagFile = dir.resolve(".libraries.global.toml.etag");

        assertThatThrownBy(() -> client().fetch(uri, etagFile)).isInstanceOf(IOException.class);
    }
}
