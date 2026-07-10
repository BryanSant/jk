// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.util.JkDirs;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * User-global policy for the engine's optional embedded HTTP server, parsed from the {@code [http]}
 * table of {@code ~/.jk/config.toml} ({@link JkDirs#userConfigFile()}). See {@code docs/http.md}.
 *
 * <p><strong>The presence of the table is the enable switch</strong>: a missing table (or missing
 * file) means the feature is off and the engine performs no HTTP initialization at all — hence the
 * {@code Optional} shape, unlike {@link JkEngineConfig}'s always-present defaults. An enabled table
 * also means the engine never self-terminates (see {@code docs/http.md}); the dashboard it serves
 * must not vanish out from under an open browser tab.
 *
 * <p>Deliberately <strong>not project-overridable</strong>, same reasoning as {@link
 * JkEngineConfig}: which port this machine's engine listens on is machine/user policy.
 *
 * <pre>{@code
 * [http]                        # the presence of this table enables the server
 * host = "127.0.0.1"            # JK_HTTP_HOST                     non-loopback gates /api reads on the token
 * port = 8910                   # JK_HTTP_PORT                     0 = OS-assigned
 * max-concurrent-requests = 16  # JK_HTTP_MAX_CONCURRENT_REQUESTS  0 = container-aware core count
 * www-root = "state/www"        # JK_HTTP_WWW_ROOT                 absolute, or relative to ~/.jk
 * }</pre>
 *
 * <p>Env overrides apply <em>only when the table is present</em> — an env var alone never enables
 * the feature, so nothing can silently expose a port the config file didn't ask for.
 *
 * <p>Read once when an engine starts — a running engine does not hot-reload this file; {@code jk
 * engine stop} followed by a fresh lazy-start picks up a change.
 */
public record JkHttpConfig(String host, int port, int maxConcurrentRequests, String wwwRoot) {

    public static final String DEFAULT_HOST = "127.0.0.1";

    public static final int DEFAULT_PORT = 8910;

    /**
     * Default admission cap on concurrently served requests (see {@code docs/http.md} — this is the
     * knob that replaced a physical thread-pool size, which virtual threads can't express). A
     * dashboard's initial burst (a handful of assets + a couple of API calls + one SSE stream) fits
     * comfortably; {@code 0} means "match the container-aware core count".
     */
    public static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 16;

    /** Relative to the resolved {@code ~/.jk} home — i.e. {@code ~/.jk/state/www} by default. */
    public static final String DEFAULT_WWW_ROOT = "state/www";

    public static final JkHttpConfig DEFAULTS =
            new JkHttpConfig(DEFAULT_HOST, DEFAULT_PORT, DEFAULT_MAX_CONCURRENT_REQUESTS, DEFAULT_WWW_ROOT);

    /**
     * The effective HTTP configuration for this machine: empty (feature off) unless the user-global
     * {@code ~/.jk/config.toml} has an {@code [http]} table; otherwise that table's values with
     * per-key defaults, overridden by {@code JK_HTTP_*} env vars (env &gt; user-config &gt; default).
     */
    public static Optional<JkHttpConfig> resolve() {
        return resolve(JkDirs.userConfigFile(), System::getenv);
    }

    /** As {@link #resolve()} but against an explicit config file + env — for tests. */
    static Optional<JkHttpConfig> resolve(Path userConfig, Function<String, String> env) {
        return fromToml(userConfig).map(base -> new JkHttpConfig(
                EnvValues.string(env, "JK_HTTP_HOST").orElse(base.host),
                EnvValues.intValue(env, "JK_HTTP_PORT")
                        .filter(JkHttpConfig::validPort)
                        .orElse(base.port),
                EnvValues.intValue(env, "JK_HTTP_MAX_CONCURRENT_REQUESTS")
                        .filter(JkHttpConfig::validMaxConcurrentRequests)
                        .orElse(base.maxConcurrentRequests),
                EnvValues.string(env, "JK_HTTP_WWW_ROOT").orElse(base.wwwRoot)));
    }

    /**
     * Load from a TOML file's {@code [http]} table. Missing file, missing table, or malformed file →
     * empty: the feature is off. A present table with a missing or out-of-range key falls back to
     * that key's default — like every other config view, values are advisory, never a
     * build-breaking gate (and an unusable value must not silently disable a server the user asked
     * for; the per-key default keeps it up on a sane setting instead).
     */
    public static Optional<JkHttpConfig> fromToml(Path file) {
        Optional<TomlParseResult> parsed = TomlValues.parse(file);
        if (parsed.isEmpty()) return Optional.empty();
        TomlTable http = parsed.get().getTable("http");
        if (http == null) return Optional.empty();
        return Optional.of(new JkHttpConfig(
                TomlValues.optString(http, "host").orElse(DEFAULT_HOST),
                TomlValues.optInt(http, "port")
                        .filter(JkHttpConfig::validPort)
                        .orElse(DEFAULT_PORT),
                TomlValues.optInt(http, "max-concurrent-requests")
                        .filter(JkHttpConfig::validMaxConcurrentRequests)
                        .orElse(DEFAULT_MAX_CONCURRENT_REQUESTS),
                TomlValues.optString(http, "www-root").orElse(DEFAULT_WWW_ROOT)));
    }

    private static boolean validPort(int port) {
        return port >= 0 && port <= 65535; // 0 = OS-assigned at bind, recorded in <key>.http
    }

    private static boolean validMaxConcurrentRequests(int max) {
        return max >= 0; // 0 = match the container-aware core count
    }

    /** The admission-semaphore size: the configured cap, or the container-aware core count for 0. */
    public int effectiveMaxConcurrentRequests() {
        return maxConcurrentRequests > 0 ? maxConcurrentRequests : Runtime.getRuntime().availableProcessors();
    }

    /** {@code www-root} resolved against the live {@link JkDirs#home()} when relative. */
    public Path wwwRootPath() {
        return wwwRootPath(JkDirs.home());
    }

    /** As {@link #wwwRootPath()} but against an explicit home dir — for tests. */
    public Path wwwRootPath(Path homeDir) {
        Path p = Path.of(wwwRoot);
        return (p.isAbsolute() ? p : homeDir.resolve(p)).normalize();
    }
}
