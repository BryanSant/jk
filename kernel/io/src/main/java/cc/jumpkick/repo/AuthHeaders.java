// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.credential.RepoCredential;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Renders a {@link RepoCredential} to the HTTP request headers a transport sends. Lives in {@code
 * :io} (next to the transports) so {@code :core}'s credential model stays free of HTTP concerns.
 */
public final class AuthHeaders {

    private AuthHeaders() {}

    /** {@code Authorization} header(s) for the credential; empty for anonymous. */
    public static Map<String, String> of(RepoCredential cred) {
        return switch (cred) {
            case RepoCredential.Anonymous ignored -> Map.of();
            case RepoCredential.Basic b ->
                Map.of("Authorization", "Basic " + base64(b.username() + ":" + b.password()));
            case RepoCredential.Bearer b -> Map.of("Authorization", "Bearer " + b.token());
        };
    }

    private static String base64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
