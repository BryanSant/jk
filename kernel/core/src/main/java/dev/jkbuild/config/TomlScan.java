// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A line scanner for reading a handful of scalar keys out of TOML files on latency-critical,
 * engine-free paths — above all the shell hook ({@code jk hook-env} runs on <em>every prompt</em>;
 * an engine round trip, or worse an engine spawn, is an explicit anti-goal there, and so is a
 * full tomlj parse of a potentially large lockfile per keystroke).
 *
 * <p>This is NOT a TOML parser. It reads {@code key = "value"} / {@code key = 123} /
 * {@code key = true} scalars, tracks {@code [section]} headers for scoping, skips comments and
 * blank lines, and stops early once every requested key is found. Multi-line strings, inline
 * tables, arrays, and dotted keys are out of scope — the fast paths only read keys jk itself
 * documents as flat scalars ({@code [project] jdk/java}, {@code [native] graal}, the lockfile's
 * top-level {@code jdk}). A file that hides one of those behind exotic TOML simply reads as
 * absent, exactly like a missing key — the same graceful degradation the full readers have for
 * absent keys, never a wrong value.
 *
 * <p>Same design lineage as {@code JdkCatalogClient}'s jdks.json line scanner: exploit a format
 * we control instead of shipping a parser on the hot path.
 */
public final class TomlScan {

    private final Map<String, String> values;
    private final Map<String, List<String>> arrays;
    private final Set<String> sections;

    private TomlScan(Map<String, String> values, Map<String, List<String>> arrays, Set<String> sections) {
        this.values = values;
        this.arrays = arrays;
        this.sections = sections;
    }

    private static final Pattern QUOTED = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * Scan {@code file} for {@code keys}, each spelled {@code "section.key"} (or just
     * {@code "key"} for top-level). Missing file → an empty result (every lookup absent).
     */
    public static TomlScan scan(Path file, String... keys) {
        Map<String, String> values = new HashMap<>();
        Map<String, List<String>> arrays = new HashMap<>();
        Set<String> sections = new HashSet<>();
        Set<String> wanted = Set.of(keys);
        if (!Files.isRegularFile(file)) return new TomlScan(values, arrays, sections);
        try {
            String section = "";
            String arrayKey = null; // a wanted key whose `[ … ]` array spans lines
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (arrayKey != null) {
                    // Inside a multi-line string array: collect quoted elements until the
                    // `]` outside quotes that closes it (IPv6-style values carry `]` inside).
                    if (collectArrayLine(line, arrays.get(arrayKey))) arrayKey = null;
                    continue;
                }
                if (line.startsWith("[")) {
                    int close = line.indexOf(']');
                    if (close > 1) {
                        section = line.substring(line.startsWith("[[") ? 2 : 1, close)
                                .replace("]", "")
                                .strip();
                        sections.add(section);
                    }
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).strip();
                String qualified = section.isEmpty() ? key : section + "." + key;
                if (!wanted.contains(qualified)) continue;
                String rest = line.substring(eq + 1).strip();
                if (rest.startsWith("[")) {
                    if (arrays.containsKey(qualified)) continue;
                    List<String> elements = new ArrayList<>();
                    arrays.put(qualified, elements);
                    if (!collectArrayLine(rest.substring(1), elements)) arrayKey = qualified;
                    continue;
                }
                if (values.containsKey(qualified)) continue;
                values.put(qualified, scalar(rest));
                if (values.size() == wanted.size()) break; // all found — stop reading
            }
        } catch (IOException ignored) {
            // unreadable file — every lookup reads as absent, like the tolerant full readers
        }
        return new TomlScan(values, arrays, sections);
    }

    /** Collect quoted elements from one array line; true when the closing {@code ]} was seen. */
    private static boolean collectArrayLine(String line, List<String> elements) {
        int close = line.indexOf(']', line.lastIndexOf('"') + 1);
        String body = close >= 0 ? line.substring(0, close) : line;
        Matcher m = QUOTED.matcher(body);
        while (m.find()) {
            elements.add(m.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return close >= 0;
    }

    /** The scanned value for {@code "section.key"} / {@code "key"}, or {@code null} when absent. */
    public String get(String qualifiedKey) {
        return values.get(qualifiedKey);
    }

    /** As {@link #get}, parsed as an int; {@code fallback} when absent or non-numeric. */
    public int getInt(String qualifiedKey, int fallback) {
        String v = values.get(qualifiedKey);
        if (v == null) return fallback;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * The scanned string-array value for {@code "section.key"}, or an empty list when absent or
     * not an array. Single-line and one-element-per-line forms both read; non-string elements and
     * exotic TOML degrade to absent elements, never wrong values.
     */
    public List<String> stringArray(String qualifiedKey) {
        List<String> v = arrays.get(qualifiedKey);
        return v == null ? List.of() : List.copyOf(v);
    }

    /** True when a {@code [section]} (or {@code [[section]]}) header was seen at all. */
    public boolean hasSection(String section) {
        return sections.contains(section);
    }

    /** Strip quotes from a scalar; drop a trailing same-line comment on unquoted values. */
    static String scalar(String v) {
        if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'')) {
            char quote = v.charAt(0);
            int end = v.indexOf(quote, 1);
            return end > 0 ? v.substring(1, end) : v.substring(1);
        }
        int hash = v.indexOf('#');
        return (hash >= 0 ? v.substring(0, hash) : v).strip();
    }
}
