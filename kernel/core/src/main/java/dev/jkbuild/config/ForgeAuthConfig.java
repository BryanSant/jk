// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Forge OAuth-app client IDs sourced from {@code jk.toml} config files. This lets a self-hosted
 * GitHub Enterprise / GitLab / Gitea instance supply its own registered OAuth app, since the public
 * {@code client_id} differs per deployment. A focused slice parser (like {@link JkCacheConfig})
 * rather than a field on the scalar {@link JkConfig} record — client IDs are a host-keyed map, not
 * a CLI-wide toggle.
 *
 * <p>TOML shape (all optional), under a top-level {@code [forge]} table:
 *
 * <pre>
 *   # default client id per provider
 *   [forge.github]
 *   client-id = "Iv1.0123456789abcdef"
 *
 *   [forge.gitea]
 *   client-id = "0e9b…"
 *
 *   # per-host override (wins over the provider default); use for
 *   # self-hosted instances with their own registered app
 *   [[forge.host]]
 *   name = "ghe.corp.example"
 *   client-id = "Iv1.fedcba9876543210"
 * </pre>
 *
 * <p>Precedence mirrors {@link JkConfigLoader} via the shared {@link ConfigSources}: user-global
 * {@code ~/.jk/config.toml} &lt; project {@code jk.toml} (or explicit {@code --config-file}). The
 * environment variable {@code JK_<PROVIDER>_OAUTH_CLIENT_ID} sits above all of these and is applied
 * by the caller, not here.
 */
public final class ForgeAuthConfig {

    private final Map<String, String> byProvider; // providerId (lowercased) → client id
    private final Map<String, String> byHost; // host (lowercased)       → client id

    private ForgeAuthConfig(Map<String, String> byProvider, Map<String, String> byHost) {
        this.byProvider = byProvider;
        this.byHost = byHost;
    }

    /** Empty config — no client IDs configured. */
    public static ForgeAuthConfig empty() {
        return new ForgeAuthConfig(Map.of(), Map.of());
    }

    public boolean isEmpty() {
        return byProvider.isEmpty() && byHost.isEmpty();
    }

    /**
     * Resolve the client ID for a provider on a host: a per-host override wins over the provider
     * default. {@code providerId} is the {@code ForgeKind.id()} string (github / gitlab / gitea /
     * bitbucket); {@code host} may be null when only the provider default is wanted.
     */
    public Optional<String> oauthClientId(String providerId, String host) {
        if (host != null) {
            String byHostId = byHost.get(host.toLowerCase(Locale.ROOT).strip());
            if (byHostId != null) return Optional.of(byHostId);
        }
        if (providerId != null) {
            String byProviderId =
                    byProvider.get(providerId.toLowerCase(Locale.ROOT).strip());
            if (byProviderId != null) return Optional.of(byProviderId);
        }
        return Optional.empty();
    }

    /** Lay {@code over}'s entries on top of this config's; {@code over} wins on conflicts. */
    public ForgeAuthConfig mergedWith(ForgeAuthConfig over) {
        Map<String, String> provider = new HashMap<>(this.byProvider);
        provider.putAll(over.byProvider);
        Map<String, String> host = new HashMap<>(this.byHost);
        host.putAll(over.byHost);
        return new ForgeAuthConfig(provider, host);
    }

    /**
     * Discover and merge client-id config across the standard layers, matching {@link
     * JkConfigLoader#load}'s precedence via the shared {@link ConfigSources}: user-global {@code
     * ~/.jk/config.toml} &lt; project {@code jk.toml} (or explicit {@code --config-file}). {@code
     * noConfig} short-circuits all file layers.
     */
    public static ForgeAuthConfig discover(Path startDir, boolean noConfig, Optional<Path> explicitConfigFile) {
        ForgeAuthConfig out = empty();
        for (Path layer :
                ConfigSources.discover(startDir, noConfig, explicitConfigFile).layers()) {
            out = out.mergedWith(loadFrom(layer));
        }
        return out;
    }

    /** Parse the {@code [forge]} table from a single TOML file; missing/invalid → empty. */
    public static ForgeAuthConfig loadFrom(Path path) {
        // Degrade gracefully on missing/foreign/malformed files, like JkConfigLoader.
        Optional<TomlParseResult> parsed = TomlValues.parse(path);
        if (parsed.isEmpty()) return empty();
        TomlTable forge = parsed.get().getTable("forge");
        if (forge == null) return empty();

        Map<String, String> byProvider = new HashMap<>();
        for (String key : forge.keySet()) {
            if (key.equals("host")) continue; // reserved for the per-host array
            Object v = forge.get(key);
            if (v instanceof TomlTable provider) {
                String cid = provider.getString("client-id");
                if (cid != null && !cid.isBlank()) {
                    byProvider.put(key.toLowerCase(Locale.ROOT), cid.strip());
                }
            }
        }

        Map<String, String> byHost = new HashMap<>();
        TomlArray hosts = forge.getArray("host");
        if (hosts != null) {
            for (int i = 0; i < hosts.size(); i++) {
                if (!(hosts.get(i) instanceof TomlTable h)) continue;
                String name = h.getString("name");
                String cid = h.getString("client-id");
                if (name != null && !name.isBlank() && cid != null && !cid.isBlank()) {
                    byHost.put(name.toLowerCase(Locale.ROOT).strip(), cid.strip());
                }
            }
        }

        return new ForgeAuthConfig(byProvider, byHost);
    }
}
