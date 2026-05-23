// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.config;

import dev.buildjk.model.Scope;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Surgical text editor for {@code jk.toml}. Adds and removes single-line
 * dependency entries while preserving user formatting and comments — the
 * way {@code cargo add} / {@code uv add} treat their config files.
 *
 * <p>Supports both array shapes:
 * <pre>{@code
 *   [dependencies]
 *   main = [
 *     "com.foo:bar:1.0",
 *     "org.slf4j:slf4j-api:2.0.16",
 *   ]
 *   test = ["org.assertj:assertj-core:3.27.7"]
 * }</pre>
 *
 * <p>After every edit we run the result through {@link Toml#parse(String)}
 * for validation; if the document no longer parses, the edit is rejected.
 *
 * <p>For inline single-line arrays the editor expands them to multi-line
 * form on add. The result is always valid TOML.
 */
public final class BuildJkEditor {

    /** Header line for the [dependencies] table, on its own line. */
    private static final Pattern DEPS_HEADER = Pattern.compile("^\\s*\\[dependencies]\\s*$");

    /** Matches {@code <scope> = [} starting a multi-line array. */
    private static final Pattern MULTI_OPEN = Pattern.compile(
            "^(\\s*)([a-zA-Z][a-zA-Z0-9_-]*)\\s*=\\s*\\[\\s*$");

    /** Matches {@code <scope> = [ ... ]} on a single line. */
    private static final Pattern SINGLE_LINE = Pattern.compile(
            "^(\\s*)([a-zA-Z][a-zA-Z0-9_-]*)\\s*=\\s*\\[(.*)]\\s*$");

    /** Matches the closing bracket line of a multi-line array. */
    private static final Pattern MULTI_CLOSE = Pattern.compile("^\\s*]\\s*$");

    private BuildJkEditor() {}

    public static String addDependency(String content, Scope scope, String module, String versionLiteral) {
        String entry = module + ":" + versionLiteral;
        List<String> lines = splitPreservingTerminator(content);

        if (containsCoord(lines, scope, module)) {
            throw new IllegalStateException(
                    "dependencies." + scope.canonical() + " already contains \"" + module + "\"");
        }

        int headerLine = findDepsHeader(lines);
        if (headerLine < 0) {
            // No [dependencies] block; append a fresh one.
            ensureTrailingBlankLine(lines);
            lines.add("[dependencies]");
            lines.add(scope.canonical() + " = [");
            lines.add("  \"" + entry + "\",");
            lines.add("]");
            return validated(join(lines));
        }

        ScopeRange range = findScopeArray(lines, headerLine, scope);
        if (range == null) {
            // [dependencies] exists but no entry for this scope; insert one.
            int insertAt = endOfDepsTable(lines, headerLine);
            lines.add(insertAt, scope.canonical() + " = [");
            lines.add(insertAt + 1, "  \"" + entry + "\",");
            lines.add(insertAt + 2, "]");
            return validated(join(lines));
        }

        if (range.singleLine) {
            // Expand the single-line array to multi-line, append the new entry.
            String original = lines.get(range.startLine);
            Matcher m = SINGLE_LINE.matcher(original);
            if (!m.matches()) throw new IllegalStateException("unexpected: single-line scope didn't match");
            String indent = m.group(1);
            String body = m.group(3).trim();
            List<String> rebuilt = new ArrayList<>();
            rebuilt.add(indent + scope.canonical() + " = [");
            if (!body.isEmpty()) {
                for (String item : splitInlineArray(body)) {
                    rebuilt.add(indent + "  " + item + ",");
                }
            }
            rebuilt.add(indent + "  \"" + entry + "\",");
            rebuilt.add(indent + "]");
            lines.remove(range.startLine);
            lines.addAll(range.startLine, rebuilt);
        } else {
            lines.add(range.closingLine, "  \"" + entry + "\",");
        }
        return validated(join(lines));
    }

    public static String removeDependency(String content, Scope scope, String module) {
        List<String> lines = splitPreservingTerminator(content);
        int headerLine = findDepsHeader(lines);
        if (headerLine < 0) {
            throw new IllegalStateException("[dependencies] table not found in jk.toml");
        }
        ScopeRange range = findScopeArray(lines, headerLine, scope);
        if (range == null) {
            throw new IllegalStateException(
                    "dependencies." + scope.canonical() + " not found in jk.toml");
        }

        if (range.singleLine) {
            String original = lines.get(range.startLine);
            Matcher m = SINGLE_LINE.matcher(original);
            if (!m.matches()) throw new IllegalStateException("unexpected: single-line scope didn't match");
            String indent = m.group(1);
            String body = m.group(3).trim();
            List<String> items = body.isEmpty() ? new ArrayList<>() : splitInlineArray(body);
            int hit = -1;
            for (int i = 0; i < items.size(); i++) {
                if (entryMatchesModule(items.get(i), module)) { hit = i; break; }
            }
            if (hit < 0) {
                throw new IllegalStateException(
                        "\"" + module + "\" not found in dependencies." + scope.canonical());
            }
            items.remove(hit);
            lines.set(range.startLine, indent + scope.canonical() + " = ["
                    + String.join(", ", items) + "]");
        } else {
            int hit = -1;
            for (int i = range.startLine + 1; i < range.closingLine; i++) {
                String trimmed = lines.get(i).trim();
                String coord = trimmed.endsWith(",") ? trimmed.substring(0, trimmed.length() - 1).trim() : trimmed;
                if (entryMatchesModule(coord, module)) { hit = i; break; }
            }
            if (hit < 0) {
                throw new IllegalStateException(
                        "\"" + module + "\" not found in dependencies." + scope.canonical());
            }
            lines.remove(hit);
        }
        return validated(join(lines));
    }

    // --- internals ---------------------------------------------------------

    private static int findDepsHeader(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (DEPS_HEADER.matcher(lines.get(i)).matches()) return i;
        }
        return -1;
    }

    private record ScopeRange(int startLine, int closingLine, boolean singleLine) {}

    private static ScopeRange findScopeArray(List<String> lines, int headerLine, Scope scope) {
        String target = scope.canonical();
        for (int i = headerLine + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("[")) break;  // next table — give up
            Matcher single = SINGLE_LINE.matcher(line);
            if (single.matches() && single.group(2).equals(target)) {
                return new ScopeRange(i, i, true);
            }
            Matcher multi = MULTI_OPEN.matcher(line);
            if (multi.matches() && multi.group(2).equals(target)) {
                for (int j = i + 1; j < lines.size(); j++) {
                    if (MULTI_CLOSE.matcher(lines.get(j)).matches()) {
                        return new ScopeRange(i, j, false);
                    }
                }
                return null;
            }
        }
        return null;
    }

    /** Find the line just past the last entry of [dependencies] — where new scopes can be appended. */
    private static int endOfDepsTable(List<String> lines, int headerLine) {
        int depth = 0;
        int i = headerLine + 1;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (depth == 0 && line.startsWith("[")) return i;
            if (MULTI_OPEN.matcher(line).matches()) {
                depth = 1;
                i++;
                continue;
            }
            if (depth > 0 && MULTI_CLOSE.matcher(line).matches()) {
                depth = 0;
            }
            i++;
        }
        return lines.size();
    }

    private static boolean containsCoord(List<String> lines, Scope scope, String module) {
        int headerLine = findDepsHeader(lines);
        if (headerLine < 0) return false;
        ScopeRange range = findScopeArray(lines, headerLine, scope);
        if (range == null) return false;
        if (range.singleLine) {
            String body = SINGLE_LINE.matcher(lines.get(range.startLine)).replaceFirst("$3").trim();
            if (body.isEmpty()) return false;
            for (String item : splitInlineArray(body)) {
                if (entryMatchesModule(item, module)) return true;
            }
            return false;
        }
        for (int i = range.startLine + 1; i < range.closingLine; i++) {
            String trimmed = lines.get(i).trim();
            String coord = trimmed.endsWith(",") ? trimmed.substring(0, trimmed.length() - 1).trim() : trimmed;
            if (entryMatchesModule(coord, module)) return true;
        }
        return false;
    }

    /**
     * An array entry like {@code "g:a:1.0"} matches module {@code "g:a"} when
     * the entry's coord portion (group:artifact, before the version colon)
     * equals the module. Source-only entries {@code "g:a"} also match.
     */
    private static boolean entryMatchesModule(String entry, String module) {
        if (entry == null) return false;
        String unquoted = entry;
        if (unquoted.startsWith("\"") && unquoted.endsWith("\"")) {
            unquoted = unquoted.substring(1, unquoted.length() - 1);
        }
        int firstColon = unquoted.indexOf(':');
        if (firstColon < 0) return false;
        int secondColon = unquoted.indexOf(':', firstColon + 1);
        String coord = secondColon < 0 ? unquoted : unquoted.substring(0, secondColon);
        return coord.equals(module);
    }

    /** Split {@code "a", "b", "c"} into its quoted-string elements, respecting escapes. */
    private static List<String> splitInlineArray(String body) {
        List<String> items = new ArrayList<>();
        int i = 0;
        while (i < body.length()) {
            // skip whitespace and commas
            while (i < body.length() && (Character.isWhitespace(body.charAt(i)) || body.charAt(i) == ',')) i++;
            if (i >= body.length()) break;
            if (body.charAt(i) != '"') {
                // Non-string element; pass it through as raw token.
                int start = i;
                while (i < body.length() && body.charAt(i) != ',') i++;
                items.add(body.substring(start, i).trim());
                continue;
            }
            int start = i;
            i++;
            while (i < body.length()) {
                char c = body.charAt(i);
                if (c == '\\' && i + 1 < body.length()) { i += 2; continue; }
                if (c == '"') { i++; break; }
                i++;
            }
            items.add(body.substring(start, i));
        }
        return items;
    }

    private static String validated(String text) {
        TomlParseResult result = Toml.parse(text);
        if (result.hasErrors()) {
            throw new IllegalStateException(
                    "edit produced invalid TOML: " + result.errors().getFirst().getMessage());
        }
        return text;
    }

    private static List<String> splitPreservingTerminator(String content) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines.add(content.substring(start, i));
                start = i + 1;
            }
        }
        if (start < content.length()) {
            lines.add(content.substring(start));
        }
        return lines;
    }

    private static void ensureTrailingBlankLine(List<String> lines) {
        if (lines.isEmpty()) return;
        if (!lines.getLast().isBlank()) {
            lines.add("");
        }
    }

    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
