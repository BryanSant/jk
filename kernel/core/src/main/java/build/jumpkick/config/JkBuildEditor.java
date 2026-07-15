// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.config;

import build.jumpkick.model.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/**
 * Surgical text editor for {@code jk.toml}. Adds and removes single-line dependency entries while
 * preserving user formatting and comments — the way {@code cargo add} / {@code uv add} treat their
 * config files.
 *
 * <p>Works against the v0.7 name-as-key top-level section format:
 *
 * <pre>{@code
 * [dependencies]
 * spring-web = { group = "org.springframework.boot", name = "spring-boot-starter-web", version = "3.4.0" }
 *
 * [test-dependencies]
 * junit-jupiter.workspace = true
 * }</pre>
 *
 * <p>Each scope is a top-level section: {@code [dependencies]} for MAIN, {@code
 * [test-dependencies]} for TEST, {@code [provided-dependencies]} for PROVIDED, {@code
 * [processor-dependencies]} for PROCESSOR, {@code [export-dependencies]} for EXPORT.
 *
 * <p>After every edit we run the result through {@link Toml#parse(String)} for validation; if the
 * document no longer parses, the edit is rejected.
 */
public final class JkBuildEditor {

    /** Header line for the {@code [dependencies]} table (MAIN scope). */
    private static final Pattern DEPS_FLAT_HEADER = Pattern.compile("^\\s*\\[dependencies]\\s*$");

    /** Any TOML table header. */
    private static final Pattern ANY_HEADER = Pattern.compile("^\\s*\\[[^]]+]\\s*$");

    /** Header line for the {@code [workspace]} table. */
    private static final Pattern WORKSPACE_HEADER = Pattern.compile("^(\\s*)\\[workspace]\\s*$");

    /** The {@code modules = ...} assignment within {@code [workspace]}. */
    private static final Pattern MODULES_KEY = Pattern.compile("^\\s*modules\\s*=.*$");

    /** A double-quoted string literal element. */
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

    /**
     * A dep entry line: {@code key = { ... }} or {@code key.workspace = true}. Captures key in group
     * 2.
     */
    private static final Pattern DEP_ENTRY =
            Pattern.compile("^(\\s*)([A-Za-z][A-Za-z0-9_-]*)(?:\\.[a-zA-Z][a-zA-Z0-9_-]*)?\\s*=.*$");

    private JkBuildEditor() {}

    /**
     * Add a dependency entry to the scope section (e.g. {@code [dependencies]} for MAIN,
     * {@code [test-dependencies]} for TEST).
     *
     * <p>Decisions:
     *
     * <ul>
     *   <li>If {@code artifact.equals(name)}, the {@code artifact} field is omitted (it defaults to
     *       the key per the design doc).
     *   <li>If the file has no section for the requested scope, we append a fresh one at the end.
     *   <li>If a section already exists, we append the new entry just before the next table header.
     * </ul>
     *
     * @param versionLiteral the value placed inside {@code version = "..."}. Pass {@code "=1.2.3"}
     *     for an exact pin or {@code "1.2.3"} for caret-floating (the parser uses {@code
     *     parseFloating}).
     */
    public static String addDependency(
            String content, Scope scope, String name, String group, String artifact, String versionLiteral) {
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
            throw new IllegalStateException(scope.tomlSection() + " already contains \"" + name + "\"");
        }

        String entryLine = renderEntry(name, group, artifact, versionLiteral);

