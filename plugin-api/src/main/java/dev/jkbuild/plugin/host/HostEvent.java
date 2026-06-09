// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.host;

import dev.jkbuild.plugin.protocol.Ndjson;

import java.util.List;

/**
 * A serialized {@link dev.jkbuild.run.GoalListener} callback, sent from the
 * Workspace Host to the CLI over stdout as a prefixed NDJSON line.
 *
 * <p>Each event type carries exactly the fields its corresponding
 * {@code GoalListener} method takes. The CLI deserializes these back into
 * {@code GoalListener} calls to drive the TUI, run log, etc. — so the
 * listener implementations are unchanged.
 *
 * <p>Format: {@code ##JKH:<json>} where {@code <json>} is a flat JSON object
 * with {@code "e"} as the event discriminator. All fields optional; absent
 * fields default to 0 / "" / false.
 *
 * <pre>
 *   ##JKH:{"e":"goalStart","goal":"build","scope":10}
 *   ##JKH:{"e":"phaseStart","phase":"compile-main","scope":5}
 *   ##JKH:{"e":"progress","phase":"compile-main","delta":1,"num":1,"den":5}
 *   ##JKH:{"e":"label","phase":"compile-main","label":"compiling Widget.java"}
 *   ##JKH:{"e":"output","phase":"compile-main","line":"note: something"}
 *   ##JKH:{"e":"warn","phase":"compile-main","code":"jdk","message":"..."}
 *   ##JKH:{"e":"error","phase":"compile-main","code":"build","message":"..."}
 *   ##JKH:{"e":"phaseFinish","phase":"compile-main","status":"SUCCESS","ms":1234}
 *   ##JKH:{"e":"goalFinish","success":true,"ms":5678}
 *   ##JKH:{"e":"exit","code":0}
 * </pre>
 */
public final class HostEvent {

    public static final String PREFIX = "##JKH:";

    public enum Type {
        GOAL_START, PHASE_START, PROGRESS, SCOPE_UPDATE,
        LABEL, OUTPUT, WARN, ERROR, PHASE_FINISH, GOAL_FINISH,
        EXIT;

        public String wire() {
            return name().toLowerCase().replace('_', '-');
        }

        public static Type fromWire(String s) {
            return valueOf(s.toUpperCase().replace('-', '_'));
        }
    }

    private HostEvent() {}

    // --- writers (Host side) ------------------------------------------------

    public static String goalStart(String goalName, int scope) {
        return PREFIX + "{\"e\":\"goal-start\",\"goal\":" + Ndjson.quote(goalName) + ",\"scope\":" + scope + "}";
    }

    public static String phaseStart(String phase, int scope) {
        return PREFIX + "{\"e\":\"phase-start\",\"phase\":" + Ndjson.quote(phase) + ",\"scope\":" + scope + "}";
    }

    public static String progress(String phase, int delta, int num, int den) {
        return PREFIX + "{\"e\":\"progress\",\"phase\":" + Ndjson.quote(phase)
                + ",\"delta\":" + delta + ",\"num\":" + num + ",\"den\":" + den + "}";
    }

    public static String scopeUpdate(String phase, int delta) {
        return PREFIX + "{\"e\":\"scope-update\",\"phase\":" + Ndjson.quote(phase) + ",\"delta\":" + delta + "}";
    }

    public static String label(String phase, String label) {
        return PREFIX + "{\"e\":\"label\",\"phase\":" + Ndjson.quote(phase) + ",\"label\":" + Ndjson.quote(label) + "}";
    }

    public static String output(String phase, String line) {
        return PREFIX + "{\"e\":\"output\",\"phase\":" + Ndjson.quote(phase) + ",\"line\":" + Ndjson.quote(line) + "}";
    }

    public static String warn(String phase, String code, String message) {
        return PREFIX + "{\"e\":\"warn\",\"phase\":" + Ndjson.quote(phase)
                + ",\"code\":" + Ndjson.quote(code) + ",\"message\":" + Ndjson.quote(message) + "}";
    }

    public static String error(String phase, String code, String message) {
        return PREFIX + "{\"e\":\"error\",\"phase\":" + Ndjson.quote(phase)
                + ",\"code\":" + Ndjson.quote(code) + ",\"message\":" + Ndjson.quote(message) + "}";
    }

    public static String phaseFinish(String phase, String status, long ms) {
        return PREFIX + "{\"e\":\"phase-finish\",\"phase\":" + Ndjson.quote(phase)
                + ",\"status\":" + Ndjson.quote(status) + ",\"ms\":" + ms + "}";
    }

    public static String goalFinish(boolean success, long ms) {
        return PREFIX + "{\"e\":\"goal-finish\",\"success\":" + success + ",\"ms\":" + ms + "}";
    }

    public static String exit(int code) {
        return PREFIX + "{\"e\":\"exit\",\"code\":" + code + "}";
    }

    // --- reader helpers (CLI side) -------------------------------------------

    /** Type field — null if absent/unknown. */
    public static Type type(String json) {
        String e = Ndjson.str(json, "e");
        if (e == null) return null;
        try { return Type.fromWire(e); } catch (IllegalArgumentException ex) { return null; }
    }

    public static String phase(String json)   { return Ndjson.str(json, "phase"); }
    public static String goal(String json)    { return Ndjson.str(json, "goal"); }
    public static String status(String json)  { return Ndjson.str(json, "status"); }
    public static String code(String json)    { return Ndjson.str(json, "code"); }
    public static String message(String json) { return Ndjson.str(json, "message"); }
    public static String label(String json)   { return Ndjson.str(json, "label"); }
    public static String line(String json)    { return Ndjson.str(json, "line"); }
    public static int scope(String json)      { return Ndjson.intValue(json, "scope", 0); }
    public static int delta(String json)      { return Ndjson.intValue(json, "delta", 0); }
    public static int num(String json)        { return Ndjson.intValue(json, "num", 0); }
    public static int den(String json)        { return Ndjson.intValue(json, "den", 1); }
    public static boolean success(String json){ return Ndjson.bool(json, "success", false); }
    public static long ms(String json)        { return Ndjson.longValue(json, "ms", 0L); }
    public static int exitCode(String json)   { return Ndjson.intValue(json, "code", 0); }
}
