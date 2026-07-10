// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.engine.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The tiny hand-rolled router for {@code /api/*}: exact method + path dispatch, no pattern
 * language until something needs it ({@code docs/http.md}). A known path with the wrong method is
 * {@code 405} with a correct {@code Allow}; an unknown path is {@code 404}.
 */
final class ApiRouter {

    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    /** {@code "GET /api/status"} → handler. Insertion-ordered only for readable test dumps. */
    private final Map<String, Handler> routes = new LinkedHashMap<>();

    void register(String method, String path, Handler handler) {
        routes.put(method + " " + path, handler);
    }

    void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Handler handler = routes.get(exchange.getRequestMethod() + " " + path);
        if (handler != null) {
            handler.handle(exchange);
            return;
        }
        String allow = allowedMethods(path);
        if (!allow.isEmpty()) {
            exchange.getResponseHeaders().set("Allow", allow);
            HttpEngineServer.sendText(exchange, 405, "method not allowed\n");
            return;
        }
        HttpEngineServer.sendText(exchange, 404, "no such endpoint\n");
    }

    private String allowedMethods(String path) {
        String suffix = " " + path;
        return routes.keySet().stream()
                .filter(key -> key.endsWith(suffix))
                .map(key -> key.substring(0, key.indexOf(' ')))
                .collect(Collectors.joining(", "));
    }
}
