// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.util.JkDirs;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * User-global UI/runtime preferences from the {@code [global]} table of
 * {@code ~/.jk/config.toml} ({@link JkDirs#userConfigFile()}). Reading is
 * lenient: a missing file, missing table, or wrong type falls back to the
 * documented default — these settings only tune presentation, never
 * correctness.
 *
 * <pre>{@code
 *   [global]
 *   nerdfont = true   # use Nerd Font powerline glyphs where jk offers them
 * }</pre>
 */
public final class GlobalConfig {

    private GlobalConfig() {}

    /**
     * {@code [global].nerdfont} — whether the user's terminal font carries Nerd
     * Font / powerline glyphs, so jk may use them (e.g. rounded pill caps) and
     * fall back to plain Unicode otherwise. Default {@code false}.
     */
    public static boolean nerdfont() {
        return nerdfont(JkDirs.userConfigFile());
    }

    /** As {@link #nerdfont()} but against an explicit config file — for tests. */
    static boolean nerdfont(Path configFile) {
        return booleanFromGlobal(configFile, "nerdfont", false);
    }

    private static boolean booleanFromGlobal(Path file, String key, boolean fallback) {
        try {
            if (file == null || !Files.isRegularFile(file)) return fallback;
            TomlParseResult toml = Toml.parse(file);
            if (toml.hasErrors()) return fallback;
            TomlTable global = toml.getTable("global");
            if (global == null) return fallback;
            Object v = global.get(key);
            return (v instanceof Boolean b) ? b : fallback;
        } catch (Exception e) {
            return fallback; // presentation-only — never fail the command
        }
    }
}
