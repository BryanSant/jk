// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.hocon;

import dev.buildjk.model.Scope;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Surgical text editor for {@code build.jk}. Adds and removes single-line
 * dependency declarations while preserving user formatting and comments —
 * the way {@code cargo add} / {@code uv add} treat their config files.
 *
 * <p>v0.1 supports the canonical dotted form:
 * <pre>{@code
 *   dependencies.main {
 *     "com.foo:bar" = "1.0"
 *   }
 * }</pre>
 *
 * <p>Limitations (acceptable for v0.1):
 * <ul>
 *   <li>Each dep declaration must fit on a single line.</li>
 *   <li>The {@code dependencies.<scope>} block header must be on its own
 *       line and use the dotted form (not a nested {@code dependencies { main { ... } }}).</li>
 * </ul>
 *
 * <p>If a user's build.jk uses an unsupported layout we fall back to
 * appending a new block at the end of file — never silently rewrite the
 * structure they wrote.
 */
public final class BuildJkEditor {

    /** Matches {@code dependencies.&lt;scope&gt; {} or `dependencies."<scope>"` opening on its own line. */
    private static final Pattern BLOCK_HEADER = Pattern.compile(
            "^\\s*dependencies\\.([a-zA-Z]+)\\s*\\{\\s*$");

    private BuildJkEditor() {}

    public static String addDependency(String content, Scope scope, String module, String versionLiteral) {
        List<String> lines = splitPreservingTerminator(content);
        BlockRange block = findScopeBlock(lines, scope);
        String declaration = "  " + quote(module) + " = " + quote(versionLiteral);

        if (alreadyDeclared(lines, block, module)) {
            throw new IllegalStateException(
                    "dependencies." + scope.canonical() + " already contains \"" + module + "\"");
        }

        if (block != null) {
            // Insert before the closing brace, preserving its indentation/trailing content.
            lines.add(block.closingLine, declaration);
        } else {
            // Append a new block at end of file.
            ensureTrailingBlankLine(lines);
            lines.add("dependencies." + scope.canonical() + " {");
            lines.add(declaration);
            lines.add("}");
        }
        return join(lines);
    }

    public static String removeDependency(String content, Scope scope, String module) {
        List<String> lines = splitPreservingTerminator(content);
        BlockRange block = findScopeBlock(lines, scope);
        if (block == null) {
            throw new IllegalStateException(
                    "dependencies." + scope.canonical() + " block not found in build.jk");
        }
        int hit = findDeclaration(lines, block, module);
        if (hit < 0) {
            throw new IllegalStateException(
                    "\"" + module + "\" not found in dependencies." + scope.canonical());
        }
        lines.remove(hit);
        return join(lines);
    }

    // --- internals ---------------------------------------------------------

    /**
     * @return the line index range of the {@code dependencies.<scope> { ... }}
     *         block, or {@code null} if not found.
     */
    private static BlockRange findScopeBlock(List<String> lines, Scope scope) {
        String target = scope.canonical();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = BLOCK_HEADER.matcher(lines.get(i));
            if (m.matches() && m.group(1).equals(target)) {
                int closing = findMatchingClose(lines, i);
                if (closing < 0) continue;
                return new BlockRange(i, closing);
            }
        }
        return null;
    }

    /** Find the line containing the matching {@code }} for an opening at {@code openLine}. */
    private static int findMatchingClose(List<String> lines, int openLine) {
        int depth = 0;
        for (int i = openLine; i < lines.size(); i++) {
            String line = stripComments(lines.get(i));
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    private static boolean alreadyDeclared(List<String> lines, BlockRange block, String module) {
        if (block == null) return false;
        return findDeclaration(lines, block, module) >= 0;
    }

    private static int findDeclaration(List<String> lines, BlockRange block, String module) {
        Pattern decl = Pattern.compile(
                "^\\s*\"" + Pattern.quote(module) + "\"\\s*=.*$");
        for (int i = block.openingLine + 1; i < block.closingLine; i++) {
            if (decl.matcher(lines.get(i)).matches()) return i;
        }
        return -1;
    }

    private static String stripComments(String line) {
        // HOCON line comments: # or //
        int hash = line.indexOf('#');
        int slashes = line.indexOf("//");
        int cut = -1;
        if (hash >= 0) cut = hash;
        if (slashes >= 0 && (cut < 0 || slashes < cut)) cut = slashes;
        return cut >= 0 ? line.substring(0, cut) : line;
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

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private record BlockRange(int openingLine, int closingLine) {}
}
