// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * User-global policy for the engine's embedded HTTP server, parsed from the {@code [http]} table
 * of {@code ~/.jk/config.toml} ({@link JkDirs#userConfigFile()}). See {@code docs/http.md}.
 *
 * <p><strong>Enabled by default</strong> (loopback-only, token-gated mutations): a missing file or
 * missing table means the server runs with {@link #DEFAULTS}. Opting out is explicit — {@code
 * enabled = false} in the table, or {@code JK_HTTP_ENABLED=false} in the environment. The {@code
 * Optional} shape survives as the off signal: empty means the engine performs no HTTP
 * initialization at all. An <em>unreadable</em> (malformed) config file also disables: the file
 * may contain an {@code enabled = false} we can't read, and an explicit disable must fail closed —
 * never silently serve because of a syntax error.
 *
 * <p>Deliberately <strong>not project-overridable</strong>, same reasoning as {@link
 * JkEngineConfig}: which port this machine's engine listens on is machine/user policy.
 *
 * <pre>{@code
 * [http]                        # present or absent, the server is on unless disabled
 * enabled = true                # JK_HTTP_ENABLED                  false = no HTTP at all
 * host = "127.0.0.1"            # JK_HTTP_HOST                     non-loopback gates /api reads on the token
 * port = 8910                   # JK_HTTP_PORT                     0 = OS-assigned
 * max-concurrent-requests = 16  # JK_HTTP_MAX_CONCURRENT_REQUESTS  0 = container-aware core count
 * web-root = "state/web"        # JK_HTTP_WEB_ROOT                 absolute, or relative to ~/.jk
 * }</pre>
 *
 * <p>Read once when an engine starts — a running engine does not hot-reload this file; {@code jk
 * engine stop} followed by a fresh lazy-start picks up a change.
 */
public record JkHttpConfig(String host, int port, int maxConcurrentRequests, String webRoot) {

    public static final String DEFAULT_HOST = "127.0.0.1";

    public static final int DEFAULT_PORT = 8910;

    /**
     * Default admission cap on concurrently served requests (see {@code docs/http.md} — this is the
     * knob that replaced a physical thread-pool size, which virtual threads can't express). A
     * dashboard's initial burst (a handful of assets + a couple of API calls + one SSE stream) fits
     * comfortably; {@code 0} means "match the container-aware core count".
     */
    public static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 16;

    /** Relative to the resolved {@code ~/.jk} home — i.e. {@code ~/.jk/state/web} by default. */
    public static final String DEFAULT_WEB_ROOT = "state/web";

    public static final JkHttpConfig DEFAULTS =
            new JkHttpConfig(DEFAULT_HOST, DEFAULT_PORT, DEFAULT_MAX_CONCURRENT_REQUESTS, DEFAULT_WEB_ROOT);

    /**
     * The effective HTTP configuration for this machine: on by default with {@link #DEFAULTS},
     * customized by the user-global {@code ~/.jk/config.toml}'s {@code [http]} table when present,
     * overridden per key by {@code JK_HTTP_*} env vars (env &gt; user-config &gt; default). Empty —
     * feature off — only when explicitly disabled ({@code enabled = false} in the table, or {@code
     * JK_HTTP_ENABLED=false} in the environment) or when an existing config file is unreadable (a
     * disable must fail closed).
     */
    public static Optional<JkHttpConfig> resolve() {
        return resolve(JkDirs.userConfigFile(), System::getenv);
    }

    /** As {@link #resolve()} but against an explicit config file + env — for tests. */
    static Optional<JkHttpConfig> resolve(Path userConfig, Function<String, String> env) {
        Optional<Boolean> envEnabled = EnvValues.bool(env, "JK_HTTP_ENABLED");
        if (envEnabled.isPresent() && !envEnabled.get()) return Optional.empty();
        Optional<JkHttpConfig> file = fromToml(userConfig);
        // Env wins over a file disable (env > user-config); the file's other keys are gone with it,
        // so a forced re-enable serves on the defaults.
        if (file.isEmpty() && !envEnabled.orElse(false)) return Optional.empty();
        JkHttpConfig base = file.orElse(DEFAULTS);
        return Optional.of(new JkHttpConfig(
                EnvValues.string(env, "JK_HTTP_HOST").orElse(base.host),
                EnvValues.intValue(env, "JK_HTTP_PORT")
                        .filter(JkHttpConfig::validPort)
                        .orElse(base.port),
                EnvValues.intValue(env, "JK_HTTP_MAX_CONCURRENT_REQUESTS")
                        .filter(JkHttpConfig::validMaxConcurrentRequests)
                        .orElse(base.maxConcurrentRequests),
                EnvValues.string(env, "JK_HTTP_WEB_ROOT").orElse(base.webRoot)));
    }

    /**
     * The file-only view (no env): {@link #DEFAULTS} when the file or the {@code [http]} table is
     * missing (the server is on by default), the table's values with per-key defaults when present,
     * and empty — feature off — when the table says {@code enabled = false} <em>or the file exists
     * but doesn't parse</em> (it may contain a disable we can't read; fail closed). A present table
     * with a missing or out-of-range key falls back to that key's default — like every other config
     * view, values are advisory, never a build-breaking gate.
     */
    public static Optional<JkHttpConfig> fromToml(Path file) {
        if (!java.nio.file.Files.isRegularFile(file)) return Optional.of(DEFAULTS);
        Optional<TomlParseResult> parsed = TomlValues.parse(file);
        if (parsed.isEmpty()) return Optional.empty(); // exists but unreadable → fail closed
        TomlTable http = parsed.get().getTable("http");
        if (http == null) return Optional.of(DEFAULTS);
        if (!TomlValues.optBoolean(http, "enabled").orElse(true)) return Optional.empty();
        return Optional.of(new JkHttpConfig(
                TomlValues.optString(http, "host").orElse(DEFAULT_HOST),
                TomlValues.optInt(http, "port").filter(JkHttpConfig::validPort).orElse(DEFAULT_PORT),
                TomlValues.optInt(http, "max-concurrent-requests")
                        .filter(JkHttpConfig::validMaxConcurrentRequests)
                        .orElse(DEFAULT_MAX_CONCURRENT_REQUESTS),
                TomlValues.optString(http, "web-root").orElse(DEFAULT_WEB_ROOT)));
    }

    private static boolean validPort(int port) {
        return port >= 0 && port <= 65535; // 0 = OS-assigned at bind, recorded in <key>.http
    }

    private static boolean validMaxConcurrentRequests(int max) {
        return max >= 0; // 0 = match the container-aware core count
    }

    /** The admission-semaphore size: the configured cap, or the container-aware core count for 0. */
    public int effectiveMaxConcurrentRequests() {
        return maxConcurrentRequests > 0
                ? maxConcurrentRequests
                : Runtime.getRuntime().availableProcessors();
    }

    /** {@code web-root} resolved against the live {@link JkDirs#home()} when relative. */
    public Path webRootPath() {
        return webRootPath(JkDirs.home());
    }

    /** As {@link #webRootPath()} but against an explicit home dir — for tests. */
    public Path webRootPath(Path homeDir) {
        Path p = Path.of(webRoot);
        return (p.isAbsolute() ? p : homeDir.resolve(p)).normalize();
    }
}
