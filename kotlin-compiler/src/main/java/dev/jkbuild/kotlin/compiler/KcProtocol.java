// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.kotlin.compiler;

import dev.jkbuild.plugin.protocol.Ndjson;

import java.io.PrintStream;

/**
 * Emits the {@code jk-kotlin-compiler} worker's protocol lines to the parent jk
 * process: one JSON object per line, each prefixed with {@link #PREFIX}. The
 * parent treats any line without the prefix as passthrough compiler chatter.
 *
 * <p>String escaping is delegated to the shared {@link Ndjson} codec in
 * plugin-api (bundled into this worker jar), so the worker no longer carries its
 * own copy of the escaping algorithm.
 */
final class KcProtocol {

    /** Marks a structured protocol line; mirrors jk-test-runner's {@code ##JK:}. */
    static final String PREFIX = "##JKKC:";

    private final PrintStream out;

    KcProtocol(PrintStream out) {
        this.out = out;
    }

    /** A compiler diagnostic: {@code {"t":"diag","sev":"ERROR","msg":"..."}}. */
    void diagnostic(String severity, String message) {
        out.println(PREFIX + "{\"t\":\"diag\",\"sev\":\"" + severity
                + "\",\"msg\":" + Ndjson.quote(message) + "}");
        out.flush();
    }

    /** The terminal outcome: {@code {"t":"result","status":"COMPILATION_SUCCESS"}}. */
    void result(String status) {
        out.println(PREFIX + "{\"t\":\"result\",\"status\":\"" + status + "\"}");
        out.flush();
    }
}
