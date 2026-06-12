// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver;

import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.cache.Cas;
import dev.jkbuild.http.Http;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.util.Hashing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CacheSyncTest {

    private HttpServer server;
    private URI base;
    private final Map<String, byte[]> served = new HashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = served.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void fetches_missing_artifact(@TempDir Path tempDir) throws Exception {
        byte[] jar = "abc".getBytes(StandardCharsets.UTF_8);
        String hex = Hashing.sha256Hex(jar);
        registerJar("com.foo", "leaf", "1.0", jar);

        Lockfile lock = lockOf(pkg("com.foo:leaf", "1.0", "sha256:" + hex));
        CacheSync.Report report = newSync(tempDir).sync(lock);

        assertThat(report.fetched()).isEqualTo(1);
        assertThat(report.upToDate()).isZero();
        assertThat(report.errors()).isEmpty();
    }

    @Test
    void recognizes_already_cached(@TempDir Path tempDir) throws Exception {
        byte[] jar = "already-cached".getBytes(StandardCharsets.UTF_8);
        String hex = Hashing.sha256Hex(jar);
        // Pre-populate the cache without going through the server.
        Cas cas = new Cas(tempDir.resolve("cache"));
        cas.put(jar);
        registerJar("com.foo", "leaf", "1.0", jar);

        CacheSync.Report report = new CacheSync(cas, new Http())
                .sync(lockOf(pkg("com.foo:leaf", "1.0", "sha256:" + hex)));

        assertThat(report.upToDate()).isEqualTo(1);
        assertThat(report.fetched()).isZero();
    }

    @Test
    void reports_checksum_mismatch(@TempDir Path tempDir) throws Exception {
        // Serve a jar whose actual sha256 won't match what the lockfile claims.
        byte[] jar = "real".getBytes(StandardCharsets.UTF_8);
        registerJar("com.foo", "leaf", "1.0", jar);
        Lockfile lock = lockOf(pkg("com.foo:leaf", "1.0", "sha256:deadbeef"));

        CacheSync.Report report = newSync(tempDir).sync(lock);

        assertThat(report.fetched()).isZero();
        assertThat(report.errors())
                .singleElement()
                .asString()
                .contains("checksum mismatch");
    }

    @Test
    void skips_packages_without_checksum(@TempDir Path tempDir) throws Exception {
        Lockfile lock = lockOf(pkg("com.foo:parent-pom", "1.0", null));
        CacheSync.Report report = newSync(tempDir).sync(lock);
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.errors()).isEmpty();
    }

    // --- helpers -----------------------------------------------------------

    private CacheSync newSync(Path tempDir) {
        return new CacheSync(new Cas(tempDir.resolve("cache")), new Http());
    }

    private void registerJar(String group, String artifact, String version, byte[] bytes) {
        String path = "/" + group.replace('.', '/') + "/" + artifact + "/" + version
                + "/" + artifact + "-" + version + ".jar";
        served.put(path, bytes);
    }

    private Lockfile lockOf(Lockfile.Artifact... packages) {
        return new Lockfile(Lockfile.CURRENT_VERSION, "jk test",
                Lockfile.RESOLUTION_ALGORITHM, List.of(packages));
    }

    private Lockfile.Artifact pkg(String module, String version, String checksum) {
        return new Lockfile.Artifact(module, version, "central+" + base + "/", checksum, null, List.of());
    }
}
