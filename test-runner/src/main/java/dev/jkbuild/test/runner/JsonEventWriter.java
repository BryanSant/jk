// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.test.runner;

import java.io.IOException;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NDJSON encoder for the jk-test wire protocol. Each event is rendered as a
 * single line on the supplied {@link PrintStream}, prefixed with
 * {@value #PREFIX} so the parent jk process can distinguish protocol lines
 * from the user's plain test output streaming through the same pipe.
 *
 * <p>Thread-safety: {@link #write} synchronises on {@code this} so listeners
 * firing from different test threads can't interleave a half-written line.
 */
public final class JsonEventWriter implements EventWriter {

    /** Marker every protocol line starts with. Lines without it are passed through to the user verbatim. */
    public static final String PREFIX = "##JK:";

    private final PrintStream out;

    public JsonEventWriter(PrintStream out) {
        this.out = out;
    }

    @Override
    public synchronized void write(EventType type, Map<String, Object> payload) {
        // Preserve insertion order — `e` first reads more naturally on the wire.
        var event = new LinkedHashMap<String, Object>();
        event.put("e", type.wire());
        if (payload != null) event.putAll(payload);

        var sb = new StringBuilder(PREFIX.length() + 64);
        sb.append(PREFIX);
        JsonOut.write(sb, event);
        out.println(sb);
    }

    @Override
    public void flush() {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        out.flush();
        // We don't close stdout — the JVM owns it and we may still want to
        // print final diagnostic lines after close().
    }
}
