// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.forge;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A git forge jk knows how to authenticate against. The enum captures the
 * provider <i>software</i> (the protocol/endpoint shape); the concrete
 * <i>host</i> is a separate axis ({@code github.com} vs a self-hosted GHE,
 * {@code gitlab.com} vs a private GitLab, any number of Gitea/Forgejo
 * instances). See docs/forge-auth.md.
 *
 * <p>Each kind declares:
 * <ul>
 *   <li>how to reach its OAuth device-flow endpoints for a given host
 *       (or that it has none — {@link #supportsDeviceFlow()});</li>
 *   <li>the native CLI we can piggyback on for a token, if one exists
 *       ({@code gh}, {@code glab});</li>
 *   <li>the native environment variables that ecosystem already uses
 *       ({@code GH_TOKEN}, {@code GITLAB_TOKEN}, …);</li>
 *   <li>jk's own override variable, {@code JK_<KIND>_TOKEN}.</li>
 * </ul>
 *
 * <p>OAuth client IDs are <i>not</i> baked in here — they are a per-app
 * registration detail resolved at login time (a public client_id, no
 * secret). The placeholders below are wired through {@code DeviceFlow}'s
 * caller, not consumed from the enum.
 */
public enum ForgeKind {

    GITHUB("github", "GitHub", "github.com",
            true,
            List.of("gh", "auth", "token"),
            new String[]{"GH_TOKEN", "GITHUB_TOKEN"}),

    GITLAB("gitlab", "GitLab", "gitlab.com",
            true,
            List.of("glab", "auth", "token"),
            new String[]{"GITLAB_TOKEN"}),

    // Covers Gitea and its forks (Forgejo, Codeberg). No single canonical
    // host, so --host is effectively required; no widely-installed native
    // token CLI to piggyback on.
    GITEA("gitea", "Gitea/Forgejo", null,
            true,
            null,
            new String[]{"GITEA_TOKEN", "FORGEJO_TOKEN"}),

    // Bitbucket Cloud has no device-authorization grant; auth is via app
    // passwords / OAuth consumers. We fall back to token paste.
    BITBUCKET("bitbucket", "Bitbucket", "bitbucket.org",
            false,
            null,
            new String[]{"BITBUCKET_TOKEN"});

    private final String id;
    private final String displayName;
    private final String defaultHost;       // null → --host required
    private final boolean supportsDeviceFlow;
    private final List<String> nativeCliToken;   // null → none
    private final String[] nativeEnvVars;

    ForgeKind(String id, String displayName, String defaultHost,
              boolean supportsDeviceFlow, List<String> nativeCliToken,
              String[] nativeEnvVars) {
        this.id = id;
        this.displayName = displayName;
        this.defaultHost = defaultHost;
        this.supportsDeviceFlow = supportsDeviceFlow;
        this.nativeCliToken = nativeCliToken;
        this.nativeEnvVars = nativeEnvVars;
    }

    public String id()                  { return id; }
    public String displayName()         { return displayName; }
    public boolean supportsDeviceFlow() { return supportsDeviceFlow; }

    /** Default host, or empty when the user must pass {@code --host}. */
    public Optional<String> defaultHost() { return Optional.ofNullable(defaultHost); }

    /** Native token CLI argv ({@code gh auth token}), or empty if none. */
    public Optional<List<String>> nativeCliToken() {
        return Optional.ofNullable(nativeCliToken);
    }

    /** Ecosystem-native env vars to honour, in precedence order. */
    public List<String> nativeEnvVars() { return List.of(nativeEnvVars); }

    /** jk's own override variable: {@code JK_GITHUB_TOKEN}, etc. */
    public String jkEnvVar() {
        return "JK_" + name() + "_TOKEN";
    }

    /** Env var that overrides the OAuth-app client id: {@code JK_GITHUB_OAUTH_CLIENT_ID}, etc. */
    public String oauthClientIdEnvVar() {
        return "JK_" + name() + "_OAUTH_CLIENT_ID";
    }

    /**
     * jk's built-in public OAuth-app client id for this provider's <i>default
     * host</i> (the app jk registered on the public instance). Empty for
     * providers/instances jk hasn't registered an app for — those must supply
     * one via {@code JK_<PROVIDER>_OAUTH_CLIENT_ID} or {@code [forge.*]}
     * config. Not a secret; safe to ship.
     *
     * <p>Deliberately scoped to the default host only: github.com's client id
     * is meaningless to a self-hosted GitHub Enterprise instance, so callers
     * must not fall back to it for non-default hosts.
     */
    public Optional<String> defaultOAuthClientId() {
        return switch (this) {
            // Registered under github.com/jkbuild.
            case GITHUB                       -> Optional.of("Ov23liOYrWd84ZK2Eg2n");
            case GITLAB, GITEA, BITBUCKET     -> Optional.empty();
        };
    }

    // -- endpoints (RFC 8628 device authorization grant) -----------------

    /** Device-code endpoint for a concrete host. */
    public URI deviceCodeUri(String host) {
        String h = normalizeHost(host);
        return switch (this) {
            case GITHUB    -> URI.create("https://" + h + "/login/device/code");
            case GITLAB    -> URI.create("https://" + h + "/oauth/authorize_device");
            case GITEA     -> URI.create("https://" + h + "/login/oauth/authorize_device");
            case BITBUCKET -> throw new AuthException("Bitbucket does not support the device flow.");
        };
    }

    /** Token endpoint for a concrete host. */
    public URI tokenUri(String host) {
        String h = normalizeHost(host);
        return switch (this) {
            case GITHUB    -> URI.create("https://" + h + "/login/oauth/access_token");
            case GITLAB    -> URI.create("https://" + h + "/oauth/token");
            case GITEA     -> URI.create("https://" + h + "/login/oauth/access_token");
            case BITBUCKET -> throw new AuthException("Bitbucket does not support the device flow.");
        };
    }

    /** REST API base for a concrete host. */
    public URI apiBase(String host) {
        String h = normalizeHost(host);
        return switch (this) {
            // github.com → api.github.com; GHE → https://HOST/api/v3
            case GITHUB    -> h.equals("github.com")
                    ? URI.create("https://api.github.com")
                    : URI.create("https://" + h + "/api/v3");
            case GITLAB    -> URI.create("https://" + h + "/api/v4");
            case GITEA     -> URI.create("https://" + h + "/api/v1");
            case BITBUCKET -> URI.create("https://api.bitbucket.org/2.0");
        };
    }

    // -- lookup ----------------------------------------------------------

    /** Resolve a provider by its CLI id (case-insensitive); aliases included. */
    public static Optional<ForgeKind> fromId(String raw) {
        if (raw == null) return Optional.empty();
        String id = raw.toLowerCase(Locale.ROOT).strip();
        return switch (id) {
            case "github", "gh"                       -> Optional.of(GITHUB);
            case "gitlab", "glab"                     -> Optional.of(GITLAB);
            case "gitea", "forgejo", "codeberg"       -> Optional.of(GITEA);
            case "bitbucket", "bb"                    -> Optional.of(BITBUCKET);
            default                                   -> Optional.empty();
        };
    }

    /** Best-effort guess of the provider from a host name. */
    public static Optional<ForgeKind> inferFromHost(String host) {
        if (host == null) return Optional.empty();
        String h = normalizeHost(host);
        if (h.equals("github.com")) return Optional.of(GITHUB);
        if (h.equals("gitlab.com")) return Optional.of(GITLAB);
        if (h.equals("codeberg.org")) return Optional.of(GITEA);
        if (h.equals("bitbucket.org")) return Optional.of(BITBUCKET);
        return Optional.empty();
    }

    /** Strip any scheme/path/trailing slash so callers can pass a URL or a bare host. */
    static String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            throw new AuthException("A host is required for this provider.");
        }
        String h = host.strip();
        int scheme = h.indexOf("://");
        if (scheme >= 0) h = h.substring(scheme + 3);
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        return h;
    }
}
