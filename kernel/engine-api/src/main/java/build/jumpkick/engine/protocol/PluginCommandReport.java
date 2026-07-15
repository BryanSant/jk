// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.engine.protocol;

import build.jumpkick.plugin.protocol.Ndjson;
import java.util.List;

/**
 * The engine's answer to a {@link EngineProtocol#PLUGIN_VERB_REQUEST} (build-plugins plan row
 * 11): whether an installed plugin owns the command for this project, and — when it ran — the
 * worker's user-facing output lines and exit code. {@code found=false} sends the client back to
 * its normal unknown-command help; {@code error} non-null means the command was found but failed to
 * run (message ready to print).
 */
public record PluginCommandReport(String error, boolean found, int exit, List<String> output) {

    public static PluginCommandReport notFound() {
        return new PluginCommandReport(null, false, 0, List.of());
    }

    public static PluginCommandReport error(String message) {
        return new PluginCommandReport(message, true, 1, List.of());
    }

    public String encode() {
        return "{\"t\":\"" + EngineProtocol.PLUGIN_VERB_ACK + "\""
                + ",\"error\":" + (error == null ? "null" : Ndjson.quote(error))
                + ",\"found\":" + found
                + ",\"exit\":" + exit
                + ",\"output\":" + EngineProtocol.quoteArray(output)
                + "}";
    }

    public static PluginCommandReport decode(String line) {
        return new PluginCommandReport(
                Ndjson.str(line, "error"),
                Ndjson.bool(line, "found", false),
                Ndjson.intValue(line, "exit", 0),
                Ndjson.strArray(line, "output"));
    }
}
