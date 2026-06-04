// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.kotlin.compiler;

import java.io.PrintStream;

/**
 * Emits the worker's protocol lines to the parent jk process: one JSON object
 * per line, each prefixed with {@link #PREFIX}. The parent treats any line
 * without the prefix as passthrough compiler chatter.
 *
 * <p>Dependency-free on purpose (the worker bundles nothing but itself), so we
 * hand-roll the small amount of JSON string escaping we need.
 */
final class Ndjson {

    /** Marks a structured protocol line; mirrors jk-test-runner's {@code ##JK:}. */
    static final String PREFIX = "##JKKC:";

    private final PrintStream out;

    Ndjson(PrintStream out) {
        this.out = out;
    }

    /** A compiler diagnostic: {@code {"t":"diag","sev":"ERROR","msg":"..."}}. */
    void diagnostic(String severity, String message) {
        out.println(PREFIX + "{\"t\":\"diag\",\"sev\":\"" + severity
                + "\",\"msg\":" + quote(message) + "}");
        out.flush();
    }

    /** The terminal outcome: {@code {"t":"result","status":"COMPILATION_SUCCESS"}}. */
    void result(String status) {
        out.println(PREFIX + "{\"t\":\"result\",\"status\":\"" + status + "\"}");
        out.flush();
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
