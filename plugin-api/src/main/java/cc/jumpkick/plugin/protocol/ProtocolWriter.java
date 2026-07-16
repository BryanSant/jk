// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.protocol;

import java.io.PrintStream;

/**
 * Plugin-side emitter for the host&lt;-&gt;plugin protocol: writes one JSON object per line, each
 * prefixed with the plugin's marker so the host can tell structured protocol lines apart from
 * passthrough chatter (anything the underlying tool writes to stdout). Pair with {@link
 * Ndjson#quote} to encode string values.
 *
 * <p>Lines are flushed eagerly so the host sees progress as it happens.
 */
public final class ProtocolWriter {

    private final PrintStream out;
    private final String prefix;

    public ProtocolWriter(PrintStream out, String prefix) {
        this.out = out;
        this.prefix = prefix;
    }

    /** The marker every emitted line carries (e.g. {@code ##JKGIT:}). */
    public String prefix() {
        return prefix;
    }

    /**
     * Emit one already-encoded JSON object as a prefixed protocol line. Synchronised so plugins that
     * emit from multiple threads (e.g. a test runner firing listener events from parallel test
     * threads) can't interleave a half-written line.
     */
    public synchronized void emit(String json) {
        out.println(prefix + json);
        out.flush();
    }
}
