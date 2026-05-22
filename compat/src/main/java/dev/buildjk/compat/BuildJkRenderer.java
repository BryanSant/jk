// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.compat;

import dev.buildjk.model.BuildJk;
import dev.buildjk.model.Dependency;
import dev.buildjk.model.RepositorySpec;
import dev.buildjk.model.Scope;
import dev.buildjk.model.VersionSelector;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Renders a {@link BuildJk} value as a HOCON {@code build.jk} document.
 *
 * <p>The output uses the canonical layout the editor/parser recognise:
 * {@code project { ... }} block, dotted {@code dependencies.<scope>} blocks,
 * dotted {@code repositories.<name>} blocks. Modules are alphabetised within
 * each scope so the output is deterministic; declared order is not preserved
 * (importers regenerate the file each run).
 *
 * <p>Profiles, features and workspaces are not yet emitted — those land with
 * slice D's Tier-2 importer.
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
        return sb.toString();
    }

    private static void renderWorkspace(StringBuilder sb, BuildJk buildJk) {
        if (!buildJk.isWorkspaceRoot()) return;
        sb.append('\n');
        sb.append("workspace {\n");
        sb.append("  members = [");
        List<String> members = buildJk.workspace().members();
        for (int i = 0; i < members.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quote(members.get(i)));
        }
        sb.append("]\n");
        sb.append("}\n");
    }

    private static void renderProject(StringBuilder sb, BuildJk.Project p) {
        sb.append("project {\n");
        sb.append("  group    = ").append(quote(p.group())).append('\n');
        sb.append("  artifact = ").append(quote(p.artifact())).append('\n');
        sb.append("  version  = ").append(quote(p.version())).append('\n');
        sb.append("  jdk      = ").append(quote(p.jdk())).append('\n');
        sb.append("}\n");
    }

    private static void renderRepositories(StringBuilder sb, List<RepositorySpec> repos) {
        if (repos.isEmpty()) return;
        sb.append('\n');
        for (RepositorySpec r : repos) {
            sb.append("repositories.").append(safeBlockName(r.name()))
                    .append(" { url = ").append(quote(r.url().toString())).append(" }\n");
        }
    }

    private static void renderDependencies(StringBuilder sb, BuildJk buildJk) {
        Map<Scope, List<Dependency>> byScope = buildJk.dependencies().byScope();
        // Render in a stable, user-recognisable scope order.
        for (Scope scope : new Scope[] {
                Scope.PLATFORM, Scope.MAIN, Scope.RUNTIME, Scope.PROVIDED, Scope.TEST, Scope.PROCESSOR}) {
            List<Dependency> deps = byScope.get(scope);
            if (deps == null || deps.isEmpty()) continue;
            sb.append('\n');
            sb.append("dependencies.").append(scope.canonical()).append(" {\n");
            // Sort by module for determinism.
            Map<String, Dependency> sorted = new TreeMap<>();
            for (Dependency d : deps) sorted.put(d.module(), d);
            for (Dependency d : sorted.values()) {
                sb.append("  ").append(quote(d.module()))
                        .append(" = ").append(quote(renderVersion(d.version()))).append('\n');
            }
            sb.append("}\n");
        }
    }

    private static String renderVersion(VersionSelector v) {
        return v.raw();
    }

    /**
     * Repo names in HOCON dotted form must be plain identifiers; quote
     * when the upstream name contains chars HOCON would refuse.
     */
    private static String safeBlockName(String name) {
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
