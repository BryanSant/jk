// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.compat;

import dev.buildjk.model.BuildJk;
import dev.buildjk.model.Dependency;
import dev.buildjk.model.GitRefSpec;
import dev.buildjk.model.GitSource;
import dev.buildjk.model.RepositorySpec;
import dev.buildjk.model.Scope;
import dev.buildjk.model.VersionSelector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Renders a {@link BuildJk} value as a TOML {@code jk.toml} document.
 *
 * <p>Output shape:
 * <ul>
 *   <li>{@code [project]} block — group/artifact/version/jdk/language always
 *       emitted; main/shadow/native emitted only when set.</li>
 *   <li>{@code [workspace]} block when this is a workspace root.</li>
 *   <li>{@code [repositories]} block with per-name URL strings.</li>
 *   <li>{@code [dependencies]} block with per-scope arrays of
 *       {@code "group:artifact:version"} strings. Coords with a non-null
 *       {@link Dependency#gitSource()} are emitted as {@code "group:artifact"}
 *       and a matching entry in {@code [sources]}.</li>
 *   <li>{@code [sources]} block — only when at least one dep is git-sourced.</li>
 * </ul>
 *
 * <p>Modules are alphabetised within each scope for determinism; declared
 * order is not preserved.
 */
public final class BuildJkRenderer {

    private BuildJkRenderer() {}

    public static String render(BuildJk buildJk) {
        Objects.requireNonNull(buildJk, "buildJk");
        StringBuilder sb = new StringBuilder();
        renderProject(sb, buildJk.project());
        renderWorkspace(sb, buildJk);
        renderRepositories(sb, buildJk.repositories());
        renderDependencies(sb, buildJk);
        renderSources(sb, buildJk);
        return sb.toString();
    }

    private static void renderProject(StringBuilder sb, BuildJk.Project p) {
        sb.append("[project]\n");
        sb.append("group    = ").append(quote(p.group())).append('\n');
        sb.append("artifact = ").append(quote(p.artifact())).append('\n');
        sb.append("version  = ").append(quote(p.version())).append('\n');
        if (p.jdk() != null) {
            sb.append("jdk      = ").append(quote(p.jdk())).append('\n');
        }
        sb.append("language = ").append(quote(p.language())).append('\n');
        if (p.main() != null) {
            sb.append("main     = ").append(quote(p.main())).append('\n');
        }
        if (p.shadow()) sb.append("shadow   = true\n");
        if (p.nativeImage()) sb.append("native   = true\n");
    }

    private static void renderWorkspace(StringBuilder sb, BuildJk buildJk) {
        if (!buildJk.isWorkspaceRoot()) return;
        sb.append('\n');
        sb.append("[workspace]\n");
        sb.append("members = [");
        List<String> members = buildJk.workspace().members();
        for (int i = 0; i < members.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quote(members.get(i)));
        }
        sb.append("]\n");
    }

    private static void renderRepositories(StringBuilder sb, List<RepositorySpec> repos) {
        if (repos.isEmpty()) return;
        sb.append('\n');
        sb.append("[repositories]\n");
        for (RepositorySpec r : repos) {
            sb.append(safeKey(r.name())).append(" = ")
                    .append(quote(r.url().toString())).append('\n');
        }
    }

    private static void renderDependencies(StringBuilder sb, BuildJk buildJk) {
        Map<Scope, List<Dependency>> byScope = buildJk.dependencies().byScope();
        if (byScope.isEmpty()) return;
        boolean any = false;
        StringBuilder body = new StringBuilder();
        for (Scope scope : new Scope[] {
                Scope.PLATFORM, Scope.MAIN, Scope.RUNTIME, Scope.PROVIDED, Scope.TEST, Scope.PROCESSOR}) {
            List<Dependency> deps = byScope.get(scope);
            if (deps == null || deps.isEmpty()) continue;
            any = true;
            Map<String, Dependency> sorted = new TreeMap<>();
            for (Dependency d : deps) sorted.put(d.module(), d);
            body.append(scope.canonical()).append(" = [\n");
            for (Dependency d : sorted.values()) {
                body.append("  ").append(quote(formatDep(d))).append(",\n");
            }
            body.append("]\n");
        }
        if (!any) return;
        sb.append('\n');
        sb.append("[dependencies]\n");
        sb.append(body);
    }

    private static String formatDep(Dependency d) {
        if (d.isGit()) {
            // Git deps have no version in the scope array — the override lives
            // in [sources].
            return d.module();
        }
        // Pinned → `:` form; floating → `@` form.
        String sep = d.pinned() ? ":" : "@";
        return d.module() + sep + d.version().raw();
    }

    private static void renderSources(StringBuilder sb, BuildJk buildJk) {
        Map<String, GitSource> sources = collectGitSources(buildJk);
        if (sources.isEmpty()) return;
        sb.append('\n');
        sb.append("[sources]\n");
        for (Map.Entry<String, GitSource> entry : sources.entrySet()) {
            sb.append(quote(entry.getKey())).append(" = ");
            sb.append(formatGitSource(entry.getValue())).append('\n');
        }
    }

    private static Map<String, GitSource> collectGitSources(BuildJk buildJk) {
        Map<String, GitSource> result = new TreeMap<>();
        for (List<Dependency> deps : buildJk.dependencies().byScope().values()) {
            for (Dependency d : deps) {
                if (d.isGit() && !result.containsKey(d.module())) {
                    result.put(d.module(), d.gitSource());
                }
            }
        }
        return result;
    }

    private static String formatGitSource(GitSource s) {
        List<String> parts = new ArrayList<>();
        parts.add("git = " + quote(s.originalUrl()));
        switch (s.ref()) {
            case GitRefSpec.Tag t -> parts.add("tag = " + quote(t.name()));
            case GitRefSpec.Branch b -> parts.add("branch = " + quote(b.name()));
            case GitRefSpec.Rev r -> parts.add("rev = " + quote(r.sha()));
        }
        if (s.path() != null) parts.add("path = " + quote(s.path()));
        if (!s.submodules()) parts.add("submodules = false");
        if (s.verifySignature()) parts.add("verify-signed = true");
        return "{ " + String.join(", ", parts) + " }";
    }

    /**
     * TOML bare-key check — a name with only [A-Za-z0-9_-] can be emitted
     * unquoted; anything else gets wrapped in a quoted key.
     */
    private static String safeKey(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) {
                return quote(name);
            }
        }
        return name;
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
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
        sb.append('"');
        return sb.toString();
    }
}
