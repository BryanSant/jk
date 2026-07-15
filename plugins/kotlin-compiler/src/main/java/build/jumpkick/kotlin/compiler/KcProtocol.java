// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.kotlin.compiler;

import build.jumpkick.plugin.protocol.Ndjson;
import build.jumpkick.plugin.protocol.ProtocolWriter;

/**
 * Typed view over the shared {@link ProtocolWriter} for the kotlin-compiler worker's two message
 * kinds. The prefix and line framing live in the {@code ProtocolWriter} (built by {@code
 * PluginWorkerMain} from the plugin manifest); this just builds the JSON for each event.
 */
final class KcProtocol {

    private final ProtocolWriter out;

    KcProtocol(ProtocolWriter out) {
        this.out = out;
    }

    /** A compiler diagnostic: {@code {"t":"diag","sev":"ERROR","msg":"..."}}. */
    void diagnostic(String severity, String message) {
        out.emit("{\"t\":\"diag\",\"sev\":\"" + severity + "\",\"msg\":" + Ndjson.quote(message) + "}");
    }

    /** The terminal outcome: {@code {"t":"result","status":"COMPILATION_SUCCESS"}}. */
    void result(String status) {
        out.emit("{\"t\":\"result\",\"status\":\"" + status + "\"}");
    }
}
