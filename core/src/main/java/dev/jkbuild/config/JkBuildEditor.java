// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.model.Scope;
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
 * <p>Works against the v0.7 name-as-key sub-table format:
 * <pre>{@code
 *   [dependencies.main]
 *   spring-web = { group = "org.springframework.boot", artifact = "spring-boot-starter-web", version = "3.4.0" }
 *
 *   [dependencies.test]
 *   junit-jupiter.workspace = true
 * }</pre>
 *
 * <p>Default-scope shorthand: when a file's {@code [dependencies]} table
 * contains only inline-table children (i.e. no sub-scopes), it is treated
 * as {@code [dependencies.main]}; we add main deps directly under that
 * header. Adding a {@code test} dep to such a file creates a separate
 * {@code [dependencies.test]} sub-table.
 *
 * <p>After every edit we run the result through {@link Toml#parse(String)}
 * for validation; if the document no longer parses, the edit is rejected.
 */
public final class JkBuildEditor {

    /** Header line for the bare {@code [dependencies]} table (flat-shorthand). */
    private static final Pattern DEPS_FLAT_HEADER = Pattern.compile("^\\s*\\[dependencies]\\s*$");

    /** Header line for {@code [dependencies.<scope>]}. Captures scope name in group 1. */
    private static final Pattern DEPS_SCOPE_HEADER = Pattern.compile(
            "^\\s*\\[dependencies\\.([a-zA-Z][a-zA-Z0-9_-]*)]\\s*$");

    /** Any TOML table header. */
    private static final Pattern ANY_HEADER = Pattern.compile("^\\s*\\[[^]]+]\\s*$");

    /** Header line for the {@code [workspace]} table. */
    private static final Pattern WORKSPACE_HEADER = Pattern.compile("^(\\s*)\\[workspace]\\s*$");

    /** The {@code members = ...} assignment within {@code [workspace]}. */
    private static final Pattern MEMBERS_KEY = Pattern.compile("^\\s*members\\s*=.*$");

    /** A double-quoted string literal element. */
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

    /** A dep entry line: {@code key = { ... }} or {@code key.workspace = true}. Captures key in group 2. */
    private static final Pattern DEP_ENTRY = Pattern.compile(
            "^(\\s*)([A-Za-z][A-Za-z0-9_-]*)(?:\\.[a-zA-Z][a-zA-Z0-9_-]*)?\\s*=.*$");

    private JkBuildEditor() {}

    /**
     * Add a dependency entry to {@code [dependencies.<scope>]}.
     *
     * <p>Decisions:
     * <ul>
     *   <li>If {@code artifact.equals(name)}, the {@code artifact} field is
     *       omitted (it defaults to the key per the design doc).</li>
     *   <li>If the file has no {@code [dependencies]} table at all, we append
     *       a fresh {@code [dependencies.<scope>]} header at the end.</li>
     *   <li>If the file has a flat {@code [dependencies]} table holding only
     *       inline-table deps (the shorthand for {@code main}), we treat it
     *       as {@code [dependencies.main]}: adding a main dep extends it in
     *       place; adding a non-main dep appends a separate sub-table.</li>
     *   <li>If a {@code [dependencies.<scope>]} sub-table already exists, we
     *       append the new entry just before the next table header.</li>
     * </ul>
     *
     * @param versionLiteral the value placed inside {@code version = "..."}.
     *                       Pass {@code "=1.2.3"} for an exact pin or {@code "1.2.3"}
     *                       for caret-floating (the parser uses {@code parseFloating}).
     */
    public static String addDependency(String content, Scope scope, String name,
                                       String group, String artifact, String versionLiteral) {
        validateName(name);
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException("group must not be blank");
        }
        if (versionLiteral == null || versionLiteral.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (artifact == null || artifact.isBlank()) artifact = name;

        List<String> lines = splitPreservingTerminator(content);
        if (findDepKey(lines, scope, name) >= 0) {
            throw new IllegalStateException(
                    "dependencies." + scope.canonical() + " already contains \"" + name + "\"");
        }

        String entryLine = renderEntry(name, group, artifact, versionLiteral);

        // If we're adding a non-main dep while a flat [dependencies] shorthand
        // is in use, promote the flat header to [dependencies.main] first —
        // otherwise the resulting file mixes shapes and the parser rejects it.
        if (scope != Scope.MAIN) {
            promoteFlatShorthandIfPresent(lines);
        }

        int header = findScopeHeader(lines, scope);
        if (header < 0) {
            // No sub-table for this scope. Append one at the end of the file.
            ensureTrailingBlankLine(lines);
            lines.add("[dependencies." + scope.canonical() + "]");
            lines.add(entryLine);
            return validated(join(lines));
        }
        int insertAt = endOfTable(lines, header);
        // Insert just before the next header / EOF, trimming any trailing
        // blank lines that belong between this table and the next so the
        // entry sits flush at the bottom of the current sub-table.
        while (insertAt > header + 1 && lines.get(insertAt - 1).isBlank()) {
            insertAt--;
        }
        lines.add(insertAt, entryLine);
        return validated(join(lines));
    }

    /**
     * Remove a dependency by short {@code name} from
     * {@code [dependencies.<scope>]}. Leaves the (possibly now-empty)
     * sub-table in place — minimal blast radius on surrounding formatting.
     *
     * @throws IllegalStateException if the scope or name isn't present.
     */
    public static String removeDependency(String content, Scope scope, String name) {
        validateName(name);
        List<String> lines = splitPreservingTerminator(content);
        int hit = findDepKey(lines, scope, name);
        if (hit < 0) {
            // Determine whether the scope sub-table existed for a better error.
            if (findScopeHeader(lines, scope) < 0) {
                throw new IllegalStateException(
                        "dependencies." + scope.canonical() + " not found in jk.toml");
            }
            throw new IllegalStateException(
                    "\"" + name + "\" not found in dependencies." + scope.canonical());
        }
        lines.remove(hit);
        return validated(join(lines));
    }

    /**
     * Append {@code memberPath} to the root manifest's
     * {@code [workspace].members} array, preserving the array's existing
     * shape (single-line vs multi-line) and any surrounding comments.
     *
     * <p>Idempotent: if the path is already a member the content is
     * returned unchanged. Used by {@code jk new}/{@code jk init}/
     * {@code jk add <path>} to register a new member, the way
     * {@code cargo new} / {@code uv init} edit the workspace manifest.
     *
     * @throws IllegalStateException if there is no {@code [workspace]} table.
     */
    public static String addWorkspaceMember(String content, String memberPath) {
        if (memberPath == null || memberPath.isBlank()) {
            throw new IllegalArgumentException("member path must not be blank");
        }
        String path = memberPath.replace('\\', '/');

        List<String> lines = splitPreservingTerminator(content);
        int wsHeader = -1;
        String wsIndent = "";
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = WORKSPACE_HEADER.matcher(lines.get(i));
            if (m.matches()) {
                wsHeader = i;
                wsIndent = m.group(1);
                break;
            }
        }
        if (wsHeader < 0) {
            throw new IllegalStateException("no [workspace] table in jk.toml");
        }

        int end = endOfTable(lines, wsHeader);
        int membersLine = -1;
        for (int i = wsHeader + 1; i < end; i++) {
            if (MEMBERS_KEY.matcher(lines.get(i)).matches()) {
                membersLine = i;
                break;
            }
        }
        // No members key yet — add one right under the header.
        if (membersLine < 0) {
            lines.add(wsHeader + 1, wsIndent + "members = [\"" + escape(path) + "\"]");
            return validated(join(lines));
        }

        // Find the line carrying the array's closing ']'. String elements
        // never contain ']', so the first ']' at/after membersLine closes it.
        int closeLine = -1;
        for (int i = membersLine; i < end; i++) {
            if (lines.get(i).indexOf(']') >= 0) { closeLine = i; break; }
        }
        if (closeLine < 0) {
            throw new IllegalStateException("malformed members array in [workspace]");
        }

        // Idempotency: collect existing elements across the array's lines.
        StringBuilder arrayText = new StringBuilder();
        for (int i = membersLine; i <= closeLine; i++) arrayText.append(lines.get(i)).append('\n');
        Matcher q = QUOTED.matcher(arrayText);
        while (q.find()) {
            if (q.group(1).equals(path)) return content; // already a member
        }

        if (membersLine == closeLine) {
            insertInlineMember(lines, closeLine, path);
        } else {
            insertMultilineMember(lines, membersLine, closeLine, path);
        }
        return validated(join(lines));
    }

    /** Insert {@code "path"} before the {@code ]} on a single-line members array. */
    private static void insertInlineMember(List<String> lines, int lineIdx, String path) {
        String line = lines.get(lineIdx);
        int close = line.lastIndexOf(']');
        int j = close - 1;
        while (j >= 0 && Character.isWhitespace(line.charAt(j))) j--;
        char prev = j >= 0 ? line.charAt(j) : '\0';
        String insertion = switch (prev) {
            case '[' -> "\"" + escape(path) + "\"";       // empty array
            case ',' -> " \"" + escape(path) + "\"";       // trailing comma already present
            default  -> ", \"" + escape(path) + "\"";
        };
        lines.set(lineIdx, line.substring(0, close) + insertion + line.substring(close));
    }

    /** Insert a new element line just before the {@code ]} of a multi-line array. */
    private static void insertMultilineMember(List<String> lines, int membersLine, int closeLine, String path) {
        // Ensure the last element line carries a trailing comma.
        for (int i = closeLine - 1; i > membersLine - 1; i--) {
            String t = lines.get(i);
            String trimmed = t.stripTrailing();
            if (trimmed.isEmpty() || trimmed.stripLeading().startsWith("#")) continue;
            if (!trimmed.endsWith(",") && !trimmed.endsWith("[")) {
                lines.set(i, trimmed + ",");
            }
            break;
        }
        // Indent like the first element line if there is one, else 4 spaces.
        String indent = "    ";
        if (membersLine + 1 < closeLine) {
            String el = lines.get(membersLine + 1);
            indent = el.substring(0, el.length() - el.stripLeading().length());
        }
        lines.add(closeLine, indent + "\"" + escape(path) + "\",");
    }

    // --- internals ---------------------------------------------------------

    /**
     * Locate the line that opens {@code [dependencies.<scope>]}. Honors
     * default-scope shorthand: a bare {@code [dependencies]} table whose
     * direct children are all dep entries (not sub-scopes) counts as
     * {@code [dependencies.main]}.
     */
    private static int findScopeHeader(List<String> lines, Scope scope) {
        int flat = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher sub = DEPS_SCOPE_HEADER.matcher(line);
            if (sub.matches() && sub.group(1).equals(scope.canonical())) {
                return i;
            }
            if (DEPS_FLAT_HEADER.matcher(line).matches()) {
                flat = i;
            }
        }
        if (flat >= 0 && scope == Scope.MAIN && isFlatShorthand(lines, flat)) {
            return flat;
        }
        return -1;
    }

    /**
     * A bare {@code [dependencies]} table is the shorthand for
     * {@code [dependencies.main]} iff every direct child line under it is
     * a dep entry (i.e. no sub-table line like {@code [dependencies.test]}
     * comes between this header and the next top-level header).
     *
     * <p>In practice all we need to check is that the bare header isn't
     * immediately followed by sub-scope headers — those would mean the
     * flat header is a parse error per the design doc. The parser rejects
     * mixed shape, but the editor must still be forgiving: if any sub-scope
     * appears anywhere after {@code [dependencies]}, that sub-scope is the
     * canonical container; we don't treat the bare table as main.
     */
    private static boolean isFlatShorthand(List<String> lines, int flatHeaderLine) {
        // If any [dependencies.<scope>] header exists in the file, the bare
        // [dependencies] block is something else (e.g., an empty placeholder
        // or user-introduced mistake) and we don't claim it as main.
        for (int i = 0; i < lines.size(); i++) {
            if (i == flatHeaderLine) continue;
            if (DEPS_SCOPE_HEADER.matcher(lines.get(i)).matches()) return false;
        }
        // Scan children: every non-blank, non-comment line up to the next
        // header must be a dep entry (key = ...). Mixed shape produces a
        // parse error downstream anyway; here we just want a sane heuristic.
        for (int i = flatHeaderLine + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (ANY_HEADER.matcher(line).matches()) break;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (!DEP_ENTRY.matcher(line).matches()) return false;
        }
        return true;
    }

    /**
     * If a flat {@code [dependencies]} shorthand block is present, rewrite
     * its header to {@code [dependencies.main]} so a sibling sub-scope can
     * be added without producing a "mixed flat and sub-scope" parse error.
     * No-op when no flat shorthand exists.
     */
    private static void promoteFlatShorthandIfPresent(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (DEPS_FLAT_HEADER.matcher(lines.get(i)).matches() && isFlatShorthand(lines, i)) {
                // Preserve the original indentation, in case the user wrote it
                // with leading whitespace (uncommon but legal).
                String original = lines.get(i);
                String leadingWs = original.substring(0, original.indexOf('['));
                lines.set(i, leadingWs + "[dependencies.main]");
                return;
            }
        }
    }

    /** Find the line index of the dep entry named {@code name} in {@code scope}, or {@code -1}. */
    private static int findDepKey(List<String> lines, Scope scope, String name) {
        int header = findScopeHeader(lines, scope);
        if (header < 0) return -1;
        int end = endOfTable(lines, header);
        for (int i = header + 1; i < end; i++) {
            String line = lines.get(i);
            Matcher m = DEP_ENTRY.matcher(line);
            if (m.matches() && m.group(2).equals(name)) return i;
        }
        return -1;
    }

    /** Return the index of the next top-level header after {@code headerLine}, or {@code lines.size()}. */
    private static int endOfTable(List<String> lines, int headerLine) {
        for (int i = headerLine + 1; i < lines.size(); i++) {
            if (ANY_HEADER.matcher(lines.get(i)).matches()) return i;
        }
        return lines.size();
    }

    /** Render a single dep-entry line. Omits {@code artifact} when it matches the key. */
    private static String renderEntry(String name, String group, String artifact, String versionLiteral) {
        // Cargo-style one-liner when the user's name + coord matches a curated
        // catalog entry — `picocli = "4.7.7"` reads better in big manifests
        // than the full structured form. Falls back to the structured form
        // otherwise.
        var hit = dev.jkbuild.alias.AliasCatalog.bundled().lookup(name);
        if (hit.isPresent()
                && hit.get().group().equals(group)
                && hit.get().artifact().equals(artifact)) {
            return name + " = \"" + escape(versionLiteral) + "\"";
        }
        StringBuilder sb = new StringBuilder(name).append(" = { group = \"")
                .append(escape(group)).append("\"");
        if (!artifact.equals(name)) {
            sb.append(", artifact = \"").append(escape(artifact)).append("\"");
        }
        sb.append(", version = \"").append(escape(versionLiteral)).append("\" }");
        return sb.toString();
    }

    /** Escape a value destined for a TOML basic string literal. */
    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("dependency name must not be blank");
        }
        // Bare-key character class per TOML: [A-Za-z0-9_-]+. We additionally
        // require a leading letter to keep the rendered TOML free of quoting.
        if (!name.matches("[A-Za-z][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException(
                    "dependency name must match [A-Za-z][A-Za-z0-9_-]* (got: " + name + ")");
        }
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
