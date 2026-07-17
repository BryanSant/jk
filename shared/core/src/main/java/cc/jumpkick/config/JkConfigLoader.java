// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Function;

/**
 * Discovers and merges configuration into a single {@link JkConfig} per the precedence documented
 * on that record. CLI-flag values are applied <em>by the caller after</em> {@link #load} returns —
 * this class folds the file layers (from {@link ConfigSources}) and the {@code JK_*} environment
 * layer.
 *
 * <p>Toml shape (all sections optional):
 *
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
 * <p>File discovery and precedence live in {@link ConfigSources}: the user-global {@code
 * ~/.jk/config.toml} then the project {@code jk.toml} (or an explicit {@code --config-file}). There
 * is no {@code /etc/jk} system layer and jk never reads {@code ~/.config}. The config layer
 * extracts only the {@code [config]} table; the rest of a {@code jk.toml} is consumed by {@code
 * JkBuildParser}.
 *
 * <p>{@code --no-config} short-circuits the file layers; env vars still apply. {@code --config-file
 * <path>} replaces the project layer exclusively (the user-global layer still merges underneath).
 */
public final class JkConfigLoader {

    /** Env var → JkConfig setter. */
    private static final String ENV_COLOR = "JK_COLOR";

    private static final String ENV_OFFLINE = "JK_OFFLINE";
    private static final String ENV_FORCE = "JK_FORCE";
    private static final String ENV_NO_PROGRESS = "JK_NO_PROGRESS";
    private static final String ENV_QUIET = "JK_QUIET";
    private static final String ENV_VERBOSE = "JK_VERBOSE";
    private static final String ENV_NO_COLOR = "NO_COLOR";

    private JkConfigLoader() {}

    /**
     * Load and merge config layers. {@code startDir} is the search root for the project {@code
     * jk.toml}; pass the current working directory in normal use.
     *
     * <p>The returned config does NOT include CLI-flag values — the caller should call {@link
     * JkConfig#mergedWith(JkConfig)} with the CLI-derived layer last to enforce the documented
     * precedence.
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
        // TomlScan, not tomlj: this runs at the top of EVERY client invocation and reads
        // nine flat [config] scalars from files jk owns (thin-client plan Milestone C).
        TomlScan scan = TomlScan.scan(
                path,
                "config.color",
                "config.offline",
                "config.rerun",
                "config.refresh",
                "config.no-progress",
                "config.quiet",
                "config.verbose",
                "config.directory",
                "config.force");
        return new JkConfig(
                Optional.ofNullable(scan.get("config.color")).flatMap(JkConfig.ColorChoice::parse),
                scanBool(scan, "config.offline"),
                Optional.empty(), // rebuild is a per-invocation CLI flag, not a config-file key
                scanBool(scan, "config.no-progress"),
                scanBool(scan, "config.quiet"),
                scanBool(scan, "config.verbose"),
                Optional.ofNullable(scan.get("config.directory")).map(Paths::get),
                scanBool(scan, "config.force"),
                Optional.empty()); // no-ansi is CLI-only, not config-file settable
    }

    /** A scanned TOML boolean: strictly {@code true}/{@code false}, anything else = absent. */
    private static Optional<Boolean> scanBool(TomlScan scan, String key) {
        String v = scan.get(key);
        if ("true".equalsIgnoreCase(v)) return Optional.of(true);
        if ("false".equalsIgnoreCase(v)) return Optional.of(false);
        return Optional.empty();
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
        Optional<Boolean> force = EnvValues.bool(env, ENV_FORCE);
        return new JkConfig(
                color,
                EnvValues.bool(env, ENV_OFFLINE),
                Optional.empty(), // rebuild is a per-invocation CLI flag, not env-driven
                EnvValues.bool(env, ENV_NO_PROGRESS),
                EnvValues.bool(env, ENV_QUIET),
                EnvValues.bool(env, ENV_VERBOSE),
                Optional.empty(), // directory isn't env-var-driven
                force,
                Optional.empty()); // no-ansi is CLI-only
    }
}
