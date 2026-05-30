// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.cache.Cas;
import dev.jkbuild.cache.Journal;
import dev.jkbuild.config.ActiveConfig;
import dev.jkbuild.config.JkConfig;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.util.Hashing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MavenRepoTest {

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
        ActiveConfig.reset();
    }

    private static void goOffline() {
        ActiveConfig.install(new JkConfig(
                Optional.empty(), Optional.of(true), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Test
    void fetches_pom_into_cas(@TempDir Path tempDir) throws Exception {
        byte[] pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                </project>
                """.getBytes(StandardCharsets.UTF_8);

        serve("/com/example/widget/1.0/widget-1.0.pom", 200, pom);

        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tempDir));
        MavenRepo.Fetched fetched = repo.fetchPom(Coordinate.of("com.example", "widget", "1.0"));

        assertThat(fetched.url().toString()).endsWith("/widget-1.0.pom");
        assertThat(fetched.sha256()).isEqualTo(Hashing.sha256Hex(pom));
        assertThat(Files.readAllBytes(fetched.cachePath())).isEqualTo(pom);
    }

    @Test
    void fetches_jar_into_cas(@TempDir Path tempDir) throws Exception {
        byte[] jar = "fake-jar-bytes".getBytes(StandardCharsets.UTF_8);
        serve("/com/example/widget/1.0/widget-1.0.jar", 200, jar);

        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tempDir));
        MavenRepo.Fetched fetched = repo.fetchArtifact(Coordinate.of("com.example", "widget", "1.0"));

        assertThat(fetched.size()).isEqualTo(jar.length);
        assertThat(fetched.sha256()).isEqualTo(Hashing.sha256Hex(jar));
    }

    @Test
    void translates_404_to_typed_exception(@TempDir Path tempDir) {
        // No handler registered → 404 from the SimpleHttpServer fallback.
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tempDir));
        assertThatThrownBy(() ->
                repo.fetchArtifact(Coordinate.of("com.example", "missing", "9.9.9")))
                .isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
    }

    @Test
    void layout_paths_match_maven_convention() {
        Coordinate widget = Coordinate.of("com.fasterxml.jackson.core", "jackson-databind", "2.18.2");
        assertThat(MavenLayout.pomPath(widget))
                .isEqualTo("com/fasterxml/jackson/core/jackson-databind/2.18.2/jackson-databind-2.18.2.pom");
        assertThat(MavenLayout.artifactPath(widget))
                .isEqualTo("com/fasterxml/jackson/core/jackson-databind/2.18.2/jackson-databind-2.18.2.jar");
        assertThat(MavenLayout.metadataPath(widget))
                .isEqualTo("com/fasterxml/jackson/core/jackson-databind/maven-metadata.xml");
    }

    @Test
    void online_fetch_records_to_journal(@TempDir Path tempDir) throws Exception {
        byte[] pom = "<project/>".getBytes(StandardCharsets.UTF_8);
        serve("/com/example/widget/1.0/widget-1.0.pom", 200, pom);
        Journal journal = new Journal(tempDir);
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tempDir), journal);

        Coordinate coord = Coordinate.of("com.example", "widget", "1.0");
        repo.fetchPom(coord);

        Optional<Journal.Blob> blob = journal.lookup(coord, "pom");
        assertThat(blob).isPresent();
        assertThat(blob.get().sha256()).isEqualTo(Hashing.sha256Hex(pom));
        // metadata is deliberately not journaled
        assertThat(journal.versions("com.example", "widget")).containsExactly("1.0");
    }

    @Test
    void offline_fetch_is_served_from_journal_and_cas(@TempDir Path tempDir) throws Exception {
        byte[] pom = "<project/>".getBytes(StandardCharsets.UTF_8);
        serve("/com/example/widget/1.0/widget-1.0.pom", 200, pom);
        Journal journal = new Journal(tempDir);
        Cas cas = new Cas(tempDir);
        Coordinate coord = Coordinate.of("com.example", "widget", "1.0");

        // Warm the cache + journal online.
        new MavenRepo("test", base, new Http(), cas, journal).fetchPom(coord);

        // Now offline: stop the server so any network attempt would fail loudly.
        server.stop(0);
        goOffline();
        MavenRepo offline = new MavenRepo("test", base, new Http(), cas, journal);
        MavenRepo.Fetched fetched = offline.fetchPom(coord);

        assertThat(fetched.sha256()).isEqualTo(Hashing.sha256Hex(pom));
        assertThat(Files.readAllBytes(fetched.cachePath())).isEqualTo(pom);
    }

    @Test
    void offline_fetch_of_unjournaled_coord_is_not_found(@TempDir Path tempDir) {
        goOffline();
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tempDir), new Journal(tempDir));
        assertThatThrownBy(() -> repo.fetchPom(Coordinate.of("com.example", "absent", "1.0")))
                .isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
    }

    @Test
    void offline_pointer_to_gc_d_blob_is_not_found(@TempDir Path tempDir) {
        Journal journal = new Journal(tempDir);
        Coordinate coord = Coordinate.of("com.example", "widget", "1.0");
        // Journal points at a sha that was never stored in the CAS.
        journal.record(coord, "pom", "deadbeef".repeat(8), 10, "test", "https://repo/widget-1.0.pom");
        goOffline();

        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tempDir), journal);
        assertThatThrownBy(() -> repo.fetchPom(coord))
                .isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
    }

    @Test
    void offline_available_versions_come_from_journal(@TempDir Path tempDir) throws Exception {
        Journal journal = new Journal(tempDir);
        journal.record(Coordinate.of("com.example", "widget", "1.0"), "pom", "a", 1, "t", "u");
        journal.record(Coordinate.of("com.example", "widget", "2.0"), "pom", "b", 1, "t", "u");
        goOffline();

        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tempDir), journal);
        assertThat(repo.availableVersions(Coordinate.of("com.example", "widget", "0")))
                .containsExactlyInAnyOrder("1.0", "2.0");
    }

    private void serve(String path, int status, byte[] body) {
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }
}
