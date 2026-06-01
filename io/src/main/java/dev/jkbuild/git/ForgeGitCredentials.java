// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.git;

import dev.jkbuild.forge.ForgeAuth;
import dev.jkbuild.forge.ForgeKind;
import dev.jkbuild.forge.ResolvedToken;

import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/**
 * {@link GitCredentials} backed by forge auth: for an {@code http(s)} remote on
 * a known forge host, reuse the token a prior {@code jk auth login} stored (via
 * {@code ForgeAuth}) as HTTP credentials for the clone. SSH remotes and unknown
 * hosts return null (the SSH agent / anonymous access handle those).
 *
 * <p>The HTTP username/password shape differs per forge:
 * <ul>
 *   <li>GitHub / Gitea-Forgejo — token as the username, empty password.</li>
 *   <li>GitLab — username {@code oauth2}, token as the password.</li>
 *   <li>Bitbucket — username {@code x-token-auth}, token as the password.</li>
 * </ul>
 * NOTE: only the GitHub shape is well-established here; the others need a
 * real-remote smoke test (tracked with the other forge verification items).
 */
public final class ForgeGitCredentials implements GitCredentials {

    private final ForgeAuth forgeAuth;

    public ForgeGitCredentials() {
        this(new ForgeAuth());
    }

    public ForgeGitCredentials(ForgeAuth forgeAuth) {
        this.forgeAuth = forgeAuth;
    }

    @Override
    public CredentialsProvider forRemote(String remoteUrl) {
        return credentials(remoteUrl, forgeAuth)
                .map(up -> (CredentialsProvider) new UsernamePasswordCredentialsProvider(up[0], up[1]))
                .orElse(null);
    }

    /**
     * Resolve {@code {username, password}} for a remote, or empty when auth
     * isn't applicable (non-HTTP, unknown host, or no stored token). Pure +
     * testable — the jgit wrapping happens in {@link #forRemote}.
     */
    static Optional<String[]> credentials(String remoteUrl, ForgeAuth forgeAuth) {
        URI uri;
        try {
            uri = URI.create(remoteUrl);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            return Optional.empty();   // ssh:// and scp form → SSH agent/keys
        }
        String host = uri.getHost();
        if (host == null) return Optional.empty();
        Optional<ForgeKind> kind = ForgeKind.inferFromHost(host);
        if (kind.isEmpty()) return Optional.empty();   // unknown host → anonymous
        Optional<ResolvedToken> token = forgeAuth.resolveSilently(kind.get(), host);
        return token.map(t -> usernamePassword(kind.get(), t.value()));
    }

    /** The per-forge HTTP Basic shape for a clone token. */
    static String[] usernamePassword(ForgeKind kind, String token) {
        return switch (kind) {
            case GITHUB, GITEA -> new String[]{token, ""};
            case GITLAB -> new String[]{"oauth2", token};
            case BITBUCKET -> new String[]{"x-token-auth", token};
        };
    }
}
