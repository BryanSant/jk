// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.engine.protocol;

import dev.jkbuild.plugin.protocol.Ndjson;
import java.util.List;

/**
 * The engine's answer to a {@link EngineProtocol#PLUGIN_VERB_REQUEST} (build-plugins plan row
 * 11): whether an installed plugin owns the verb for this project, and — when it ran — the
 * worker's user-facing output lines and exit code. {@code found=false} sends the client back to
 * its normal unknown-command help; {@code error} non-null means the verb was found but failed to
 * run (message ready to print).
 */
public record PluginVerbReport(String error, boolean found, int exit, List<String> output) {

    public static PluginVerbReport notFound() {
        return new PluginVerbReport(null, false, 0, List.of());
    }

    public static PluginVerbReport error(String message) {
        return new PluginVerbReport(message, true, 1, List.of());
    }

    public String encode() {
        return "{\"t\":\"" + EngineProtocol.PLUGIN_VERB_ACK + "\""
                + ",\"error\":" + (error == null ? "null" : Ndjson.quote(error))
                + ",\"found\":" + found
                + ",\"exit\":" + exit
                + ",\"output\":" + EngineProtocol.quoteArray(output)
                + "}";
    }

    public static PluginVerbReport decode(String line) {
        return new PluginVerbReport(
                Ndjson.str(line, "error"),
                Ndjson.bool(line, "found", false),
                Ndjson.intValue(line, "exit", 0),
                Ndjson.strArray(line, "output"));
    }
}
