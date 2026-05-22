// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits a {@code build.jk} (HOCON) document from {@link InitInputs}. The shape
 * matches the new schema (Task 1): {@code main}/{@code shadow}/{@code native}/
 * {@code language} on the {@code project} block, no {@code bin} key.
 */
public final class InitBuildJkRenderer {

    private InitBuildJkRenderer() {}

    public static String render(InitInputs inputs) {
        var sb = new StringBuilder();
        sb.append("project {\n");
        sb.append("  group    = \"").append(inputs.group()).append("\"\n");
        sb.append("  artifact = \"").append(inputs.name()).append("\"\n");
        sb.append("  version  = \"0.1.0\"\n");
        sb.append("  jdk      = \"").append(inputs.jdk()).append("\"\n");
        sb.append("  language = \"").append(inputs.lang().hoconValue()).append("\"\n");
        if (inputs.main().isPresent()) {
            sb.append("  main     = \"").append(inputs.main().get()).append("\"\n");
        }
        if (inputs.shadow()) {
            sb.append("  shadow   = true\n");
        }
        if (inputs.nativeImage()) {
            sb.append("  native   = true\n");
        }
        sb.append("}\n");

        var picks = resolvePicks(inputs.deps());
        var mainScope = picks.getOrDefault("main", Map.of());
        var processorScope = picks.getOrDefault("processor", Map.of());
        var providedScope = picks.getOrDefault("provided", Map.of());

        if (!mainScope.isEmpty()) {
            sb.append('\n');
            sb.append("dependencies.main {\n");
            mainScope.forEach((coord, version) ->
                    sb.append("  \"").append(coord).append("\" = \"").append(version).append("\"\n"));
            sb.append("}\n");
        }
        if (!processorScope.isEmpty()) {
            sb.append('\n');
            sb.append("dependencies.processor {\n");
            processorScope.forEach((coord, version) ->
                    sb.append("  \"").append(coord).append("\" = \"").append(version).append("\"\n"));
            sb.append("}\n");
        }
        if (!providedScope.isEmpty()) {
            sb.append('\n');
            sb.append("dependencies.provided {\n");
            providedScope.forEach((coord, version) ->
                    sb.append("  \"").append(coord).append("\" = \"").append(version).append("\"\n"));
            sb.append("}\n");
        }
        return sb.toString();
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
