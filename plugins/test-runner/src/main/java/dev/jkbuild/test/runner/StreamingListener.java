// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.test.runner;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * {@link TestExecutionListener} that translates JUnit Platform callbacks
 * into protocol events on a {@link EventWriter}. Stateful enough to track
 * per-test timing (JUnit's listener API gives us "started" / "finished"
 * but no built-in duration).
 *
 * <p>Throwables are flattened to {@code {class, message, stack[]}} maps —
 * a {@link Throwable} can't safely cross classpaths intact, and the parent
 * jk process doesn't need a live exception, just its rendered form.
 */
final class StreamingListener implements TestExecutionListener {

    private final EventWriter out;
    private final int workerId;
    private final ConcurrentHashMap<String, Long> startNanos = new ConcurrentHashMap<>();
    private long planStartNanos;

    StreamingListener(EventWriter out, int workerId) {
        this.out = out;
        this.workerId = workerId;
    }

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        // PLAN_STARTED is emitted by JkRunner once per worker session, not
        // here — in pull mode this method fires per-class, so emitting here
        // would produce N plan_started events per worker.
    }

    @Override
    public void dynamicTestRegistered(TestIdentifier id) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("id", id.getUniqueId());
        payload.put("display", id.getDisplayName());
        payload.put("parent", id.getParentId().orElse(null));
        payload.put("type", id.getType().name());
        emit(EventType.DYNAMIC_REGISTERED, payload);
    }

    @Override
    public void executionSkipped(TestIdentifier id, String reason) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("id", id.getUniqueId());
        payload.put("display", id.getDisplayName());
        payload.put("type", id.getType().name());
        payload.put("reason", reason == null ? "" : reason);
        emit(EventType.SKIPPED, payload);
    }

    @Override
    public void executionStarted(TestIdentifier id) {
        startNanos.put(id.getUniqueId(), System.nanoTime());
        var payload = new LinkedHashMap<String, Object>();
        payload.put("id", id.getUniqueId());
        payload.put("display", id.getDisplayName());
        payload.put("parent", id.getParentId().orElse(null));
        payload.put("type", id.getType().name());
        id.getSource().ifPresent(src -> payload.put("source", src.toString()));
        emit(EventType.STARTED, payload);
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        long started = startNanos.getOrDefault(id.getUniqueId(), System.nanoTime());
        long durationMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);

        var payload = new LinkedHashMap<String, Object>();
        payload.put("id", id.getUniqueId());
        payload.put("status", result.getStatus().name());
        payload.put("type", id.getType().name()); // parent counts FINISHED[type=TEST] for totals
        payload.put("display", id.getDisplayName());
        payload.put("duration_ms", durationMs);
        result.getThrowable().ifPresent(t -> payload.put("throwable", flatten(t)));
        emit(EventType.FINISHED, payload);
    }

    @Override
    public void reportingEntryPublished(TestIdentifier id, ReportEntry entry) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("id", id.getUniqueId());
        payload.put("entries", new LinkedHashMap<>(entry.getKeyValuePairs()));
        emit(EventType.REPORT, payload);
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        // Counters are derived from this listener's view of the just-finished
        // plan: walking the plan + the TestExecutionResult callbacks would be
        // redundant when ResultAggregator on the parent already counts
        // FINISHED events directly. Emit a marker event with the per-plan
        // duration so the parent can show "engine X took Yms" if it wants.
        long durationMs = Math.max(0, (System.nanoTime() - planStartNanos) / 1_000_000);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("duration_ms", durationMs);
        emit(EventType.PLAN_FINISHED, payload);
    }

    /**
     * Render a throwable in a form the parent process can display without
     * needing the failure's classes on its own classpath. Includes the
     * type, message, and stack trace lines.
     */
    private static Map<String, Object> flatten(Throwable t) {
        var sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        var map = new LinkedHashMap<String, Object>();
        map.put("class", t.getClass().getName());
        map.put("message", t.getMessage() == null ? "" : t.getMessage());
        map.put("stack", sw.toString());
        return map;
    }

    /**
     * Pull the engine identifier out of a uniqueId like {@code [engine:junit-jupiter]}.
     * Returns the raw string when the pattern doesn't match — safe fallback.
     */
    private static String displayEngine(String uniqueId) {
        var prefix = "[engine:";
        if (uniqueId.startsWith(prefix) && uniqueId.endsWith("]")) {
            return uniqueId.substring(prefix.length(), uniqueId.length() - 1);
        }
        return uniqueId;
    }

    private void emit(EventType type, Map<String, Object> payload) {
        try {
            // Stamp the worker id on every event in pull/parallel mode so
            // the parent can attribute output. Single-worker (id 0) runs
            // omit it to keep the wire form unchanged from Stage A.
            if (workerId > 0) payload.put("w", workerId);
            out.write(type, payload);
            out.flush();
        } catch (IOException e) {
            // Parent has gone away or stdout is broken — nothing useful to do.
            // Don't propagate: we don't want to derail the test run for an IPC blip.
            System.err.println("jk-test-runner: " + e.getMessage());
        }
    }

    /** Pull-mode helper: signal "send me the next class" to the parent. */
    void emitReady() {
        emit(EventType.READY, new LinkedHashMap<>());
    }

    /** Discovery helper: emit one DISCOVERED per top-level class. */
    void emitDiscovered(String className) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("class", className);
        emit(EventType.DISCOVERED, payload);
    }

    /**
     * Discovery helper: emit the up-front totals so the parent can populate
     * a {@code [n of N]} progress display before the first test runs.
     */
    void emitDiscoveryTotal(int classes, int tests) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("classes", classes);
        payload.put("tests", tests);
        emit(EventType.DISCOVERY_TOTAL, payload);
    }
}
