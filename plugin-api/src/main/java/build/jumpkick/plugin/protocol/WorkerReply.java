// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.protocol;

import java.util.List;
import java.util.Map;

/**
 * Builds {@link WorkerProtocol} reply lines (worker→engine) as NDJSON strings. A worker emits them
 * through its {@link ProtocolWriter} ({@code out.emit(WorkerReply.finding(...))}), so no worker
 * hand-writes the wire JSON. Value types are serialized by shape: {@link String} quoted, numbers
 * and booleans raw.
 */
public final class WorkerReply {

    private WorkerReply() {}

    /** A free-text progress label. */
    public static String label(String text) {
        return "{\"t\":\"label\",\"text\":" + Ndjson.quote(text) + "}";
    }

    /** Numeric progress. */
    public static String progress(int done, int total) {
        return "{\"t\":\"progress\",\"done\":" + done + ",\"total\":" + total + "}";
    }

    /** One user-facing output line (command ops). */
    public static String out(String line) {
        return "{\"t\":\"out\",\"line\":" + Ndjson.quote(line) + "}";
    }

    /** A structured compiler/format diagnostic; {@code file} may be null, {@code line}/{@code col} 0 when unknown. */
    public static String diagnostic(String sev, String file, int line, int col, String msg) {
        StringBuilder b = new StringBuilder("{\"t\":\"diagnostic\",\"sev\":").append(Ndjson.quote(sev));
        if (file != null) b.append(",\"file\":").append(Ndjson.quote(file));
        if (line > 0) b.append(",\"line\":").append(line);
        if (col > 0) b.append(",\"col\":").append(col);
        b.append(",\"msg\":").append(Ndjson.quote(msg)).append('}');
        return b.toString();
    }

    /** Annotation-processing provenance: a generated file and its originating sources. */
    public static String provenance(String generated, List<String> sources) {
        StringBuilder b = new StringBuilder("{\"t\":\"provenance\",\"gen\":").append(Ndjson.quote(generated))
                .append(",\"src\":[");
        for (int i = 0; i < sources.size(); i++) {
            if (i > 0) b.append(',');
            b.append(Ndjson.quote(sources.get(i)));
        }
        return b.append("]}").toString();
    }

    /** An audit vulnerability finding. */
    public static String finding(String module, String version, String id, String severity, String summary) {
        return "{\"t\":\"finding\",\"module\":" + Ndjson.quote(module)
                + ",\"version\":" + Ndjson.quote(version)
                + ",\"id\":" + Ndjson.quote(id)
                + ",\"severity\":" + Ndjson.quote(severity)
                + ",\"summary\":" + Ndjson.quote(summary) + "}";
    }

    /** A formatter per-file outcome. */
    public static String file(String path, String status, String msg) {
        StringBuilder b = new StringBuilder("{\"t\":\"file\",\"path\":").append(Ndjson.quote(path))
                .append(",\"status\":").append(Ndjson.quote(status));
        if (msg != null) b.append(",\"msg\":").append(Ndjson.quote(msg));
        return b.append('}').toString();
    }

    /** A compat-import wrote-a-file note. */
    public static String wrote(String path) {
        return "{\"t\":\"wrote\",\"path\":" + Ndjson.quote(path) + "}";
    }

    /** A terminal typed result payload; {@code fields} serialized by shape. */
    public static String result(Map<String, Object> fields) {
        StringBuilder b = new StringBuilder("{\"t\":\"result\"");
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            b.append(',').append(Ndjson.quote(e.getKey())).append(':').append(value(e.getValue()));
        }
        return b.append('}').toString();
    }

    /** A structured error. */
    public static String error(String code, String message) {
        return "{\"t\":\"error\",\"code\":" + Ndjson.quote(code) + ",\"message\":" + Ndjson.quote(message) + "}";
    }

    /** The terminal marker carrying the worker's exit code. */
    public static String done(int exit) {
        return "{\"t\":\"done\",\"exit\":" + exit + "}";
    }

    private static String value(Object v) {
        if (v instanceof Boolean || v instanceof Number) return String.valueOf(v);
        return Ndjson.quote(String.valueOf(v));
    }
}
