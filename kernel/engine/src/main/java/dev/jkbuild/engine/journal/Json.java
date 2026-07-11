// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.engine.journal;

import dev.jkbuild.plugin.protocol.Ndjson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes {@link BuildRecord} as JSON for the journal's {@code record.json}. The engine's
 * {@link dev.jkbuild.engine.http.JsonOut} is deliberately flat (scalars + one string array) and
 * cannot express the record's {@code modules}/{@code phases}/{@code diagnostics} arrays-of-objects,
 * so this class carries a small nested writer; reading goes through the engine's general
 * {@link dev.jkbuild.util.MiniJson} parser (one JSON reader engine-wide — the CLI stays on its
 * size-constrained shape-specific scanners). String escaping reuses {@link Ndjson#quote} so a
 * string means the same thing on every jk wire.
 */
final class Json {

    private Json() {}

    // ---------------------------------------------------------------- write

    static String write(BuildRecord r) {
        StringBuilder b = new StringBuilder(512);
        b.append("{\n");
        scalar(b, "id", r.id()).append(",\n");
        b.append("  \"schema\": ").append(r.schema()).append(",\n");
        scalar(b, "kind", r.kind()).append(",\n");
        scalar(b, "dir", r.dir()).append(",\n");
        scalar(b, "coord", r.coord()).append(",\n");
        num(b, "startedAt", r.startedAt()).append(",\n");
        num(b, "finishedAt", r.finishedAt()).append(",\n");
        num(b, "millis", r.millis()).append(",\n");
        bool(b, "success", r.success()).append(",\n");
        bool(b, "cancelled", r.cancelled()).append(",\n");
        num(b, "exitCode", r.exitCode()).append(",\n");
        scalar(b, "jkVersion", r.jkVersion()).append(",\n");

        b.append("  \"tests\": ");
        if (r.tests() == null) {
            b.append("null");
        } else {
            BuildRecord.Tests t = r.tests();
            b.append("{\"total\":").append(t.total())
                    .append(",\"succeeded\":").append(t.succeeded())
                    .append(",\"failed\":").append(t.failed())
                    .append(",\"skipped\":").append(t.skipped()).append('}');
        }
        b.append(",\n");

        b.append("  \"modules\": [");
        for (int i = 0; i < r.modules().size(); i++) {
            BuildRecord.Module m = r.modules().get(i);
            if (i > 0) b.append(',');
            b.append("\n    {\"coord\":").append(q(m.coord()))
                    .append(",\"dir\":").append(q(m.dir()))
                    .append(",\"success\":").append(m.success())
                    .append(",\"exitCode\":").append(m.exitCode())
                    .append(",\"millis\":").append(m.millis())
                    .append(",\"phases\":");
            appendPhases(b, m.phases());
            b.append('}');
        }
        b.append(r.modules().isEmpty() ? "],\n" : "\n  ],\n");

        b.append("  \"phases\": ");
        appendPhases(b, r.phases());
        b.append(",\n");

        b.append("  \"diagnostics\": [");
        for (int i = 0; i < r.diagnostics().size(); i++) {
            BuildRecord.Diag d = r.diagnostics().get(i);
            if (i > 0) b.append(',');
            b.append("\n    {\"severity\":").append(q(d.severity()))
                    .append(",\"dir\":").append(q(d.dir()))
                    .append(",\"phase\":").append(q(d.phase()))
                    .append(",\"code\":").append(q(d.code()))
                    .append(",\"message\":").append(q(d.message()))
                    .append(",\"test\":").append(q(d.test()))
                    .append(",\"exceptionClass\":").append(q(d.exceptionClass())).append('}');
        }
        b.append(r.diagnostics().isEmpty() ? "]\n" : "\n  ]\n");

        b.append("}\n");
        return b.toString();
    }

    private static StringBuilder scalar(StringBuilder b, String key, String value) {
        return b.append("  ").append(Ndjson.quote(key)).append(": ").append(q(value));
    }

    private static StringBuilder num(StringBuilder b, String key, long value) {
        return b.append("  ").append(Ndjson.quote(key)).append(": ").append(value);
    }

    private static StringBuilder bool(StringBuilder b, String key, boolean value) {
        return b.append("  ").append(Ndjson.quote(key)).append(": ").append(value);
    }

    /** A string value or the JSON literal {@code null}. */
    private static String q(String s) {
        return s == null ? "null" : Ndjson.quote(s);
    }

    /** A compact JSON array of phase objects — reused for a module's chain and the top-level list. */
    private static void appendPhases(StringBuilder b, List<BuildRecord.Phase> phases) {
        b.append('[');
        for (int i = 0; i < phases.size(); i++) {
            BuildRecord.Phase p = phases.get(i);
            if (i > 0) b.append(',');
            b.append("{\"name\":").append(q(p.name()))
                    .append(",\"status\":").append(q(p.status()))
                    .append(",\"millis\":").append(p.millis()).append('}');
        }
        b.append(']');
    }

    // ---------------------------------------------------------------- read

    @SuppressWarnings("unchecked")
    static BuildRecord read(String json) {
        Object root = dev.jkbuild.util.MiniJson.parse(json);
        if (!(root instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("record.json is not a JSON object");
        }
        Map<String, Object> o = (Map<String, Object>) m;

        BuildRecord.Tests tests = null;
        if (o.get("tests") instanceof Map<?, ?> tm) {
            Map<String, Object> t = (Map<String, Object>) tm;
            tests = new BuildRecord.Tests(lng(t, "total"), lng(t, "succeeded"), lng(t, "failed"), lng(t, "skipped"));
        }

        List<BuildRecord.Module> modules = new ArrayList<>();
        for (Object e : arr(o, "modules")) {
            Map<String, Object> mm = (Map<String, Object>) e;
            modules.add(new BuildRecord.Module(
                    str(mm, "coord"), str(mm, "dir"), bool(mm, "success"),
                    (int) lng(mm, "exitCode"), lng(mm, "millis"), readPhases(mm)));
        }

        List<BuildRecord.Phase> phases = readPhases(o);

        List<BuildRecord.Diag> diagnostics = new ArrayList<>();
        for (Object e : arr(o, "diagnostics")) {
            Map<String, Object> dm = (Map<String, Object>) e;
            diagnostics.add(new BuildRecord.Diag(
                    str(dm, "severity"), str(dm, "dir"), str(dm, "phase"), str(dm, "code"),
                    str(dm, "message"), str(dm, "test"), str(dm, "exceptionClass")));
        }

        return new BuildRecord(
                str(o, "id"), (int) lng(o, "schema"), str(o, "kind"), str(o, "dir"), str(o, "coord"),
                lng(o, "startedAt"), lng(o, "finishedAt"), lng(o, "millis"),
                bool(o, "success"), bool(o, "cancelled"), (int) lng(o, "exitCode"), str(o, "jkVersion"),
                tests, modules, phases, diagnostics);
    }

    private static String str(Map<String, Object> o, String key) {
        return o.get(key) instanceof String s ? s : null;
    }

    private static long lng(Map<String, Object> o, String key) {
        return o.get(key) instanceof Number n ? n.longValue() : 0L;
    }

    private static boolean bool(Map<String, Object> o, String key) {
        return Boolean.TRUE.equals(o.get(key));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> arr(Map<String, Object> o, String key) {
        return o.get(key) instanceof List<?> l ? (List<Object>) l : List.of();
    }

    /** Read a {@code "phases"} array from a record or a module object. */
    @SuppressWarnings("unchecked")
    private static List<BuildRecord.Phase> readPhases(Map<String, Object> o) {
        List<BuildRecord.Phase> phases = new ArrayList<>();
        for (Object e : arr(o, "phases")) {
            Map<String, Object> pm = (Map<String, Object>) e;
            phases.add(new BuildRecord.Phase(str(pm, "name"), str(pm, "status"), lng(pm, "millis")));
        }
        return phases;
    }
}
