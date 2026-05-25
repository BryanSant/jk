// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.test.runner;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private final ConcurrentHashMap<String, Long> startNanos = new ConcurrentHashMap<>();
    private long planStartNanos;

    StreamingListener(EventWriter out) {
        this.out = out;
    }

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        planStartNanos = System.nanoTime();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("ts", System.currentTimeMillis());
        // Engines we found. Each root id has the form `[engine:junit-jupiter]`,
        // strip the brackets+key for a cleaner display string.
        var engines = new java.util.ArrayList<String>();
        for (var root : testPlan.getRoots()) {
            engines.add(displayEngine(root.getUniqueId()));
        }
        payload.put("engines", engines);
        emit(EventType.PLAN_STARTED, payload);
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
            out.write(type, payload);
            out.flush();
        } catch (IOException e) {
            // Parent has gone away or stdout is broken — nothing useful to do.
            // Don't propagate: we don't want to derail the test run for an IPC blip.
            System.err.println("jk-test-runner: " + e.getMessage());
        }
    }
}
