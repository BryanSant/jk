// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.forge.ForgeAuth;
import cc.jumpkick.forge.ForgeIdentity;
import cc.jumpkick.forge.ForgeKind;
import cc.jumpkick.forge.ResolvedToken;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Resolves the {@link RepoCredential} for an artifact repository across the sources in
 * docs/artifact-repos.md. Precedence (explicit first; the forge bridge is a convenience fallback so
 * it can't shadow a credential the user set deliberately):
 *
 * <ol>
 *   <li>inline {@code jk.toml} credential (passed in by the caller);
 *   <li>env vars {@code JK_REPO_<NAME>_TOKEN} or {@code JK_REPO_<NAME>_USERNAME} + {@code
 *       _PASSWORD};
 *   <li>the jk repo credential store ({@code jk repo login});
 *   <li>{@code ~/.m2/settings.xml} {@code <server>} matched by id;
 *   <li>forge-token bridge — for package-registry hosts, borrow the token a prior {@code jk auth
 *       login} stored (see {@link ForgeAuth}).
 * </ol>
 *
 * <p>All collaborators are injected so the resolver is unit-testable without the environment, real
 * files, or the network.
 */
public final class RepoCredentialResolver {

    private final Function<String, String> env;
    private final MavenSettings settings;
    private final RepoCredentialStore store;
    private final ForgeAuth forgeAuth;
    private final ForgeIdentity identity;

    public RepoCredentialResolver() {
        this(System::getenv, MavenSettings.load(), new RepoCredentialStore(), new ForgeAuth(), ForgeIdentity.real());
    }

    public RepoCredentialResolver(
            Function<String, String> env,
            MavenSettings settings,
            RepoCredentialStore store,
            ForgeAuth forgeAuth,
            ForgeIdentity identity) {
        this.env = env;
        this.settings = settings;
        this.store = store;
        this.forgeAuth = forgeAuth;
        this.identity = identity;
    }

    /**
     * Resolve credentials for the repository named {@code repoId} at {@code url}. {@code inline} is
     * the credential declared inline in {@code jk.toml} (empty when none / not yet parsed). Never
     * returns null; falls back to {@link RepoCredential#ANONYMOUS}.
     */
    public RepoCredential resolve(String repoId, URI url, Optional<RepoCredential> inline) {
        // 1. inline jk.toml
        Optional<RepoCredential> fromInline = inline.filter(c -> !c.isAnonymous());
        if (fromInline.isPresent()) return fromInline.get();

        // Sources 2–4 are keyed by repo id; skip them when there's no declared
        // name (e.g. `jk publish --repo-url <url>` with no matching repo).
        boolean named = repoId != null && !repoId.isBlank();
        if (named) {
            // 2. environment variables
            Optional<RepoCredential> fromEnv = fromEnv(repoId);
            if (fromEnv.isPresent()) return fromEnv.get();

            // 3. jk repo credential store
            Optional<RepoCredential> fromStore = store.read(repoId);
            if (fromStore.isPresent()) return fromStore.get();

            // 4. ~/.m2/settings.xml
            Optional<RepoCredential> fromSettings = settings.server(repoId)
                    .map(s -> new RepoCredential.Basic(
                            s.username() == null ? "" : s.username(), s.password() == null ? "" : s.password()));
            if (fromSettings.isPresent()) return fromSettings.get();
        }

        // 5. forge-token bridge (package registries) — keyed by host, always tried
        Optional<RepoCredential> fromForge = forgeBridge(url);
        if (fromForge.isPresent()) return fromForge.get();

        return RepoCredential.ANONYMOUS;
    }

    private Optional<RepoCredential> fromEnv(String repoId) {
        String prefix = "JK_REPO_" + sanitizeEnv(repoId) + "_";
        String token = nonBlank(env.apply(prefix + "TOKEN"));
        if (token != null) return Optional.of(new RepoCredential.Bearer(token));
        String username = nonBlank(env.apply(prefix + "USERNAME"));
        if (username != null) {
            String password = env.apply(prefix + "PASSWORD");
            return Optional.of(new RepoCredential.Basic(username, password == null ? "" : password));
        }
        return Optional.empty();
    }

    /**
     * If {@code url}'s host is a known forge package registry, reuse the token a prior {@code jk auth
     * login} stored, in the auth shape that registry expects:
     *
     * <ul>
     *   <li><b>GitHub Packages</b> ({@code maven.pkg.github.com}) — HTTP Basic with the account login
     *       as username and the token as password. The login is looked up via the GitHub API; if that
     *       can't be resolved (offline / error) we fall back to Bearer, which is no worse than
     *       before.
     *   <li><b>GitLab / Gitea</b> — Bearer. The device-flow login yields an OAuth access token, which
     *       these accept on their package APIs.
     * </ul>
     */
    private Optional<RepoCredential> forgeBridge(URI url) {
        if (url == null || url.getHost() == null) return Optional.empty();
        String host = url.getHost().toLowerCase(Locale.ROOT);

        ForgeKind kind;
        String forgeHost;
        if (host.equals("maven.pkg.github.com")) {
            kind = ForgeKind.GITHUB;
            forgeHost = "github.com";
        } else {
            Optional<ForgeKind> inferred = ForgeKind.inferFromHost(host);
            if (inferred.isEmpty()) return Optional.empty();
            kind = inferred.get();
            forgeHost = host;
        }

        Optional<ResolvedToken> token = forgeAuth.resolveSilently(kind, forgeHost);
        if (token.isEmpty()) return Optional.empty();
        String tok = token.get().value();

        if (kind == ForgeKind.GITHUB) {
            URI userEndpoint = URI.create(kind.apiBase(forgeHost).toString() + "/user");
            return Optional.of(identity.login(userEndpoint, "login", tok)
                    .map(login -> (RepoCredential) new RepoCredential.Basic(login, tok))
                    .orElseGet(() -> new RepoCredential.Bearer(tok)));
        }
        return Optional.of(new RepoCredential.Bearer(tok));
    }

    private static String sanitizeEnv(String repoId) {
        StringBuilder sb = new StringBuilder(repoId.length());
        for (char c : repoId.toUpperCase(Locale.ROOT).toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return sb.toString();
    }

    private static String nonBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }
}
