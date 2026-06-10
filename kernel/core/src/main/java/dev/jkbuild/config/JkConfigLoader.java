// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Function;

/**
 * Discovers and merges configuration into a single {@link JkConfig} per
 * the precedence documented on that record. CLI-flag values are applied
 * <em>by the caller after</em> {@link #load} returns — this class handles
 * env vars, project-local {@code jk.toml}, user config, and system config.
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
 * <p>Project-local discovery: walks up from {@code startDir} until it
 * finds the first {@code jk.toml} (or hits the filesystem root). The
 * config layer extracts only the {@code [config]} table; the rest of the
 * file is consumed by {@link JkBuildParser}.
 *
 * <p>{@code --no-config} short-circuits all four file layers; env vars
 * still apply. {@code --config-file <path>} replaces the project layer
 * exclusively (user + system still merge underneath).
 */
public final class JkConfigLoader {

    /** Env var → JkConfig setter. */
    private static final String ENV_COLOR = "JK_COLOR";
    private static final String ENV_OFFLINE = "JK_OFFLINE";
    private static final String ENV_NO_CACHE = "JK_NO_CACHE";
    private static final String ENV_NO_PROGRESS = "JK_NO_PROGRESS";
    private static final String ENV_QUIET = "JK_QUIET";
    private static final String ENV_VERBOSE = "JK_VERBOSE";
    private static final String ENV_NO_COLOR = "NO_COLOR";

    /**
     * Discoverable file paths, in order (lowest precedence to highest within "files").
     * Package-private so sibling slice parsers (e.g. {@link ForgeAuthConfig}) reuse the
     * same locations and precedence rather than re-deriving them.
     */
    static final Path USER_CONFIG = Paths.get(
            System.getProperty("user.home", ""), ".config", "jk", "jk.toml");
    static final Path SYSTEM_CONFIG = Paths.get("/etc/jk/jk.toml");

    private JkConfigLoader() {}

    /**
     * Load and merge config layers. {@code startDir} is the search root
     * for project-local {@code jk.toml}; pass the current working
     * directory in normal use.
     *
     * <p>The returned config does NOT include CLI-flag values — the
     * caller should call {@link JkConfig#mergedWith(JkConfig)} with the
     * CLI-derived layer last to enforce the documented precedence.
     */
    public static JkConfig load(Path startDir, boolean noConfig, Optional<Path> explicitConfigFile)
            throws IOException {
        // Layers built from LOWEST precedence to HIGHEST, then folded with mergedWith.
        JkConfig out = JkConfig.empty();
        if (!noConfig) {
            out = out.mergedWith(loadTomlOrEmpty(SYSTEM_CONFIG));
            out = out.mergedWith(loadTomlOrEmpty(USER_CONFIG));
            if (explicitConfigFile.isPresent()) {
                // Explicit --config-file replaces only the project layer.
                out = out.mergedWith(loadTomlOrEmpty(explicitConfigFile.get()));
            } else {
                Path projectConfig = findProjectConfig(startDir);
                if (projectConfig != null) {
                    out = out.mergedWith(loadTomlOrEmpty(projectConfig));
                }
            }
        }
        // Env vars override files but are overridden by CLI flags (caller's job).
        out = out.mergedWith(loadFromEnv(System::getenv));
        return out;
    }

    /** Search {@code startDir} and ancestors for a {@code jk.toml}. */
    static Path findProjectConfig(Path startDir) {
        Path here = startDir == null ? null : startDir.toAbsolutePath().normalize();
        while (here != null) {
            Path candidate = here.resolve("jk.toml");
            if (Files.isRegularFile(candidate)) return candidate;
            here = here.getParent();
        }
        return null;
    }

    /** Parse a {@code jk.toml}'s {@code [config]} table into a config layer; missing file → empty. */
    static JkConfig loadTomlOrEmpty(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) return JkConfig.empty();
        TomlParseResult toml = Toml.parse(path);
        // Don't fail loud on syntax errors in foreign files — system/user configs may be
        // experimental; report problems but degrade gracefully.
        if (toml.hasErrors()) return JkConfig.empty();
        TomlTable config = toml.getTable("config");
        if (config == null) return JkConfig.empty();
        return new JkConfig(
                stringFrom(config, "color").flatMap(JkConfig.ColorChoice::parse),
                booleanFrom(config, "offline"),
                booleanFrom(config, "no-cache"),
                booleanFrom(config, "no-progress"),
                booleanFrom(config, "quiet"),
                booleanFrom(config, "verbose"),
                stringFrom(config, "directory").map(Paths::get));
    }

    /** Build a config layer from environment variables. */
    static JkConfig loadFromEnv(Function<String, String> env) {
        // NO_COLOR (any non-empty value) → never; defers to JK_COLOR if also set.
        Optional<JkConfig.ColorChoice> color = stringEnv(env, ENV_COLOR)
                .flatMap(JkConfig.ColorChoice::parse)
                .or(() -> {
                    String noColor = env.apply(ENV_NO_COLOR);
                    return (noColor != null && !noColor.isEmpty())
                            ? Optional.of(JkConfig.ColorChoice.NEVER)
                            : Optional.empty();
                });
        return new JkConfig(
                color,
                booleanEnv(env, ENV_OFFLINE),
                booleanEnv(env, ENV_NO_CACHE),
                booleanEnv(env, ENV_NO_PROGRESS),
                booleanEnv(env, ENV_QUIET),
                booleanEnv(env, ENV_VERBOSE),
                Optional.empty()); // directory isn't env-var-driven
    }

    // ------ TOML / env value coercion -------------------------------------

    private static Optional<String> stringFrom(TomlTable table, String key) {
        Object v = table.get(key);
        return (v instanceof String s && !s.isBlank()) ? Optional.of(s) : Optional.empty();
    }

    private static Optional<Boolean> booleanFrom(TomlTable table, String key) {
        Object v = table.get(key);
        return (v instanceof Boolean b) ? Optional.of(b) : Optional.empty();
    }

    private static Optional<String> stringEnv(Function<String, String> env, String name) {
        String v = env.apply(name);
        return (v != null && !v.isBlank()) ? Optional.of(v) : Optional.empty();
    }

    private static Optional<Boolean> booleanEnv(Function<String, String> env, String name) {
        String v = env.apply(name);
        if (v == null) return Optional.empty();
        String t = v.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.isEmpty()) return Optional.empty();
        return switch (t) {
            case "1", "true", "yes", "on" -> Optional.of(Boolean.TRUE);
            case "0", "false", "no", "off" -> Optional.of(Boolean.FALSE);
            default -> Optional.empty();
        };
    }
}