        int header = findScopeHeader(lines, scope);
        if (header < 0) {
            // No section for this scope. Append one at the end of the file.
            ensureTrailingBlankLine(lines);
            lines.add("[" + scope.tomlSection() + "]");
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
     * Add a file-backed (CAS sha256) dependency entry to the scope's dependency section (e.g.
     * {@code [dependencies]} for MAIN, {@code [test-dependencies]} for TEST). The rendered
     * form is:
     *
     * <pre>{@code
     * library = { sha256 = "...", group = "...", version = "..." }
     * }</pre>
     *
     * The {@code name} field is omitted when {@code artifact} equals {@code library}, following the
     * same convention as {@link #addDependency}.
     */
    public static String addFileDependency(
            String content, Scope scope, String library, String group, String artifact, String version, String sha256) {
        validateName(library);
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException("group must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (sha256 == null || sha256.isBlank()) {
            throw new IllegalArgumentException("sha256 must not be blank");
        }
        if (artifact == null || artifact.isBlank()) artifact = library;

        List<String> lines = splitPreservingTerminator(content);
        if (findDepKey(lines, scope, library) >= 0) {
            throw new IllegalStateException(
                    scope.tomlSection() + " already contains \"" + library + "\"");
        }

        StringBuilder sb = new StringBuilder(library)
                .append(" = { sha256 = \"")
                .append(escape(sha256))
                .append("\", group = \"")
                .append(escape(group))
                .append("\"");
        if (!artifact.equals(library)) {
            sb.append(", name = \"").append(escape(artifact)).append("\"");
        }
        sb.append(", version = \"").append(escape(version)).append("\" }");
        String entryLine = sb.toString();

        int header = findScopeHeader(lines, scope);
        if (header < 0) {
            ensureTrailingBlankLine(lines);
            lines.add("[" + scope.tomlSection() + "]");
            lines.add(entryLine);
            return validated(join(lines));
        }
        int insertAt = endOfTable(lines, header);
        while (insertAt > header + 1 && lines.get(insertAt - 1).isBlank()) {
            insertAt--;
        }
        lines.add(insertAt, entryLine);
        return validated(join(lines));
    }

    /**
     * Remove a dependency by short {@code name} from its scope section (e.g. {@code [dependencies]}
     * for MAIN, {@code [test-dependencies]} for TEST). Leaves the
     * (possibly now-empty) sub-table in place — minimal blast radius on surrounding formatting.
     *
     * @throws IllegalStateException if the scope or name isn't present.
     */
    public static String removeDependency(String content, Scope scope, String name) {
        validateName(name);
        List<String> lines = splitPreservingTerminator(content);
        int hit = findDepKey(lines, scope, name);
        if (hit < 0) {
            // Determine whether the scope section existed for a better error.
            if (findScopeHeader(lines, scope) < 0) {
                throw new IllegalStateException(scope.tomlSection() + " not found in jk.toml");
            }
            throw new IllegalStateException("\"" + name + "\" not found in " + scope.tomlSection());
        }
        lines.remove(hit);
        return validated(join(lines));
    }

    /**
     * Append {@code modulePath} to the root manifest's {@code [workspace].modules} array, preserving
     * the array's existing shape (single-line vs multi-line) and any surrounding comments.
     *
     * <p>Idempotent: if the path is already a module the content is returned unchanged. Used by
     * {@code jk new}/{@code jk init}/ {@code jk add <path>} to register a new module, the way {@code
     * cargo new} / {@code uv init} edit the workspace manifest.
     *
     * @throws IllegalStateException if there is no {@code [workspace]} table.
     */
    public static String addWorkspaceModule(String content, String modulePath) {
        return addWorkspaceModule(content, modulePath, false);
    }

    /**
     * Register a workspace module, <em>creating</em> the {@code [workspace]} table if the manifest
     * doesn't have one yet. This is how adding the first module promotes a plain single-project
     * {@code jk.toml} into a workspace root (Cargo/uv semantics). When a {@code [workspace]} table
     * already exists this is identical to {@link #addWorkspaceModule(String, String)}.
     */
    public static String registerWorkspaceModule(String content, String modulePath) {
        return addWorkspaceModule(content, modulePath, true);
    }

    private static String addWorkspaceModule(String content, String modulePath, boolean createTable) {
        if (modulePath == null || modulePath.isBlank()) {
            throw new IllegalArgumentException("module path must not be blank");
        }
        String path = modulePath.replace('\\', '/');

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
            if (!createTable) {
                throw new IllegalStateException("no [workspace] table in jk.toml");
            }
            // Promote a plain project into a workspace: append a [workspace]
            // table with this module as its first entry.
            StringBuilder sb = new StringBuilder(content);
            if (!content.isEmpty() && !content.endsWith("\n")) sb.append('\n');
            sb.append("\n[workspace]\nmodules = [\"").append(escape(path)).append("\"]\n");
            return validated(sb.toString());
        }

        int end = endOfTable(lines, wsHeader);
        int modulesLine = -1;
        for (int i = wsHeader + 1; i < end; i++) {
            if (MODULES_KEY.matcher(lines.get(i)).matches()) {
                modulesLine = i;
                break;
            }
        }
        // No modules key yet — add one right under the header.
        if (modulesLine < 0) {
            lines.add(wsHeader + 1, wsIndent + "modules = [\"" + escape(path) + "\"]");
            return validated(join(lines));
        }

        // Find the line carrying the array's closing ']'. String elements
        // never contain ']', so the first ']' at/after modulesLine closes it.
        int closeLine = -1;
        for (int i = modulesLine; i < end; i++) {
            if (lines.get(i).indexOf(']') >= 0) {
                closeLine = i;
                break;
            }
        }
        if (closeLine < 0) {
            throw new IllegalStateException("malformed modules array in [workspace]");
        }

        // Idempotency: collect existing elements across the array's lines.
        StringBuilder arrayText = new StringBuilder();
        for (int i = modulesLine; i <= closeLine; i++)
            arrayText.append(lines.get(i)).append('\n');
        Matcher q = QUOTED.matcher(arrayText);
        while (q.find()) {
            if (q.group(1).equals(path)) return content; // already a module
        }

        if (modulesLine == closeLine) {
            insertInlineModule(lines, closeLine, path);
        } else {
            insertMultilineModule(lines, modulesLine, closeLine, path);
        }
        return validated(join(lines));
    }

