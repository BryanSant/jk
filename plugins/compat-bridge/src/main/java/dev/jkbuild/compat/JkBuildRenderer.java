// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compat;

import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.GitSource;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Renders a {@link JkBuild} value as a TOML {@code jk.toml} document in the v0.7 name-as-key
 * sub-table format.
 *
 * <p>Output shape:
 *
 * <ul>
 *   <li>{@code [project]} block — group/artifact/version/jdk/language always emitted.
 *   <li>{@code [application]} block, emitted only when {@code jkBuild.application()} is present.
 *   <li>{@code [native]} block, emitted only when {@code jkBuild.nativeConfig()} is present.
 *   <li>{@code [workspace]} block when this is a workspace root.
 *   <li>{@code [repositories]} block with per-name URL strings.
 *   <li>Top-level per-scope sections ({@code [dependencies]} for MAIN, {@code [test-dependencies]},
 *       …, per {@link Scope#tomlSection()}); each entry is {@code <lib> = { group = "...", name =
 *       "...", version = "..." }}. The {@code name} field is omitted when it equals the {@code
 *       <lib>} key. Workspace placeholders ({@code module} starts with {@code workspace:}) collapse
 *       to {@code <lib>.workspace = true}. Git sources emit the inline-table form with {@code
 *       git}/{@code tag|branch|rev} fields — no {@code group}/{@code name}, which {@code
 *       JkBuildParser} rejects alongside a source override. Path sources emit {@code path = "..."}
 *       the same way.
 * </ul>
 *
 * <p>Within a scope, deps are alphabetised by the short library key for determinism; declared order
 * is not preserved.
 */
public final class JkBuildRenderer {

    private JkBuildRenderer() {}

    public static String render(JkBuild jkBuild) {
        Objects.requireNonNull(jkBuild, "jkBuild");
        StringBuilder sb = new StringBuilder();
        renderProject(sb, jkBuild.project());
        renderSpringBoot(sb, jkBuild.pluginConfig(JkBuild.SPRING_BOOT_ID).orElse(null));
        renderApplication(sb, jkBuild.application().orElse(null));
        renderNative(sb, jkBuild.nativeConfig().orElse(null));
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
            sb.append(quote(e.getKey()))
                    .append(" = ")
                    .append(quote(e.getValue()))
                    .append('\n');
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
        if (p.m2install()) sb.append("m2install = true\n");
    }

    /** {@code [spring-boot]} table — its presence switches packaging to the Boot layout. */
    private static void renderSpringBoot(StringBuilder sb, dev.jkbuild.model.PluginConfig boot) {
        if (boot == null) return;
        sb.append("\n[spring-boot]\n");
        sb.append("version = ").append(quote(boot.string("version"))).append('\n');
        boot.bool("aot").ifPresent(aot -> sb.append("aot = ").append(aot).append('\n'));
        if (boot.bool("build-info", false)) sb.append("build-info = true\n");
        if (!boot.bool("include-tools", true)) sb.append("include-tools = false\n");
        var aotArgs = boot.stringList("aot-args");
        if (!aotArgs.isEmpty()) {
            sb.append("aot-args = [");
            for (int i = 0; i < aotArgs.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(quote(aotArgs.get(i)));
            }
            sb.append("]\n");
        }
    }

    /** {@code [application]} table — its presence alone marks the project as an application. */
    private static void renderApplication(StringBuilder sb, JkBuild.Application app) {
        if (app == null) return;
        sb.append("\n[application]\n");
        if (app.main() != null) sb.append("main       = ").append(quote(app.main())).append('\n');
        if (app.shadowJar()) sb.append("shadow-jar = true\n");
    }

    /** {@code [native]} table — its presence alone marks the project as native-image-eligible. */
    private static void renderNative(StringBuilder sb, JkBuild.NativeConfig nc) {
        if (nc == null) return;
        sb.append("\n[native]\n");
        if (nc.mainClass() != null) sb.append("main-class = ").append(quote(nc.mainClass())).append('\n');
        if (nc.name() != null) sb.append("name       = ").append(quote(nc.name())).append('\n');
        if (!nc.args().isEmpty()) {
            sb.append("args       = [");
            for (int i = 0; i < nc.args().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(quote(nc.args().get(i)));
            }
            sb.append("]\n");
        }
        // graal defaults to "native" at parse time when [native] is declared and the key is
        // omitted — only emit it when it differs, so a round-trip stays minimal.
        if (nc.graal() != null && !nc.graal().equals("native")) {
            sb.append("graal      = ").append(quote(nc.graal())).append('\n');
        }
        if (nc.always()) sb.append("always     = true\n");
    }

