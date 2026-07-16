// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.util.MiniJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes {@link BuildRecord} as JSON for the journal's {@code record.json}. Pure
 * shape-mapping: this class converts {@code BuildRecord} to/from the {@link MiniJson} object
 * model (Map/List/scalars) — serialization, parsing, and escaping all live in {@link MiniJson},
 * the engine's single JSON home.
 */
final class Json {

    private Json() {}

    // ---------------------------------------------------------------- write

    static String write(BuildRecord r) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", r.id());
        o.put("buildNumber", r.buildNumber());
        o.put("schema", r.schema());
        o.put("kind", r.kind());
        o.put("dir", r.dir());
        o.put("coord", r.coord());
        o.put("startedAt", r.startedAt());
        o.put("finishedAt", r.finishedAt());
        o.put("millis", r.millis());
        o.put("success", r.success());
        o.put("cancelled", r.cancelled());
        o.put("exitCode", r.exitCode());
        o.put("jkVersion", r.jkVersion());

        if (r.tests() == null) {
            o.put("tests", null);
        } else {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("total", r.tests().total());
            t.put("succeeded", r.tests().succeeded());
            t.put("failed", r.tests().failed());
            t.put("skipped", r.tests().skipped());
            o.put("tests", t);
        }

        List<Object> modules = new ArrayList<>();
        for (BuildRecord.Module m : r.modules()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("coord", m.coord());
            mm.put("dir", m.dir());
            mm.put("success", m.success());
            mm.put("exitCode", m.exitCode());
            mm.put("millis", m.millis());
            mm.put("steps", stepList(m.steps()));
            modules.add(mm);
        }
        o.put("modules", modules);

        o.put("steps", stepList(r.steps()));

        List<Object> diagnostics = new ArrayList<>();
        for (BuildRecord.Diag d : r.diagnostics()) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("severity", d.severity());
            dm.put("dir", d.dir());
            dm.put("step", d.step());
            dm.put("code", d.code());
            dm.put("message", d.message());
            dm.put("test", d.test());
            dm.put("exceptionClass", d.exceptionClass());
            diagnostics.add(dm);
        }
        o.put("diagnostics", diagnostics);

        o.put("trigger", r.trigger());
        o.put("commit", r.commit());

        if (r.benefit() == null) {
            o.put("benefit", null);
        } else {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("estimatedUncachedMillis", r.benefit().estimatedUncachedMillis());
            b.put("savedMillis", r.benefit().savedMillis());
            b.put("coveredSkips", r.benefit().coveredSkips());
            b.put("totalSkips", r.benefit().totalSkips());
            o.put("benefit", b);
        }

        return MiniJson.writePretty(o);
    }

    private static List<Object> stepList(List<BuildRecord.Step> steps) {
        List<Object> out = new ArrayList<>(steps.size());
        for (BuildRecord.Step p : steps) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.name());
            pm.put("phase", p.phase());
            pm.put("status", p.status());
            pm.put("millis", p.millis());
            out.add(pm);
        }
        return out;
    }

    // ---------------------------------------------------------------- read

    @SuppressWarnings("unchecked")
    static BuildRecord read(String json) {
        Object root = MiniJson.parse(json);
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
                    str(mm, "coord"),
                    str(mm, "dir"),
                    bool(mm, "success"),
                    (int) lng(mm, "exitCode"),
                    lng(mm, "millis"),
                    readSteps(mm)));
        }

        List<BuildRecord.Step> steps = readSteps(o);

        BuildRecord.CacheBenefit benefit = null;
        if (o.get("benefit") instanceof Map<?, ?> bm) {
            Map<String, Object> b = (Map<String, Object>) bm;
            benefit = new BuildRecord.CacheBenefit(
                    lng(b, "estimatedUncachedMillis"), lng(b, "savedMillis"),
                    lng(b, "coveredSkips"), lng(b, "totalSkips"));
        }

        List<BuildRecord.Diag> diagnostics = new ArrayList<>();
        for (Object e : arr(o, "diagnostics")) {
            Map<String, Object> dm = (Map<String, Object>) e;
            diagnostics.add(new BuildRecord.Diag(
                    str(dm, "severity"),
                    str(dm, "dir"),
                    str(dm, "step"),
                    str(dm, "code"),
                    str(dm, "message"),
                    str(dm, "test"),
                    str(dm, "exceptionClass")));
        }

        return new BuildRecord(
                str(o, "id"),
                lng(o, "buildNumber"),
                (int) lng(o, "schema"),
                str(o, "kind"),
                str(o, "dir"),
                str(o, "coord"),
                lng(o, "startedAt"),
                lng(o, "finishedAt"),
                lng(o, "millis"),
                bool(o, "success"),
                bool(o, "cancelled"),
                (int) lng(o, "exitCode"),
                str(o, "jkVersion"),
                tests,
                modules,
                steps,
                diagnostics,
                str(o, "trigger"),
                str(o, "commit"),
                benefit);
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

    /** Read a {@code "steps"} array from a record or a module object. */
    @SuppressWarnings("unchecked")
    private static List<BuildRecord.Step> readSteps(Map<String, Object> o) {
        List<BuildRecord.Step> steps = new ArrayList<>();
        for (Object e : arr(o, "steps")) {
            Map<String, Object> pm = (Map<String, Object>) e;
            steps.add(new BuildRecord.Step(str(pm, "name"), str(pm, "phase"), str(pm, "status"), lng(pm, "millis")));
        }
        return steps;
    }
}
