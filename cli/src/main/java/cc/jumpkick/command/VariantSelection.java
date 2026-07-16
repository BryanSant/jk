// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The variant flags every artifact-producing command shares ({@code --release},
 * {@code --variant <dim>=<value>}) and their wiring: {@link #selector} builds the compact wire
 * selection (`release|contentType=demo`), {@link #install} additionally resolves the client-side
 * {@code env:} values (signing secrets — the engine names them via ProjectInfo.envRefs) and
 * parks both on the ambient {@link cc.jumpkick.config.SessionContext} session. Engine request
 * writers attach the session's selection to their request lines, and in-process pipeline factories
 * inherit it through {@code BuildPipelines.Inputs}' session default — so a command opts in with
 * two lines: options() in its flag list, install() before it builds.
 */
final class VariantSelection {

    private VariantSelection() {}

    static List<Opt> options() {
        return List.of(
                Opt.flag("Build the release build type (shorthand for --variant build-type=release).", "--release"),
                Opt.value(
                                "<dim>=<value>",
                                "Select a variant value (repeatable; bare <value> when one dimension).",
                                "--variant")
                        .repeat());
    }

    /** The compact wire selection — {@code ""} (defaults) / {@code "release"} / {@code "release|tier=free"}. */
    static String selector(Invocation in) {
        String buildType = null;
        var dims = new LinkedHashMap<String, String>();
        for (String raw : in.values("variant")) {
            for (String part : raw.split(",")) {
                if (part.isBlank()) continue;
                int eq = part.indexOf('=');
                String dim = eq > 0 ? part.substring(0, eq).trim() : "*";
                String value = (eq > 0 ? part.substring(eq + 1) : part).trim();
                if ("build-type".equals(dim)) buildType = value;
                else dims.put(dim, value);
            }
        }
        if (in.isSet("release")) buildType = "release";
        StringBuilder sel = new StringBuilder(buildType == null ? "" : buildType);
        for (var e : dims.entrySet()) {
            if (sel.length() > 0) sel.append('|');
            sel.append(e.getKey()).append('=').append(e.getValue());
        }
        return sel.toString();
    }

    /**
     * Resolve the selection + client env and install both on the ambient session. Returns the
     * selector for commands that also thread it explicitly (jk build's request records).
     */
    static String install(Invocation in, Path projectDir) {
        String selector = selector(in);
        Map<String, String> clientEnv = resolveClientEnv(projectDir);
        cc.jumpkick.config.SessionContext.install(
                cc.jumpkick.config.SessionContext.current().withVariant(selector, clientEnv));
        return selector;
    }

    /**
     * The {@code env:}-indirected values plugin configs reference (signing credentials), resolved
     * CLIENT-side — the engine's environment belongs to whichever invocation spawned it. The
     * engine names the vars (ProjectInfo.envRefs); only those set here ride the request.
     */
    static Map<String, String> resolveClientEnv(Path projectDir) {
        var info = BuildCommand.projectInfoOrNull(projectDir);
        if (info == null || info.envRefs().isEmpty()) return Map.of();
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String name : info.envRefs()) {
            String v = System.getenv(name);
            if (v != null) resolved.put(name, v);
        }
        return resolved;
    }
}
