// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.util.Hashing;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The spawn path's engine-jar self-heal ({@link EngineJarFetcher}): download from the release
 * layout ({@code releases/<version>/jk-engine-<version>.jar} + {@code SHA256SUMS}), verify, land
 * atomically in the lib dir. The wiring INTO {@code spawn()} is native-client-only and stays
 * manual-verification territory, like the spawn itself (see {@code EngineClientTest}).
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

    @Test
    void fetch_verifies_installs_atomically_and_removes_stale_versions(@TempDir Path libDir) throws Exception {
        Path stale = libDir.resolve("jk-engine-0.9.0.jar");
        Files.createDirectories(libDir);
        Files.writeString(stale, "old");

        dev.jkbuild.cache.Cas cas = new dev.jkbuild.cache.Cas(libDir.resolve("cache"));
        dev.jkbuild.cache.VersionStore store = new dev.jkbuild.cache.VersionStore(libDir.resolve("versions"));
        Path installed = EngineJarFetcher.fetch(base, VERSION, libDir, cas, store, null);

        assertThat(installed).isEqualTo(libDir.resolve("jk-engine-1.2.3.jar"));
        assertThat(installed).hasBinaryContent(JAR);
        assertThat(stale).doesNotExist();
        assertThat(libDir.resolve("jk-engine-1.2.3.jar.part")).doesNotExist();
        // …and the same verified bytes were materialized into the side-by-side layout via the CAS.
        var m = store.resolve(VERSION).orElseThrow();
        assertThat(m.engineJar()).hasBinaryContent(JAR);
    }

    @Test
    void checksum_mismatch_fails_and_installs_nothing(@TempDir Path libDir) {
        jarBody = "tampered bytes".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> EngineJarFetcher.fetch(base, VERSION, libDir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("checksum mismatch");
        assertThat(libDir.resolve("jk-engine-1.2.3.jar")).doesNotExist();
        assertThat(libDir.resolve("jk-engine-1.2.3.jar.part")).doesNotExist();
    }

    @Test
    void missing_checksums_file_refuses_to_install(@TempDir Path libDir) {
        sumsStatus = 404;

        assertThatThrownBy(() -> EngineJarFetcher.fetch(base, VERSION, libDir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 404");
        assertThat(libDir.resolve("jk-engine-1.2.3.jar")).doesNotExist();
    }

    @Test
    void checksums_without_an_entry_for_the_jar_refuses_to_install(@TempDir Path libDir) {
        sumsBody = "abc123  jk-linux-x86_64.xz\n".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> EngineJarFetcher.fetch(base, VERSION, libDir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no entry for jk-engine-1.2.3.jar");
    }

    @Test
    void missing_jar_fails_with_the_url_and_status(@TempDir Path libDir) {
        jarStatus = 404;

        assertThatThrownBy(() -> EngineJarFetcher.fetch(base, VERSION, libDir))
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
