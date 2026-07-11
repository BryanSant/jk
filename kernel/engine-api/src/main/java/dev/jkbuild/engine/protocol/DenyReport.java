// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.engine.protocol;

import dev.jkbuild.plugin.protocol.Ndjson;
import java.util.List;

/**
 * The engine's one-shot deny-policy check ({@link EngineProtocol#DENY_CHECK_REQUEST}) — the thin
 * client's replacement for the client-side policy parse + lock read + check (docs/thin-client-plan.md
 * Milestone B). The {@code [deny]} block is user-authored jk.toml, so its interpretation must be
 * engine-side: a fail-soft client scan that misread exotic TOML would yield a silently
 * <em>permissive</em> policy — the one wrong answer a policy gate must never give. Violations ride
 * as three parallel string lists, per the flat wire discipline.
 *
 * <p>{@code error} non-null means the check could not run (parse failure, unreadable lock); its
 * message is ready to print and every other field is defaulted.
 */
public record DenyReport(
        String error, int checked, List<String> modules, List<String> versions, List<String> reasons) {

    public static DenyReport error(String message) {
        return new DenyReport(message, 0, List.of(), List.of(), List.of());
    }

    public int violationCount() {
        return modules.size();
    }

    public String encode() {
        return "{\"t\":\"" + EngineProtocol.DENY_CHECK_ACK + "\""
                + ",\"error\":" + (error == null ? "null" : Ndjson.quote(error))
                + ",\"checked\":" + checked
                + ",\"modules\":" + EngineProtocol.quoteArray(modules)
                + ",\"versions\":" + EngineProtocol.quoteArray(versions)
                + ",\"reasons\":" + EngineProtocol.quoteArray(reasons)
                + "}";
    }

    public static DenyReport decode(String line) {
        return new DenyReport(
                Ndjson.str(line, "error"),
                Ndjson.intValue(line, "checked", 0),
                Ndjson.strArray(line, "modules"),
                Ndjson.strArray(line, "versions"),
                Ndjson.strArray(line, "reasons"));
    }
}
