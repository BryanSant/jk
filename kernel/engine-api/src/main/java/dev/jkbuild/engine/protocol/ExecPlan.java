// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.engine.protocol;

import dev.jkbuild.plugin.protocol.Ndjson;
import java.util.List;

/**
 * A complete execution plan computed by the engine ({@link EngineProtocol#EXEC_PLAN_REQUEST}) —
 * the thin client's replacement for client-side classpath assembly, install layout, and artifact
 * preference logic (docs/thin-client-plan.md §2.2). The engine decides; the client executes.
 *
 * <p>Kinds and the fields they populate:
 *
 * <ul>
 *   <li>{@code run}: {@code argv} (complete command line), {@code workingDir}, {@code display}.
 *   <li>{@code dev}: run-shaped {@code argv} + {@code hotReload}, {@code devtoolsInjected},
 *       {@code watchRoots}.
 *   <li>{@code install}: {@code linkSrcs → linkDests} (hard-link/copy pairs), {@code
 *       launcherPath} + {@code launcherScript} (empty when {@code argv[0]} is a native binary
 *       linked directly), {@code binPath} (what ends up executable on PATH).
 *   <li>{@code aot-cache}: {@code boot}, {@code mainJar}, {@code tier} (aot|cds), {@code
 *       mainClass}, {@code libNames → libPaths}, {@code display}.
 * </ul>
 *
 * <p>{@code error} non-null: the plan could not be computed (gate failed, missing artifact…);
 * the message is ready to print and doubles as the command's failure output.
 */
public record ExecPlan(
        String error,
        String kind,
        List<String> argv,
        String workingDir,
        String display,
        String javaHome,
        boolean hotReload,
        boolean devtoolsInjected,
        List<String> watchRoots,
        List<String> linkSrcs,
        List<String> linkDests,
        String launcherPath,
        String launcherScript,
        String binPath,
        boolean boot,
        String mainJar,
        String tier,
        String mainClass,
        List<String> libNames,
        List<String> libPaths) {

    public static ExecPlan error(String kind, String message) {
        return new ExecPlan(
                message, kind, List.of(), "", "", "", false, false, List.of(), List.of(), List.of(), "", "", "",
                false, "", "", "", List.of(), List.of());
    }

    public String encode() {
        return "{\"t\":\"" + EngineProtocol.EXEC_PLAN_ACK + "\""
                + ",\"error\":" + (error == null ? "null" : Ndjson.quote(error))
                + ",\"kind\":" + Ndjson.quote(kind)
                + ",\"argv\":" + EngineProtocol.quoteArray(argv)
                + ",\"workingDir\":" + Ndjson.quote(workingDir)
                + ",\"display\":" + Ndjson.quote(display)
                + ",\"javaHome\":" + Ndjson.quote(javaHome)
                + ",\"hotReload\":" + hotReload
                + ",\"devtoolsInjected\":" + devtoolsInjected
                + ",\"watchRoots\":" + EngineProtocol.quoteArray(watchRoots)
                + ",\"linkSrcs\":" + EngineProtocol.quoteArray(linkSrcs)
                + ",\"linkDests\":" + EngineProtocol.quoteArray(linkDests)
                + ",\"launcherPath\":" + Ndjson.quote(launcherPath)
                + ",\"launcherScript\":" + Ndjson.quote(launcherScript)
                + ",\"binPath\":" + Ndjson.quote(binPath)
                + ",\"boot\":" + boot
                + ",\"mainJar\":" + Ndjson.quote(mainJar)
                + ",\"tier\":" + Ndjson.quote(tier)
                + ",\"mainClass\":" + Ndjson.quote(mainClass)
                + ",\"libNames\":" + EngineProtocol.quoteArray(libNames)
                + ",\"libPaths\":" + EngineProtocol.quoteArray(libPaths)
                + "}";
    }

    public static ExecPlan decode(String line) {
        return new ExecPlan(
                Ndjson.str(line, "error"),
                orEmpty(Ndjson.str(line, "kind")),
                Ndjson.strArray(line, "argv"),
                orEmpty(Ndjson.str(line, "workingDir")),
                orEmpty(Ndjson.str(line, "display")),
                orEmpty(Ndjson.str(line, "javaHome")),
                Ndjson.bool(line, "hotReload", false),
                Ndjson.bool(line, "devtoolsInjected", false),
                Ndjson.strArray(line, "watchRoots"),
                Ndjson.strArray(line, "linkSrcs"),
                Ndjson.strArray(line, "linkDests"),
                orEmpty(Ndjson.str(line, "launcherPath")),
                orEmpty(Ndjson.str(line, "launcherScript")),
                orEmpty(Ndjson.str(line, "binPath")),
                Ndjson.bool(line, "boot", false),
                orEmpty(Ndjson.str(line, "mainJar")),
                orEmpty(Ndjson.str(line, "tier")),
                orEmpty(Ndjson.str(line, "mainClass")),
                Ndjson.strArray(line, "libNames"),
                Ndjson.strArray(line, "libPaths"));
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
