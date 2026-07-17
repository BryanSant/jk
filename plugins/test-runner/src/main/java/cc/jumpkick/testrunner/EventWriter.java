// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import java.io.IOException;
import java.util.Map;

/**
 * Strategy for emitting protocol events back to the parent jk process.
 *
 * <p>The wire payloads are fixed (see {@link EventType}); only the on-the-wire <em>encoding</em>
 * varies. Today: {@link JsonEventWriter} (JSONL, one event per line, prefixed with {@code ##JKT:}
 * so the parent can distinguish protocol lines from user test stdout). Tomorrow: a CBOR encoder
 * with the same schema for size/parse-speed wins on huge test suites.
 */
public interface EventWriter extends AutoCloseable {

    /**
     * Emit one event. Implementations are expected to be thread-safe enough to call from JUnit's
     * listener threads.
     */
    void write(EventType type, Map<String, Object> payload) throws IOException;

    /** Flush any buffered output. Called between events; cheap no-op when buffering isn't used. */
    void flush() throws IOException;

    @Override
    void close() throws IOException;
}
