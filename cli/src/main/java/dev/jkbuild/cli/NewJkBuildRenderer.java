// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits a {@code jk.toml} document from {@link NewInputs}. The schema reflects
 * the post-migration form:
 * <ul>
 *   <li>{@code jdk = <major>} — integer feature release; resolved to a concrete
 *       install identifier in {@code jk.lock}.</li>
 *   <li>{@code java = <major>} or {@code kotlin = <major>} (mutually exclusive)
 *       — the compiler-level language indicator.</li>
 * </ul>
 */
public final class NewJkBuildRenderer {

    /** Current Kotlin major when language=kotlin. Bumped when we settle on a new floor. */
    private static final int DEFAULT_KOTLIN_MAJOR = 2;

    private NewJkBuildRenderer() {}

    public static String render(NewInputs inputs) {
        var sb = new StringBuilder();
        sb.append("[project]\n");
        sb.append("group    = \"").append(inputs.group()).append("\"\n");
        sb.append("artifact = \"").append(inputs.artifact()).append("\"\n");
        sb.append("version  = \"0.1.0\"\n");
        sb.append("jdk      = ").append(inputs.jdkMajor()).append('\n');
        switch (inputs.lang()) {
            case JAVA -> sb.append("java     = ").append(inputs.jdkMajor()).append('\n');
            case KOTLIN -> sb.append("kotlin   = ").append(DEFAULT_KOTLIN_MAJOR).append('\n');
        }
        if (inputs.main().isPresent()) {
            sb.append("main     = \"").append(inputs.main().get()).append("\"\n");
        }
        if (inputs.shadow()) {
            sb.append("shadow   = true\n");
        }
        if (inputs.nativeImage()) {
            sb.append("native   = true\n");
        }
        if (inputs.kotlinCompact()) {
            sb.append("compact  = true\n");
        }
        inputs.kotlinModuleName().ifPresent(m ->
                sb.append("module   = \"").append(m).append("\"\n"));

        var picks = resolvePicks(inputs.deps());
        if (picks.isEmpty()) return sb.toString();

        sb.append('\n');
        sb.append("[dependencies]\n");
        renderScope(sb, "main", picks.getOrDefault("main", Map.of()));
        renderScope(sb, "processor", picks.getOrDefault("processor", Map.of()));
        renderScope(sb, "provided", picks.getOrDefault("provided", Map.of()));
        renderScope(sb, "test", picks.getOrDefault("test", Map.of()));
        return sb.toString();
    }

    private static void renderScope(StringBuilder sb, String scope, Map<String, String> entries) {
        if (entries.isEmpty()) return;
        sb.append(scope).append(" = [\n");
        // `@major` is the floating caret form — pinned bumps stay opt-in.
        entries.forEach((coord, version) ->
                sb.append("  \"").append(coord).append('@').append(version).append("\",\n"));
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
            var entries = NewScaffolder.CURATED_DEPS.get(id);
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
