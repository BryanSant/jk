// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.cache.Cas;
import dev.jkbuild.config.JkConfig;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.util.Hashing;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenRepoTest {

    // Fetches mirror into the Maven local repo; point that at a throwaway dir (see
    // M2Dirs) so these tests never write into the developer's real ~/.m2.
    @BeforeAll
    static void isolateM2(@TempDir Path m2) {
        System.setProperty("jk.m2.local", m2.toString());
    }

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
        dev.jkbuild.config.SessionContext.reset();
    }

    private static void goOffline() {
        dev.jkbuild.config.SessionContext.installConfig(new JkConfig(
                Optional.empty(),
                Optional.of(true),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
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
        assertThatThrownBy(() -> repo.fetchArtifact(Coordinate.of("com.example", "missing", "9.9.9")))
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
    void online_fetch_mirrors_into_named_repo_store(@TempDir Path tempDir) throws Exception {
        byte[] pom = "<project/>".getBytes(StandardCharsets.UTF_8);
        serve("/com/example/widget/1.0/widget-1.0.pom", 200, pom);
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tempDir));

        Coordinate coord = Coordinate.of("com.example", "widget", "1.0");
        repo.fetchPom(coord);

        // metadata is deliberately not mirrored
        assertThat(RepoArtifactStore.forRepoName(tempDir, "test").versions("com.example", "widget"))
                .containsExactly("1.0");
    }

    @Test
    void default_fetch_never_touches_m2(@TempDir Path tempDir, @TempDir Path m2) throws Exception {
        String previous = System.setProperty("jk.m2.local", m2.toString());
        try {
            byte[] jar = "fake-jar-bytes".getBytes(StandardCharsets.UTF_8);
            serve("/com/example/widget/1.0/widget-1.0.jar", 200, jar);
            // mirrorToM2 defaults to false — no project has opted in.
            MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tempDir));

            Coordinate coord = Coordinate.of("com.example", "widget", "1.0");
            repo.fetchArtifact(coord);

            // Primary store: repos/<name>/ holds the real, human-readable jar.
            assertThat(RepoArtifactStore.forRepoName(tempDir, "test")
                            .locate(MavenLayout.artifactPath(coord)))
                    .isPresent();
            // ~/.m2 (the temp dir standing in for it) is untouched.
            assertThat(m2.resolve(MavenLayout.artifactPath(coord))).doesNotExist();
        } finally {
            restoreM2Local(previous);
        }
    }

    @Test
    void mirror_to_m2_true_also_populates_m2(@TempDir Path tempDir, @TempDir Path m2) throws Exception {
        String previous = System.setProperty("jk.m2.local", m2.toString());
        try {
            byte[] jar = "fake-jar-bytes".getBytes(StandardCharsets.UTF_8);
            serve("/com/example/widget/1.0/widget-1.0.jar", 200, jar);
            MavenRepo repo = new MavenRepo(
                    "test", base, new Http(), new Cas(tempDir), dev.jkbuild.credential.RepoCredential.ANONYMOUS, true);

            Coordinate coord = Coordinate.of("com.example", "widget", "1.0");
            repo.fetchArtifact(coord);

            // Primary store, as always.
            assertThat(RepoArtifactStore.forRepoName(tempDir, "test")
                            .locate(MavenLayout.artifactPath(coord)))
                    .isPresent();
            // Opt-in mirror: jar + Maven-compatible sidecars land in ~/.m2 too.
            Path m2Jar = m2.resolve(MavenLayout.artifactPath(coord));
            assertThat(m2Jar).exists();
            assertThat(Files.readAllBytes(m2Jar)).isEqualTo(jar);
            assertThat(m2Jar.resolveSibling(m2Jar.getFileName() + ".sha1")).exists();
            assertThat(m2Jar.resolveSibling(m2Jar.getFileName() + ".md5")).exists();
            assertThat(m2Jar.resolveSibling("_remote.repositories")).exists();
        } finally {
            restoreM2Local(previous);
        }
    }

    /** Restore {@code jk.m2.local} to its prior value (the class-level {@link #isolateM2} temp dir). */
    private static void restoreM2Local(String previous) {
        if (previous != null) {
            System.setProperty("jk.m2.local", previous);
        } else {
            System.clearProperty("jk.m2.local");
        }
    }

    @Test
    void offline_fetch_is_served_from_the_named_repo_store(@TempDir Path tempDir) throws Exception {
        byte[] pom = "<project/>".getBytes(StandardCharsets.UTF_8);
        serve("/com/example/widget/1.0/widget-1.0.pom", 200, pom);
        Cas cas = new Cas(tempDir);
        Coordinate coord = Coordinate.of("com.example", "widget", "1.0");

        // Warm the cache + named-repo store online.
        new MavenRepo("test", base, new Http(), cas).fetchPom(coord);

        // Now offline: stop the server so any network attempt would fail loudly.
        server.stop(0);
        goOffline();
        MavenRepo offline = new MavenRepo("test", base, new Http(), cas);
        MavenRepo.Fetched fetched = offline.fetchPom(coord);

        assertThat(fetched.sha256()).isEqualTo(Hashing.sha256Hex(pom));
        assertThat(Files.readAllBytes(fetched.cachePath())).isEqualTo(pom);
    }

    @Test
    void offline_fetch_of_unindexed_coord_is_not_found(@TempDir Path tempDir) {
        goOffline();
        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tempDir));
        assertThatThrownBy(() -> repo.fetchPom(Coordinate.of("com.example", "absent", "1.0")))
                .isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
    }

    @Test
    void offline_available_versions_come_from_the_named_repo_store(@TempDir Path tempDir) throws Exception {
        Cas cas = new Cas(tempDir);
        RepoArtifactStore store = RepoArtifactStore.forRepoName(tempDir, "test");
        store.materialize(
                MavenLayout.artifactPath(Coordinate.of("com.example", "widget", "1.0")),
                cas.put("jar-1".getBytes(StandardCharsets.UTF_8)),
                "sha-1");
        store.materialize(
                MavenLayout.artifactPath(Coordinate.of("com.example", "widget", "2.0")),
                cas.put("jar-2".getBytes(StandardCharsets.UTF_8)),
                "sha-2");
        goOffline();

        MavenRepo repo = new MavenRepo("test", base, new Http(), new Cas(tempDir));
        assertThat(repo.availableVersions(Coordinate.of("com.example", "widget", "0")))
                .containsExactlyInAnyOrder("1.0", "2.0");
    }

    @Test
    void file_repo_enumerates_versions_via_transport_not_the_http_cache(@TempDir Path tempDir) throws Exception {
        // Regression: a non-HTTP (file://) repo may be paired with an Http client,
        // but version enumeration must go through the transport. The HTTP metadata
        // cache speaks java.net.http directly and throws "invalid URI scheme file"
        // on a file:// URI, so it must not be engaged for such repos.
        Path repoDir = tempDir.resolve("repo");
        Path metaFile = repoDir.resolve(MavenLayout.metadataPath(Coordinate.of("com.example", "widget", "0")));
        Files.createDirectories(metaFile.getParent());
        Files.writeString(metaFile, """
                <metadata><groupId>com.example</groupId><artifactId>widget</artifactId>
                <versioning><versions><version>1.0</version><version>2.0</version></versions></versioning>
                </metadata>
                """);

        MavenRepo repo = new MavenRepo("local", repoDir.toUri(), new Http(), new Cas(tempDir.resolve("cas")));
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
