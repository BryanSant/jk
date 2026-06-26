// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Function;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Discovers and merges configuration into a single {@link JkConfig} per the
 * precedence documented on that record. CLI-flag values are applied <em>by the
 * caller after</em> {@link #load} returns — this class folds the file layers
 * (from {@link ConfigSources}) and the {@code JK_*} environment layer.
 *
 * <p>Toml shape (all sections optional):
 * <pre>
 *   [config]
 *   color = "auto" | "always" | "never"
 *   offline = false
 *   no-progress = false
 *   quiet = false
 *   verbose = false
 *   directory = "/some/path"   # rarely set here; mostly CLI-only
 * </pre>
 *
 * <p>File discovery and precedence live in {@link ConfigSources}: the
 * user-global {@code ~/.jk/config.toml} then the project {@code jk.toml} (or an
 * explicit {@code --config-file}). There is no {@code /etc/jk} system layer and
 * jk never reads {@code ~/.config}. The config layer extracts only the
 * {@code [config]} table; the rest of a {@code jk.toml} is consumed by
 * {@code JkBuildParser}.
 *
 * <p>{@code --no-config} short-circuits the file layers; env vars still apply.
 * {@code --config-file <path>} replaces the project layer exclusively (the
 * user-global layer still merges underneath).
 */
public final class JkConfigLoader {

    /** Env var → JkConfig setter. */
    private static final String ENV_COLOR = "JK_COLOR";

    private static final String ENV_OFFLINE = "JK_OFFLINE";
    private static final String ENV_RERUN = "JK_RERUN";
    private static final String ENV_REFRESH = "JK_REFRESH";
    private static final String ENV_NO_PROGRESS = "JK_NO_PROGRESS";
    private static final String ENV_QUIET = "JK_QUIET";
    private static final String ENV_VERBOSE = "JK_VERBOSE";
    private static final String ENV_NO_COLOR = "NO_COLOR";

    private JkConfigLoader() {}

    /**
     * Load and merge config layers. {@code startDir} is the search root for the
     * project {@code jk.toml}; pass the current working directory in normal use.
     *
     * <p>The returned config does NOT include CLI-flag values — the caller
     * should call {@link JkConfig#mergedWith(JkConfig)} with the CLI-derived
     * layer last to enforce the documented precedence.
     */
    public static JkConfig load(Path startDir, boolean noConfig, Optional<Path> explicitConfigFile) throws IOException {
        // File layers, lowest precedence first, then the env layer on top.
        JkConfig out = JkConfig.empty();
        for (Path layer :
                ConfigSources.discover(startDir, noConfig, explicitConfigFile).layers()) {
            out = out.mergedWith(loadTomlOrEmpty(layer));
        }
        // Env vars override files but are overridden by CLI flags (caller's job).
        out = out.mergedWith(loadFromEnv(System::getenv));
        return out;
    }

    /** Parse a TOML file's {@code [config]} table into a config layer; missing/malformed → empty. */
    static JkConfig loadTomlOrEmpty(Path path) throws IOException {
        // Degrade gracefully on missing or syntactically-broken files — system/user
        // configs may be experimental; problems fall back rather than fail the build.
        Optional<TomlParseResult> parsed = TomlValues.parse(path);
        if (parsed.isEmpty()) return JkConfig.empty();
        TomlTable config = parsed.get().getTable("config");
        if (config == null) return JkConfig.empty();
        return new JkConfig(
                TomlValues.optString(config, "color").flatMap(JkConfig.ColorChoice::parse),
                TomlValues.optBoolean(config, "offline"),
                TomlValues.optBoolean(config, "rerun"),
                TomlValues.optBoolean(config, "refresh"),
                TomlValues.optBoolean(config, "no-progress"),
                TomlValues.optBoolean(config, "quiet"),
                TomlValues.optBoolean(config, "verbose"),
                TomlValues.optString(config, "directory").map(Paths::get));
    }

    /** Build a config layer from environment variables. */
    static JkConfig loadFromEnv(Function<String, String> env) {
        // NO_COLOR (any non-empty value) → never; defers to JK_COLOR if also set.
        Optional<JkConfig.ColorChoice> color = EnvValues.string(env, ENV_COLOR)
                .flatMap(JkConfig.ColorChoice::parse)
                .or(() -> {
                    String noColor = env.apply(ENV_NO_COLOR);
                    return (noColor != null && !noColor.isEmpty())
                            ? Optional.of(JkConfig.ColorChoice.NEVER)
                            : Optional.empty();
                });
        return new JkConfig(
                color,
                EnvValues.bool(env, ENV_OFFLINE),
                EnvValues.bool(env, ENV_RERUN),
                EnvValues.bool(env, ENV_REFRESH),
                EnvValues.bool(env, ENV_NO_PROGRESS),
                EnvValues.bool(env, ENV_QUIET),
                EnvValues.bool(env, ENV_VERBOSE),
                Optional.empty()); // directory isn't env-var-driven
    }
}
