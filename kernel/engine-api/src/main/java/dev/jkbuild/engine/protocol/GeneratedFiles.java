// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.engine.protocol;

import dev.jkbuild.plugin.protocol.Ndjson;
import java.util.List;

/**
 * The engine's answer to a {@link EngineProtocol#GENERATE_REQUEST}: a full-model generator's file
 * payloads (docs/thin-client-plan.md Milestone B — export/IDE generation needs the parsed project
 * + lock, so content generation runs engine-side; the client owns overwrite guards, disk writes,
 * and console output). {@code paths} are absolute; {@code contents} is parallel to it. {@code
 * notes} carry fidelity report lines as {@code severity|message} ({@code error} or {@code
 * warning}).
 *
 * <p>{@code error} non-null means generation could not run; its message is ready to print.
 */
public record GeneratedFiles(String error, List<String> paths, List<String> contents, List<String> notes) {

    public static GeneratedFiles error(String message) {
        return new GeneratedFiles(message, List.of(), List.of(), List.of());
    }

    public String encode() {
        return "{\"t\":\"" + EngineProtocol.GENERATE_ACK + "\""
                + ",\"error\":" + (error == null ? "null" : Ndjson.quote(error))
                + ",\"paths\":" + EngineProtocol.quoteArray(paths)
                + ",\"contents\":" + EngineProtocol.quoteArray(contents)
                + ",\"notes\":" + EngineProtocol.quoteArray(notes)
                + "}";
    }

    public static GeneratedFiles decode(String line) {
        return new GeneratedFiles(
                Ndjson.str(line, "error"),
                Ndjson.strArray(line, "paths"),
                Ndjson.strArray(line, "contents"),
                Ndjson.strArray(line, "notes"));
    }
}
