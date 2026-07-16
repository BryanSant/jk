// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The spawn path's engine-jar self-heal ({@link EngineJarFetcher}): download from the release
 * layout ({@code releases/<version>/jk-engine-<version>.jar} + {@code SHA256SUMS}), verify,
 * ingest into the CAS, and materialize {@code versions/<v>/} — the only installed layout. The
 * wiring INTO {@code spawn()} is native-client-only and stays manual-verification territory,
 * like the spawn itself (see {@code EngineClientTest}).
 */
class EngineJarFetcherTest {

    private static final String VERSION = "1.2.3";
    private static final byte[] JAR = "fake engine jar bytes".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private URI base;
    private volatile byte[] sumsBody;
    private volatile byte[] jarBody;
    private volatile int sumsStatus = 200;
    private volatile int jarStatus = 200;

    @BeforeEach
    void start() throws IOException {
        jarBody = JAR;
        sumsBody = (Hashing.sha256Hex(JAR) + "  jk-engine-" + VERSION + ".jar\n").getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/releases/" + VERSION + "/SHA256SUMS", exchange -> {
            exchange.sendResponseHeaders(sumsStatus, sumsStatus == 200 ? sumsBody.length : -1);
            if (sumsStatus == 200) exchange.getResponseBody().write(sumsBody);
            exchange.close();
        });
        server.createContext("/releases/" + VERSION + "/jk-engine-" + VERSION + ".jar", exchange -> {
            exchange.sendResponseHeaders(jarStatus, jarStatus == 200 ? jarBody.length : -1);
            if (jarStatus == 200) exchange.getResponseBody().write(jarBody);
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/releases");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static cc.jumpkick.cache.Cas cas(Path root) {
        return new cc.jumpkick.cache.Cas(root.resolve("cache"));
    }

    private static cc.jumpkick.cache.VersionStore store(Path root) {
        return new cc.jumpkick.cache.VersionStore(root.resolve("versions"));
    }

    @Test
    void fetch_verifies_and_materializes_cas_first(@TempDir Path root) throws Exception {
        var cas = cas(root);
        var store = store(root);
        Path installed = EngineJarFetcher.fetch(base, VERSION, cas, store, null);

        var m = store.resolve(VERSION).orElseThrow();
        assertThat(installed).isEqualTo(m.engineJar());
        assertThat(installed).hasBinaryContent(JAR);
        // The CAS holds the blob — a pruned version re-materializes from it offline.
        assertThat(cas.pathFor(Hashing.sha256Hex(JAR))).exists();
    }

    @Test
    void checksum_mismatch_fails_and_installs_nothing(@TempDir Path root) {
        jarBody = "tampered bytes".getBytes(StandardCharsets.UTF_8);

        var store = store(root);
        assertThatThrownBy(() -> EngineJarFetcher.fetch(base, VERSION, cas(root), store, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("checksum mismatch");
        assertThat(store.resolve(VERSION)).isEmpty();
    }

    @Test
    void missing_checksums_file_refuses_to_install(@TempDir Path root) {
        sumsStatus = 404;

        var store = store(root);
        assertThatThrownBy(() -> EngineJarFetcher.fetch(base, VERSION, cas(root), store, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 404");
        assertThat(store.resolve(VERSION)).isEmpty();
    }

    @Test
    void checksums_without_an_entry_for_the_jar_refuses_to_install(@TempDir Path root) {
        sumsBody = "abc123  jk-linux-x86_64.xz\n".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> EngineJarFetcher.fetch(base, VERSION, cas(root), store(root), null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no entry for jk-engine-1.2.3.jar");
    }

    @Test
    void missing_jar_fails_with_the_url_and_status(@TempDir Path root) {
        jarStatus = 404;

        assertThatThrownBy(() -> EngineJarFetcher.fetch(base, VERSION, cas(root), store(root), null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("engine jar")
                .hasMessageContaining("HTTP 404");
    }

    /** The gate that keeps this out of the JVM dist, offline runs, and dev/CI snapshot builds. */
    @Test
    void applicable_only_for_online_native_release_clients() {
        assertThat(EngineJarFetcher.applicable("1.2.3", true, false)).isTrue();
        assertThat(EngineJarFetcher.applicable("1.2.3-SNAPSHOT", true, false)).isFalse();
        assertThat(EngineJarFetcher.applicable("1.2.3", false, false)).isFalse();
        assertThat(EngineJarFetcher.applicable("1.2.3", true, true)).isFalse();
    }
}
