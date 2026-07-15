// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.forge;

import build.jumpkick.http.Http;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Looks up the login name a forge token authenticates as, via the forge's {@code /user} API. Needed
 * because GitHub Packages (Maven) uses HTTP Basic auth with the <em>username</em> as the account
 * login and the token as the password — so reusing a {@code jk auth login} token there requires
 * knowing the login.
 *
 * <p>Endpoint-based (the caller resolves the {@code /user} URI and the JSON field to read from
 * {@link ForgeKind}) so the mechanism stays decoupled and testable against a local server.
 * Best-effort by contract: offline, a non-2xx response, or any error yields {@link
 * Optional#empty()} so the caller falls back to another auth shape. Results are cached per
 * (endpoint, token).
 */
public interface ForgeIdentity {

    /**
     * The login at {@code userEndpoint} (a forge {@code /user} API), reading {@code loginField} from
     * the JSON ({@code "login"} for GitHub/Gitea, {@code "username"} for GitLab). Empty when it can't
     * be determined.
     */
    Optional<String> login(URI userEndpoint, String loginField, String token);

    /** The real implementation, backed by a fresh {@link Http} client. */
    static ForgeIdentity real() {
        return new HttpForgeIdentity(new Http());
    }

    final class HttpForgeIdentity implements ForgeIdentity {

        private final Http http;
        private final Map<String, String> cache = new ConcurrentHashMap<>();

        public HttpForgeIdentity(Http http) {
            this.http = http;
        }

        @Override
        public Optional<String> login(URI userEndpoint, String loginField, String token) {
            if (token == null || token.isBlank()) return Optional.empty();
            String cacheKey = userEndpoint + " " + token;
            String cached = cache.get(cacheKey);
            if (cached != null) return Optional.of(cached);
            try {
                HttpResponse<byte[]> resp = http.get(
                        userEndpoint, Map.of("Authorization", "Bearer " + token, "Accept", "application/json"));
                if (resp.statusCode() / 100 != 2) return Optional.empty();
                String login = readJsonStr(new String(resp.body(), StandardCharsets.UTF_8), loginField);
                if (login == null || login.isBlank()) return Optional.empty();
                cache.put(cacheKey, login);
                return Optional.of(login);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (Exception e) {
                // Offline, network error, malformed JSON — caller falls back.
                return Optional.empty();
            }
        }

        /** Extract a top-level string field from a flat JSON object without a JSON library. */
        private static String readJsonStr(String json, String key) {
            String needle = "\"" + key + "\":\"";
            int start = json.indexOf(needle);
            if (start < 0) return null;
            start += needle.length();
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char n = json.charAt(++i);
                    if (n == '"') sb.append('"');
                    else if (n == '\\') sb.append('\\');
                    else {
                        sb.append('\\');
                        sb.append(n);
                    }
                } else if (c == '"') {
                    break;
                } else sb.append(c);
            }
            return sb.toString();
        }
    }
}
