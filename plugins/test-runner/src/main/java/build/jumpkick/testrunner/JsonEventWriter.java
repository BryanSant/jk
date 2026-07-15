// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.testrunner;

import build.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NDJSON encoder for the jk-test wire protocol. Each event is rendered as a single JSON object and
 * handed to the shared {@link ProtocolWriter}, which frames it with the plugin's prefix so the
 * parent jk process can distinguish protocol lines from the user's plain test output streaming
 * through the same pipe.
 *
 * <p>Thread-safety: {@link #write} synchronises on {@code this}, and {@code ProtocolWriter.emit}
 * synchronises too, so listeners firing from different test threads can't interleave a half-written
 * line.
 */
public final class JsonEventWriter implements EventWriter {

    private final ProtocolWriter out;

    public JsonEventWriter(ProtocolWriter out) {
        this.out = out;
    }

    @Override
    public synchronized void write(EventType type, Map<String, Object> payload) {
        // Preserve insertion order — `e` first reads more naturally on the wire.
        var event = new LinkedHashMap<String, Object>();
        event.put("e", type.wire());
        if (payload != null) event.putAll(payload);
        out.emit(JsonOut.string(event));
    }

    @Override
    public void flush() {
        // ProtocolWriter flushes per emit; nothing buffered here.
    }

    @Override
    public void close() {
        // We don't close stdout — the JVM owns it and we may still want to
        // print final diagnostic lines after close().
    }
}
