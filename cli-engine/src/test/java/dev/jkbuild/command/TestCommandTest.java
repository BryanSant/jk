// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.cli.Jk;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCommandTest {

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
    void test_with_no_test_sources_passes(@TempDir Path tempDir) throws Exception {
        scaffoldNoDeps(tempDir);
        int exit = run(
                "test",
                "-C",
                tempDir.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);
    }

    // (Removed test_without_lockfile_errors: `jk test` no longer requires a
    // pre-existing jk.lock — the pipeline auto-locks like `jk build`/`run`.
    // That auto-lock path is covered by the build/run integration tests.)

    // A genuinely test-source-free project: bare manifest, no sources. (`jk new`
    // now scaffolds a sample CalcTest, so it can't stand in for "no tests".)
    private static void scaffoldNoDeps(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve("jk.toml"),
                "[project]\ngroup = \"com.example\"\nname = \"x\"\nversion = \"0.1.0\"\njdk = \"25\"\njava = 25\n");
        ScaffoldTestSupport.writeEmptyLock(dir); // jk test needs a lock; nothing to resolve
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }
}
