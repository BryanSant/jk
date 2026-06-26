// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.forge;

import java.net.URI;
import java.util.Optional;

/**
 * Resolves HTTP git credentials from forge auth tokens (PRD §11.4).
 * Returns {@code {username, password}} pairs suitable for passing to a
 * git operation; the JGit wrapping happens in the {@code jk-git-runner}
 * worker subprocess.
 *
 * <p>Per-forge HTTP Basic shape:
 * <ul>
 *   <li>GitHub / Gitea-Forgejo — token as username, empty password.</li>
 *   <li>GitLab — username {@code oauth2}, token as password.</li>
 *   <li>Bitbucket — username {@code x-token-auth}, token as password.</li>
 * </ul>
 */
public final class ForgeGitCredentials {

    private final ForgeAuth forgeAuth;

    public ForgeGitCredentials() {
        this(new ForgeAuth());
    }

    public ForgeGitCredentials(ForgeAuth forgeAuth) {
        this.forgeAuth = forgeAuth;
    }

    /**
     * Resolve {@code {username, password}} for a remote, or {@code null} when
     * auth isn't applicable (non-HTTP, unknown host, or no stored token).
     */
    public String[] resolveCredentials(String remoteUrl) {
        return credentials(remoteUrl, forgeAuth).orElse(null);
    }

    /** Pure static helper for testing without a ForgeAuth instance. */
    static Optional<String[]> credentials(String remoteUrl, ForgeAuth forgeAuth) {
        URI uri;
        try {
            uri = URI.create(remoteUrl);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            return Optional.empty();
        }
        String host = uri.getHost();
        if (host == null) return Optional.empty();
        Optional<ForgeKind> kind = ForgeKind.inferFromHost(host);
        if (kind.isEmpty()) return Optional.empty();
        Optional<ResolvedToken> token = forgeAuth.resolveSilently(kind.get(), host);
        return token.map(t -> usernamePassword(kind.get(), t.value()));
    }

    static String[] usernamePassword(ForgeKind kind, String token) {
        return switch (kind) {
            case GITHUB, GITEA -> new String[] {token, ""};
            case GITLAB -> new String[] {"oauth2", token};
            case BITBUCKET -> new String[] {"x-token-auth", token};
        };
    }
}