    private static void renderWorkspace(StringBuilder sb, JkBuild jkBuild) {
        if (!jkBuild.isWorkspaceRoot()) return;
        sb.append('\n');
        sb.append("[workspace]\n");
        sb.append("modules = [");
        List<String> modules = jkBuild.workspace().modules();
        for (int i = 0; i < modules.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quote(modules.get(i)));
        }
        sb.append("]\n");
    }

    private static void renderRepositories(StringBuilder sb, List<RepositorySpec> repos) {
        if (repos.isEmpty()) return;
        sb.append('\n');
        sb.append("[repositories]\n");
        for (RepositorySpec r : repos) {
            sb.append(safeKey(r.name()))
                    .append(" = ")
                    .append(quote(r.url().toString()))
                    .append('\n');
        }
    }

    private static void renderDependencies(StringBuilder sb, JkBuild jkBuild) {
        Map<Scope, List<Dependency>> byScope = jkBuild.dependencies().byScope();
        if (byScope.isEmpty()) return;
        for (Scope scope : new Scope[] {
            Scope.PLATFORM, Scope.MAIN, Scope.RUNTIME, Scope.DEV, Scope.TEST_DEV, Scope.PROVIDED, Scope.TEST,
            Scope.PROCESSOR
        }) {
            List<Dependency> deps = byScope.get(scope);
            if (deps == null || deps.isEmpty()) continue;
            // Sort by short name for determinism. The dep `name` is the
            // user-facing manifest key; module ordering is no longer the
            // identifier.
            Map<String, Dependency> sorted = new TreeMap<>();
            for (Dependency d : deps) sorted.put(d.library(), d);

            sb.append('\n');
            sb.append('[').append(scope.tomlSection()).append("]\n");
            for (Dependency d : sorted.values()) {
                sb.append(renderEntry(d)).append('\n');
            }
        }
    }

    /**
     * Render a single dep entry line. Three forms:
     *
     * <ul>
     *   <li>Workspace placeholder (module starts with {@code workspace:}): {@code <lib>.workspace =
     *       true}
     *   <li>Git-sourced: {@code <lib> = { group = "...", name?, git = "...", tag|branch|rev = "..."
     *       }}
     *   <li>Versioned: {@code <lib> = { group = "...", name?, version = "..." }}
     * </ul>
     */
    private static String renderEntry(Dependency d) {
        if (d.isWorkspace()) {
            return safeKey(d.library()) + ".workspace = true";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(safeKey(d.library())).append(" = { ");
        if (d.isGit()) {
            // Pure discovery: JkBuildParser rejects `group`/`name` alongside `git` — the
            // coordinate and version always come from the cloned repo's own jk.toml.
            GitSource s = d.gitSource();
            sb.append("git = ").append(quote(s.originalUrl()));
            switch (s.ref()) {
                case GitRefSpec.Tag t -> sb.append(", tag = ").append(quote(t.name()));
                case GitRefSpec.Branch b -> sb.append(", branch = ").append(quote(b.name()));
                case GitRefSpec.Rev r -> sb.append(", rev = ").append(quote(r.sha()));
            }
            if (s.path() != null) sb.append(", path = ").append(quote(s.path()));
            if (!s.submodules()) sb.append(", submodules = false");
            if (s.verifySignature()) sb.append(", verify-signed = true");
        } else {
            sb.append("group = ").append(quote(d.group()));
            if (!d.name().equals(d.library())) {
                sb.append(", name = ").append(quote(d.name()));
            }
            // Platform-managed (versionless — a BOM pins it): no version clause; the
            // parser re-derives the platform-managed marker from its absence.
            if (!d.isPlatformManaged()) {
                sb.append(", version = ").append(quote(versionLiteral(d.version())));
            }
        }
        sb.append(" }");
        return sb.toString();
    }

    /**
     * Convert a {@link VersionSelector} into the literal that goes inside {@code version = "..."}.
     * Exact selectors keep their {@code =} prefix so a re-parse via {@code parseFloating} round-trips
     * back to {@code Exact}; other selectors emit their decoration as written.
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
     * TOML bare-key check — a name with only [A-Za-z0-9_-] can be emitted unquoted; anything else
     * gets wrapped in a quoted key.
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
