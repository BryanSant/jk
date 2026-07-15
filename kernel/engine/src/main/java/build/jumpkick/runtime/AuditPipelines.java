// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.run.StepNames;

import build.jumpkick.cache.Cas;
import build.jumpkick.lock.LockfileReader;
import build.jumpkick.plugin.protocol.Ndjson;
import build.jumpkick.run.Pipeline;
import build.jumpkick.run.Step;
import build.jumpkick.run.StepKind;
import build.jumpkick.worker.WorkerClient;
import build.jumpkick.worker.WorkerJar;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The shared {@code jk audit} pipeline — scan {@code jk.lock} against OSV via the {@code jk-auditor}
 * worker — hoisted out of the CLI so the resident engine can host the command (Wave 2 of {@code
 * docs/architecture/slim-client.md}) while the command's test-only in-process path builds the exact
 * same pipeline. Three steps: {@code read-lock} (SYNC) validates the lockfile is readable, {@code
 * query-osv} (IO) forks the worker and streams its NDJSON findings to the {@link FindingObserver},
 * {@code evaluate} (SYNC) is the threshold bookkeeping marker (the threshold itself is applied by
 * the client, which owns the report rendering and the exit code).
 *
 * <p>Findings cross the observer as plain structured strings exactly as the worker reported them —
 * no theming, no report assembly — so the same observer contract serves the wire ({@code
 * audit-finding} events) and the in-process accumulation.
 */
public final class AuditPipelines {

    private AuditPipelines() {}

    /** Receives each finding as the worker streams it (raw worker fields; any may be {@code null}). */
    public interface FindingObserver {
        void onFinding(String module, String version, String vulnId, String severity, String summary);
    }

    /**
     * Build the audit pipeline for {@code lockPath}. Locates the worker jar eagerly, so a missing worker
     * fails here (with {@link build.jumpkick.worker.WorkerJarNotFoundException}'s side-load
     * instructions) rather than mid-pipeline. {@code thresholdLabel} only feeds the evaluate step's
     * label; {@code osvBatchUrl}/{@code osvVulnsUrl} are the hidden test overrides ({@code null} =
     * the real OSV endpoints).
     */
    public static Pipeline auditPipeline(
            Path lockPath,
            Path cache,
            String thresholdLabel,
            URI osvBatchUrl,
            URI osvVulnsUrl,
            FindingObserver observer) {
        Path workerJar = WorkerJar.AUDITOR.locate(new Cas(cache));

        Step readLock = Step.builder(StepNames.READ_LOCK)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("read jk.lock");
                    // Validates the lockfile is readable; the worker re-reads it.
                    LockfileReader.read(lockPath);
                    ctx.progress(1);
                })
                .build();

        Step queryOsv = Step.builder(StepNames.QUERY_OSV)
                .kind(StepKind.IO)
                .requires(StepNames.READ_LOCK)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("query OSV via audit worker");
                    try {
                        runWorker(workerJar, lockPath, osvBatchUrl, osvVulnsUrl, observer);
                    } catch (RuntimeException e) {
                        ctx.error("osv", e.getMessage());
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();

        Step evaluate = Step.builder("evaluate")
                .requires(StepNames.QUERY_OSV)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("apply " + thresholdLabel + " threshold");
                    ctx.progress(1);
                })
                .build();

        return Pipeline.builder("audit")
                .addStep(readLock)
                .addStep(queryOsv)
                .addStep(evaluate)
                .build();
    }

    /** Fork the {@code jk-auditor} worker and stream its NDJSON findings to {@code observer}. */
    private static void runWorker(
            Path workerJar, Path lockPath, URI osvBatchUrl, URI osvVulnsUrl, FindingObserver observer) {
        try {
            Path spec = writeSpec(lockPath, osvBatchUrl, osvVulnsUrl);
            try {
                int exit = new WorkerClient("##JKAU:")
                        .on("finding", json -> observer.onFinding(
                                Ndjson.str(json, "module"),
                                Ndjson.str(json, "version"),
                                Ndjson.str(json, "vuln_id"),
                                Ndjson.str(json, "severity"),
                                Ndjson.str(json, "summary")))
                        .run(WorkerCommands.javaCommand(workerJar, spec));
                if (exit != 0) {
                    throw new RuntimeException("audit worker exited with code " + exit);
                }
            } finally {
                Files.deleteIfExists(spec);
            }
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("audit worker interrupted", e);
        }
    }

    private static Path writeSpec(Path lockPath, URI osvBatchUrl, URI osvVulnsUrl) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("LOCKFILE " + lockPath.toAbsolutePath());
        if (osvBatchUrl != null) lines.add("BATCH_URL " + osvBatchUrl);
        if (osvVulnsUrl != null) lines.add("VULNS_URL " + osvVulnsUrl);
        Path spec = Files.createTempFile("jk-audit-", ".spec");
        Files.write(spec, lines, StandardCharsets.UTF_8);
        return spec;
    }
}
