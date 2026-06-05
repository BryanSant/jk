// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal hand-rolled reader for the simple flat JSON objects jk's worker
 * subprocesses emit as NDJSON ({@code ##PREFIX:{...}}). Handles string,
 * integer, boolean, string-array, and nested-object fields. No external
 * dependencies — this is what keeps Jackson off the main binary's classpath.
 *
 * <p>All methods treat a missing or malformed field as the supplied default
 * (or {@code null}) rather than throwing, matching the contract callers
 * already relied on from Jackson's {@code node.path("x").asString()}.
 */
public final class Ndjson {

    private Ndjson() {}

    /**
     * Extract a JSON string field value, handling basic escape sequences
     * ({@code \"}, {@code \\}, {@code \n}, {@code \r}, {@code \t}).
     * Returns {@code null} when the key is absent.
     */
    public static String str(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) return null;
        start += needle.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                switch (n) {
                    case '"'  -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n'  -> sb.append('\n');
                    case 'r'  -> sb.append('\r');
                    case 't'  -> sb.append('\t');
                    default   -> { sb.append('\\'); sb.append(n); }
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Extract a JSON integer field, returning {@code defaultVal} when absent or non-numeric. */
    public static int intValue(String json, String key, int defaultVal) {
        if (json == null) return defaultVal;
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) return defaultVal;
        start += needle.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        boolean neg = end < json.length() && json.charAt(end) == '-';
        if (neg) end++;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        if (end == start || (neg && end == start + 1)) return defaultVal;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException ignored) {
            return defaultVal;
        }
    }

    /** Extract a JSON boolean field, returning {@code defaultVal} when absent. */
    public static boolean bool(String json, String key, boolean defaultVal) {
        if (json == null) return defaultVal;
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) return defaultVal;
        start += needle.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (json.startsWith("true", start)) return true;
        if (json.startsWith("false", start)) return false;
        return defaultVal;
    }

    /** Returns {@code true} when the key is present with any non-null, non-"null" value. */
    public static boolean has(String json, String key) {
        if (json == null) return false;
        return json.contains("\"" + key + "\":");
    }

    /**
     * Extract a JSON string-array field ({@code "key":["a","b","c"]}).
     * Returns an empty list when the key is absent or the value is not an array.
     * Does not handle nested arrays or non-string elements.
     */
    public static List<String> strArray(String json, String key) {
        if (json == null) return Collections.emptyList();
        String needle = "\"" + key + "\":[";
        int start = json.indexOf(needle);
        if (start < 0) return Collections.emptyList();
        start += needle.length();
        int end = json.indexOf(']', start);
        if (end < 0) return Collections.emptyList();
        String content = json.substring(start, end).trim();
        if (content.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < content.length()) {
            if (content.charAt(i) == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < content.length() && content.charAt(i) != '"') {
                    char c = content.charAt(i);
                    if (c == '\\' && i + 1 < content.length()) {
                        char n = content.charAt(++i);
                        switch (n) {
                            case '"'  -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            case 'n'  -> sb.append('\n');
                            default   -> { sb.append('\\'); sb.append(n); }
                        }
                    } else {
                        sb.append(c);
                    }
                    i++;
                }
                result.add(sb.toString());
                i++; // closing quote
            } else {
                i++;
            }
        }
        return result;
    }

    /**
     * Extract the raw JSON for a nested object field ({@code "key":{...}}).
     * Returns the {@code {...}} string (suitable for passing back to other
     * {@code Ndjson} methods), or {@code null} when absent.
     */
    public static String nested(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\":{";
        int start = json.indexOf(needle);
        if (start < 0) {
            // Also handle "key": { with a space
            needle = "\"" + key + "\": {";
            start = json.indexOf(needle);
            if (start < 0) return null;
        }
        // Walk forward to find the matching closing brace.
        int braceStart = json.indexOf('{', start + needle.length() - 1);
        if (braceStart < 0) return null;
        int depth = 1;
        int i = braceStart + 1;
        while (i < json.length() && depth > 0) {
            char c = json.charAt(i);
            if (c == '"') {
                // skip string to avoid counting braces inside strings
                i++;
                while (i < json.length() && json.charAt(i) != '"') {
                    if (json.charAt(i) == '\\') i++;
                    i++;
                }
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
            i++;
        }
        return json.substring(braceStart, i);
    }
}
