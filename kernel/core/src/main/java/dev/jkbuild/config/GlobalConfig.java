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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        if (ActiveConfig.get().noAnsiOr(false)) return false;
        var choice = ActiveConfig.get().colorOr(JkConfig.ColorChoice.AUTO);
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

    /** Read a boolean from the {@code [global]} table, falling back leniently. */
    private static boolean booleanFromGlobal(Path file, String key, boolean fallback) {
        return TomlValues.parse(file)
                .flatMap(toml -> TomlValues.optBoolean(toml.getTable("global"), key))
                .orElse(fallback);
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
        return TomlValues.parse(configFile)
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
                    credential = parseRepoCredential(t);
                    objectStore = parseObjectStore(t);
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

    private static Optional<RepoCredential> parseRepoCredential(TomlTable t) {
        String token = interpolateEnv(t.getString("token"));
        String username = interpolateEnv(t.getString("username"));
        String password = interpolateEnv(t.getString("password"));
        if (token != null && !token.isBlank()) {
            return Optional.of(new RepoCredential.Bearer(token));
        }
        if (username != null && !username.isBlank()) {
            return Optional.of(new RepoCredential.Basic(username, password == null ? "" : password));
        }
        return Optional.empty();
    }

    private static Optional<ObjectStoreConfig> parseObjectStore(TomlTable t) {
        ObjectStoreConfig cfg = new ObjectStoreConfig(
                blankToNull(interpolateEnv(t.getString("region"))),
                blankToNull(interpolateEnv(t.getString("endpoint"))),
                blankToNull(interpolateEnv(t.getString("access-key"))),
                blankToNull(interpolateEnv(t.getString("secret-key"))),
                blankToNull(interpolateEnv(t.getString("session-token"))));
        return cfg.isEmpty() ? Optional.empty() : Optional.of(cfg);
    }

    private static final Pattern ENV_REF = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /** Expand {@code ${VAR}} against the environment; missing var → returns the original text (lenient). */
    private static String interpolateEnv(String raw) {
        if (raw == null) return null;
        Matcher m = ENV_REF.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String val = System.getenv(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(val != null ? val : m.group(0)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
