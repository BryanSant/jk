// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** TTL + conditional-GET behaviour of the maven-metadata.xml cache. */
class MavenMetadataCacheTest {

    private static final String ETAG = "\"v1\"";
    private static final byte[] BODY = ("""
            <metadata><groupId>g</groupId><artifactId>a</artifactId>
            <versioning><versions><version>1.0</version></versions></versioning></metadata>
            """).getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private URI uri;
    private AtomicInteger hits;
    private volatile int forceStatus; // when >0, reply with this status (e.g. 429/404)
    private volatile String lastIfNoneMatch;

    @BeforeEach
    void start() throws IOException {
        hits = new AtomicInteger();
        forceStatus = 0;
        lastIfNoneMatch = null;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/g/a/maven-metadata.xml", exchange -> {
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
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/g/a/maven-metadata.xml");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private MavenMetadataCache cache(Path dir, Duration ttl) {
        return new MavenMetadataCache(new Http(), dir, ttl);
    }

    @Test
    void within_ttl_serves_from_disk_without_a_second_request(@TempDir Path dir) throws Exception {
        MavenMetadataCache cache = cache(dir, Duration.ofHours(1));

        assertThat(cache.fetch(uri, RepoCredential.ANONYMOUS)).isEqualTo(BODY);
        assertThat(hits.get()).isEqualTo(1);

        // Second resolve within the TTL must not touch the network at all.
        assertThat(cache.fetch(uri, RepoCredential.ANONYMOUS)).isEqualTo(BODY);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void past_ttl_revalidates_with_conditional_get_and_reuses_on_304(@TempDir Path dir) throws Exception {
        MavenMetadataCache cache = cache(dir, Duration.ZERO); // always stale → always revalidate

        assertThat(cache.fetch(uri, RepoCredential.ANONYMOUS)).isEqualTo(BODY); // 200, stores ETag
        assertThat(cache.fetch(uri, RepoCredential.ANONYMOUS)).isEqualTo(BODY); // conditional → 304

        assertThat(hits.get()).isEqualTo(2);
        assertThat(lastIfNoneMatch).isEqualTo(ETAG); // the revalidation was conditional
    }

    @Test
    void server_refusal_falls_back_to_the_cached_copy(@TempDir Path dir) throws Exception {
        MavenMetadataCache cache = cache(dir, Duration.ZERO);

        assertThat(cache.fetch(uri, RepoCredential.ANONYMOUS)).isEqualTo(BODY); // warm the cache
        forceStatus = 429; // now rate-limited

        // Rather than fail the resolve, the stale-but-usable copy is returned.
        assertThat(cache.fetch(uri, RepoCredential.ANONYMOUS)).isEqualTo(BODY);
    }

    @Test
    void missing_metadata_surfaces_as_not_found(@TempDir Path dir) {
        forceStatus = 404;
        MavenMetadataCache cache = cache(dir, Duration.ofHours(1));

        assertThatThrownBy(() -> cache.fetch(uri, RepoCredential.ANONYMOUS))
                .isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
    }
}
