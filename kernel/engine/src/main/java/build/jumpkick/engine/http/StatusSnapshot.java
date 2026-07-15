// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.engine.http;

/**
 * The engine vitals {@code GET /api/status} reports — supplied per request by {@code EngineServer}
 * (the same numbers its socket {@code status-ack} carries), so the dashboard and {@code jk engine
 * status} can never drift apart. Memory fields are best-effort; {@code -1} = unobservable.
 * {@code aotTrainingPid} is the sidecar AOT trainer's pid while one runs, {@code -1} otherwise.
 */
public record StatusSnapshot(
        String version,
        long pid,
        long startedAtMillis,
        int activeRequests,
        int activePipelines,
        long heapUsedBytes,
        long heapCommittedBytes,
        long heapMaxBytes,
        long rssBytes,
        long aotTrainingPid) {}
