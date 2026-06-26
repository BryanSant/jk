// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.util.JkDirs;
import java.nio.file.Path;

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
        return nerdfont(JkDirs.userConfigFile(), System.getenv("JK_NERDFONT"));
    }

    /** As {@link #nerdfont()} but against an explicit config file — for tests. */
    static boolean nerdfont(Path configFile) {
        return nerdfont(configFile, System.getenv("JK_NERDFONT"));
    }

    /** As {@link #nerdfont(Path)} but with an explicit env value — for tests. */
    static boolean nerdfont(Path configFile, String envValue) {
        return EnvValues.parseBool(envValue).orElseGet(() -> booleanFromGlobal(configFile, "nerdfont", true));
    }

    /** Read a boolean from the {@code [global]} table, falling back leniently. */
    private static boolean booleanFromGlobal(Path file, String key, boolean fallback) {
        return TomlValues.parse(file)
                .flatMap(toml -> TomlValues.optBoolean(toml.getTable("global"), key))
                .orElse(fallback);
    }
}
