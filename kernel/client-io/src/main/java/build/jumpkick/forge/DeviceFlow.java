// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.forge;

import build.jumpkick.http.Http;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OAuth 2.0 Device Authorization Grant (RFC 8628) — the "copy this code into the browser" login
 * that {@code gh auth login} popularized. Works against any forge whose endpoints follow the RFC
 * (GitHub, GitLab, Gitea/Forgejo); Bitbucket has no device grant, so {@link
 * ForgeKind#deviceCodeUri} refuses to produce endpoints for it.
 *
 * <p>This class is the provider-neutral <i>mechanism</i>: it takes the two resolved endpoints and
 * drives the request → poll loop. Endpoint resolution is {@link ForgeKind}'s job; use {@link
 * #forHost} to wire the two together.
 */
public final class DeviceFlow {

    /** Injectable sleep so tests don't wait real seconds between polls. */
    @FunctionalInterface
    public interface Sleeper {
        void sleepSeconds(int seconds) throws InterruptedException;
    }

    private static final Sleeper REAL_SLEEP =
            s -> Thread.sleep(Duration.ofSeconds(s).toMillis());

    private final Http http;
    private final URI deviceCodeUri;
    private final URI tokenUri;
    private final String providerName;
    private final String clientId;
    private final String scope;
    private final Sleeper sleeper;

    public DeviceFlow(Http http, URI deviceCodeUri, URI tokenUri, String providerName, String clientId, String scope) {
        this(http, deviceCodeUri, tokenUri, providerName, clientId, scope, REAL_SLEEP);
    }

    /** Visible for tests — inject a no-op {@link Sleeper}. */
    public DeviceFlow(
            Http http,
            URI deviceCodeUri,
            URI tokenUri,
            String providerName,
            String clientId,
            String scope,
            Sleeper sleeper) {
        this.http = http;
        this.deviceCodeUri = deviceCodeUri;
        this.tokenUri = tokenUri;
        this.providerName = providerName;
        this.clientId = clientId;
        this.scope = scope;
        this.sleeper = sleeper;
    }

    /** Wire a flow for a concrete provider + host, sourcing endpoints from {@link ForgeKind}. */
    public static DeviceFlow forHost(Http http, ForgeKind kind, String host, String clientId, String scope) {
        if (!kind.supportsDeviceFlow()) {
            throw new AuthException(kind.displayName() + " does not support the device flow — use a token instead.");
        }
        return new DeviceFlow(http, kind.deviceCodeUri(host), kind.tokenUri(host), kind.displayName(), clientId, scope);
    }

    /**
     * Run the flow to completion and return the access token. {@code prompt} is invoked once with the
     * {@link DeviceCode} so the caller can display the user code / verification URL and optionally
     * open a browser; the code and URL must always be shown so headless sessions still work.
     */
    public String run(Consumer<DeviceCode> prompt) {
        DeviceCode dc = requestCode();
        prompt.accept(dc);
        return poll(dc);
    }

    private DeviceCode requestCode() {
        String body = parseBody(post(deviceCodeUri, Map.of("client_id", clientId, "scope", scope)));
        return new DeviceCode(
                str(body, "device_code"),
                str(body, "user_code"),
                str(body, "verification_uri"),
                str(body, "verification_uri_complete"),
                intVal(body, "interval", 5),
                intVal(body, "expires_in", 900));
    }

    private String poll(DeviceCode dc) {
        long deadline = System.nanoTime() + Duration.ofSeconds(dc.expiresIn()).toNanos();
        int interval = Math.max(1, dc.interval());
        while (System.nanoTime() < deadline) {
            sleep(interval);
            String body = parseBody(post(
                    tokenUri,
                    Map.of(
                            "client_id",
                            clientId,
                            "device_code",
                            dc.deviceCode(),
                            "grant_type",
                            "urn:ietf:params:oauth:grant-type:device_code")));

            String token = str(body, "access_token");
            if (token != null) return token;
            String error = str(body, "error");
            switch (error != null ? error : "") {
                case "authorization_pending" -> {
                    /* keep polling */
                }
                case "slow_down" -> interval += 5;
                case "expired_token" -> throw new AuthException("Code expired — run `jk auth login` again.");
                case "access_denied" -> throw new AuthException("Authorization was denied.");
                case "" -> throw new AuthException("Unexpected response from " + providerName + ".");
                default -> throw new AuthException("Device flow failed: " + error);
            }
        }
        throw new AuthException("Timed out waiting for authorization.");
    }

    private HttpResponse<byte[]> post(URI uri, Map<String, String> form) {
        try {
            return http.postForm(uri, form);
        } catch (IOException e) {
            throw new AuthException(
                    "Network error talking to " + providerName + " (" + uri.getHost() + "): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException("Interrupted while contacting " + providerName + ".");
        }
    }

    /** Return the response body as a UTF-8 string (2xx and 4xx alike). */
    private String parseBody(HttpResponse<byte[]> resp) {
        int status = resp.statusCode();
        if (status >= 500) {
            throw new AuthException(providerName + " returned HTTP " + status + ".");
        }
        return new String(resp.body(), StandardCharsets.UTF_8);
    }

    private static String str(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int s = json.indexOf(needle);
        if (s < 0) return null;
        s += needle.length();
        StringBuilder sb = new StringBuilder();
        for (int i = s; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                if (n == '"') sb.append('"');
                else {
                    sb.append('\\');
                    sb.append(n);
                }
            } else if (c == '"') break;
            else sb.append(c);
        }
        return sb.toString();
    }

    private static int intVal(String json, String key, int def) {
        String needle = "\"" + key + "\":";
        int s = json.indexOf(needle);
        if (s < 0) return def;
        s += needle.length();
        while (s < json.length() && json.charAt(s) == ' ') s++;
        int e = s;
        while (e < json.length() && Character.isDigit(json.charAt(e))) e++;
        try {
            return e > s ? Integer.parseInt(json.substring(s, e)) : def;
        } catch (NumberFormatException x) {
            return def;
        }
    }

    private void sleep(int seconds) {
        try {
            sleeper.sleepSeconds(seconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException("Interrupted while waiting for authorization.");
        }
    }
}
