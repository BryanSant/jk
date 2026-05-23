// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits a {@code jk.toml} document from {@link InitInputs}. The shape mirrors
 * the canonical TOML schema: {@code [project]}, then {@code [dependencies]}
 * with arrays of {@code "group:artifact:version"} strings per scope.
 */
public final class InitBuildJkRenderer {

    private InitBuildJkRenderer() {}

    public static String render(InitInputs inputs) {
        var sb = new StringBuilder();
        sb.append("[project]\n");
        sb.append("group    = \"").append(inputs.group()).append("\"\n");
        sb.append("artifact = \"").append(inputs.name()).append("\"\n");
        sb.append("version  = \"0.1.0\"\n");
        sb.append("jdk      = \"").append(inputs.jdk()).append("\"\n");
        sb.append("language = \"").append(inputs.lang().hoconValue()).append("\"\n");
        if (inputs.main().isPresent()) {
            sb.append("main     = \"").append(inputs.main().get()).append("\"\n");
        }
        if (inputs.shadow()) {
            sb.append("shadow   = true\n");
        }
        if (inputs.nativeImage()) {
            sb.append("native   = true\n");
        }

        var picks = resolvePicks(inputs.deps());
        if (picks.isEmpty()) return sb.toString();

        sb.append('\n');
        sb.append("[dependencies]\n");
        renderScope(sb, "main", picks.getOrDefault("main", Map.of()));
        renderScope(sb, "processor", picks.getOrDefault("processor", Map.of()));
        renderScope(sb, "provided", picks.getOrDefault("provided", Map.of()));
        return sb.toString();
    }

    private static void renderScope(StringBuilder sb, String scope, Map<String, String> entries) {
        if (entries.isEmpty()) return;
        sb.append(scope).append(" = [\n");
        entries.forEach((coord, version) ->
                sb.append("  \"").append(coord).append(':').append(version).append("\",\n"));
        sb.append("]\n");
    }

    /**
     * Group selected dep ids by scope. Returns a nested map of
     * scope -> (group:artifact -> version), preserving insertion order so
     * generated files have a stable shape.
     */
    private static Map<String, Map<String, String>> resolvePicks(List<String> deps) {
        Map<String, Map<String, String>> byScope = new LinkedHashMap<>();
        for (var id : deps) {
            var entries = InitScaffolder.CURATED_DEPS.get(id);
            if (entries == null) continue;
            for (var e : entries) {
                byScope
                        .computeIfAbsent(e.scope(), _ -> new LinkedHashMap<>())
                        .put(e.coord(), e.version());
            }
        }
        return byScope;
    }
}
