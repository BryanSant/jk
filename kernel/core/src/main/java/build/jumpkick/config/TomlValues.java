// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Lenient coercion helpers for reading jk's TOML config. Every config view reads values through
 * these so type-checking, blank handling, and integer ranges are identical everywhere, and a
 * malformed value degrades to "unset" (an empty {@link Optional}) rather than throwing —
 * configuration tunes presentation/optimisation and is never a correctness gate.
 *
 * <p>A {@link TomlParseResult} is itself a {@link TomlTable} (its root table), so the {@code optX}
 * accessors work equally on a parsed file's top level (bare keys) and on any nested {@link
 * TomlTable} obtained via {@link TomlTable#getTable}.
 *
 * <p>Companion to {@link EnvValues} (environment-variable coercion) and {@link ConfigSources}
 * (which files to read). See those for the full picture of how a setting flows from disk/env into a
 * typed config.
 */
public final class TomlValues {

    private TomlValues() {}

    /**
     * Parse a TOML file, swallowing IO and syntax errors. Empty when the path is {@code null},
     * absent, unreadable, or fails to parse — callers then fall back to their defaults instead of
     * failing the command.
     */
    public static Optional<TomlParseResult> parse(Path file) {
        try {
            if (file == null || !Files.isRegularFile(file)) return Optional.empty();
            TomlParseResult toml = Toml.parse(file);
            return toml.hasErrors() ? Optional.empty() : Optional.of(toml);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** A boolean value; absent table/key or wrong type → empty. */
    public static Optional<Boolean> optBoolean(TomlTable table, String key) {
        if (table == null) return Optional.empty();
        return (table.get(key) instanceof Boolean b) ? Optional.of(b) : Optional.empty();
    }

    /** A non-blank string value; absent table/key, blank, or wrong type → empty. */
    public static Optional<String> optString(TomlTable table, String key) {
        if (table == null) return Optional.empty();
        return (table.get(key) instanceof String s && !s.isBlank()) ? Optional.of(s) : Optional.empty();
    }

    /**
     * A TOML integer coerced to {@code int}; absent, non-integer, or out of {@code int} range →
     * empty. Callers needing a narrower range (e.g. only non-negative) should {@link Optional#filter}
     * the result.
     */
    public static Optional<Integer> optInt(TomlTable table, String key) {
        if (table == null) return Optional.empty();
        if (table.get(key) instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
            return Optional.of(l.intValue());
        }
        return Optional.empty();
    }

    /** A TOML integer as {@code long}; absent or non-integer → empty. */
    public static Optional<Long> optLong(TomlTable table, String key) {
        if (table == null) return Optional.empty();
        return (table.get(key) instanceof Long l) ? Optional.of(l) : Optional.empty();
    }

    /** A TOML number (integer or float) as {@code double}; absent or non-number → empty. */
    public static Optional<Double> optDouble(TomlTable table, String key) {
        if (table == null) return Optional.empty();
        return (table.get(key) instanceof Number n) ? Optional.of(n.doubleValue()) : Optional.empty();
    }

    /**
     * A TOML array of strings; absent table/key or non-array → empty list. Non-string elements are
     * skipped rather than failing the whole list.
     */
    public static List<String> stringList(TomlTable table, String key) {
        if (table == null || !(table.getArray(key) instanceof TomlArray arr)) return List.of();
        List<String> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            if (arr.get(i) instanceof String s) out.add(s);
        }
        return out;
    }
}
