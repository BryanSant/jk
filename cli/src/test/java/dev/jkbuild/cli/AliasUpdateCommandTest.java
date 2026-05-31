// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AliasUpdateCommandTest {

    private HttpServer server;
    private URI base;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/aliases.toml", exchange -> {
            int code = status.get();
            byte[] payload = body.get() == null
                    ? new byte[0]
                    : body.get().getBytes(StandardCharsets.UTF_8);
            if (code == 200) {
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            } else {
                exchange.sendResponseHeaders(code, -1);
            }
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/aliases.toml");
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void update_writes_payload_to_cache(@TempDir Path tempDir) throws Exception {
        body.set("""
                [aliases]
                foo = "com.acme:foo"
                bar = "org.example:bar"
                """);

        Path cache = tempDir.resolve("aliases.toml");
        int exit = run(cache);

        assertThat(exit).isZero();
        assertThat(Files.readString(cache)).contains("foo = \"com.acme:foo\"");
    }

    @Test
    void update_backs_up_previous_cache_before_overwriting(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("aliases.toml");
        Files.writeString(cache, "[aliases]\nold = \"com.old:thing\"\n");

        body.set("[aliases]\nnew = \"com.new:thing\"\n");

        int exit = run(cache);

        assertThat(exit).isZero();
        assertThat(Files.readString(cache)).contains("new = \"com.new:thing\"");
        assertThat(cache.resolveSibling("aliases.toml.prev")).exists();
        assertThat(Files.readString(cache.resolveSibling("aliases.toml.prev")))
                .contains("old = \"com.old:thing\"");
    }

    @Test
    void update_rejects_malformed_payload(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("aliases.toml");
        Files.writeString(cache, "[aliases]\nkeep = \"com.acme:keep\"\n");

        // No [aliases] table → parse rejects.
        body.set("# garbage with no aliases table\n");

        int exit = run(cache);

        assertThat(exit).isOne();
        // Previous cache untouched.
        assertThat(Files.readString(cache)).contains("keep = \"com.acme:keep\"");
    }

    @Test
    void update_rejects_payload_with_versioned_coords(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("aliases.toml");
        body.set("""
                [aliases]
                bad = "com.acme:bad:1.0.0"
                """);

        int exit = run(cache);

        assertThat(exit).isOne();
        // Previous cache wasn't there → no file written.
        assertThat(cache).doesNotExist();
    }

    @Test
    void update_reports_http_failure_without_touching_cache(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("aliases.toml");
        Files.writeString(cache, "[aliases]\nkeep = \"com.acme:keep\"\n");

        status.set(503);

        int exit = run(cache);

        assertThat(exit).isOne();
        assertThat(Files.readString(cache)).contains("keep = \"com.acme:keep\"");
    }

    private int run(Path cacheFile) {
        return new CommandLine(new Jk()).execute(
                "alias", "update",
                "--source", base.toString(),
                "--cache-file", cacheFile.toString());
    }
}
