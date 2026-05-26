// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.GoalView;
import dev.jkbuild.run.PhaseStatus;

import java.time.Duration;
import java.time.Instant;

/**
 * Stable wire format for goal events as one-JSON-object-per-line text.
 * Shared by {@link NdjsonListener} (writes to stdout for
 * {@code --output json}) and {@link EventLogListener} (writes to
 * {@code <cacheRoot>/runs/<ts>.ndjson} always). Centralising the shape
 * here means anyone parsing jk's event stream has one schema to keep
 * compatible with, no matter which channel they read it from.
 */
final class NdjsonShape {

    private NdjsonShape() {}

    static String goalStart(GoalView v) {
        return "{\"ts\":" + nowMillis()
                + ",\"type\":\"goal-start\""
                + ",\"goal\":" + js(v.goalName())
                + ",\"denominator\":" + v.denominator()
                + ",\"phases\":" + v.phasesTotal()
                + "}";
    }

    static String phaseStart(String phase, int scope) {
        return "{\"ts\":" + nowMillis()
                + ",\"type\":\"phase-start\""
                + ",\"phase\":" + js(phase) + ",\"scope\":" + scope + "}";
    }

    static String progress(String phase, int delta, GoalView v) {
        return "{\"ts\":" + nowMillis()
                + ",\"type\":\"progress\""
                + ",\"phase\":" + js(phase) + ",\"delta\":" + delta
                + ",\"numerator\":" + v.numerator()
                + ",\"denominator\":" + v.denominator() + "}";
    }

    static String scopeUpdate(String phase, int delta, GoalView v) {
        return "{\"ts\":" + nowMillis()
                + ",\"type\":\"scope-update\""
                + ",\"phase\":" + js(phase) + ",\"delta\":" + delta
                + ",\"denominator\":" + v.denominator() + "}";
    }

    static String label(String phase, String label) {
        return "{\"ts\":" + nowMillis()
                + ",\"type\":\"label\""
                + ",\"phase\":" + js(phase) + ",\"label\":" + js(label) + "}";
    }

    static String warn(String phase, String code, String msg) {
        return "{\"ts\":" + nowMillis()
                + ",\"type\":\"warn\""
                + ",\"phase\":" + js(phase) + ",\"code\":" + js(code)
                + ",\"message\":" + js(msg) + "}";
    }

    static String error(String phase, String code, String msg) {
        return "{\"ts\":" + nowMillis()
                + ",\"type\":\"error\""
                + ",\"phase\":" + js(phase) + ",\"code\":" + js(code)
                + ",\"message\":" + js(msg) + "}";
    }

    static String phaseFinish(String phase, PhaseStatus status, Duration duration) {
        return "{\"ts\":" + nowMillis()
                + ",\"type\":\"phase-finish\""
                + ",\"phase\":" + js(phase)
                + ",\"status\":" + js(status.name())
                + ",\"duration_ms\":" + duration.toMillis() + "}";
    }

    static String goalFinish(GoalResult r) {
        return "{\"ts\":" + nowMillis()
                + ",\"type\":\"goal-finish\""
                + ",\"goal\":" + js(r.goalName())
                + ",\"success\":" + r.success()
                + ",\"duration_ms\":" + r.duration().toMillis()
                + ",\"warnings\":" + r.warnings().size()
                + ",\"errors\":" + r.errors().size() + "}";
    }

    /** Bare-bones JSON string escaping — adequate for diagnostic payloads. */
    static String js(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    static long nowMillis() {
        return Instant.now().toEpochMilli();
    }
}
