// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.forge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class DeviceFlowTest {

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

    /** A flow wired to the local server, with sleeps stubbed out. */
    private DeviceFlow flow() {
        return new DeviceFlow(
                new Http(),
                base.resolve("/device/code"),
                base.resolve("/token"),
                "TestForge",
                "client-123",
                "read:packages",
                seconds -> {
                    /* no real waiting in tests */
                });
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Test
    void happy_path_returns_token() throws Exception {
        AtomicReference<String> codeBody = new AtomicReference<>();
        server.createContext("/device/code", ex -> {
            codeBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, 200, """
                {"device_code":"dev-abc","user_code":"WXYZ-1234",
                 "verification_uri":"https://forge.test/device","interval":5,"expires_in":900}""");
        });
        server.createContext("/token", ex -> {
            ex.getRequestBody().readAllBytes();
            respond(ex, 200, "{\"access_token\":\"tok-secret\"}");
        });

        AtomicReference<DeviceCode> prompted = new AtomicReference<>();
        String token = flow().run(prompted::set);

        assertThat(token).isEqualTo("tok-secret");
        // The prompt callback saw the user-facing code + URL.
        assertThat(prompted.get().userCode()).isEqualTo("WXYZ-1234");
        assertThat(prompted.get().verificationUri()).isEqualTo("https://forge.test/device");
        // The device-code request was form-encoded with client_id + scope.
        assertThat(codeBody.get()).contains("client_id=client-123").contains("scope=read%3Apackages");
    }

    @Test
    void polls_through_authorization_pending() throws Exception {
        server.createContext("/device/code", ex -> {
            ex.getRequestBody().readAllBytes();
            respond(ex, 200, """
                {"device_code":"dev-abc","user_code":"WXYZ-1234",
                 "verification_uri":"https://forge.test/device","interval":1,"expires_in":900}""");
        });
        AtomicInteger polls = new AtomicInteger();
        server.createContext("/token", ex -> {
            ex.getRequestBody().readAllBytes();
            int n = polls.incrementAndGet();
            if (n < 3) {
                respond(ex, 400, "{\"error\":\"authorization_pending\"}");
            } else {
                respond(ex, 200, "{\"access_token\":\"tok-after-wait\"}");
            }
        });

        String token = flow().run(dc -> {});
        assertThat(token).isEqualTo("tok-after-wait");
        assertThat(polls.get()).isEqualTo(3);
    }

    @Test
    void access_denied_aborts() throws Exception {
        server.createContext("/device/code", ex -> {
            ex.getRequestBody().readAllBytes();
            respond(ex, 200, """
                {"device_code":"d","user_code":"U","verification_uri":"https://forge.test/device",
                 "interval":1,"expires_in":900}""");
        });
        server.createContext("/token", ex -> {
            ex.getRequestBody().readAllBytes();
            respond(ex, 400, "{\"error\":\"access_denied\"}");
        });

        assertThatThrownBy(() -> flow().run(dc -> {}))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("denied");
    }

    @Test
    void expired_token_aborts() throws Exception {
        server.createContext("/device/code", ex -> {
            ex.getRequestBody().readAllBytes();
            respond(ex, 200, """
                {"device_code":"d","user_code":"U","verification_uri":"https://forge.test/device",
                 "interval":1,"expires_in":900}""");
        });
        server.createContext("/token", ex -> {
            ex.getRequestBody().readAllBytes();
            respond(ex, 400, "{\"error\":\"expired_token\"}");
        });

        assertThatThrownBy(() -> flow().run(dc -> {}))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void forHost_refuses_provider_without_device_flow() {
        assertThatThrownBy(() -> DeviceFlow.forHost(new Http(), ForgeKind.BITBUCKET, "bitbucket.org", "cid", "scope"))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("does not support");
    }
}
