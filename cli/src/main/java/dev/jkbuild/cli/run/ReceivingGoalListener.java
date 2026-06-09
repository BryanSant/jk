// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.plugin.host.HostEvent;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.GoalView;
import dev.jkbuild.run.PhaseStatus;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deserializes {@link HostEvent} lines from the Workspace Host's stdout and
 * fans them out to one or more {@link GoalListener}s — typically the progress
 * bar listener + the event-log listener. This is the CLI's half of the
 * CLI↔Host event bridge; it turns the wire protocol back into typed callbacks
 * so the existing TUI and logging machinery are unchanged.
 *
 * <p>Call {@link #accept(String)} for each line read from the Host process.
 * Lines that don't carry the {@link HostEvent#PREFIX} prefix are passthrough
 * output (JVM startup noise, user stderr from inside the build) — the caller
 * should handle those (forward to stderr or suppress).
 */
public final class ReceivingGoalListener {

    private final List<GoalListener> listeners;
    private final AtomicLong numerator = new AtomicLong(0);
    private final AtomicLong denominator = new AtomicLong(1);
    private volatile String goalName = "build";
    private final ConcurrentHashMap<String, Integer> phaseScopes = new ConcurrentHashMap<>();

    public ReceivingGoalListener(List<GoalListener> listeners) {
        this.listeners = List.copyOf(listeners);
    }

    /**
     * Process one line from the Host's stdout. Returns {@code true} when the
     * line was a {@link HostEvent} and was dispatched; {@code false} when it
     * was passthrough text the caller should handle.
     */
    public boolean accept(String line) {
        if (!line.startsWith(HostEvent.PREFIX)) return false;
        String json = line.substring(HostEvent.PREFIX.length());
        HostEvent.Type type = HostEvent.type(json);
        if (type == null) return true;

        switch (type) {
            case GOAL_START -> {
                goalName = HostEvent.goal(json);
                if (goalName == null) goalName = "build";
                int scope = HostEvent.scope(json);
                if (scope > 0) denominator.set(scope);
                for (var l : listeners) l.goalStart(view());
            }
            case PHASE_START -> {
                String phase = HostEvent.phase(json);
                int scope = HostEvent.scope(json);
                phaseScopes.put(phase, scope);
                for (var l : listeners) l.phaseStart(phase, scope);
            }
            case PROGRESS -> {
                String phase = HostEvent.phase(json);
                int delta = HostEvent.delta(json);
                long num = HostEvent.num(json);
                long den = HostEvent.den(json);
                numerator.set(num);
                if (den > 0) denominator.set(den);
                for (var l : listeners) l.progress(phase, delta, view());
            }
            case SCOPE_UPDATE -> {
                String phase = HostEvent.phase(json);
                int delta = HostEvent.delta(json);
                denominator.addAndGet(delta);
                for (var l : listeners) l.scopeUpdate(phase, delta, view());
            }
            case LABEL -> {
                String phase = HostEvent.phase(json);
                String label = HostEvent.label(json);
                for (var l : listeners) l.label(phase, label);
            }
            case OUTPUT -> {
                String phase = HostEvent.phase(json);
                String text = HostEvent.line(json);
                if (text != null) for (var l : listeners) l.output(phase, text);
            }
            case WARN -> {
                String phase = HostEvent.phase(json);
                for (var l : listeners) l.warn(phase, HostEvent.code(json), HostEvent.message(json));
            }
            case ERROR -> {
                String phase = HostEvent.phase(json);
                for (var l : listeners) l.error(phase, HostEvent.code(json), HostEvent.message(json));
            }
            case PHASE_FINISH -> {
                String phase = HostEvent.phase(json);
                PhaseStatus status = parseStatus(HostEvent.status(json));
                Duration dur = Duration.ofMillis(HostEvent.ms(json));
                for (var l : listeners) l.phaseFinish(phase, status, dur);
            }
            case GOAL_FINISH -> {
                boolean success = HostEvent.success(json);
                Duration dur = Duration.ofMillis(HostEvent.ms(json));
                // Construct a minimal GoalResult: diagnostics are in the run-log
                // (EventLogListener recorded them as they arrived via warn/error events).
                GoalResult result = new GoalResult(
                        goalName, success, dur,
                        List.of(), List.of(), List.of(), false);
                for (var l : listeners) l.goalFinish(result);
            }
            case EXIT -> { /* handled by the caller tracking the process exit */ }
        }
        return true;
    }

    private GoalView view() {
        return new GoalView(goalName, numerator.get(), denominator.get(), 0, 0, false);
    }

    private static PhaseStatus parseStatus(String s) {
        if (s == null) return PhaseStatus.FAIL;
        return switch (s) {
            case "SUCCESS"   -> PhaseStatus.SUCCESS;
            case "SKIPPED"   -> PhaseStatus.SKIPPED;
            case "CANCELLED" -> PhaseStatus.CANCELLED;
            default          -> PhaseStatus.FAIL;
        };
    }
}
