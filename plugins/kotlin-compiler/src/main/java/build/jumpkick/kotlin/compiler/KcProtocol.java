// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.kotlin.compiler;

import build.jumpkick.plugin.protocol.PluginReply;
import build.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.Map;

/**
 * Typed view over the shared {@link ProtocolWriter} for the kotlin-compiler plugin's messages, on
 * the unified plugin wire. The prefix and line framing live in the {@code ProtocolWriter} (built by
 * {@code PluginMain} from the plugin manifest); this just builds the reply for each event.
 */
final class KcProtocol {

    private final ProtocolWriter out;

    KcProtocol(ProtocolWriter out) {
        this.out = out;
    }

    /** A compiler diagnostic (the BTA logger surfaces text only — no file/line/col). */
    void diagnostic(String severity, String message) {
        out.emit(PluginReply.diagnostic(severity, null, 0, 0, message));
    }

    /** The terminal outcome, e.g. {@code COMPILATION_SUCCESS}. */
    void result(String status) {
        out.emit(PluginReply.result(Map.of("status", status)));
    }

    /** The terminal marker carrying the exit code. */
    void done(int exit) {
        out.emit(PluginReply.done(exit));
    }
}
