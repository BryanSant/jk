// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.engine.protocol;

import build.jumpkick.plugin.protocol.Ndjson;
import java.util.List;

/**
 * The engine's {@code jk why} answer ({@link EngineProtocol#WHY_REQUEST}): the lock artifacts
 * matching the query, and every provenance path to each — flat parallel lists per the wire
 * discipline. {@code pathOwners.get(i)} is the index (as a string) into {@code matches} that
 * {@code paths.get(i)} belongs to; each path is {@code module@version} steps joined with
 * {@code >}. The client owns matching-free rendering: split and style.
 *
 * <p>{@code error} non-null means the lookup could not run; its message is ready to print.
 */
public record WhyReport(
        String error,
        List<String> matchNames,
        List<String> matchVersions,
        List<String> pathOwners,
        List<String> paths) {

    public static WhyReport error(String message) {
        return new WhyReport(message, List.of(), List.of(), List.of(), List.of());
    }

    public String encode() {
        return "{\"t\":\"" + EngineProtocol.WHY_ACK + "\""
                + ",\"error\":" + (error == null ? "null" : Ndjson.quote(error))
                + ",\"matchNames\":" + EngineProtocol.quoteArray(matchNames)
                + ",\"matchVersions\":" + EngineProtocol.quoteArray(matchVersions)
                + ",\"pathOwners\":" + EngineProtocol.quoteArray(pathOwners)
                + ",\"paths\":" + EngineProtocol.quoteArray(paths)
                + "}";
    }

    public static WhyReport decode(String line) {
        return new WhyReport(
                Ndjson.str(line, "error"),
                Ndjson.strArray(line, "matchNames"),
                Ndjson.strArray(line, "matchVersions"),
                Ndjson.strArray(line, "pathOwners"),
                Ndjson.strArray(line, "paths"));
    }
}