    /** Insert {@code "path"} before the {@code ]} on a single-line modules array. */
    private static void insertInlineModule(List<String> lines, int lineIdx, String path) {
        String line = lines.get(lineIdx);
        int close = line.lastIndexOf(']');
        int j = close - 1;
        while (j >= 0 && Character.isWhitespace(line.charAt(j))) j--;
        char prev = j >= 0 ? line.charAt(j) : '\0';
        String insertion =
                switch (prev) {
                    case '[' -> "\"" + escape(path) + "\""; // empty array
                    case ',' -> " \"" + escape(path) + "\""; // trailing comma already present
                    default -> ", \"" + escape(path) + "\"";
                };
        lines.set(lineIdx, line.substring(0, close) + insertion + line.substring(close));
    }

    /** Insert a new element line just before the {@code ]} of a multi-line array. */
    private static void insertMultilineModule(List<String> lines, int modulesLine, int closeLine, String path) {
        // Ensure the last element line carries a trailing comma.
        for (int i = closeLine - 1; i > modulesLine - 1; i--) {
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
        if (modulesLine + 1 < closeLine) {
            String el = lines.get(modulesLine + 1);
            indent = el.substring(0, el.length() - el.stripLeading().length());
        }
        lines.add(closeLine, indent + "\"" + escape(path) + "\",");
    }

    // --- internals ---------------------------------------------------------

    /**
     * Map a {@link Scope} to its top-level TOML section name. MAIN → {@code dependencies}; others →
     * {@code <canonical>-dependencies} (e.g. {@code test-dependencies}).
     */
    /**
     * Locate the line that opens the section for {@code scope}. MAIN scope uses the {@code
     * [dependencies]} header; non-MAIN scopes use {@code [<canonical>-dependencies]}.
     */
    private static int findScopeHeader(List<String> lines, Scope scope) {
        String sectionName = scope.tomlSection();
        Pattern target;
        if (scope == Scope.MAIN) {
            target = DEPS_FLAT_HEADER;
        } else {
            // Build a pattern that matches exactly this scope's section header.
            target = Pattern.compile("^\\s*\\[" + Pattern.quote(sectionName) + "]\\s*$");
        }
        for (int i = 0; i < lines.size(); i++) {
            if (target.matcher(lines.get(i)).matches()) {
                return i;
            }
        }
        return -1;
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

    /**
     * Return the index of the next top-level header after {@code headerLine}, or {@code
     * lines.size()}.
     */
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
        var hit = build.jumpkick.library.LibraryCatalog.bundled().lookup(name);
        if (hit.isPresent()
                && hit.get().group().equals(group)
                && hit.get().artifact().equals(artifact)) {
            return name + " = \"" + escape(versionLiteral) + "\"";
        }
        StringBuilder sb = new StringBuilder(name)
                .append(" = { group = \"")
                .append(escape(group))
                .append("\"");
        if (!artifact.equals(name)) {
            sb.append(", name = \"").append(escape(artifact)).append("\"");
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
            throw new IllegalArgumentException("dependency name must match [A-Za-z][A-Za-z0-9_-]* (got: " + name + ")");
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
