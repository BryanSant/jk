// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compat;

import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.GitSource;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Renders a {@link JkBuild} value as a TOML {@code jk.toml} document in
 * the v0.7 name-as-key sub-table format.
 *
 * <p>Output shape:
 * <ul>
 *   <li>{@code [project]} block — group/artifact/version/jdk/language always
 *       emitted; main/shadow/native emitted only when set.</li>
 *   <li>{@code [workspace]} block when this is a workspace root.</li>
 *   <li>{@code [repositories]} block with per-name URL strings.</li>
 *   <li>{@code [dependencies.<scope>]} sub-tables; each entry is
 *       {@code <lib> = { group = "...", name = "...", version = "..." }}.
 *       The {@code name} field is omitted when it equals the {@code <lib>} key. Workspace
 *       placeholders ({@code module} starts with {@code workspace:}) collapse
 *       to {@code <lib>.workspace = true}. Git sources emit the inline-table
 *       form with {@code git}/{@code tag|branch|rev} fields. Path sources emit
 *       {@code path = "..."}.</li>
 * </ul>
 *
 * <p>Within a scope, deps are alphabetised by the short library key for
 * determinism; declared order is not preserved.
 */
public final class JkBuildRenderer {

    private static final String WORKSPACE_PLACEHOLDER_PREFIX = "workspace:";

    private JkBuildRenderer() {}

    public static String render(JkBuild jkBuild) {
        Objects.requireNonNull(jkBuild, "jkBuild");
        StringBuilder sb = new StringBuilder();
        renderProject(sb, jkBuild.project());
        renderManifest(sb, jkBuild.manifest());
        renderWorkspace(sb, jkBuild);
        renderRepositories(sb, jkBuild.repositories());
        renderDependencies(sb, jkBuild);
        return sb.toString();
    }

    /** {@code [manifest]} table — custom jar-manifest attributes, in insertion order. */
    private static void renderManifest(StringBuilder sb, Map<String, String> manifest) {
        if (manifest == null || manifest.isEmpty()) return;
        sb.append("\n[manifest]\n");
        for (Map.Entry<String, String> e : manifest.entrySet()) {
            sb.append(quote(e.getKey())).append(" = ").append(quote(e.getValue())).append('\n');
        }
    }

    private static void renderProject(StringBuilder sb, JkBuild.Project p) {
        sb.append("[project]\n");
        sb.append("group    = ").append(quote(p.group())).append('\n');
        sb.append("name     = ").append(quote(p.name())).append('\n');
        sb.append("version  = ").append(quote(p.version())).append('\n');
        if (p.description() != null) {
            // Longer key than the rest of the block; emit unpadded.
            sb.append("description = ").append(quote(p.description())).append('\n');
        }
        if (p.jdk() != null) {
            sb.append("jdk      = ").append(quote(p.jdk())).append('\n');
        }
        if (p.isKotlin()) {
            sb.append("kotlin   = ").append(quote(versionLiteral(p.kotlin()))).append('\n');
        } else if (p.java() > 0) {
            sb.append("java     = ").append(p.java()).append('\n');
        }
        if (p.main() != null) {
            sb.append("main     = ").append(quote(p.main())).append('\n');
        }
        // application defaults to (main != null); emit only when it differs.
        boolean derivedApplication = p.main() != null;
        if (p.isApplication() != derivedApplication) {
            sb.append("application = ").append(p.isApplication()).append('\n');
        }
        if (p.m2install()) sb.append("m2install = true\n");
        if (p.shadow()) sb.append("shadow   = true\n");
        if (p.nativeImage()) sb.append("native   = true\n");
    }

    private static void renderWorkspace(StringBuilder sb, JkBuild jkBuild) {
        if (!jkBuild.isWorkspaceRoot()) return;
        sb.append('\n');
        sb.append("[workspace]\n");
        sb.append("members = [");
        List<String> members = jkBuild.workspace().members();
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

    private static void renderDependencies(StringBuilder sb, JkBuild jkBuild) {
        Map<Scope, List<Dependency>> byScope = jkBuild.dependencies().byScope();
        if (byScope.isEmpty()) return;
        for (Scope scope : new Scope[] {
                Scope.PLATFORM, Scope.MAIN, Scope.RUNTIME, Scope.PROVIDED, Scope.TEST, Scope.PROCESSOR}) {
            List<Dependency> deps = byScope.get(scope);
            if (deps == null || deps.isEmpty()) continue;
            // Sort by short name for determinism. The dep `name` is the
            // user-facing manifest key; module ordering is no longer the
            // identifier.
            Map<String, Dependency> sorted = new TreeMap<>();
            for (Dependency d : deps) sorted.put(d.library(), d);

            sb.append('\n');
            sb.append("[dependencies.").append(scope.canonical()).append("]\n");
            for (Dependency d : sorted.values()) {
                sb.append(renderEntry(d)).append('\n');
            }
        }
    }

    /**
     * Render a single dep entry line. Three forms:
     * <ul>
     *   <li>Workspace placeholder (module starts with {@code workspace:}):
     *       {@code <lib>.workspace = true}</li>
     *   <li>Git-sourced: {@code <lib> = { group = "...", name?, git = "...", tag|branch|rev = "..." }}</li>
     *   <li>Path-sourced: {@code <lib> = { group = "...", name?, path = "..." }}</li>
     *   <li>Versioned: {@code <lib> = { group = "...", name?, version = "..." }}</li>
     * </ul>
     */
    private static String renderEntry(Dependency d) {
        if (d.module().startsWith(WORKSPACE_PLACEHOLDER_PREFIX)) {
            return safeKey(d.library()) + ".workspace = true";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(safeKey(d.library())).append(" = { group = ").append(quote(d.group()));
        if (!d.name().equals(d.library())) {
            sb.append(", name = ").append(quote(d.name()));
        }
        if (d.isGit()) {
            GitSource s = d.gitSource();
            sb.append(", git = ").append(quote(s.originalUrl()));
            switch (s.ref()) {
                case GitRefSpec.Tag t -> sb.append(", tag = ").append(quote(t.name()));
                case GitRefSpec.Branch b -> sb.append(", branch = ").append(quote(b.name()));
                case GitRefSpec.Rev r -> sb.append(", rev = ").append(quote(r.sha()));
            }
            if (s.path() != null) sb.append(", path = ").append(quote(s.path()));
            if (!s.submodules()) sb.append(", submodules = false");
            if (s.verifySignature()) sb.append(", verify-signed = true");
        } else if (d.isPath()) {
            sb.append(", path = ").append(quote(d.pathSource()));
        } else {
            sb.append(", version = ").append(quote(versionLiteral(d.version())));
        }
        sb.append(" }");
        return sb.toString();
    }

    /**
     * Convert a {@link VersionSelector} into the literal that goes inside
     * {@code version = "..."}. Exact selectors keep their {@code =} prefix so
     * a re-parse via {@code parseFloating} round-trips back to {@code Exact};
     * other selectors emit their decoration as written.
     */
    private static String versionLiteral(VersionSelector v) {
        return switch (v) {
            case VersionSelector.Exact e -> "=" + e.version();
            case VersionSelector.Caret c -> c.version();
            case VersionSelector.Tilde t -> "~" + t.version();
            case VersionSelector.Range r -> r.raw();
            case VersionSelector.Latest l -> "latest";
        };
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
