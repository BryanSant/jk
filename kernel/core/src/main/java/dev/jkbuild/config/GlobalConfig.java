// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.credential.RepoCredential;
import dev.jkbuild.model.ObjectStoreConfig;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.util.JkDirs;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * User-global UI/runtime preferences from the {@code [global]} table of {@code ~/.jk/config.toml}
 * ({@link JkDirs#userConfigFile()}). Reading is lenient: a missing file, missing table, or wrong
 * type falls back to the documented default — these settings only tune presentation, never
 * correctness.
 *
 * <pre>{@code
 * [global]
 * nerdfont = true   # use Nerd Font powerline glyphs where jk offers them
 * }</pre>
 *
 * <p>Like {@code [cache]}, {@code [global]} is <strong>not project-overridable</strong>: a project
 * {@code jk.toml}'s {@code [global]} table is ignored — these describe <em>your</em>
 * terminal/machine, not a checked-out project. Each key <em>can</em> be overridden by its {@code
 * JK_*} environment variable (env &gt; user-config &gt; default), e.g. {@code JK_NERDFONT} for
 * {@code nerdfont}, which keeps jk container- and CI-friendly.
 *
 * <p>Built on the shared config SPI: values are read with {@link TomlValues} and env overrides with
 * {@link EnvValues}, so coercion matches every other config view. See {@code package-info} for the
 * full model.
 *
 * <h2>Global repositories ({@code [repositories]})</h2>
 *
 * <p>Maven repositories can be declared globally in {@code ~/.jk/config.toml} so that every project
 * on the machine can resolve from them without repeating the declaration in each {@code jk.toml}.
 * The format mirrors the per-project {@code [repositories]} block:
 *
 * <pre>{@code
 * [repositories]
 * internal = "https://nexus.example.com/repository/maven-public/"
 * google    = { url = "https://maven.google.com/", token = "${NEXUS_TOKEN}" }
 * }</pre>
 *
 * <p>Project-level {@code [repositories]} always take precedence: if a project declares a repo
 * with the same name as a global repo, the project's declaration wins. Global repos fill in repos
 * not declared by the project. Maven Central is the implicit final fallback when neither the
 * project nor the global config declares any repos.
 *
 * <p>Values support {@code ${ENV}} interpolation so credentials need not be committed literally.
 */
public final class GlobalConfig {

    private GlobalConfig() {}

    /**
     * {@code [global].nerdfont} — whether the user's terminal font carries Nerd Font / powerline
     * glyphs, so jk may use them (e.g. rounded pill caps) and fall back to plain Unicode otherwise.
     * Default {@code true}; opt out with {@code nerdfont = false} in {@code ~/.jk/config.toml} or
     * {@code JK_NERDFONT=false} in the environment.
     *
     * <p>Resolution order: {@code JK_NERDFONT} env var → {@code [global].nerdfont} in the config file
     * → {@code true}. The env var accepts the jk-wide boolean truth set ({@code 1/true/yes/on},
     * {@code 0/false/no/off}); see {@link EnvValues}.
     */
    public static boolean nerdfont() {
        return nerdfont(JkDirs.userConfigFile(), System.getenv("JK_NERDFONT"), colorActivelyEnabled());
    }

    /**
     * True when color output is currently enabled — same logic as {@code Theme.colorEnabled()} in
     * the CLI layer, duplicated here so {@code kernel/core} can apply it without a circular dep.
     */
    static boolean colorActivelyEnabled() {
        // No-ANSI triggers: --no-ansi flag, TERM=dumb, CI=true/1.
        if (dev.jkbuild.config.SessionContext.current().config().noAnsiOr(false)) return false;
        if ("dumb".equals(System.getenv("TERM"))) return false;
        String ci = System.getenv("CI");
        if ("true".equalsIgnoreCase(ci) || "1".equals(ci)) return false;
        var choice = dev.jkbuild.config.SessionContext.current().config().colorOr(JkConfig.ColorChoice.AUTO);
        return switch (choice) {
            case ALWAYS -> true;
            case NEVER  -> false;
            case AUTO   -> {
                String nc = System.getenv("NO_COLOR");
                yield nc == null || nc.isEmpty();
            }
        };
    }

    /** As {@link #nerdfont()} but against an explicit config file — for tests. */
    static boolean nerdfont(Path configFile) {
        return nerdfont(configFile, System.getenv("JK_NERDFONT"), colorActivelyEnabled());
    }

    /** As {@link #nerdfont(Path)} but with an explicit env value — bypasses color check for tests. */
    static boolean nerdfont(Path configFile, String envValue) {
        return EnvValues.parseBool(envValue).orElseGet(() -> booleanFromGlobal(configFile, "nerdfont", true));
    }

    /** Full testable overload: config file + env value + explicit color-enabled flag. */
    static boolean nerdfont(Path configFile, String envValue, boolean colorEnabled) {
        if (!colorEnabled) return false;
        return EnvValues.parseBool(envValue).orElseGet(() -> booleanFromGlobal(configFile, "nerdfont", true));
    }

    /**
     * Read a boolean from the {@code [global]} table, falling back leniently. Uses {@link
     * TomlScan}, not tomlj: this is the CLIENT-reachable half of GlobalConfig (nerdfont fires
     * 20+ times per command; {@code repositories()} below is engine-only via RepoGroupBuilder),
     * and the thin client must not reach a TOML parser for a one-key read of a file we own.
     * Memoized per (path, size, mtime) like the full parse used to be.
     */
    private static boolean booleanFromGlobal(Path file, String key, boolean fallback) {
        if (file == null) return fallback;
        String cacheKey;
        try {
            if (!java.nio.file.Files.exists(file)) return fallback;
            var attrs = java.nio.file.Files.readAttributes(
                    file, java.nio.file.attribute.BasicFileAttributes.class);
            cacheKey = file + "|" + key + "|" + attrs.size() + "|" + attrs.lastModifiedTime().toMillis();
        } catch (java.io.IOException e) {
            return fallback;
        }
        String value = SCAN_CACHE.computeIfAbsent(
                cacheKey, k -> Optional.ofNullable(TomlScan.scan(file, "global." + key).get("global." + key)))
                .orElse(null);
        if (value == null) return fallback;
        return "true".equalsIgnoreCase(value) ? true : "false".equalsIgnoreCase(value) ? false : fallback;
    }

    /**
     * {@code [toolchain].jdk} — the JDK that hosts the jk build engine, pinned by vendor+major (e.g.
     * {@code "temurin-25"}). This is deliberately separate from a project's {@code [project].jdk}:
     * one engine serves many workspaces, and the engine's AOT cache is only valid for one JDK
     * identity, so the engine JVM is pinned independently. Empty when unset — the caller then
     * defaults to the LTS Temurin at the engine's floor release.
     *
     * <p>Resolution order: {@code JK_ENGINE_JDK} env var → {@code [toolchain].jdk} → empty. Read via
     * {@link TomlScan} (client-reachable, no tomlj) and memoized like {@link #booleanFromGlobal}.
     */
    public static Optional<String> engineJdkPin() {
        return engineJdkPin(JkDirs.userConfigFile(), System.getenv("JK_ENGINE_JDK"));
    }

    /** As {@link #engineJdkPin()} but against an explicit config file + env value — for tests. */
    static Optional<String> engineJdkPin(Path file, String envValue) {
        if (envValue != null && !envValue.isBlank()) return Optional.of(envValue.trim());
        return stringFromGlobal(file, "toolchain", "jdk");
    }

    /** Read a single string value from an arbitrary {@code [table].key}, leniently, via TomlScan. */
    private static Optional<String> stringFromGlobal(Path file, String table, String key) {
        if (file == null) return Optional.empty();
        String dotted = table + "." + key;
        String cacheKey;
        try {
            if (!java.nio.file.Files.exists(file)) return Optional.empty();
            var attrs = java.nio.file.Files.readAttributes(
                    file, java.nio.file.attribute.BasicFileAttributes.class);
            cacheKey = file + "|" + dotted + "|" + attrs.size() + "|" + attrs.lastModifiedTime().toMillis();
        } catch (java.io.IOException e) {
            return Optional.empty();
        }
        return SCAN_CACHE.computeIfAbsent(cacheKey, k -> Optional.ofNullable(TomlScan.scan(file, dotted).get(dotted)))
                .map(String::trim)
                .filter(s -> !s.isEmpty());
    }

    private static final ConcurrentHashMap<String, Optional<String>> SCAN_CACHE = new ConcurrentHashMap<>();

    // ~/.jk/config.toml is process-stable but was re-parsed on every nerdfont()/repositories() call
    // (nerdfont alone fires 20+ times per invocation, several within one command). Memoize the parse
    // per (path, size, mtime) — mirrors JkBuildParser/LockfileReader — so the file is read at most
    // once per (unchanged) invocation.
    private static final ConcurrentHashMap<String, Optional<TomlParseResult>> CONFIG_CACHE = new ConcurrentHashMap<>();

    private static Optional<TomlParseResult> parseConfig(Path file) {
        if (file == null) return Optional.empty();
        String key;
        try {
            if (!java.nio.file.Files.exists(file)) return Optional.empty();
            var attrs = java.nio.file.Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
            key = file.toAbsolutePath() + "|" + attrs.size() + "|"
                    + attrs.lastModifiedTime().toMillis();
        } catch (java.io.IOException e) {
            return TomlValues.parse(file); // uncached fallback on stat failure
        }
        return CONFIG_CACHE.computeIfAbsent(key, k -> TomlValues.parse(file));
    }

    /** Clear the memoized config parse. For tests that rewrite {@code ~/.jk/config.toml} in one JVM. */
    static void clearCache() {
        CONFIG_CACHE.clear();
    }

    // -------------------------------------------------------------------------
    // Repositories
    // -------------------------------------------------------------------------

    /**
     * Repositories declared in the {@code [repositories]} table of {@code ~/.jk/config.toml}.
     * Returns an empty list when the file is absent, the table is missing, or any entry is
     * malformed (lenient — global config must never fail a build).
     */
    public static List<RepositorySpec> repositories() {
        return repositories(JkDirs.userConfigFile());
    }

    /** As {@link #repositories()} but against an explicit config file — for tests. */
    static List<RepositorySpec> repositories(Path configFile) {
        return parseConfig(configFile)
                .map(toml -> parseRepositories(toml.getTable("repositories")))
                .orElse(List.of());
    }

    private static List<RepositorySpec> parseRepositories(TomlTable repos) {
        if (repos == null) return List.of();
        List<RepositorySpec> result = new ArrayList<>(repos.size());
        for (String name : repos.keySet()) {
            Object value = repos.get(name);
            String url;
            Optional<RepoCredential> credential = Optional.empty();
            Optional<ObjectStoreConfig> objectStore = Optional.empty();
            try {
                if (value instanceof String s) {
                    url = s;
                } else if (value instanceof TomlTable t) {
                    String u = t.getString("url");
                    if (u == null) continue; // malformed — skip leniently
                    url = u;
                    credential = RepositoryToml.credential(t, LENIENT_INTERP);
                    objectStore = RepositoryToml.objectStore(t, LENIENT_INTERP);
                } else {
                    continue; // unexpected type — skip leniently
                }
                result.add(new RepositorySpec(name, URI.create(url), credential, objectStore));
            } catch (RuntimeException ignored) {
                // malformed URL or env var — skip this entry leniently
            }
        }
        return result;
    }

    /**
     * Global-layer {@code ${ENV}} interpolation: lenient — an unset variable is left as the literal
     * {@code ${VAR}} text (global config must never fail a build). Field parsing lives in {@link
     * RepositoryToml}.
     */
    private static final java.util.function.UnaryOperator<String> LENIENT_INTERP = raw -> RepositoryToml.interpolate(
            raw, var -> {
                String v = System.getenv(var);
                return v != null ? v : "${" + var + "}";
            });
}
