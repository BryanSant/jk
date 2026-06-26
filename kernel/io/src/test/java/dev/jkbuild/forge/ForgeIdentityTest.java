// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.forge;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.http.Http;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ForgeIdentityTest {

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

    private ForgeIdentity.HttpForgeIdentity identity() {
        return new ForgeIdentity.HttpForgeIdentity(new Http());
    }

    @Test
    void reads_login_and_sends_bearer() throws Exception {
        AtomicReference<String> seenAuth = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/user", ex -> {
            calls.incrementAndGet();
            seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"login\":\"octocat\",\"id\":1}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });

        URI endpoint = base.resolve("/user");
        var id = identity();
        assertThat(id.login(endpoint, "login", "tok-123")).contains("octocat");
        assertThat(seenAuth.get()).isEqualTo("Bearer tok-123");

        // Second lookup is cached — no extra HTTP call.
        assertThat(id.login(endpoint, "login", "tok-123")).contains("octocat");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void reads_gitlab_username_field() throws Exception {
        server.createContext("/user", ex -> {
            byte[] body = "{\"username\":\"alice\",\"id\":7}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        assertThat(identity().login(base.resolve("/user"), "username", "tok")).contains("alice");
    }

    @Test
    void non_2xx_yields_empty() {
        server.createContext("/user", ex -> {
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });
        assertThat(identity().login(base.resolve("/user"), "login", "bad-token"))
                .isEmpty();
    }

    @Test
    void blank_token_yields_empty_without_calling() {
        // No context registered; a call would 404. Blank token must short-circuit.
        assertThat(identity().login(base.resolve("/user"), "login", "  ")).isEmpty();
    }
}
