// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.forge;

import java.util.Optional;
import java.util.function.Function;

/**
 * Resolves a usable token for a {@code (provider, host)} pair, in priority
 * order. The chain is provider-neutral — each {@link ForgeKind} supplies the
 * variable names and native CLI to consult:
 *
 * <ol>
 *   <li>{@code JK_<KIND>_TOKEN} — jk's own override (CI / scripting wins);</li>
 *   <li>ecosystem-native env vars ({@code GH_TOKEN}, {@code GITLAB_TOKEN}, …);</li>
 *   <li>native CLI piggyback ({@code gh auth token});</li>
 *   <li>jk's stored per-host credential;</li>
 *   <li>(interactive only) the device flow — see {@link DeviceFlow}.</li>
 * </ol>
 *
 * <p>Env and CLI access are injected so the resolver is fully unit-testable
 * without touching the real environment or spawning processes.
 */
public final class ForgeAuth {

    private final TokenStore store;
    private final Function<String, String> env;
    private final CliTokenProbe cliProbe;

    public ForgeAuth() {
        this(new TokenStore(), System::getenv, CliTokenProbe.REAL);
    }

    /** Visible for tests — inject the store, env lookup, and CLI probe. */
    public ForgeAuth(TokenStore store, Function<String, String> env, CliTokenProbe cliProbe) {
        this.store = store;
        this.env = env;
        this.cliProbe = cliProbe;
    }

    /**
     * Steps 1–4: resolve a token without ever prompting. Returns empty when
     * nothing is configured and the caller should fall back to an
     * interactive login.
     */
    public Optional<ResolvedToken> resolveSilently(ForgeKind kind, String host) {
        // 1. jk's own override
        String jk = nonBlank(env.apply(kind.jkEnvVar()));
        if (jk != null) return Optional.of(new ResolvedToken(jk, TokenSource.JK_ENV));

        // 2. ecosystem-native env vars
        for (String var : kind.nativeEnvVars()) {
            String v = nonBlank(env.apply(var));
            if (v != null) return Optional.of(new ResolvedToken(v, TokenSource.NATIVE_ENV));
        }

        // 3. native CLI piggyback (gh/glab) — best-effort, never throws
        Optional<String> cli = kind.nativeCliToken().flatMap(cliProbe::token);
        if (cli.isPresent()) return cli.map(t -> new ResolvedToken(t, TokenSource.NATIVE_CLI));

        // 4. jk's stored credential for this host
        return store.read(resolveHost(kind, host)).map(t -> new ResolvedToken(t, TokenSource.STORE));
    }

    /** Persist a freshly-obtained token (device flow or pasted PAT). */
    public void store(ForgeKind kind, String host, String token) {
        store.write(resolveHost(kind, host), token);
    }

    /** Forget jk's stored credential for a host. Leaves env/CLI tokens alone. */
    public void logout(ForgeKind kind, String host) {
        store.clear(resolveHost(kind, host));
    }

    /**
     * Resolve the effective host: explicit value wins, else the provider's
     * default. Providers without a default (Gitea/Forgejo) require one.
     */
    public static String resolveHost(ForgeKind kind, String host) {
        String h = nonBlank(host);
        if (h != null) return ForgeKind.normalizeHost(h);
        return kind.defaultHost()
                .orElseThrow(() -> new AuthException(kind.displayName() + " has no default host — pass --host."));
    }

    private static String nonBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }
}
