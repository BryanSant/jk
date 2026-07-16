// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.library.LibraryCatalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits a {@code jk.toml} document from {@link NewInputs}. The schema reflects the v0.7 name-as-key
 * sub-table form:
 *
 * <pre>{@code
 * [project]
 * group    = "..."
 * name     = "..."
 * version  = "0.1.0"
 * jdk      = "25"
 * java     = 25
 *
 * [test-dependencies]
 * junit-jupiter = { group = "org.junit.jupiter", version = "6.1.0" }
 * }</pre>
 *
 * <ul>
 *   <li>{@code jdk = "<spec>"} — a JDK spec string: a bare major ({@code "25"}) or, only when the
 *       user passed {@code --jdk <vendor>-<major>}, a vendor-hinted pin ({@code "corretto-25"}).
 *       Resolved to a concrete install identifier in {@code jk.lock}.
 *   <li>{@code java = <major>} (integer) or {@code kotlin = "<version>"} (compiler version string)
 *       — mutually exclusive language indicator.
 * </ul>
 */
public final class NewJkBuildRenderer {

    /**
     * Default Kotlin compiler version selector when language=kotlin. Floating (caret) so {@code jk
     * lock} pins it to the latest compatible release.
     */
    private static final String DEFAULT_KOTLIN_VERSION = cc.jumpkick.kotlin.KotlinResolver.DEFAULT_VERSION;

    private NewJkBuildRenderer() {}

    public static String render(NewInputs inputs) {
        var sb = new StringBuilder();
        sb.append("[project]\n");
        sb.append("name     = \"").append(inputs.name()).append("\"\n");
        sb.append("group    = \"").append(inputs.group()).append("\"\n");
        sb.append("version  = \"0.1.0\"\n");
        sb.append("jdk      = \"").append(inputs.jdk()).append("\"\n");
        switch (inputs.lang()) {
            case JAVA -> sb.append("java     = ").append(inputs.javaRelease()).append('\n');
            case KOTLIN ->
                sb.append("kotlin   = \"").append(DEFAULT_KOTLIN_VERSION).append("\"\n");
        }
        if (inputs.layout() != null && !inputs.layout().isBlank() && !"auto".equalsIgnoreCase(inputs.layout())) {
            sb.append("layout   = \"").append(inputs.layout().toLowerCase()).append("\"\n");
        }
        inputs.kotlinModuleName()
                .ifPresent(m -> sb.append("module   = \"").append(m).append("\"\n"));

        if (inputs.main().isPresent() || inputs.shadow()) {
            sb.append("\n[application]\n");
            if (inputs.main().isPresent()) {
                sb.append("main       = \"").append(inputs.main().get()).append("\"\n");
            }
            if (inputs.shadow()) {
                sb.append("shadow-jar = true\n");
            }
        }
        if (inputs.nativeImage()) {
            sb.append("\n[native]\n");
            sb.append("always     = true\n");
        }

        // Plugin scaffolds (--spring) append their own tables engine-side (the plugin's
        // [scaffold] fragments) — this renderer emits only jk-core content.

        var picks = resolvePicks(inputs.deps());
        if (picks.isEmpty()) return sb.toString();

        // Emit scopes in the order they show up in the curated catalog: the
        // wizard's display order should roughly match the rendered file.
        renderScope(sb, "main", picks.getOrDefault("main", List.of()));
        renderScope(sb, "processor", picks.getOrDefault("processor", List.of()));
        renderScope(sb, "provided", picks.getOrDefault("provided", List.of()));
        renderScope(sb, "test", picks.getOrDefault("test", List.of()));
        return sb.toString();
    }

    private static void renderScope(StringBuilder sb, String scope, List<NewScaffolder.CuratedEntry> entries) {
        if (entries.isEmpty()) return;
        sb.append('\n');
        String sectionHeader = scope.equals("main") ? "dependencies" : scope + "-dependencies";
        sb.append("[").append(sectionHeader).append("]\n");
        for (var e : entries) {
            sb.append(formatEntry(e)).append('\n');
        }
    }

    /**
     * Render a single curated dep. The short name is the artifactId (the part after the colon in
     * {@code group:artifact}).
     *
     * <p>When that short name resolves through the bundled library catalog to this exact coordinate,
     * emit the Cargo-style one-liner {@code name = "latest"} — the catalog supplies group/artifact
     * and the resolver floats to the newest release. Otherwise fall back to the explicit inline table
     * {@code { group = "...", version = "..." }} using the curated major (bare-string version is
     * caret-floating per the v1 default — {@code ^1} → 1.x.x).
     */
    private static String formatEntry(NewScaffolder.CuratedEntry e) {
        int colon = e.coord().indexOf(':');
        String group = e.coord().substring(0, colon);
        String artifact = e.coord().substring(colon + 1);
        var hit = LibraryCatalog.bundled().lookup(artifact).orElse(null);
        if (hit != null && hit.group().equals(group) && hit.artifact().equals(artifact)) {
            return artifact + " = \"latest\"";
        }
        return artifact + " = { group = \"" + group + "\", version = \"" + e.version() + "\" }";
    }

    /**
     * Group selected dep ids by scope. Returns a nested map of scope -> curated entries, preserving
     * insertion order so generated files have a stable shape and de-duping by short name (artifactId)
     * within a scope.
     */
    private static Map<String, List<NewScaffolder.CuratedEntry>> resolvePicks(List<String> deps) {
        Map<String, Map<String, NewScaffolder.CuratedEntry>> byScope = new LinkedHashMap<>();
        for (var id : deps) {
            var entries = NewScaffolder.CURATED_DEPS.get(id);
            if (entries == null) continue;
            for (var e : entries) {
                String shortName = e.coord().substring(e.coord().indexOf(':') + 1);
                byScope.computeIfAbsent(e.scope(), _ -> new LinkedHashMap<>()).putIfAbsent(shortName, e);
            }
        }
        Map<String, List<NewScaffolder.CuratedEntry>> out = new LinkedHashMap<>();
        byScope.forEach((scope, entries) -> out.put(scope, List.copyOf(entries.values())));
        return out;
    }
}
