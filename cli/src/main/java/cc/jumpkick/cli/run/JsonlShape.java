// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.plugin.protocol.Jsonl;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.PipelineView;
import cc.jumpkick.run.StepStatus;
import java.time.Duration;
import java.time.Instant;

/**
 * Stable wire format for pipeline events as one-JSON-object-per-line text. Shared by {@link
 * JsonlListener} (writes to stdout for {@code --output json}) and {@link EventLogListener} (writes
 * to {@code <cacheRoot>/runs/<ts>.jsonl} always). Centralising the shape here means anyone parsing
 * jk's event stream has one schema to keep compatible with, no matter which channel they read it
 * from.
 */
final class JsonlShape {

    private JsonlShape() {}

    static String pipelineStart(PipelineView v) {
        return "{\"ts\":"
                + nowMillis()
                + ",\"type\":\"pipeline-start\""
                + ",\"pipeline\":"
                + js(v.pipelineName())
                + ",\"denominator\":"
                + v.denominator()
                + ",\"steps\":"
                + v.stepsTotal()
                + "}";
    }

    static String stepStart(String step, String phase, int ticks) {
        return "{\"ts\":"
                + nowMillis()
                + ",\"type\":\"step-start\""
                + ",\"step\":"
                + js(step)
                + ",\"phase\":"
                + js(phase)
                + ",\"ticks\":"
                + ticks
                + "}";
    }

    static String progress(String step, int delta, PipelineView v) {
        return "{\"ts\":"
                + nowMillis()
                + ",\"type\":\"progress\""
                + ",\"step\":"
                + js(step)
                + ",\"delta\":"
                + delta
                + ",\"numerator\":"
                + v.numerator()
                + ",\"denominator\":"
                + v.denominator()
                + "}";
    }

    static String tickUpdate(String step, int delta, PipelineView v) {
        return "{\"ts\":"
                + nowMillis()
                + ",\"type\":\"tick-update\""
                + ",\"step\":"
                + js(step)
                + ",\"delta\":"
                + delta
                + ",\"denominator\":"
                + v.denominator()
                + "}";
    }

    static String label(String step, String label) {
        return "{\"ts\":"
                + nowMillis()
                + ",\"type\":\"label\""
                + ",\"step\":"
                + js(step)
                + ",\"label\":"
                + js(label)
                + "}";
    }

    static String output(String step, String line) {
        return "{\"ts\":"
                + nowMillis()
                + ",\"type\":\"output\""
                + ",\"step\":"
                + js(step)
                + ",\"line\":"
                + js(line)
                + "}";
    }

    static String warn(String step, String code, String msg) {
        return "{\"ts\":"
                + nowMillis()
                + ",\"type\":\"warn\""
                + ",\"step\":"
                + js(step)
                + ",\"code\":"
                + js(code)
                + ",\"message\":"
                + js(msg)
                + "}";
    }

    static String error(String step, String code, String msg) {
        return error(step, code, msg, "", "");
    }

    /**
     * Error event with optional discrete {@code test} / {@code exceptionClass} fields — emitted (in
     * addition to {@code message}) only when non-empty, so a test failure's parts stay separate on
     * the wire without bloating the common diagnostic shape.
     */
    static String error(String step, String code, String msg, String test, String exceptionClass) {
        StringBuilder sb = new StringBuilder("{\"ts\":")
                .append(nowMillis())
                .append(",\"type\":\"error\"")
                .append(",\"step\":")
                .append(js(step))
                .append(",\"code\":")
                .append(js(code))
                .append(",\"message\":")
                .append(js(msg));
        if (!test.isEmpty()) sb.append(",\"test\":").append(js(test));
        if (!exceptionClass.isEmpty()) sb.append(",\"exceptionClass\":").append(js(exceptionClass));
        return sb.append("}").toString();
    }

    static String stepFinish(String step, String phase, StepStatus status, Duration duration) {
        return "{\"ts\":"
                + nowMillis()
                + ",\"type\":\"step-finish\""
                + ",\"step\":"
                + js(step)
                + ",\"phase\":"
                + js(phase)
                + ",\"status\":"
                + js(status.name())
                + ",\"duration_ms\":"
                + duration.toMillis()
                + "}";
    }

    static String pipelineFinish(PipelineResult r) {
        return "{\"ts\":"
                + nowMillis()
                + ",\"type\":\"pipeline-finish\""
                + ",\"pipeline\":"
                + js(r.pipelineName())
                + ",\"success\":"
                + r.success()
                + ",\"duration_ms\":"
                + r.duration().toMillis()
                + ",\"warnings\":"
                + r.warnings().size()
                + ",\"errors\":"
                + r.errors().size()
                + "}";
    }

    /**
     * JSON string escaping — delegates to the shared {@link Jsonl#quote} codec (same escaping the
     * worker wire protocol uses) so there's one implementation to keep correct. A {@code null}
     * encodes as the bare literal {@code null}.
     */
    static String js(String s) {
        return Jsonl.quote(s);
    }

    static long nowMillis() {
        return Instant.now().toEpochMilli();
    }
}
