// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.plugin.protocol.Jsonl;
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
 *
 * <p>{@code mainIssue} non-empty: the {@code error} is specifically an unresolved-entry-point
 * failure from the main-class scan ({@code cc.jumpkick.layout.MainClassScanner}) — {@code
 * "missing"} (nothing found) or {@code "ambiguous"} (several found) — so the client can render its
 * own styled sentence instead of printing {@code error} verbatim. Empty for every other error.
 */
public record ExecPlan(
        String error,
        String mainIssue,
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
        List<String> libPaths,
        String deployCommand) {

    public static ExecPlan error(String kind, String message) {
        return error(kind, message, "");
    }

    /** As {@link #error(String, String)}, tagging the failure as an unresolved main-class scan. */
    public static ExecPlan error(String kind, String message, String mainIssue) {
        return new ExecPlan(
                message, mainIssue, kind, List.of(), "", "", "", false, false, List.of(), List.of(), List.of(), "", "",
                "", false, "", "", "", List.of(), List.of(), "");
    }

    public String encode() {
        return "{\"t\":\"" + EngineProtocol.EXEC_PLAN_ACK + "\""
                + ",\"error\":" + (error == null ? "null" : Jsonl.quote(error))
                + ",\"mainIssue\":" + Jsonl.quote(mainIssue)
                + ",\"kind\":" + Jsonl.quote(kind)
                + ",\"argv\":" + EngineProtocol.quoteArray(argv)
                + ",\"workingDir\":" + Jsonl.quote(workingDir)
                + ",\"display\":" + Jsonl.quote(display)
                + ",\"javaHome\":" + Jsonl.quote(javaHome)
                + ",\"hotReload\":" + hotReload
                + ",\"devtoolsInjected\":" + devtoolsInjected
                + ",\"watchRoots\":" + EngineProtocol.quoteArray(watchRoots)
                + ",\"linkSrcs\":" + EngineProtocol.quoteArray(linkSrcs)
                + ",\"linkDests\":" + EngineProtocol.quoteArray(linkDests)
                + ",\"launcherPath\":" + Jsonl.quote(launcherPath)
                + ",\"launcherScript\":" + Jsonl.quote(launcherScript)
                + ",\"binPath\":" + Jsonl.quote(binPath)
                + ",\"boot\":" + boot
                + ",\"mainJar\":" + Jsonl.quote(mainJar)
                + ",\"tier\":" + Jsonl.quote(tier)
                + ",\"mainClass\":" + Jsonl.quote(mainClass)
                + ",\"libNames\":" + EngineProtocol.quoteArray(libNames)
                + ",\"libPaths\":" + EngineProtocol.quoteArray(libPaths)
                + ",\"deployCommand\":" + Jsonl.quote(deployCommand)
                + "}";
    }

    public static ExecPlan decode(String line) {
        return new ExecPlan(
                Jsonl.str(line, "error"),
                orEmpty(Jsonl.str(line, "mainIssue")),
                orEmpty(Jsonl.str(line, "kind")),
                Jsonl.strArray(line, "argv"),
                orEmpty(Jsonl.str(line, "workingDir")),
                orEmpty(Jsonl.str(line, "display")),
                orEmpty(Jsonl.str(line, "javaHome")),
                Jsonl.bool(line, "hotReload", false),
                Jsonl.bool(line, "devtoolsInjected", false),
                Jsonl.strArray(line, "watchRoots"),
                Jsonl.strArray(line, "linkSrcs"),
                Jsonl.strArray(line, "linkDests"),
                orEmpty(Jsonl.str(line, "launcherPath")),
                orEmpty(Jsonl.str(line, "launcherScript")),
                orEmpty(Jsonl.str(line, "binPath")),
                Jsonl.bool(line, "boot", false),
                orEmpty(Jsonl.str(line, "mainJar")),
                orEmpty(Jsonl.str(line, "tier")),
                orEmpty(Jsonl.str(line, "mainClass")),
                Jsonl.strArray(line, "libNames"),
                Jsonl.strArray(line, "libPaths"),
                orEmptyDeploy(Jsonl.str(line, "deployCommand")));
    }

    private static String orEmptyDeploy(String s) {
        return s == null ? "" : s;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
